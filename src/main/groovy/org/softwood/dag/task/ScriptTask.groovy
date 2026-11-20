package org.softwood.dag.task

import groovy.util.logging.Slf4j
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.runtime.InvokerHelper
import org.softwood.dag.script.RestrictedCompiler
import org.softwood.dag.script.RestrictedJavaScript
import org.softwood.dag.script.ScriptCompilationException
import org.softwood.dag.script.ScriptContextView
import org.softwood.dag.script.ScriptFiles
import org.softwood.dag.script.ScriptHttp
import org.softwood.dag.script.ScriptInterruptedError
import org.softwood.promise.Promise

import javax.script.ScriptEngine
import javax.script.ScriptEngineManager
import javax.script.ScriptException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * ScriptTask - runs a script as a task. Groovy by default; JavaScript; other JSR 223 languages
 * when trusted.
 *
 * <h3>Restricted by default (1.4.0, decision D2)</h3>
 * <p>A Groovy script is compiled under the script profile of {@link RestrictedCompiler}:</p>
 * <ul>
 *   <li>it may name only the classes of the profile: the {@code java.lang} value types and
 *       {@code Math}, {@code java.util} with its function, stream and regex packages,
 *       {@code java.time}, {@code java.math}, {@code java.text}, the concurrent collections,
 *       {@code UUID}, {@code Base64}, {@code URI}, {@code MessageDigest}, {@code groovy.json},
 *       {@code groovy.xml}, {@code groovy.yaml}, {@code Promise} and {@code Gstream}. No files,
 *       network, processes, threads, reflection, class loaders or further script evaluation;</li>
 *   <li>the ways out through an object ({@code getClass}, {@code metaClass}, {@code invokeMethod},
 *       {@code with}) and through the script itself ({@code evaluate}, {@code binding}) are refused
 *       by name;</li>
 *   <li>while it runs, the receiver of every call, property access and index is checked, so an
 *       object of a forbidden type is refused however the script came by it;</li>
 *   <li>{@code ctx} is a read-only view: the globals and the task id, not the TaskContext.</li>
 * </ul>
 * <p>A script outside the profile fails {@code TaskGraph.validate()}, so the graph does not start.
 * This is a reduction inside one JVM, not isolation: a script can call any public method of the
 * objects the flow puts in its bindings.</p>
 *
 * <h3>The run-time rules</h3>
 * <ul>
 *   <li>A script is compiled once and each run gets a new instance with its own binding.</li>
 *   <li>{@code scriptTimeout} is enforced: the script runs on its own thread, the task fails when
 *       the time is up, and the script is interrupted. A restricted Groovy script checks for
 *       interruption on every loop pass, method and closure and cannot catch it.</li>
 *   <li>A refusal is never retried.</li>
 * </ul>
 *
 * <h3>Capabilities (level 2)</h3>
 * <p>{@code allow 'files'} on the graph binds {@code files}; {@code allow 'http'} binds {@code http}.
 * See {@link ScriptFiles} and {@link ScriptHttp}.</p>
 *
 * <h3>trusted true (level 3)</h3>
 * <p>The script is compiled as plain Groovy and is given the TaskContext itself, as every script
 * was before 1.4.0. Logged each time the task runs. {@code sandboxed false} is the old word for it.</p>
 *
 * <h3>Other languages</h3>
 * <p>JavaScript runs on Nashorn with no access to Java. Any other language runs only with
 * {@code trusted true}. A JavaScript loop cannot be interrupted: on timeout the run is abandoned.</p>
 *
 * <h3>Example</h3>
 * <pre>
 * scriptTask("calculate-total") {
 *     script """
 *         def total = items.sum { it.price * it.quantity }
 *         return [total: total, tax: total * 0.08]
 *     """
 *     bindings { ctx, prev -> [items: prev.items] }
 *     scriptTimeout Duration.ofSeconds(5)
 * }
 * </pre>
 */
@Slf4j
class ScriptTask extends TaskBase<Object> implements SelfValidating {

    // =========================================================================
    // Configuration
    // =========================================================================

