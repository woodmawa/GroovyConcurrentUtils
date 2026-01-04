package org.softwood.dag.task

import groovy.text.GStringTemplateEngine
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.softwood.security.SecretsResolver

/**
 * Universal resolver for all tasks in the TaskGraph framework.
 * 
 * <p>Provides task closures with unified access to:</p>
 * <ul>
 *   <li><strong>Previous Results:</strong> Access output from predecessor tasks via {@code prev}</li>
 *   <li><strong>Global Context:</strong> Read/write shared state via {@code ctx.globals}</li>
 *   <li><strong>Template Rendering:</strong> Groovy template engine for dynamic content</li>
 *   <li><strong>Credential Resolution:</strong> Secure credential access via SecretsResolver</li>
 * </ul>
 * 
 * <p>Available in all task DSL closures via the 'r' or 'resolver' parameter.</p>
 * 
 * <h2>Basic Usage</h2>
 * <pre>
 * // Access previous task result
 * task("process") { r ->
 *     def userId = r.prev.userId
 *     def email = r.prev.email
 * }
 * 
 * // Read/write globals
 * task("accumulate") { r ->
 *     def count = r.global('processCount', 0)
 *     r.setGlobal('processCount', count + 1)
 * }
 * 
 * // Render templates
 * task("email") { r ->
 *     def body = r.template('''
 *         Hello ${prev.name},
 *         Your order #${prev.orderId} is ready!
 *     ''')
 * }
 * 
 * // Resolve credentials securely
 * task("db-query") { r ->
 *     def password = r.credential('db.password')
 *     def apiKey = r.env('API_KEY')
 * }
 * </pre>
 * 
 * <h2>Credential Resolution</h2>
 * <p>Credentials are resolved from multiple sources in priority order:</p>
 * <ol>
 *   <li>Task context globals ({@code ctx.globals})</li>
 *   <li>Configured SecretManager (Vault, AWS Secrets Manager, Azure Key Vault)</li>
 *   <li>Environment variables</li>
 *   <li>System properties</li>
 *   <li>Default value (if provided)</li>
 * </ol>
 * 
 * <p>Uses the library's {@link org.softwood.security.SecretsResolver} for secure
 * credential management. Configure a SecretManager for production use:</p>
 * 
 * <pre>
 * // Configure Vault (one-time setup)
 * def vaultManager = new VaultSecretManager(
 *     vaultUrl: "https://vault.example.com",
 *     token: System.getenv("VAULT_TOKEN")
 * )
 * vaultManager.initialize()
 * SecretsResolver.setSecretManager(vaultManager)
 * 
 * // Now all tasks can securely resolve credentials
 * task("api-call") { r ->
 *     def apiKey = r.credential('api.key')  // Retrieved from Vault
 * }
 * </pre>
 * 
 * @since 2.1.0
 * @see org.softwood.security.SecretsResolver
 * @see TaskContext
 */
@Slf4j
@CompileStatic
class TaskResolver {
    
    /** Result from previous task (null if no predecessor) */
    final Object prev
    
    /** Task execution context with shared state */
    final TaskContext ctx
    
    /** Groovy template engine for rendering (shared across all resolvers) */
    private static final GStringTemplateEngine templateEngine = new GStringTemplateEngine()
    
    /**
     * Creates a new task resolver.
     * 
     * @param prev Previous task result (may be null)
     * @param ctx Task context (never null)
     */
    TaskResolver(Object prev, TaskContext ctx) {
        this.prev = prev
        this.ctx = ctx
    }
    
    // =========================================================================
    // Context Globals Access
    // =========================================================================
    
    /**
     * Get all global variables as a map.
     * 
     * @return Map of all globals (never null, may be empty)
     */
    Map<String, Object> getGlobals() {
        return ctx.globals ?: [:]
    }
    
    /**
     * Get a specific global value.
     * 
     * @param key Global variable name
     * @return Value or null if not found
     */
    Object global(String key) {
        return ctx.globals?.get(key)
    }
    
    /**
     * Get a global value with a default fallback.
     * 
     * @param key Global variable name
     * @param defaultValue Value to return if key not found
     * @return Global value or default
     */
    Object global(String key, Object defaultValue) {
        def globals = ctx.globals
        return globals?.containsKey(key) ? globals[key] : defaultValue
    }
    
    /**
     * Set a global variable.
     * 
     * @param key Variable name
     * @param value Value to store
     */
    void setGlobal(String key, Object value) {
        if (!ctx.globals) {
            log.warn("TaskResolver: ctx.globals is null, cannot set global '${key}'")
            return
        }
        ctx.globals[key] = value
        log.trace("Global set: ${key} = ${value}")
    }
    
