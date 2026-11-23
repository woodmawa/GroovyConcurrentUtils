package org.softwood.dag.resilience

import java.time.Duration

/**
 * Configuration policy for Dead Letter Queue behavior.
 * 
 * <p>Defines how the DLQ should behave including size limits, retention,
 * retry behavior, and callbacks.</p>
 * 
 * <h3>Usage:</h3>
 * <pre>
 * def policy = new DeadLetterQueuePolicy(
 *     maxSize: 1000,
 *     maxAge: Duration.ofHours(24),
 *     autoRetry: true,
 *     maxRetries: 3,
 *     retryDelay: Duration.ofMinutes(5)
 * )
 * 
 * // Or use DSL-style configuration
 * def policy = new DeadLetterQueuePolicy()
 * policy.maxSize(1000)
 * policy.maxAge(Duration.ofHours(24))
 * policy.autoRetry(true)
 * policy.onEntryAdded { entry ->
 *     println "Failed: ${entry.taskId}"
 * }
 * </pre>
 */
class DeadLetterQueuePolicy {
    
    // =========================================================================
    // Enabled Flag
    // =========================================================================
    
    /** Whether DLQ capture is enabled (default: false, set to true when configured) */
    boolean enabled = false
    
    // =========================================================================
    // Size and Retention Configuration
    // =========================================================================
    
    /** Maximum number of entries to keep (0 = unlimited, default: 1000) */
    int maxSize = 1000
    
    /** Maximum age of entries before auto-cleanup (null = no auto-cleanup) */
    Duration maxAge = null
    
    /** Behavior when queue is full */
    FullQueueBehavior fullQueueBehavior = FullQueueBehavior.REMOVE_OLDEST
    
    // =========================================================================
    // Retry Configuration
    // =========================================================================
    
    /** Enable automatic retry of failed entries (default: false) */
    boolean autoRetry = false
    
    /** Maximum number of retry attempts per entry (default: 3) */
    int maxRetries = 3
    
    /** Delay between automatic retry attempts (default: 5 minutes) */
    Duration retryDelay = Duration.ofMinutes(5)
    
    /** Strategy for automatic retries */
    RetryStrategy retryStrategy = RetryStrategy.EXPONENTIAL_BACKOFF
    
    /** Filter to determine which exceptions should trigger retry */
    Closure<Boolean> retryableExceptionFilter = null
    
    // =========================================================================
    // Callbacks
    // =========================================================================
    
    /** Callback when entry is added: (DeadLetterQueueEntry) -> void */
    Closure onEntryAdded = null
    
    /** Callback when entry is retried: (DeadLetterQueueEntry, RetryResult) -> void */
    Closure onEntryRetried = null
    
    /** Callback when entry is removed: (DeadLetterQueueEntry) -> void */
    Closure onEntryRemoved = null
    
    /** Callback when queue is full: (int currentSize, int maxSize) -> void */
    Closure onQueueFull = null
    
    /** Callback when retry succeeds: (DeadLetterQueueEntry) -> void */
    Closure onRetrySuccess = null
    
    /** Callback when retry fails: (DeadLetterQueueEntry, Throwable) -> void */
    Closure onRetryFailure = null
    
    // =========================================================================
    // Filtering Configuration
    // =========================================================================
    
    /** Filter to determine if a task failure should be added to DLQ */
    Closure<Boolean> captureFilter = null
    
    /** List of exception types to always capture */
    List<Class<? extends Throwable>> alwaysCaptureExceptions = []
    
    /** List of exception types to never capture */
    List<Class<? extends Throwable>> neverCaptureExceptions = []
    
    /** List of task IDs to always capture failures for */
    List<String> alwaysCaptureTaskIds = []
    
    /** List of task IDs to never capture failures for */
    List<String> neverCaptureTaskIds = []
    
    // =========================================================================
    // DSL Configuration Methods
    // =========================================================================
    