    /** Script language (default: groovy) */
    String language = "groovy"

    /** Script source code */
    String script

    /** Closure to provide variable bindings to script */
    Closure bindingsProvider

    /** How long a run may take. Enforced from 1.4.0. Null: no limit. */
    Duration scriptTimeout

    /**
     * A script base class of the caller's own. Its public methods are callable from the script by
     * name. Setting one makes the task restricted (not trusted).
     */
    Class<? extends Script> customScriptBaseClass = null

    // =========================================================================
    // Internal State
    // =========================================================================

    /** 1.4.0: true when the flow has said this script is unrestricted Groovy. */
    private boolean trustedTask = false

    /** The compiled Groovy script class, and what it was compiled from. */
    private Class<? extends Script> compiledClass
    private String compiledKey
    private final Object compileLock = new Object()

    ScriptTask(String id, String name, TaskContext ctx) {
        super(id, name, ctx)
    }

    // =========================================================================
    // DSL Methods
    // =========================================================================

    /**
     * Set the script language.
     */
    void language(String lang) {
        this.language = lang?.toLowerCase()
    }

    /**
     * Set the script source code.
     */
    void script(String code) {
        this.script = code
    }

    /**
     * 1.4.0: {@code trusted true} runs the script as unrestricted Groovy (or as an unrestricted
     * script in another language) with the TaskContext itself as {@code ctx}. The same word, with
     * the same meaning, as on HttpTask, FileTask and ControlTask. It is logged each time the task runs.
     */
    void trusted(boolean value) {
        this.trustedTask = value
    }

    boolean isTrusted() {
        return trustedTask
    }

    /**
     * The word before 1.4.0. {@code sandboxed false} is {@code trusted true}; {@code sandboxed true}
     * is the default. It no longer reads the ENVIRONMENT variable: the flow's own statement decides.
     */
    void sandboxed(boolean enabled) {
        trusted(!enabled)
    }

    boolean getSandboxed() {
        return !trustedTask
    }

    void setSandboxed(boolean enabled) {
        sandboxed(enabled)
    }

    /**
     * Set a script base class of the caller's own. Its public methods can be called from the script
     * by name; the script is still compiled under the script profile.
     *
     * @param baseClass a class extending groovy.lang.Script
     */
    void customScriptBaseClass(Class<? extends Script> baseClass) {
        if (!baseClass) {
            throw new IllegalArgumentException("customScriptBaseClass cannot be null")
        }

        if (!Script.class.isAssignableFrom(baseClass)) {
            throw new IllegalArgumentException(
                "customScriptBaseClass must extend groovy.lang.Script, got: ${baseClass.name}"
            )
        }

        this.customScriptBaseClass = baseClass
        this.trustedTask = false  // a custom base class means restricted

        log.info("ScriptTask(${id}): Using custom script base class: ${baseClass.simpleName}")
    }

    /**
     * Set the bindings provider closure.
     * The closure receives (TaskContext, prevValue) and returns a Map of bindings.
     */
    void bindings(Closure provider) {
        this.bindingsProvider = provider
    }

    /**
     * Set script execution timeout.
     */
    void scriptTimeout(Duration timeout) {
        this.scriptTimeout = timeout
    }

    // =========================================================================
    // Validation (1.4.0): compile the script before the graph runs
    // =========================================================================

    @Override
    List<String> validationProblems() {
        if (!script) {
            return ['a script is required']
        }
        String lang = language?.toLowerCase()
        if (lang != 'groovy') {
            if (!trustedTask && !RestrictedJavaScript.NAMES.contains(lang)) {
                return ["language '${language}' runs only with trusted true".toString()]
            }
            return []
        }
        try {
            scriptClass()
            return []
        } catch (ScriptCompilationException e) {
            return ["script ${e.message}".toString()]
        } catch (Exception e) {
            return ["script does not compile: ${firstLine(e.message)}".toString()]
        }
    }