    /**
     * Update a global value using a transformation closure.
     * 
     * <p>Useful for incrementing counters or modifying collections:</p>
     * <pre>
     * // Increment counter
     * r.updateGlobal('count') { (it ?: 0) + 1 }
     * 
     * // Add to list
     * r.updateGlobal('results') { (it ?: []) + newResult }
     * </pre>
     * 
     * @param key Global variable name
     * @param updater Closure that receives current value and returns new value
     */
    void updateGlobal(String key, Closure updater) {
        def currentValue = global(key)
        def newValue = updater.call(currentValue)
        setGlobal(key, newValue)
    }
    
    /**
     * Check if a global variable exists.
     * 
     * @param key Variable name
     * @return true if key exists in globals
     */
    boolean hasGlobal(String key) {
        return ctx.globals?.containsKey(key) ?: false
    }
    
    /**
     * Remove a global variable.
     * 
     * @param key Variable name to remove
     * @return Previous value or null
     */
    Object removeGlobal(String key) {
        return ctx.globals?.remove(key)
    }
    
    // =========================================================================
    // Template Rendering
    // =========================================================================
    
    /**
     * Render an inline Groovy template.
     * 
     * <p>Templates have access to:</p>
     * <ul>
     *   <li>{@code prev} - Previous task result</li>
     *   <li>{@code ctx} - Task context</li>
     *   <li>{@code globals} - Context globals map</li>
     *   <li>{@code r} - This resolver (for nested calls)</li>
     *   <li>Any additional bindings provided</li>
     * </ul>
     * 
     * <h3>Example:</h3>
     * <pre>
     * def html = r.template('''
     *     &lt;h1&gt;Order #${prev.orderId}&lt;/h1&gt;
     *     &lt;p&gt;Customer: ${prev.customerName}&lt;/p&gt;
     *     &lt;p&gt;Total: \$${prev.total}&lt;/p&gt;
     *     &lt;p&gt;Company: ${globals.companyName}&lt;/p&gt;
     * ''')
     * </pre>
     * 
     * @param templateText Template source (GString syntax)
     * @param additionalBinding Extra variables to add to binding
     * @return Rendered template as string
     */
    String template(String templateText, Map additionalBinding = [:]) {
        if (!templateText) return ""
        
        def binding = [
            prev: prev,
            ctx: ctx,
            globals: getGlobals(),
            r: this  // Self-reference for nested resolver calls
        ] + additionalBinding
        
        try {
            def template = templateEngine.createTemplate(templateText)
            return template.make(binding).toString()
        } catch (Exception e) {
            log.error("Template rendering failed", e)
            throw new IllegalStateException("Template rendering failed: ${e.message}", e)
        }
    }
    
    /**
     * Render a template from a file.
     * 
     * @param filepath Path to template file
     * @param additionalBinding Extra variables to add to binding
     * @return Rendered template as string
     * @throws FileNotFoundException if template file not found
     */
    String templateFile(String filepath, Map additionalBinding = [:]) {
        def file = new File(filepath)
        if (!file.exists()) {
            throw new FileNotFoundException("Template file not found: ${filepath}")
        }
        
        def templateText = file.text
        return template(templateText, additionalBinding)
    }
    
    /**
     * Render a template from a classpath resource.
     * 
     * @param resourcePath Classpath resource path (e.g., "/templates/email.txt")
     * @param additionalBinding Extra variables to add to binding
     * @return Rendered template as string
     * @throws IllegalArgumentException if resource not found
     */
    String templateResource(String resourcePath, Map additionalBinding = [:]) {
        def stream = getClass().getResourceAsStream(resourcePath)
        if (!stream) {
            throw new IllegalArgumentException("Template resource not found: ${resourcePath}")
        }
        
        def templateText = stream.text
        return template(templateText, additionalBinding)
    }
    
    // =========================================================================
    // Credential Resolution (Integrated with SecretsResolver)
    // =========================================================================
    
