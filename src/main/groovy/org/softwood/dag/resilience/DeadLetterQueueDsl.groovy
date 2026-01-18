package org.softwood.dag.resilience

import java.time.Duration

/**
 * DSL for configuring Dead Letter Queue in tasks.
 * 
 * <p>Provides a fluent interface for configuring DLQ behavior within task definitions.</p>
 * 
 * <h3>Usage:</h3>
 * <pre>
 * serviceTask("api-call") {
 *     action { ctx, prev -> callApi() }
 *     
 *     deadLetterQueue {
 *         maxSize 1000
 *         maxAge 24.hours
 *         
 *         alwaysCapture IOException
 *         neverCapture IllegalArgumentException
 *         
 *         onEntryAdded { entry ->
 *             log.warn "Task failed: ${entry.taskId}"
 *         }
 *     }
 * }
 * </pre>
 */
class DeadLetterQueueDsl {
    
    /** The policy being configured */
    private final DeadLetterQueuePolicy policy
    
    /**
     * Constructor with existing policy.
     */
    DeadLetterQueueDsl(DeadLetterQueuePolicy policy) {
        this.policy = policy
    }
    
    // =========================================================================
    // Size and Retention Configuration
    // =========================================================================
    
    /**
     * Set maximum queue size.
     * 
     * Usage:
     *   maxSize 1000
     *   maxSize 0  // unlimited
     */
    void maxSize(int size) {
        policy.enabled = true
        policy.maxSize(size)
    }
    
    /**
     * Set maximum entry age.
     * 
     * Usage:
     *   maxAge Duration.ofHours(24)
     */
    void maxAge(Duration age) {
        policy.enabled = true
        policy.maxAge(age)
    }
    
    // =========================================================================
    // Callbacks
    // =========================================================================
    
    /**
     * Set callback when entry is added.
     * 
     * Usage:
     *   onEntryAdded { entry ->
     *       println "Failed: ${entry.taskId}"
     *   }
     */
    void onEntryAdded(Closure callback) {
        policy.enabled = true
        policy.onEntryAdded(callback)
    }
    
    /**
     * Set callback when queue is full.
     * 
     * Usage:
     *   onQueueFull { currentSize, maxSize ->
     *       println "Queue full: ${currentSize}/${maxSize}"
     *   }
     */
    void onQueueFull(Closure callback) {
        policy.enabled = true
        policy.onQueueFull(callback)
    }
    
    // =========================================================================
    // Filtering Configuration
    // =========================================================================
    
    /**
     * Set capture filter.
     * 
     * Usage:
     *   captureWhen { taskId, exception ->
     *       exception instanceof IOException
     *   }
     */
    void captureWhen(Closure<Boolean> filter) {
        policy.enabled = true
        policy.captureWhen(filter)
    }
    
    /**
     * Add exception type to always capture.
     * 
     * Usage:
     *   alwaysCapture IOException
     *   alwaysCapture TimeoutException
     */
    void alwaysCapture(Class<? extends Throwable> exceptionType) {
        policy.enabled = true
        policy.alwaysCapture(exceptionType)
    }
    
    /**
     * Add exception type to never capture.
     * 
     * Usage:
     *   neverCapture IllegalArgumentException
     */
    void neverCapture(Class<? extends Throwable> exceptionType) {
        policy.enabled = true
        policy.neverCapture(exceptionType)
    }
    
    /**
     * Add task ID to always capture.
     * 
     * Usage:
     *   alwaysCaptureTask "critical-api"
     */
    void alwaysCaptureTask(String taskId) {
        policy.enabled = true
        policy.alwaysCaptureTask(taskId)
    }
    
    /**
     * Add task ID to never capture.
     * 
     * Usage:
     *   neverCaptureTask "non-critical-task"
     */
    void neverCaptureTask(String taskId) {
        policy.enabled = true
        policy.neverCaptureTask(taskId)
    }
    
    // =========================================================================
    // Presets
    // =========================================================================
    
    /**
     * Apply a predefined DLQ preset.
     * Presets can be overridden by calling other methods after preset().
     * 
     * Available presets:
     * - "permissive": Unlimited size, no expiration (for debugging)
     * - "strict": Limited size (100), short retention (1 hour)
     * - "autoretry": Enables auto-retry with sensible defaults
     * - "debugging": Large storage (unlimited) for analysis
     * 
     * Usage:
     *   preset "permissive"
     *   
     * Or with overrides:
     *   preset "strict"
     *   maxSize 200  // Override preset value
     */
    void preset(String presetName) {
        policy.enabled = true  // Enable when preset is used
        DeadLetterQueuePolicy presetPolicy = null
        
        switch (presetName.toLowerCase()) {
            case "permissive":
                presetPolicy = DeadLetterQueuePolicy.permissive()
                break
            case "strict":
                presetPolicy = DeadLetterQueuePolicy.strict()
                break
            case "autoretry":
            case "auto-retry":
                presetPolicy = DeadLetterQueuePolicy.withAutoRetry()
                break
            case "debugging":
            case "debug":
                presetPolicy = DeadLetterQueuePolicy.debugging()
                break
            default:
                throw new IllegalArgumentException(
                    "Unknown DLQ preset: '${presetName}'. " +
                    "Available presets: permissive, strict, autoretry, debugging"
                )
        }
        
        // Copy preset values to current policy
        copyPresetValues(presetPolicy)
    }
    
    /**
     * Convenience method: configure common settings at once.
     * 
     * Usage:
     *   configure(maxSize: 1000, maxAge: Duration.ofHours(24))
     */
    void configure(Map settings) {
        if (settings.maxSize != null) {
            maxSize(settings.maxSize as int)
        }
        if (settings.maxAge != null) {
            maxAge(settings.maxAge as Duration)
        }
    }
    
    // =========================================================================
    // Helper Methods
    // =========================================================================
    
    /**
     * Copy values from preset policy to current policy.
     */
    private void copyPresetValues(DeadLetterQueuePolicy preset) {
        policy.enabled = preset.enabled  // Preserve enabled flag
        policy.maxSize = preset.maxSize
        policy.maxAge = preset.maxAge
        policy.fullQueueBehavior = preset.fullQueueBehavior
        policy.autoRetry = preset.autoRetry
        policy.maxRetries = preset.maxRetries
        policy.retryDelay = preset.retryDelay
        policy.retryStrategy = preset.retryStrategy
    }
}