    /** A refusal, or a script that will not compile, is the same on every attempt. */
    @Override
    protected boolean isRetriable(Throwable err) {
        for (Throwable t = err; t != null; t = t.cause) {
            if (t instanceof SecurityException || t instanceof ScriptCompilationException) return false
            if (t.cause === t) break
        }
        return super.isRetriable(err)
    }

    // =========================================================================
    // Task Execution
    // =========================================================================

    @Override
    protected Promise<Object> runTask(TaskContext ctx, Object prevValue) {

        if (!script) {
            throw new IllegalStateException("ScriptTask($id): script is required")
        }

        log.debug("ScriptTask($id): executing ${language} script")

        return ctx.promiseFactory.executeAsync {

            if (trustedTask) {
                log.warn("ScriptTask($id): trusted true - the script runs unrestricted, with the task context")
            }
            Map<String, Object> bindings = prepareBindings(ctx, prevValue)
            boolean groovy = language?.toLowerCase() == 'groovy'

            Object result = withTimeout {
                groovy ? runGroovy(bindings) : runEngine(bindings)
            }

            log.debug("ScriptTask($id): script returned: ${result?.getClass()?.simpleName}")
            return result
        }
    }

    // =========================================================================
    // Running
    // =========================================================================

    /**
     * Run the work, bounded by scriptTimeout when there is one. With a timeout the work runs on a
     * thread of its own: when the time is up the task fails and that thread is interrupted.
     */
    private Object withTimeout(Closure work) {
        if (scriptTimeout == null) {
            try {
                return work.call()
            } catch (ScriptInterruptedError e) {
                // the graph cancelled this task: report it the way the engine expects
                Thread.currentThread().interrupt()
                throw new InterruptedException("ScriptTask($id): interrupted")
            }
        }

        long millis = scriptTimeout.toMillis()
        CompletableFuture<Object> done = new CompletableFuture<Object>()
        Thread runner = Thread.ofVirtual().name("script-${id}").unstarted {
            try {
                done.complete(work.call())
            } catch (Throwable t) {
                done.completeExceptionally(t)
            }
        }
        runner.start()
        try {
            return done.get(millis, TimeUnit.MILLISECONDS)
        } catch (TimeoutException ignored) {
            runner.interrupt()
            throw new TimeoutException("ScriptTask($id): the script ran longer than its scriptTimeout of ${millis} ms and was stopped")
        } catch (ExecutionException e) {
            throw e.cause
        } catch (InterruptedException e) {
            runner.interrupt()
            throw e
        }
    }

    private Object runGroovy(Map<String, Object> bindings) {
        Script instance = InvokerHelper.createScript(scriptClass(), new Binding(new LinkedHashMap(bindings)))
        try {
            return instance.run()
        } catch (SecurityException e) {
            log.error("ScriptTask($id): the script was refused: ${e.message}")
            throw new ScriptExecutionException("Script security violation: ${e.message}", e)
        } catch (Exception e) {
            log.error("ScriptTask($id): Groovy script execution failed: ${e.message}")
            throw new ScriptExecutionException("Groovy script execution failed: ${e.message}", e)
        }
    }

    /** The script class: compiled once, and again only if the script or how it is compiled changes. */
    private Class<? extends Script> scriptClass() {
        String key = (trustedTask ? 'trusted|' : 'restricted|') + (customScriptBaseClass?.name ?: '') + '|' + script
        synchronized (compileLock) {
            if (compiledClass == null || key != compiledKey) {
                compiledClass = trustedTask ? compileTrusted() : compileRestricted()
                compiledKey = key
            }
            return compiledClass
        }
    }

    private Class<? extends Script> compileRestricted() {
        Class<? extends Script> base = customScriptBaseClass ?: SecureScriptBase
        log.debug("ScriptTask($id): compiling under the script profile, base class ${base.simpleName}")
        return RestrictedCompiler.compileScript(script, base, ScriptTask.classLoader)
    }

    private Class<? extends Script> compileTrusted() {
        CompilerConfiguration config = new CompilerConfiguration()
        if (customScriptBaseClass) config.scriptBaseClass = customScriptBaseClass.name
        log.debug("ScriptTask($id): compiling as unrestricted Groovy (trusted true)")
        return (Class<? extends Script>) new GroovyClassLoader(ScriptTask.classLoader, config).parseClass(script)
    }