    /**
     * Resolve a credential using the library's SecretsResolver.
     * 
     * <p>Resolution order (via SecretsResolver):</p>
     * <ol>
     *   <li>Task context globals</li>
     *   <li>Configured SecretManager (Vault/AWS/Azure if set)</li>
     *   <li>Environment variables (exact key, then transformed)</li>
     *   <li>System properties</li>
     *   <li>Default value</li>
     * </ol>
     * 
     * <p><strong>Security:</strong> Integrates with org.softwood.security.SecretsResolver
     * for production-grade secret management. Configure a SecretManager (Vault, AWS, Azure)
     * for secure credential storage:</p>
     * 
     * <pre>
     * // One-time SecretManager configuration
     * SecretsResolver.setSecretManager(vaultManager)
     * 
     * // All tasks now use Vault
     * task("secure-task") { r ->
     *     def apiKey = r.credential('api.key')
     * }
     * </pre>
     * 
     * @param key Credential key (e.g., "db.password", "smtp.username")
     * @param defaultValue Optional default value
     * @return Resolved credential value
     * @throws IllegalStateException if credential not found and no default
     */
    String credential(String key, String defaultValue = null) {
        if (!key) {
            throw new IllegalArgumentException("Credential key cannot be null or empty")
        }
        
        // Try context globals first (for task-specific overrides)
        def value = global(key)
        if (value) {
            log.trace("Credential '${key}' resolved from ctx.globals")
            return value.toString()
        }
        
        // Use SecretsResolver for remaining sources
        value = SecretsResolver.resolve(key, defaultValue)
        if (value) {
            log.trace("Credential '${key}' resolved via SecretsResolver")
            return value
        }
        
        // If still not found and no default, throw
        if (defaultValue != null) {
            return defaultValue
        }
        
        throw new IllegalStateException(
            "Credential not found: '${key}'. " +
            "Searched: ctx.globals, SecretsResolver (SecretManager, env vars, system properties)"
        )
    }
    
    /**
     * Resolve an environment variable with automatic key transformation.
     * 
     * <p>Transforms keys to standard environment variable format:</p>
     * <ul>
     *   <li>"smtp.password" → "SMTP_PASSWORD"</li>
     *   <li>"db-host" → "DB_HOST"</li>
     *   <li>"api.key" → "API_KEY"</li>
     * </ul>
     * 
     * <p>Also tries the exact key as provided.</p>
     * 
     * @param key Environment variable key
     * @param defaultValue Optional default value
     * @return Environment variable value
     * @throws IllegalStateException if not found and no default
     */
    String env(String key, String defaultValue = null) {
        if (!key) {
            throw new IllegalArgumentException("Environment variable key cannot be null or empty")
        }
        
        // Transform to standard env var format: "smtp.password" → "SMTP_PASSWORD"
        def envKey = key.toUpperCase().replace('.', '_').replace('-', '_')
        
        // Try transformed key first
        def value = System.getenv(envKey)
        if (value) {
            log.trace("Environment variable '${envKey}' found")
            return value
        }
        
        // Try exact key
        value = System.getenv(key)
        if (value) {
            log.trace("Environment variable '${key}' found")
            return value
        }
        
        // Use default or throw
        if (defaultValue != null) {
            return defaultValue
        }
        
        throw new IllegalStateException(
            "Environment variable not found: '${envKey}' (also tried '${key}')"
        )
    }
    
    /**
     * Resolve a system property.
     * 
     * @param key System property key
     * @param defaultValue Optional default value
     * @return System property value
     * @throws IllegalStateException if not found and no default
     */
    String sysprop(String key, String defaultValue = null) {
        if (!key) {
            throw new IllegalArgumentException("System property key cannot be null or empty")
        }
        
        def value = System.getProperty(key)
        if (value) {
            log.trace("System property '${key}' found")
            return value
        }
        
        if (defaultValue != null) {
            return defaultValue
        }
        
        throw new IllegalStateException("System property not found: '${key}'")
    }
    
    /**
     * Check if a credential exists (doesn't throw).
     * 
     * @param key Credential key
     * @return true if credential can be resolved
     */
    boolean hasCredential(String key) {
        try {
            credential(key)
            return true
        } catch (IllegalStateException e) {
            return false
        }
    }
    
    // =========================================================================
    // Convenience Resolution Methods
    // =========================================================================
    
    /**
     * Resolve a value that might be static, dynamic, or a closure.
     * 
     * <ul>
     *   <li>Closure → Call with this resolver</li>
     *   <li>GString → Evaluate in current context</li>
     *   <li>Other → Return as-is</li>
     * </ul>
     * 
     * @param value Value to resolve
     * @return Resolved value
     */
    Object resolve(Object value) {
        if (value instanceof Closure) {
            return value.call(this)
        }
        return value
    }
    
    /**
     * Resolve multiple values.
     * 
     * @param values List of values to resolve
     * @return List of resolved values
     */
    List resolveAll(List values) {
        return values.collect { resolve(it) }
    }
    
    /**
     * Resolve all values in a map.
     * 
     * @param map Map with potentially dynamic values
     * @return Map with resolved values
     */
    Map resolveMap(Map map) {
        return map.collectEntries { k, v -> [k, resolve(v)] }
    }
}
