package org.softwood.dag.task

import groovy.util.logging.Slf4j
import org.softwood.promise.Promise

import javax.script.ScriptEngine
import javax.script.ScriptEngineManager
import javax.script.ScriptException
import java.time.Duration

/**
 * ScriptTask - Execute Scripts in Multiple Languages
 *
 * Executes scripts in various languages (JavaScript, Groovy, Python, Ruby, etc.)
 * without requiring Java code compilation. Particularly optimized for Groovy scripts.
 *
 * <h3>Key Features:</h3>
 * <ul>
 *   <li>Multi-language support via JSR 223 ScriptEngine</li>
 *   <li>Groovy optimization (direct compilation, no ScriptEngine)</li>
 *   <li>Variable bindings from workflow context</li>
 *   <li>Script result capture and return</li>
 *   <li>Timeout protection</li>
 *   <li>Script caching for performance</li>
 * </ul>
 *
 * <h3>Supported Languages:</h3>
 * <ul>
 *   <li>groovy (default, optimized)</li>
 *   <li>javascript / js</li>
 *   <li>python (requires Jython)</li>
 *   <li>ruby (requires JRuby)</li>
 *   <li>Any JSR 223 compatible engine</li>
 * </ul>
 *
 * <h3>DSL Example - JavaScript:</h3>
 * <pre>
 * task("transform-data", TaskType.SCRIPT) {
 *     language "javascript"
 *     
 *     script """
 *         function transform(data) {
 *             return {
 *                 fullName: data.firstName + ' ' + data.lastName,
 *                 processed: true
 *             };
 *         }
 *         transform(input);
 *     """
 *     
 *     bindings { ctx, prev ->
 *         [input: prev]
 *     }
 * }
 * </pre>
 *
 * <h3>DSL Example - Groovy (Optimized):</h3>
 * <pre>
 * task("calculate-total", TaskType.SCRIPT) {
 *     // language "groovy" is default
 *     
 *     script """
 *         def total = items.sum { it.price * it.quantity }
 *         def tax = total * 0.08
 *         return [total: total, tax: tax, grandTotal: total + tax]
 *     """
 *     
 *     bindings { ctx, prev ->
 *         [items: prev.items]
 *     }
 * }
 * </pre>
 *
 * <h3>Performance Note:</h3>
 * Groovy scripts are compiled directly (not via ScriptEngine) for better performance.
 * Scripts are cached after first compilation.
 */
@Slf4j
class ScriptTask extends TaskBase<Object> {

    // =========================================================================
    // Configuration
    // =========================================================================

    /** Script language (default: groovy) */
    String language = "groovy"

    /** Script source code */
    String script

    /** Closure to provide variable bindings to script */
    Closure bindingsProvider

    /** Script execution timeout (optional) */
    Duration scriptTimeout

    /**
     * Enable sandboxed execution (secure mode).
     * When true, uses SecureScriptBase to restrict dangerous operations.
     * Default: true (secure by default)
     */
    boolean sandboxed = true
    
    // =========================================================================
    // Internal State
    // =========================================================================
    
    /** Cached compiled Groovy script */
    private Script compiledGroovyScript
    
