package org.softwood.dag

import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Thread-safe binding implementation for TaskContext variables.
 * 
 * Uses ConcurrentHashMap for thread-safe concurrent access.
 * Supports Groovy property syntax via propertyMissing.
 * 
 * Usage:
 * <pre>
 * def binding = new TaskBinding()
 * 
 * // Method syntax
 * binding.set('api.url', 'https://api.example.com')
 * def url = binding.get('api.url')
 * 
 * // Property syntax (in DSL closures)
 * binding.apiUrl = 'https://api.example.com'
 * def url = binding.apiUrl
 * 
 * // With defaults
 * def port = binding.get('api.port', 8080)
 * 
 * // Get immutable Map for iteration
 * binding.asImmutableMap().each { key, value ->
 *     println "${key} = ${value}"
 * }
 * 
 * // Search operations
 * def apiVars = binding.prefix('api.')
 * def dbVars = binding.search('database')
 * def urlVars = binding.filter(/.*\.url$/)
 * </pre>
 */
class TaskBinding implements IBinding {
    
    private final ConcurrentHashMap<String, Object> variables = new ConcurrentHashMap<>()
    
    @Override
    Object get(String key) {
        variables.get(key)
    }
    
    @Override
    Object get(String key, Object defaultValue) {
        variables.getOrDefault(key, defaultValue)
    }
    
    @Override
    void set(String key, Object value) {
        variables.put(key, value)
    }
    
    @Override
    boolean has(String key) {
        variables.containsKey(key)
    }
    
    @Override
    Object remove(String key) {
        variables.remove(key)
    }
    
    @Override
    Map<String, Object> getAll() {
        // Return immutable copy
        Collections.unmodifiableMap(new HashMap<>(variables))
    }
    
    @Override
    void clear() {
        variables.clear()
    }
    
    @Override
    Set<String> keys() {
        new HashSet<>(variables.keySet())
    }
    
    @Override
    Collection<Object> values() {
        new ArrayList<>(variables.values())
    }
    
    @Override
    Map<String, Object> filter(String pattern) {
        def regex = Pattern.compile(pattern)
        variables.findAll { key, value ->
            regex.matcher(key).matches()
        }
    }
    
    @Override
    Map<String, Object> search(String substring) {
        variables.findAll { key, value ->
            key.contains(substring)
        }
    }
    
    @Override
    Map<String, Object> prefix(String prefix) {
        variables.findAll { key, value ->
            key.startsWith(prefix)
        }
    }
    
    // =========================================================================
    // Groovy Property Syntax Support
    // =========================================================================
    
    /**
     * Support for property getter syntax: binding.apiUrl
     */
    def propertyMissing(String name) {
        get(name)
    }
    
    /**
     * Support for property setter syntax: binding.apiUrl = '...'
     */
    void propertyMissing(String name, value) {
        set(name, value)
    }
    
    /**
     * Support for map-style access: binding['api.url']
     */
    Object getAt(String key) {
        get(key)
    }
    
    /**
     * Support for map-style setting: binding['api.url'] = '...'
     */
    void putAt(String key, Object value) {
        set(key, value)
    }
    
    // =========================================================================
    // Filtered Globals Methods (flow-template-friendly aliases)
    // For use in groovy.eval scripts: ctx.globals.getKeys(), ctx.globals.getFiltered('mail.*')
    // =========================================================================

    /**
     * Get all keys in the binding.
     * Alias for keys() with a more discoverable name for flow template authors.
     */
    Set<String> getKeys() {
        keys()
    }

    /**
     * Get keys matching a regex pattern.
     * @param pattern Java regex applied to key names
     * @return set of matching keys
     */
    Set<String> getKeys(String pattern) {
        def regex = Pattern.compile(pattern)
        variables.keySet().findAll { regex.matcher(it).matches() } as Set<String>
    }

    /**
     * Get all entries whose key matches a regex pattern.
     * Alias for filter(pattern) with a more descriptive name.
     * @param keyPattern Java regex applied to key names
     * @return immutable map of matching entries
     */
    Map<String, Object> getFiltered(String keyPattern) {
        filter(keyPattern)
    }

    /**
     * Get a value with a default, returning defaultValue when key is absent or value is null.
     * Distinct from get(key, default) which only substitutes on absence.
     * @return stored value, or defaultValue if absent or null
     */
    Object getOrDefault(String key, Object defaultValue) {
        def v = variables.get(key)
        v != null ? v : defaultValue
    }

    /**
     * Check whether a key exists in the binding.
     * Alias for has(key) matching the Map.containsKey() convention.
     */
    boolean containsKey(String key) {
        variables.containsKey(key)
    }

    // =========================================================================
    // Backward Compatibility Methods
    // =========================================================================

    /**
     * Backward compatibility with Map.put()
     */
    Object put(String key, Object value) {
        set(key, value)
        return value
    }
    
    // =========================================================================
    // Convenience Methods
    // =========================================================================
    
    /**
     * Put all entries from a map.
     */
    void putAll(Map<String, Object> map) {
        map.each { k, v -> set(k, v) }
    }
    
    /**
     * Size of binding.
     */
    int size() {
        variables.size()
    }
    
    /**
     * Check if empty.
     */
    boolean isEmpty() {
        variables.isEmpty()
    }
    
    @Override
    String toString() {
        "TaskBinding${variables}"
    }
}
