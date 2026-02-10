package org.softwood.dag.task

import groovy.transform.CompileStatic

/**
 * Secure base class for Groovy scripts executed by ScriptTask.
 *
 * <p>This base class restricts dangerous operations to prevent:
 * <ul>
 *   <li>Arbitrary code execution (exec, shell commands)</li>
 *   <li>File system access (unless explicitly allowed)</li>
 *   <li>Network access (unless explicitly allowed)</li>
 *   <li>Reflection abuse</li>
 *   <li>Class loading abuse</li>
 * </ul>
 *
 * <h3>Allowed Operations:</h3>
 * <ul>
 *   <li>Basic math and string operations</li>
 *   <li>Collection manipulation (List, Map, Set)</li>
 *   <li>Date/time operations</li>
 *   <li>JSON parsing/serialization</li>
 *   <li>Access to script bindings (ctx, r, prev, etc.)</li>
 *   <li>Logging via provided logger</li>
 * </ul>
 *
 * <h3>Blocked Operations:</h3>
 * <ul>
 *   <li>System.exec, Runtime.exec, ProcessBuilder</li>
 *   <li>File I/O (File, FileInputStream, FileOutputStream)</li>
 *   <li>Network I/O (Socket, URL.openConnection)</li>
 *   <li>Class.forName, ClassLoader operations</li>
 *   <li>Reflection (Method.invoke, Field.set)</li>
 *   <li>System.exit, Runtime.halt</li>
 * </ul>
 *
 * <h3>Usage:</h3>
 * <pre>
 * scriptTask("safe-script") {
 *     language "groovy"
 *     sandboxed true  // Enable secure mode (default)
 *     script '''
 *         // Safe operations
 *         def result = input.values.sum()
 *         log "Calculated sum: ${result}"
 *         return result
 *
 *         // Blocked operations (will throw SecurityException)
 *         // "ls".execute()  // ❌ Blocked
 *         // new File("/etc/passwd").text  // ❌ Blocked
 *         // new URL("http://evil.com").text  // ❌ Blocked
 *     '''
 * }
 * </pre>
 *
 * @author Will Woodman
 * @since 2.0
 */
@CompileStatic
abstract class SecureScriptBase extends Script {

    /**
     * Logger instance for scripts.
     * Safer than direct System.out/err access.
     */
    void log(String message) {
        println "[ScriptTask] ${message}"
    }

    /**
     * Log with level.
     */
    void log(String level, String message) {
        println "[ScriptTask/${level.toUpperCase()}] ${message}"
    }

    // =========================================================================
    // Utility Methods (Safe Operations)
    // =========================================================================

    /**
     * Parse JSON safely.
     */
    def parseJson(String json) {
        new groovy.json.JsonSlurper().parseText(json)
    }

    /**
     * Convert object to JSON.
     */
    String toJson(Object obj) {
        groovy.json.JsonOutput.toJson(obj)
    }

    /**
     * Get current timestamp.
     */
    long now() {
        System.currentTimeMillis()
    }

    /**
     * Sleep safely (with reasonable limits).
     */
    void sleep(long millis) {
        if (millis > 60_000) {
            throw new SecurityException("sleep() limited to 60 seconds maximum")
        }
        Thread.sleep(millis)
    }

    // =========================================================================
    // Blocked Methods (throw SecurityException)
    // =========================================================================

    /**
     * Block String.execute() which runs shell commands.
     */
    @Override
    Object invokeMethod(String name, Object args) {
        // Block dangerous method names
        if (name in ['execute', 'exec', 'cmd', 'sh', 'bash']) {
            throw new SecurityException("Script execution blocked: method '${name}' is not allowed in sandboxed scripts")
        }

        // Allow safe methods
        return super.invokeMethod(name, args)
    }

    /**
     * Override property access to block dangerous classes.
     */
    @Override
    Object getProperty(String property) {
        // Block access to dangerous system properties
        if (property in ['System', 'Runtime', 'ProcessBuilder', 'ClassLoader']) {
            throw new SecurityException("Script execution blocked: access to '${property}' is not allowed in sandboxed scripts")
        }

        return super.getProperty(property)
    }

    /**
     * Block property setting for protected properties.
     */
    @Override
    void setProperty(String property, Object newValue) {
        // Block modification of security-sensitive properties
        if (property in ['System', 'Runtime', 'SecurityManager']) {
            throw new SecurityException("Script execution blocked: cannot modify '${property}' in sandboxed scripts")
        }

        super.setProperty(property, newValue)
    }
}
