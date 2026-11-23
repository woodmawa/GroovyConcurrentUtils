package org.softwood.dag.task

import groovy.transform.CompileStatic

/**
 * The base class of a restricted ScriptTask script: the helpers a script can call by name.
 *
 * <ul>
 *   <li>{@code log(message)}, {@code log(level, message)}: to stderr, so an MCP stdio transport is
 *       not disturbed;</li>
 *   <li>{@code parseJson(text)}, {@code toJson(value)};</li>
 *   <li>{@code now()}: the clock in milliseconds;</li>
 *   <li>{@code sleep(millis)}: at most 60 seconds.</li>
 * </ul>
 *
 * <p>1.4.1: this class is not what restricts a script. Since 1.4.0 that is done by
 * {@link org.softwood.dag.script.RestrictedCompiler} (the script profile, checked when the graph is
 * validated) and {@link org.softwood.dag.script.ScriptGuard} (the receiver of every call, checked
 * while the script runs). The name checks in {@code invokeMethod}, {@code getProperty} and
 * {@code setProperty} below are left from before 1.4.0; they are a second line only, and the
 * {@code sandboxed} word they were written for is now {@code trusted false}, the default.</p>
 *
 * <p>A flow can give a script a base class of its own with {@code customScriptBaseClass}; the
 * script is still compiled under the profile.</p>
 *
 * @author Will Woodman
 * @since 2.0
 */
@CompileStatic
abstract class SecureScriptBase extends Script {

    /**
     * Logger instance for scripts.
     * Routes to stderr to avoid polluting stdout in STDIO-based MCP transports.
     */
    void log(String message) {
        System.err.println "[ScriptTask] ${message}"
    }

    /**
     * Log with level.
     */
    void log(String level, String message) {
        System.err.println "[ScriptTask/${level.toUpperCase()}] ${message}"
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
