package org.softwood.dag.task

import groovy.util.logging.Slf4j
import org.codehaus.groovy.runtime.InvokerHelper
import org.softwood.dag.script.RestrictedCompiler
import org.softwood.dag.script.RuleCompilationException
import org.softwood.promise.Promise

import java.util.concurrent.ConcurrentHashMap

/**
 * A DAG task that applies a declarative policy to structured upstream results
 * and returns a typed {@link ControlAction}.
 *
 * <p><b>Design principle:</b> The LLM never directly chooses routes.
 * {@link LlmTask} produces a structured assessment; {@code ControlTask} applies
 * deterministic, auditable rules to that assessment. This keeps routing
 * reproducible and inspectable.</p>
 *
 * <h3>Policy rule format:</h3>
 * <pre>
 *   rules = [
 *     [when: "input.confidence < 0.75", action: "ESCALATE", reason: "Low confidence"],
 *     [when: "input.riskLevel == 'high'", action: "ROUTE",    target: "rollback-branch"],
 *     [when: "input.healthy == true",    action: "CONTINUE"],
 *   ]
 *   defaultRule = [action: "ESCALATE", reason: "No rule matched"]
 * </pre>
 *
 * <p>Rules are evaluated in declaration order. The first rule whose {@code when}
 * condition is true produces the action. If no rule matches, {@code defaultRule} is used.</p>
 *
 * <h3>What a condition may say (1.4.0, decision D2):</h3>
 * <p>A condition is one expression over {@code input} (the upstream result), {@code globals}
 * (a read-only copy of the graph's globals) and {@code memory} (working memory). It is compiled
 * under the rule profile of {@link RestrictedCompiler}: comparisons, boolean logic, arithmetic,
 * literals, property and index access, a fixed list of methods and {@code Math}. Nothing else:
 * no {@code new}, no closures, no assignment, no other method.</p>
 * <ul>
 *   <li>A condition that does not compile fails {@code TaskGraph.validate()}, so the graph does not start.</li>
 *   <li>A condition that throws while it is evaluated fails this task. Before 1.4.0 it was read as false
 *       and the next rule, or the default, applied.</li>
 *   <li>{@code trusted true} on the task evaluates conditions as unrestricted Groovy, with the live
 *       globals, as before 1.4.0. It is logged each time the task runs.</li>
 * </ul>
 * <p>The profile is a reduction, not isolation: see {@link RestrictedCompiler}.</p>
 *
 * <h3>Input resolution:</h3>
 * <p>When {@link #inputNodeId} is set, {@code input} is resolved from
 * {@code workingMemory.resultsByNode[inputNodeId].value}. Otherwise {@code input}
 * is the raw {@code prevResult} passed by the DAG.</p>
 *
 * <h3>Effect class:</h3>
 * PURE: no side effects. A failure is never retried, because the same input gives the same failure.
 */
@Slf4j
class ControlTask extends TaskBase<ControlAction> implements SelfValidating {

    // -------------------------------------------------------------------------
    // Config (set after construction)
    // -------------------------------------------------------------------------

    /**
     * Ordered list of policy rules. Each rule is a Map with keys:
     * <ul>
     *   <li>{@code when}: the condition, one expression (required)</li>
     *   <li>{@code action}: one of CONTINUE, ROUTE, RETRY_SCOPE, ESCALATE, ABORT (required)</li>
     *   <li>{@code target}: branch name or scopeId (required for ROUTE / RETRY_SCOPE)</li>
     *   <li>{@code reason}: human-readable description (optional; used for ESCALATE / ABORT)</li>
     * </ul>
     */
    List<Map<String, Object>> rules = []

    /**
     * Default action if no rule matches. Same map shape as a rule entry.
     * Defaults to ESCALATE with "No rule matched" reason.
     */
    Map<String, Object> defaultRule = [action: "ESCALATE", reason: "No policy rule matched"]

    /**
     * Optional: resolve {@code input} from this upstream node's result
     * rather than from raw prevResult.
     * Typically the ID of the upstream {@link LlmTask}.
     */
    String inputNodeId = null

    /**
     * The effect class for this task type: always PURE.
     */
    final EffectClass effectClass = EffectClass.PURE

    /** 1.4.0: true when the flow has said this task's conditions are unrestricted Groovy. */
    private boolean trustedTask = false

    /** Trusted conditions, compiled once each to a script class. */
    private final Map<String, Class> trustedScripts = new ConcurrentHashMap<String, Class>()
    private GroovyClassLoader trustedLoader

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    ControlTask(String id, String name, TaskContext ctx) {
        super(id, name, ctx)
    }

    /**
     * 1.4.0: {@code trusted true} evaluates this task's conditions as unrestricted Groovy against the
     * live globals, as every ControlTask did before 1.4.0. The same word, with the same meaning, as on
     * HttpTask and FileTask. It is logged each time the task runs.
     */
    void trusted(boolean value) {
        this.trustedTask = value
    }

    boolean isTrusted() {
        return trustedTask
    }

    // -------------------------------------------------------------------------
    // Validation (1.4.0): compile every condition before the graph runs
    // -------------------------------------------------------------------------

    @Override
    List<String> validationProblems() {
        List<String> problems = []
        for (Map<String, Object> rule : rules) {
            String condition = rule?.get('when')?.toString()
            if (!condition) continue
            try {
                compile(condition)
            } catch (RuleCompilationException e) {
                problems << "rule [${shorten(condition)}] ${e.message}".toString()
            } catch (Exception e) {
                problems << "rule [${shorten(condition)}] does not compile".toString()
            }
        }
        return problems
    }

