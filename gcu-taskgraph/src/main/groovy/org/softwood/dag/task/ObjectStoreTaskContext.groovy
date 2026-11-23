package org.softwood.dag.task

import groovy.transform.CompileStatic
import groovy.transform.TypeCheckingMode
import org.softwood.dag.task.objectstorage.*

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Context for ObjectStoreTask execution
 *
 * Provides access to:
 * - Provider instance
 * - Progress tracking
 * - Data emission
 * - Statistics collection
 * - Error tracking
 */
@CompileStatic
class ObjectStoreTaskContext {
    
    /** Parent task reference */
    ObjectStoreTask task
    
    /** Primary provider */
    ObjectStoreProvider provider
    
    /** Additional named providers for multi-cloud operations */
    private Map<String, ObjectStoreProvider> additionalProviders = [:]
    
    /** Context data from upstream tasks */
    Map<String, Object> contextData = [:]
    
    /** Emitted data (for downstream consumption) */
    List<Object> emittedData = Collections.synchronizedList([])
    
    /** Statistics tracking */
    private Map<String, List<Number>> statistics = new ConcurrentHashMap<>()
    
    /** Error tracking */
    private Map<ObjectRef, Exception> errors = new ConcurrentHashMap<>()
    
    /** Progress counters */
    private AtomicInteger processedCount = new AtomicInteger(0)
    int objectsTotal = 0
    
    // =========================================================================
    // Provider Access
    // =========================================================================
    
    /**
     * Get the primary provider
     */
    ObjectStoreProvider provider() {
        return provider
    }
    
    /**
     * Get a named provider (for multi-cloud operations)
     */
    ObjectStoreProvider getProvider(String providerId) {
        return additionalProviders.get(providerId)
    }
    
    /**
     * Register an additional provider
     */
    void registerProvider(String providerId, ObjectStoreProvider provider) {
        additionalProviders.put(providerId, provider)
    }
    
    // =========================================================================
    // Data Emission
    // =========================================================================
    
    /**
     * Emit data to downstream tasks
     */
    void emit(Object data) {
        emittedData.add(data)
    }
    
    /**
     * Get all emitted data
     */
    List<Object> emitted() {
        return emittedData
    }
    
    // =========================================================================
    // Statistics Tracking
    // =========================================================================
    
    /**
     * Track a statistic
     */
    void track(String name, Number value) {
        statistics.computeIfAbsent(name) { Collections.synchronizedList([]) }.add(value)
    }
    
    /**
     * Get statistics summary for a metric
     */
    @CompileStatic(TypeCheckingMode.SKIP)
    Map<String, Object> stats(String name) {
        def values = statistics.get(name)
        if (!values || values.isEmpty()) {
            return [count: 0, sum: 0, average: 0, min: 0, max: 0]
        }
        
        def doubles = values.collect { it.doubleValue() }
        def sum = doubles.sum() ?: 0.0d
        return [
            count: doubles.size(),
            sum: sum,
            average: sum / doubles.size(),
            min: doubles.min() ?: 0,
            max: doubles.max() ?: 0
        ]
    }
    
    /**
     * Get all statistics
     */
    @CompileStatic(TypeCheckingMode.SKIP)
    Map<String, Map<String, Object>> allStats() {
        def result = [:] as Map<String, Map<String, Object>>
        statistics.keySet().each { name ->
            result[name] = stats(name)
        }
        return result
    }
    
    // =========================================================================
    // Progress Tracking
    // =========================================================================
    
    /**
     * Increment processed count (thread-safe)
     */
    void incrementProcessed() {
        processedCount.incrementAndGet()
    }
    
    /**
     * Get number of objects processed
     */
    int objectsProcessed() {
        return processedCount.get()
    }
    
    /**
     * Get total objects to process
     */
    int objectsTotal() {
        return objectsTotal
    }
    
    /**
     * Get progress percentage
     */
    double progress() {
        if (objectsTotal == 0) return 0.0
        return (objectsProcessed() * 100.0d) / objectsTotal
    }
    
    // =========================================================================
    // Error Tracking
    // =========================================================================
    
    /**
     * Record an error for an object
     */
    void recordError(ObjectRef ref, Exception error) {
        errors.put(ref, error)
    }
    
    /**
     * Get all errors
     */
    Map<ObjectRef, Exception> errors() {
        return new HashMap<>(errors)
    }
    
    /**
     * Check if there were any errors
     */
    boolean hasErrors() {
        return !errors.isEmpty()
    }
    
    /**
     * Get error count
     */
    int errorCount() {
        return errors.size()
    }
    
    // =========================================================================
    // Context Data Access
    // =========================================================================
    
    /**
     * Get context value
     */
    Object get(String key) {
        return contextData.get(key)
    }
    
    /**
     * Set context value
     */
    void set(String key, Object value) {
        contextData.put(key, value)
    }
    
    /**
     * Property access for context data
     */
    @CompileStatic(TypeCheckingMode.SKIP)
    Object propertyMissing(String name) {
        return contextData.get(name)
    }
    
    @CompileStatic(TypeCheckingMode.SKIP)
    void propertyMissing(String name, Object value) {
        contextData.put(name, value)
    }
}
