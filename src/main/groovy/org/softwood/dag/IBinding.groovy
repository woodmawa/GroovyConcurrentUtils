package org.softwood.dag

/**
 * Interface for thread-safe binding/variable storage.
 * 
 * Provides a consistent API for storing and retrieving variables
 * in TaskContext (globals, credentials, etc.) with support for:
 * - Thread-safe concurrent access
 * - Default values
 * - Groovy property syntax
 * - Search and filter operations
 * - Testability via mocking
 */
interface IBinding {
    
    /**
     * Get a variable value.
     * @return value or null if not found
     */
    Object get(String key)
    
    /**
     * Get a variable value with default.
     * @return value or defaultValue if not found
     */
    Object get(String key, Object defaultValue)
    
    /**
     * Set a variable value.
     */
    void set(String key, Object value)
    
    /**
     * Check if variable exists.
     */
    boolean has(String key)
    
    /**
     * Remove a variable.
     * @return previous value or null
     */
    Object remove(String key)
    
    /**
     * Get all variables as a snapshot.
     * @return immutable copy of all variables
     */
    Map<String, Object> getAll()
    
    /**
     * Get an immutable Map view of all bindings.
     * Alias for getAll() - useful when you need to iterate or use Map methods.
     * 
     * Example:
     * <pre>
     * // Iterate over bindings
     * ctx.globals.asImmutableMap().each { key, value ->
     *     println "${key} = ${value}"
     * }
     * 
     * // Filter using Map methods
     * def apiVars = ctx.globals.asImmutableMap().findAll { k, v ->
     *     k.startsWith('api.')
     * }
     * </pre>
     * 
     * @return immutable map containing all key-value pairs
     */
    default Map<String, Object> asImmutableMap() {
        return getAll()
    }
    
    /**
     * Clear all variables.
     */
    void clear()
    
    /**
     * Get all keys.
     */
    Set<String> keys()
    
    /**
     * Get all values.
     */
    Collection<Object> values()
    
    /**
     * Filter variables by key pattern.
     * @param pattern regex pattern to match keys
     * @return map of matching entries
     */
    Map<String, Object> filter(String pattern)
    
    /**
     * Search for keys containing substring.
     * @return map of matching entries
     */
    Map<String, Object> search(String substring)
    
    /**
     * Get variables with specific prefix.
     * @param prefix key prefix
     * @return map of matching entries
     */
    Map<String, Object> prefix(String prefix)
}