    /** A failure here is the same on every attempt. */
    @Override
    protected boolean isRetriable(Throwable err) {
        return false
    }

    // -------------------------------------------------------------------------
    // Core execution
    // -------------------------------------------------------------------------

    @Override
    protected Promise<ControlAction> runTask(TaskContext ctx, Object prevResult) {
        // Synchronous policy evaluation, wrapped in a resolved promise
        Object input = resolveInput(ctx, prevResult)
        log.debug "ControlTask '${id}': evaluating ${rules.size()} rule(s)"

        Map<String, Object> memory = resolveWorkingMemory(ctx)
        Map<String, Object> variables
        if (trustedTask) {
            log.warn("ControlTask($id): trusted true - rule conditions run as unrestricted Groovy")
            variables = [input: input, globals: (ctx?.globals != null ? ctx.globals : [:]), memory: memory]
        } else {
            variables = [input: input, globals: readOnlyGlobals(ctx), memory: memory]
        }

        ControlAction result = null
        for (Map<String, Object> rule : rules) {
            String condition = rule?.get('when')?.toString()
            if (!condition) {
                log.warn "ControlTask '${id}': rule has no 'when' condition, skipping"
                continue
            }
            if (evaluate(condition, variables)) {
                result = buildAction(rule)
                log.info "ControlTask '${id}': rule matched [${shorten(condition)}] -> ${result}"
                break
            }
        }

        if (result == null) {
            result = buildAction(defaultRule)
            log.info "ControlTask '${id}': no rule matched, applying default -> ${result}"
        }

        final ControlAction finalResult = result
        return ctx.promiseFactory.createPromise(finalResult)
    }

    /**
     * True or false for one condition. Anything thrown fails the task: a refusal as a
     * SecurityException, any other failure as an IllegalStateException naming the rule.
     */
    private boolean evaluate(String condition, Map<String, Object> variables) {
        try {
            if (trustedTask) {
                Script script = InvokerHelper.createScript(trustedScript(condition), new Binding(new LinkedHashMap(variables)))
                return script.run() as boolean
            }
            return RestrictedCompiler.compileRule(condition).test(variables)
        } catch (SecurityException e) {
            throw new SecurityException("ControlTask '${id}': rule [${shorten(condition)}] refused: ${e.message}".toString(), e)
        } catch (RuleCompilationException e) {
            throw new IllegalStateException("ControlTask '${id}': rule [${shorten(condition)}] ${e.message}".toString(), e)
        } catch (Exception e) {
            throw new IllegalStateException(
                    "ControlTask '${id}': rule [${shorten(condition)}] failed: ${e.getClass().simpleName}: ${e.message}".toString(), e)
        }
    }

    private Object compile(String condition) {
        return trustedTask ? trustedScript(condition) : RestrictedCompiler.compileRule(condition)
    }

    private Class trustedScript(String condition) {
        Class known = trustedScripts.get(condition)
        if (known != null) return known
        synchronized (trustedScripts) {
            known = trustedScripts.get(condition)
            if (known == null) {
                if (trustedLoader == null) trustedLoader = new GroovyClassLoader(ControlTask.classLoader)
                known = trustedLoader.parseClass(condition)
                trustedScripts.put(condition, known)
            }
            return known
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Map<String, Object> readOnlyGlobals(TaskContext taskCtx) {
        if (taskCtx?.globals == null) return Collections.emptyMap()
        return Collections.unmodifiableMap(new LinkedHashMap<String, Object>(taskCtx.globals.getAll()))
    }

    private static String shorten(String condition) {
        return condition.length() <= 120 ? condition : condition.substring(0, 117) + '...'
    }

    private Object resolveInput(TaskContext taskCtx, Object prevResult) {
        if (!inputNodeId) return prevResult

        Map<String, Object> memory = resolveWorkingMemory(taskCtx)
        def nodeResult = memory?.resultsByNode?.get(inputNodeId)
        if (nodeResult == null) {
            log.warn "ControlTask '${id}': inputNodeId='${inputNodeId}' not found in working memory, falling back to prevResult"
            return prevResult
        }
        // nodeResult is expected to be a TaskResult-like map with a 'value' key
        return nodeResult instanceof Map ? nodeResult.value : nodeResult
    }

    private Map<String, Object> resolveWorkingMemory(TaskContext taskCtx) {
        def wm = taskCtx?.globals?.get('__workingMemory')
        return wm instanceof Map ? (Map<String, Object>) wm : [:]
    }

    /**
     * Translates a rule map into a typed {@link ControlAction}.
     */
    private ControlAction buildAction(Map<String, Object> rule) {
        String actionStr = rule.action?.toString()?.toUpperCase()
        String target    = rule.target?.toString()
        String reason    = rule.reason?.toString()

        switch (actionStr) {
            case 'CONTINUE':
                return ControlAction.continueFlow()
            case 'ROUTE':
                if (!target) throw new IllegalArgumentException("ControlTask '${id}': ROUTE action requires 'target' in rule ${rule}")
                return ControlAction.route(target)
            case 'RETRY_SCOPE':
                if (!target) throw new IllegalArgumentException("ControlTask '${id}': RETRY_SCOPE action requires 'target' (scopeId) in rule ${rule}")
                return ControlAction.retryScope(target)
            case 'ESCALATE':
                return ControlAction.escalate(reason ?: "Policy escalation from node '${id}'")
            case 'ABORT':
                return ControlAction.abort(reason ?: "Policy abort from node '${id}'")
            default:
                throw new IllegalArgumentException("ControlTask '${id}': unknown action '${actionStr}' in rule ${rule}")
        }
    }
}
