package org.softwood.dag.resilience

import java.time.Duration

/**
 * DSL for configuring resource limits in task graphs.
 * 
 * Usage:
 * <pre>
 * TaskGraph.build {
 *     resourceLimits {
 *         maxConcurrentTasks 5
 *         maxMemoryMB 512
 *         maxQueuedTasks 20
 *         
 *         memoryCheckInterval Duration.ofSeconds(2)
 *         
 *         failFastOnConcurrency true
 *         failFastOnMemory false
 *         
 *         onLimitExceeded { type, current, limit ->
 *             println "LIMIT EXCEEDED: $type ($current/$limit)"
 *         }
 *     }
 *     
 *     // ... tasks ...
 * }
 * </pre>
 */
class ResourceLimitDsl {
    
    private final ResourceLimitPolicy policy = new ResourceLimitPolicy()
    
    /**
     * Enable resource limiting (automatically enabled when limits are set).
     */
    void enabled(boolean value) {
        policy.enabled = value
    }
    
    /**
     * Set maximum number of concurrent tasks.
     */
    void maxConcurrentTasks(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("maxConcurrentTasks must be >= 1")
        }
        policy.maxConcurrentTasks = limit
        policy.enabled = true
    }
    
    /**
     * Set maximum heap memory in MB.
     */
    void maxMemoryMB(long limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("maxMemoryMB must be >= 1")
        }
        policy.maxMemoryMB = limit
        policy.enabled = true
    }
    
    /**
     * Set maximum queued tasks.
     */
    void maxQueuedTasks(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("maxQueuedTasks must be >= 1")
        }
        policy.maxQueuedTasks = limit
        policy.enabled = true
    }
    
    /**
     * Set memory check interval.
     */
    void memoryCheckInterval(Duration interval) {
        if (interval == null || interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("memoryCheckInterval must be positive")
        }
        policy.memoryCheckInterval = interval
    }
    
    /**
     * Set whether to fail immediately when concurrency limit is reached.
     * If false, tasks will queue and wait for slots.
     */
    void failFastOnConcurrency(boolean value) {
        policy.failFastOnConcurrency = value
    }
    
    /**
     * Set whether to fail immediately when memory limit is reached.
     * If false, a warning is logged but execution continues.
     */
    void failFastOnMemory(boolean value) {
        policy.failFastOnMemory = value
    }
    
    /**
     * Set callback for when a limit is exceeded.
     * 
     * Callback receives: (String type, long current, long limit)
     */
    void onLimitExceeded(Closure callback) {
        policy.onLimitExceeded = callback
    }
    
    /**
     * Apply a preset configuration.
     * 
     * Available presets:
     * - "conservative": maxConcurrent=2, maxMemory=256MB
     * - "moderate": maxConcurrent=5, maxMemory=512MB
     * - "aggressive": maxConcurrent=10, maxMemory=1024MB
     * - "unlimited": no limits (disables resource limiting)
     */
    void preset(String presetName) {
        switch (presetName.toLowerCase()) {
            case "conservative":
                maxConcurrentTasks(2)
                maxMemoryMB(256)
                break
                
            case "moderate":
                maxConcurrentTasks(5)
                maxMemoryMB(512)
                break
                
            case "aggressive":
                maxConcurrentTasks(10)
                maxMemoryMB(1024)
                break
                
            case "unlimited":
                policy.enabled = false
                policy.maxConcurrentTasks = null
                policy.maxMemoryMB = null
                policy.maxQueuedTasks = null
                break
                
            default:
                throw new IllegalArgumentException(
                    "Unknown resource limit preset: '$presetName'. " +
                    "Available: conservative, moderate, aggressive, unlimited"
                )
        }
    }
    
    /**
     * Build the final policy.
     */
    ResourceLimitPolicy build() {
        return policy
    }
}