    /**
     * Set maximum queue size.
     */
    DeadLetterQueuePolicy maxSize(int size) {
        if (size < 0) {
            throw new IllegalArgumentException("maxSize must be >= 0")
        }
        this.maxSize = size
        return this
    }
    
    /**
     * Set maximum entry age.
     */
    DeadLetterQueuePolicy maxAge(Duration age) {
        this.maxAge = age
        return this
    }
    
    /**
     * Set behavior when queue is full.
     */
    DeadLetterQueuePolicy fullQueueBehavior(FullQueueBehavior behavior) {
        this.fullQueueBehavior = behavior
        return this
    }
    
    /**
     * Enable or disable automatic retry.
     */
    DeadLetterQueuePolicy autoRetry(boolean enabled) {
        this.autoRetry = enabled
        return this
    }
    
    /**
     * Set maximum retry attempts.
     */
    DeadLetterQueuePolicy maxRetries(int retries) {
        if (retries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0")
        }
        this.maxRetries = retries
        return this
    }
    
    /**
     * Set retry delay.
     */
    DeadLetterQueuePolicy retryDelay(Duration delay) {
        this.retryDelay = delay
        return this
    }
    
    /**
     * Set retry strategy.
     */
    DeadLetterQueuePolicy retryStrategy(RetryStrategy strategy) {
        this.retryStrategy = strategy
        return this
    }
    
    /**
     * Set callback for entry added.
     */
    DeadLetterQueuePolicy onEntryAdded(Closure callback) {
        this.onEntryAdded = callback
        return this
    }
    
    /**
     * Set callback for entry retried.
     */
    DeadLetterQueuePolicy onEntryRetried(Closure callback) {
        this.onEntryRetried = callback
        return this
    }
    
    /**
     * Set callback for entry removed.
     */
    DeadLetterQueuePolicy onEntryRemoved(Closure callback) {
        this.onEntryRemoved = callback
        return this
    }
    
    /**
     * Set callback for queue full.
     */
    DeadLetterQueuePolicy onQueueFull(Closure callback) {
        this.onQueueFull = callback
        return this
    }
    
    /**
     * Set callback for retry success.
     */
    DeadLetterQueuePolicy onRetrySuccess(Closure callback) {
        this.onRetrySuccess = callback
        return this
    }
    
    /**
     * Set callback for retry failure.
     */
    DeadLetterQueuePolicy onRetryFailure(Closure callback) {
        this.onRetryFailure = callback
        return this
    }
    
    /**
     * Set capture filter.
     */
    DeadLetterQueuePolicy captureWhen(Closure<Boolean> filter) {
        this.captureFilter = filter
        return this
    }
    
    /**
     * Set retryable exception filter.
     */
    DeadLetterQueuePolicy retryWhen(Closure<Boolean> filter) {
        this.retryableExceptionFilter = filter
        return this
    }
    
    /**
     * Add exception type to always capture.
     */
    DeadLetterQueuePolicy alwaysCapture(Class<? extends Throwable> exceptionType) {
        if (!this.alwaysCaptureExceptions) {
            this.alwaysCaptureExceptions = []
        }
        this.alwaysCaptureExceptions.add(exceptionType)
        return this
    }
    
    /**
     * Add exception type to never capture.
     */
    DeadLetterQueuePolicy neverCapture(Class<? extends Throwable> exceptionType) {
        if (!this.neverCaptureExceptions) {
            this.neverCaptureExceptions = []
        }
        this.neverCaptureExceptions.add(exceptionType)
        return this
    }
    
    /**
     * Add task ID to always capture.
     */
    DeadLetterQueuePolicy alwaysCaptureTask(String taskId) {
        if (!this.alwaysCaptureTaskIds) {
            this.alwaysCaptureTaskIds = []
        }
        this.alwaysCaptureTaskIds.add(taskId)
        return this
    }
    
    /**
     * Add task ID to never capture.
     */
    DeadLetterQueuePolicy neverCaptureTask(String taskId) {
        if (!this.neverCaptureTaskIds) {
            this.neverCaptureTaskIds = []
        }
        this.neverCaptureTaskIds.add(taskId)
        return this
    }
    