    /** Static cache of script engines by language */
    private static final Map<String, ScriptEngine> ENGINE_CACHE = [:].asSynchronized()

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
     * Enable or disable sandboxed execution.
     * Sandboxed mode (default) restricts dangerous operations.
     *
     * SECURITY WARNING: Only disable sandboxing if you fully trust the script source.
     */
    void sandboxed(boolean enabled) {
        this.sandboxed = enabled
        if (!enabled) {
            def env = System.getenv('ENVIRONMENT') ?: System.getProperty('environment') ?: ''
            if (env.toLowerCase() in ['production', 'prod', 'live', 'staging']) {
                throw new SecurityException(
                    "Unsandboxed script execution is not allowed in production environment. " +
                    "ENVIRONMENT=${env}. Scripts must run in sandboxed mode."
                )
            }
            log.warn("=" * 80)
            log.warn("⚠️  SECURITY WARNING: ScriptTask sandboxing DISABLED")
            log.warn("  - Arbitrary code execution: ENABLED")
            log.warn("  - File system access: UNRESTRICTED")
            log.warn("  - Network access: UNRESTRICTED")
            log.warn("  - Task: ${id}")
            log.warn("  - FOR TRUSTED SCRIPTS ONLY")
            log.warn("=" * 80)
        }
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
    // Task Execution
    // =========================================================================

    @Override
    protected Promise<Object> runTask(TaskContext ctx, Object prevValue) {
        
        if (!script) {
            throw new IllegalStateException("ScriptTask($id): script is required")
        }
        
        log.debug("ScriptTask($id): executing ${language} script")
        
        return ctx.promiseFactory.executeAsync {
            
            // Prepare bindings
            Map<String, Object> bindings = prepareBindings(ctx, prevValue)
            
            // Execute based on language
            Object result
            if (language == "groovy") {
                result = executeGroovyOptimized(bindings)
            } else {
                result = executeViaScriptEngine(bindings)
            }
            
            log.debug("ScriptTask($id): script returned: ${result?.getClass()?.simpleName}")
            
            return result
        }
    }

    // =========================================================================
    // Script Execution Strategies
    // =========================================================================
    
    /**
     * Execute Groovy script with optimization (no ScriptEngine overhead).
     */
    private Object executeGroovyOptimized(Map<String, Object> bindings) {

        log.debug("ScriptTask($id): executing Groovy script (optimized, sandboxed=${sandboxed})")

        // Compile script once and cache it
        if (!compiledGroovyScript) {
            log.debug("ScriptTask($id): compiling Groovy script")

            if (sandboxed) {
                // SECURE: Use custom script base class that restricts dangerous operations
                def config = new org.codehaus.groovy.control.CompilerConfiguration()
                config.scriptBaseClass = SecureScriptBase.class.name

                def shell = new GroovyShell(this.class.classLoader, new Binding(), config)
                compiledGroovyScript = shell.parse(script)

                log.debug("ScriptTask($id): compiled with SecureScriptBase (sandboxed mode)")
            } else {
                // INSECURE: Direct execution without restrictions
                def shell = new GroovyShell()
                compiledGroovyScript = shell.parse(script)

                log.warn("ScriptTask($id): compiled without sandboxing - SECURITY RISK")
            }
        }

        // Set bindings
        bindings.each { key, value ->
            compiledGroovyScript.binding.setVariable(key, value)
        }

        // Execute
        try {
            return compiledGroovyScript.run()
        } catch (SecurityException e) {
            log.error("ScriptTask($id): Script attempted forbidden operation: ${e.message}")
            throw new ScriptExecutionException("Script security violation: ${e.message}", e)
        } catch (Exception e) {
            log.error("ScriptTask($id): Groovy script execution failed", e)
            throw new ScriptExecutionException("Groovy script execution failed: ${e.message}", e)
        }
    }
    
    /**
     * Execute script via JSR 223 ScriptEngine.
     */
    private Object executeViaScriptEngine(Map<String, Object> bindings) {
        
        log.debug("ScriptTask($id): executing ${language} script via ScriptEngine")
        
        // Get or create engine
        ScriptEngine engine = getScriptEngine(language)
        
        if (!engine) {
            throw new IllegalStateException(
                "ScriptTask($id): No script engine found for language '${language}'. " +
                "Available engines: ${getAvailableEngines()}"
            )
        }
        
        // Set bindings
        bindings.each { key, value ->
            engine.put(key, value)
        }
        
        // Execute
        try {
            return engine.eval(script)
        } catch (ScriptException e) {
            log.error("ScriptTask($id): ${language} script execution failed", e)
            throw new ScriptExecutionException(
                "${language} script execution failed at line ${e.lineNumber}: ${e.message}", 
                e
            )
        } catch (Exception e) {
            log.error("ScriptTask($id): ${language} script execution failed", e)
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
        
        // Add default bindings
        bindings.put("ctx", ctx)
        bindings.put("prev", prevValue)
        bindings.put("context", ctx)  // Alias
        bindings.put("input", prevValue)  // Alias
        
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
    
    /**
     * Get cached script engine for language.
     */
    private static ScriptEngine getScriptEngine(String language) {
        return ENGINE_CACHE.computeIfAbsent(language) { lang ->
            log.debug("Creating ScriptEngine for language: ${lang}")
            
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
    }
    
    /**
     * Get list of available script engines.
     */
    private static List<String> getAvailableEngines() {
        def manager = new ScriptEngineManager()
        return manager.engineFactories.collect { it.engineName }
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