    /**
     * Run a script in another language. A new engine for every run: engines are not shared.
     */
    private Object runEngine(Map<String, Object> bindings) {
        String lang = language?.toLowerCase()
        ScriptEngine engine

        if (trustedTask) {
            engine = trustedEngine(lang)
            if (!engine) {
                throw new IllegalStateException(
                    "ScriptTask($id): No script engine found for language '${language}'. " +
                    "Available engines: ${getAvailableEngines()}"
                )
            }
        } else if (RestrictedJavaScript.NAMES.contains(lang)) {
            engine = RestrictedJavaScript.newEngine(ScriptTask.classLoader)
        } else {
            throw new SecurityException("ScriptTask($id): language '${language}' runs only with trusted true")
        }

        bindings.each { key, value ->
            engine.put(key, value)
        }

        try {
            return engine.eval(script)
        } catch (ScriptException e) {
            log.error("ScriptTask($id): ${language} script execution failed: ${e.message}")
            throw new ScriptExecutionException(
                "${language} script execution failed at line ${e.lineNumber}: ${e.message}",
                e
            )
        } catch (Exception e) {
            log.error("ScriptTask($id): ${language} script execution failed: ${e.message}")
            throw new ScriptExecutionException("${language} script execution failed: ${e.message}", e)
        }
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    /**
     * Prepare variable bindings for script execution.
     */
    private Map<String, Object> prepareBindings(TaskContext ctx, Object prevValue) {

        Map<String, Object> bindings = [:]

        // Default bindings. A restricted script sees a read-only view in place of the context.
        Object contextForScript = trustedTask ? ctx : new ScriptContextView(ctx, id)
        bindings.put("ctx", contextForScript)
        bindings.put("context", contextForScript)  // Alias
        bindings.put("prev", prevValue)
        bindings.put("input", prevValue)  // Alias

        // Capabilities the graph grants its restricted scripts (level 2)
        if (!trustedTask && ctx?.scriptCapabilities) {
            if (ctx.scriptCapabilities.contains('files')) bindings.put('files', new ScriptFiles(ctx))
            if (ctx.scriptCapabilities.contains('http')) bindings.put('http', new ScriptHttp(ctx))
        }

        // Add user-provided bindings
        if (bindingsProvider) {
            try {
                Map userBindings = bindingsProvider.call(ctx, prevValue) as Map
                if (userBindings) {
                    bindings.putAll(userBindings)
                }
            } catch (Exception e) {
                log.error("ScriptTask($id): error in bindings provider", e)
                throw new IllegalStateException("Failed to prepare script bindings: ${e.message}", e)
            }
        }

        log.debug("ScriptTask($id): prepared ${bindings.size()} bindings: ${bindings.keySet()}")

        return bindings
    }

    private static ScriptEngine trustedEngine(String lang) {
        def manager = new ScriptEngineManager()
        ScriptEngine engine = manager.getEngineByName(lang)

        // Try common aliases
        if (!engine && lang == "js") {
            engine = manager.getEngineByName("javascript")
        }
        if (!engine && lang == "py") {
            engine = manager.getEngineByName("python")
        }
        return engine
    }

    /**
     * Get list of available script engines.
     */
    private static List<String> getAvailableEngines() {
        def manager = new ScriptEngineManager()
        return manager.engineFactories.collect { it.engineName }
    }

    private static String firstLine(String text) {
        if (!text) return 'no detail'
        String flat = text.replaceAll('\\s+', ' ').trim()
        return flat.length() <= 240 ? flat : flat.substring(0, 237) + '...'
    }

    // =========================================================================
    // Exception Classes
    // =========================================================================

    /**
     * Exception thrown when script execution fails.
     */
    static class ScriptExecutionException extends RuntimeException {
        ScriptExecutionException(String message, Throwable cause) {
            super(message, cause)
        }
    }
}
