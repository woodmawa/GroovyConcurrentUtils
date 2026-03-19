package org.softwood.dag.task

import groovy.util.logging.Slf4j
import org.softwood.promise.Promise

/**
 * A DAG task that applies a declarative policy to structured upstream results
 * and returns a typed {@link ControlAction}.
 *
 * <p><b>Design principle:</b> The LLM never directly chooses routes.
 * {@link LlmTask} produces a structured assessment; {@code ControlTask} applies
 * deterministic, auditable rules to that assessment. This keeps routing
 * reproducible and inspectable.</p>
 *
 * <h3>Policy rule format (set by FlowBuilder from FlowDescriptor params):</h3>
 * <pre>
 *   params.policy.rules = [
 *     [when: "input.confidence < 0.75", action: "ESCALATE", reason: "Low confidence"],
 *     [when: "input.riskLevel == 'high'", action: "ROUTE",    target: "rollback-branch"],
 *     [when: "input.healthy == true",    action: "CONTINUE"],
 *   ]
 *   params.policy.default = [action: "ESCALATE", reason: "No rule matched"]
 * </pre>
 *
 * <p>Rules are evaluated in declaration order. The first rule whose {@code when}
 * condition evaluates to {@code true} (Groovy expression against a binding that
 * has {@code input} = upstream node result, {@code globals} = ctx.globals,
 * {@code memory} = working memory) produces the action. If no rule matches,
 * {@code policy.default} is used.</p>
 *
 * <h3>Input resolution:</h3>
 * <p>When {@link #inputNodeId} is set, {@code input} is resolved from
 * {@code workingMemory.resultsByNode[inputNodeId].value}. Otherwise {@code input}
 * is the raw {@code prevResult} passed by the DAG.</p>
 *
 * <h3>Result:</h3>
 * <p>The {@link ControlAction} is stored as the task result. The server-side
 * {@code FlowExecutionService} reads it from the {@code TaskEvent.result} to
 * decide graph routing.</p>
 *
 * <h3>Effect class:</h3>
 * PURE – pure function; safe to retry.
 */
@Slf4j
class ControlTask extends TaskBase<ControlAction> {

    // -------------------------------------------------------------------------
    // Config (set by server factory after construction)
    // -------------------------------------------------------------------------

    /**
     * Ordered list of policy rules. Each rule is a Map with keys:
     * <ul>
     *   <li>{@code when}   – Groovy boolean expression string (required)</li>
     *   <li>{@code action} – One of: CONTINUE, ROUTE, RETRY_SCOPE, ESCALATE, ABORT (required)</li>
     *   <li>{@code target} – Branch name or scopeId (required for ROUTE / RETRY_SCOPE)</li>
     *   <li>{@code reason} – Human-readable description (optional; used for ESCALATE / ABORT)</li>
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
     * The effect class for this task type – always PURE.
     */
    final EffectClass effectClass = EffectClass.PURE

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    ControlTask(String id, String name, TaskContext ctx) {
        super(id, name, ctx)
    }

    // -------------------------------------------------------------------------
    // Core execution
    // -------------------------------------------------------------------------

    @Override
    protected Promise<ControlAction> runTask(TaskContext ctx, Object prevResult) {
        // Synchronous policy evaluation — wrap in resolved promise
        // 1. Resolve the input value
        Object input = resolveInput(ctx, prevResult)
        log.debug "ControlTask '${id}': evaluating ${rules.size()} rule(s) against input=${input}"

        // 2. Build evaluation binding
        Map<String, Object> memory = resolveWorkingMemory(ctx)
        Binding binding = new Binding([
            input  : input,
            globals: ctx?.globals ?: [:],
            memory : memory
        ])

        // 3. Evaluate rules in order
        ControlAction result = null
        for (Map<String, Object> rule : rules) {
            String condition = rule.when?.toString()
            if (!condition) {
                log.warn "ControlTask '${id}': rule has no 'when' condition, skipping: ${rule}"
                continue
            }
            try {
                Object condResult = new GroovyShell(binding).evaluate(condition)
                if (condResult) {
                    result = buildAction(rule)
                    log.info "ControlTask '${id}': rule matched [${condition}] -> ${result}"
                    break
                }
            } catch (Exception e) {
                log.warn "ControlTask '${id}': rule condition '${condition}' threw: ${e.message} — treating as false"
            }
        }

        if (result == null) {
            // No rule matched — apply default
            result = buildAction(defaultRule)
            log.info "ControlTask '${id}': no rule matched, applying default -> ${result}"
        }

        final ControlAction finalResult = result
        return ctx.promiseFactory.createPromise(finalResult)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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