    // =========================================================================
    // Validation Methods
    // =========================================================================
    
    /**
     * Check if a failure should be captured.
     * 
     * Precedence order:
     * 1. never capture task ID → reject (highest priority)
     * 2. always capture task ID → accept (beats exception filters)
     * 3. never capture exception → reject
     * 4. always capture exception → accept
     * 5. custom filter
     * 6. default: accept
     */
    boolean shouldCapture(String taskId, Throwable exception) {
        // Priority 1: Check never capture task IDs (highest priority)
        if (neverCaptureTaskIds && taskId in neverCaptureTaskIds) {
            return false
        }
        
        // Priority 2: Check always capture task IDs (beats exception filters)
        if (alwaysCaptureTaskIds && taskId in alwaysCaptureTaskIds) {
            return true
        }
        
        // Priority 3: Check never capture exceptions
        if (neverCaptureExceptions && neverCaptureExceptions.any { it.isInstance(exception) }) {
            return false
        }
        
        // Priority 4: Check always capture exceptions
        if (alwaysCaptureExceptions && alwaysCaptureExceptions.any { it.isInstance(exception) }) {
            return true
        }
        
        // Priority 5: Apply custom filter if provided
        if (captureFilter) {
            return captureFilter.call(taskId, exception)
        }
        
        // Priority 6: Default - capture all failures
        return true
    }
    
    /**
     * Check if an exception is retryable.
     */
    boolean isRetryable(Throwable exception) {
        if (retryableExceptionFilter) {
            return retryableExceptionFilter.call(exception)
        }
        
        // Default: all exceptions are retryable
        return true
    }
    
    // =========================================================================
    // Preset Policies
    // =========================================================================
    
    /**
     * Create a permissive policy (captures everything, unlimited size).
     */
    static DeadLetterQueuePolicy permissive() {
        def policy = new DeadLetterQueuePolicy(
            maxSize: 0,  // unlimited
            maxAge: null,
            autoRetry: false
        )
        policy.enabled = true
        return policy
    }
    
    /**
     * Create a strict policy (limited size, short retention).
     */
    static DeadLetterQueuePolicy strict() {
        def policy = new DeadLetterQueuePolicy(
            maxSize: 100,
            maxAge: Duration.ofHours(1),
            autoRetry: false,
            fullQueueBehavior: FullQueueBehavior.REJECT_NEW
        )
        policy.enabled = true
        return policy
    }
    
    /**
     * Create a policy with auto-retry enabled.
     */
    static DeadLetterQueuePolicy withAutoRetry() {
        def policy = new DeadLetterQueuePolicy(
            maxSize: 500,
            maxAge: Duration.ofHours(24),
            autoRetry: true,
            maxRetries: 3,
            retryDelay: Duration.ofMinutes(5),
            retryStrategy: RetryStrategy.EXPONENTIAL_BACKOFF
        )
        policy.enabled = true
        return policy
    }
    
    /**
     * Create a debugging policy (captures everything for analysis).
     */
    static DeadLetterQueuePolicy debugging() {
        def policy = new DeadLetterQueuePolicy(
            maxSize: 0,  // unlimited
            maxAge: Duration.ofDays(7),
            autoRetry: false
        )
        policy.enabled = true
        return policy
    }
    
    // =========================================================================
    // Enums
    // =========================================================================
    
    enum FullQueueBehavior {
        /** Remove oldest entry to make room for new entry */
        REMOVE_OLDEST,
        
        /** Reject new entry and keep existing entries */
        REJECT_NEW,
        
        /** Drop new entry silently without error */
        DROP_NEW
    }
    
    enum RetryStrategy {
        /** Fixed delay between retries */
        FIXED_DELAY,
        
        /** Exponential backoff (delay doubles each time) */
        EXPONENTIAL_BACKOFF,
        
        /** Immediate retry (no delay) */
        IMMEDIATE
    }
}
