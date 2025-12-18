// ═════════════════════════════════════════════════════════════
// CounterMap.groovy
// ═════════════════════════════════════════════════════════════
package org.softwood.actor

import groovy.transform.CompileStatic

/**
 * A map for counting that works reliably in deeply nested Groovy closures.
 * 
 * <p>This class provides a safe alternative to standard Groovy map operations
 * which can fail in deeply nested closures (6+ levels) due to meta-programming
 * and dynamic dispatch issues.</p>
 * 
 * <h2>Problem it Solves</h2>
 * <pre>
 * // FAILS in nested closures:
 * def counts = [:]
 * files.each { file ->
 *     counts[ext] = counts[ext] + 1  // Error: Cannot set read-only property
 * }
 * 
 * // ALSO FAILS:
 * def counts = [:].withDefault { 0 }
 * counts[ext] += 1  // Error: No signature of method: plus
 * </pre>
 * 
 * <h2>Solution</h2>
 * <pre>
 * // WORKS everywhere:
 * def counts = new CounterMap()
 * files.each { file ->
 *     counts.increment(ext)  // Safe!
 * }
 * </pre>
 * 
 * @since 1.0.0
 */
@CompileStatic
class CounterMap {
    private final Map<String, Integer> map = new HashMap<>()
    
    /**
     * Increment the counter for a key.
     * 
     * @param key the key to increment
     * @param by the amount to increment by (default 1)
     */
    void increment(String key, int by = 1) {
        Integer current = map.get(key)
        map.put(key, (current == null ? 0 : current) + by)
    }
    
    /**
     * Decrement the counter for a key.
     * 
     * @param key the key to decrement
     * @param by the amount to decrement by (default 1)
     */
    void decrement(String key, int by = 1) {
        Integer current = map.get(key)
        map.put(key, (current == null ? 0 : current) - by)
    }
    
    /**
     * Get the count for a key (returns 0 if not present).
     * 
     * @param key the key to look up
     * @return the count, or 0 if not present
     */
    Integer get(String key) {
        Integer value = map.get(key)
        return value == null ? 0 : value
    }
    
    /**
     * Set the count for a key explicitly.
     * 
     * @param key the key
     * @param value the count value
     */
    void set(String key, Integer value) {
        if (value == null) {
            map.remove(key)
        } else {
            map.put(key, value)
        }
    }
    
    /**
     * Check if a key exists (has been incremented at least once).
     * 
     * @param key the key to check
     * @return true if the key exists
     */
    boolean containsKey(String key) {
        return map.containsKey(key)
    }
    
    /**
     * Get all keys.
     * 
     * @return set of all keys
     */
    Set<String> keySet() {
        return map.keySet()
    }
    
    /**
     * Get all values (counts).
     * 
     * @return collection of all counts
     */
    Collection<Integer> values() {
        return map.values()
    }
    
    /**
     * Get all entries.
     * 
     * @return set of map entries
     */
    Set<Map.Entry<String, Integer>> entrySet() {
        return map.entrySet()
    }
    
    /**
     * Get the number of keys.
     * 
     * @return number of keys
     */
    int size() {
        return map.size()
    }
    
    /**
     * Check if empty.
     * 
     * @return true if no keys
     */
    boolean isEmpty() {
        return map.isEmpty()
    }
    
    /**
     * Clear all counts.
     */
    void clear() {
        map.clear()
    }
    
    /**
     * Get a copy of the underlying map.
     * 
     * @return defensive copy of the map
     */
    Map<String, Integer> toMap() {
        return new HashMap<>(map)
    }
    
    /**
     * Get total of all counts.
     * 
     * @return sum of all values
     */
    int total() {
        int sum = 0
        for (Integer value : map.values()) {
            if (value != null) {
                sum += value
            }
        }
        return sum
    }
    
    /**
     * Merge another CounterMap into this one.
     * 
     * @param other the other CounterMap to merge
     */
    void merge(CounterMap other) {
        other.map.each { key, value ->
            increment(key, value)
        }
    }
    
    @Override
    String toString() {
        return map.toString()
    }
    
    @Override
    boolean equals(Object obj) {
        if (!(obj instanceof CounterMap)) {
            return false
        }
        return map.equals(((CounterMap)obj).map)
    }
    
    @Override
    int hashCode() {
        return map.hashCode()
    }
}
