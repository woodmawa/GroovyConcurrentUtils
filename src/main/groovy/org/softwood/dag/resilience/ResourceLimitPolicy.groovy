package org.softwood.dag.resilience

import java.time.Duration

/**
 * Policy for controlling resource consumption during graph execution.
 * 
 * Supports limits on:
 * - Concurrent task execution
 * - Memory usage
 * - Task queue size
 * 
 * Usage:
 * <pre>
 * def policy = new ResourceLimitPolicy()
 * policy.maxConcurrentTasks = 5
 * policy.maxMemoryMB = 512
 * policy.enabled = true
 * </pre>
 */
class ResourceLimitPolicy {
    
    /** Enable/disable resource limiting */
    boolean enabled = false
    
    /** Maximum number of tasks that can run concurrently (null = unlimited) */
    Integer maxConcurrentTasks = null
    
    /** Maximum heap memory in MB (null = unlimited) */
    Long maxMemoryMB = null
    
    /** Maximum task queue size (null = unlimited) */
    Integer maxQueuedTasks = null
    
    /** How often to check memory usage */
    Duration memoryCheckInterval = Duration.ofSeconds(1)
    
    /** Callback invoked when a limit is exceeded (optional) */
    Closure onLimitExceeded = null
    
    /** Whether to fail fast or queue when concurrent limit reached */
    boolean failFastOnConcurrency = false
    
    /** Whether to fail fast or wait when memory limit reached */
    boolean failFastOnMemory = true
    
    /**
     * Check if any resource limits are actually configured.
     */
    boolean hasLimits() {
        return enabled && (maxConcurrentTasks != null || maxMemoryMB != null || maxQueuedTasks != null)
    }
    
    /**
     * Check if concurrency limit is active.
     */
    boolean hasConcurrencyLimit() {
        return enabled && maxConcurrentTasks != null && maxConcurrentTasks > 0
    }
    
    /**
     * Check if memory limit is active.
     */
    boolean hasMemoryLimit() {
        return enabled && maxMemoryMB != null && maxMemoryMB > 0
    }
    
    /**
     * Check if queue limit is active.
     */
    boolean hasQueueLimit() {
        return enabled && maxQueuedTasks != null && maxQueuedTasks > 0
    }
    
    /**
     * Invoke the onLimitExceeded callback if configured.
     */
    void notifyLimitExceeded(String resourceType, long currentValue, long limitValue) {
        if (onLimitExceeded) {
            try {
                onLimitExceeded.call(resourceType, currentValue, limitValue)
            } catch (Exception e) {
                // Don't let callback errors break resource limiting
                println "Error in onLimitExceeded callback: ${e.message}"
            }
        }
    }
    
    @Override
    String toString() {
        def parts = []
        if (maxConcurrentTasks) parts << "maxConcurrent=${maxConcurrentTasks}"
        if (maxMemoryMB) parts << "maxMemory=${maxMemoryMB}MB"
        if (maxQueuedTasks) parts << "maxQueue=${maxQueuedTasks}"
        return "ResourceLimitPolicy(enabled=$enabled, ${parts.join(', ')})"
    }
}
