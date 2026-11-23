package org.softwood.dag.task

import groovy.util.logging.Slf4j
import org.softwood.promise.Promise

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Circuit Breaker Task - prevents cascading failures by failing fast.
 * 
 * <p>Wraps another task and monitors its success/failure rate. If failures exceed
 * a threshold, the circuit "opens" and subsequent requests fail immediately without
 * calling the wrapped task. After a timeout, the circuit enters "half-open" state
 * to test if the service has recovered.</p>
 * 
 * <h3>States:</h3>
 * <ul>
 *   <li><b>CLOSED</b> - Normal operation, requests pass through</li>
 *   <li><b>OPEN</b> - Too many failures, fail fast without calling wrapped task</li>
 *   <li><b>HALF_OPEN</b> - Testing recovery, allow limited requests through</li>
 * </ul>
 * 
 * <h3>Usage Examples:</h3>
 * <pre>
 * // Wrap an existing task
 * circuitBreaker("protected-api") {
 *     wrap {
 *         httpTask("external-api") {
 *             url "https://unreliable-service.com/api"
 *         }
 *     }
 *     failureThreshold 5
 *     timeout 30.seconds
 *     resetTimeout 1.minute
 * }
 * 
 * // With custom failure detection
 * circuitBreaker("api-call") {
 *     wrap {
 *         serviceTask("call-api") {
 *             action { ctx, prev -> callExternalService() }
 *         }
 *     }
 *     failureThreshold 3
 *     resetTimeout 30.seconds
 *     
 *     // Custom failure detection
 *     recordFailureOn { exception ->
 *         exception instanceof TimeoutException ||
 *         exception instanceof IOException
 *     }
 *     
 *     // Callbacks for monitoring
 *     onOpen { ctx, failures ->
 *         log.warn("Circuit opened after $failures failures")
 *     }
 *     onClose { ctx ->
 *         log.info("Circuit closed - service recovered")
 *     }
 * }
 * 
 * // With half-open success threshold
 * circuitBreaker("flaky-service") {
 *     wrap { httpTask("service") { url "..." } }
 *     failureThreshold 5
 *     resetTimeout 1.minute
 *     halfOpenSuccessThreshold 2  // Need 2 successes to close
 * }
 * </pre>
 */
@Slf4j
class CircuitBreakerTask extends TaskBase<Object> {
    
    // =========================================================================
    // Configuration
    // =========================================================================
    
    /** Number of consecutive failures before opening circuit (default: 5) */
    int failureThreshold = 5
    
    /** Timeout for circuit breaker operations (default: 30 seconds) */
    Duration timeout = Duration.ofSeconds(30)
    
    /** Time to wait before attempting recovery (default: 1 minute) */
    Duration resetTimeout = Duration.ofMinutes(1)
    
    /** Number of successful requests in HALF_OPEN before closing (default: 1) */
    int halfOpenSuccessThreshold = 1
    
    /** The task being protected by this circuit breaker */
    ITask wrappedTask
    
    /** Predicate to determine if an exception should count as a failure */
    Closure<Boolean> failureDetector
    
    /** Callback when circuit opens */
    Closure onOpenCallback
    
    /** Callback when circuit closes */
    Closure onCloseCallback
    
    /** Callback when circuit transitions to half-open */
    Closure onHalfOpenCallback
    
    // =========================================================================
    // State Management (Thread-Safe)
    // =========================================================================
    
    /** Current circuit state */
    private final AtomicReference<CircuitBreakerState> state = 
        new AtomicReference<>(CircuitBreakerState.CLOSED)
    
    /** Count of consecutive failures */
    private final AtomicInteger failureCount = new AtomicInteger(0)
    
    /** Count of consecutive successes in HALF_OPEN state */
    private final AtomicInteger halfOpenSuccessCount = new AtomicInteger(0)
    
    /** Timestamp when circuit was opened (milliseconds since epoch) */
    private final AtomicLong openedAt = new AtomicLong(0)
    
    /** Timestamp when circuit will transition to HALF_OPEN */
    private final AtomicLong resetAt = new AtomicLong(0)
    
    // =========================================================================
    // Statistics (Thread-Safe)
    // =========================================================================
    
    /** Total number of requests */
    private final AtomicLong totalRequests = new AtomicLong(0)
    
    /** Total number of successful requests */
    private final AtomicLong successfulRequests = new AtomicLong(0)
    
    /** Total number of failed requests */
    private final AtomicLong failedRequests = new AtomicLong(0)
    
    /** Total number of rejected requests (circuit open) */
    private final AtomicLong rejectedRequests = new AtomicLong(0)
    
    /** Number of times circuit has opened */
    private final AtomicInteger openCount = new AtomicInteger(0)
    
    // =========================================================================
    // Constructor
    // =========================================================================
    
    CircuitBreakerTask(String id, String name, TaskContext ctx) {
        super(id, name, ctx)
    }
    
    // =========================================================================
    // DSL Methods
    // =========================================================================
    
    /**
     * Specify the task to wrap with circuit breaker protection.
     * 
     * Usage:
     *   wrap {
     *       httpTask("api") { url "..." }
     *   }
     */
    void wrap(@DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = TaskContext) Closure taskClosure) {
        // The closure will be evaluated to create the wrapped task
        // This is typically called from the DSL
        // Store the closure for later evaluation
        this.wrappedTask = taskClosure.call()
    }
    
    /**
     * Set the wrapped task directly (programmatic API).
     */
    void setWrappedTask(ITask task) {
        this.wrappedTask = task
    }
    
    /**
     * Set failure threshold.
     */
    void failureThreshold(int threshold) {
        if (threshold <= 0) {
            throw new IllegalArgumentException("Failure threshold must be > 0")
        }
        this.failureThreshold = threshold
    }
    
    /**
     * Set timeout duration.
     */
    void timeout(Duration duration) {
        this.timeout = duration
    }
    
    /**
     * Set reset timeout duration.
     */
    void resetTimeout(Duration duration) {
        this.resetTimeout = duration
    }
    
    /**
     * Set half-open success threshold.
     */
    void halfOpenSuccessThreshold(int threshold) {
        if (threshold <= 0) {
            throw new IllegalArgumentException("Half-open success threshold must be > 0")
        }
        this.halfOpenSuccessThreshold = threshold
    }
    
    /**
     * Specify custom failure detection logic.
     * 
     * Usage:
     *   recordFailureOn { exception ->
     *       exception instanceof TimeoutException
     *   }
     */
    void recordFailureOn(Closure<Boolean> detector) {
        this.failureDetector = detector
    }
    
    /**
     * Callback when circuit opens.
     * 
     * Usage:
     *   onOpen { ctx, failureCount ->
     *       log.warn("Circuit opened: $failureCount failures")
     *   }
     */
    void onOpen(Closure callback) {
        this.onOpenCallback = callback
    }
    
    /**
     * Callback when circuit closes.
     * 
     * Usage:
     *   onClose { ctx ->
     *       log.info("Circuit closed - recovered")
     *   }
     */
    void onClose(Closure callback) {
        this.onCloseCallback = callback
    }
    
    /**
     * Callback when circuit enters half-open state.
     * 
     * Usage:
     *   onHalfOpen { ctx ->
     *       log.info("Testing recovery...")
     *   }
     */
    void onHalfOpen(Closure callback) {
        this.onHalfOpenCallback = callback
    }
    
    // =========================================================================
    // Task Execution
    // =========================================================================
    
    @Override
    protected Promise<Object> runTask(TaskContext ctx, Object prevValue) {
        if (!wrappedTask) {
            return ctx.promiseFactory.executeAsync {
                throw new IllegalStateException(
                    "CircuitBreakerTask ${id}: no wrapped task specified. Use wrap { } to specify task."
                )
            }
        }
        
        totalRequests.incrementAndGet()
        
        // Check current state and potentially transition
        def currentState = checkAndTransitionState()
        
        log.debug "CircuitBreaker ${id}: state=${currentState}, failures=${failureCount.get()}"
        
        // Handle based on current state
        switch (currentState) {
            case CircuitBreakerState.OPEN:
                return handleOpen(ctx)
                
            case CircuitBreakerState.HALF_OPEN:
                return handleHalfOpen(ctx, prevValue)
                
            case CircuitBreakerState.CLOSED:
            default:
                return handleClosed(ctx, prevValue)
        }
    }
    
    /**
     * Handle request when circuit is CLOSED (normal operation).
     */
    private Promise<Object> handleClosed(TaskContext ctx, Object prevValue) {
        return executeWrappedTask(ctx, prevValue)
    }
    
    /**
     * Handle request when circuit is OPEN (fail fast).
     */
    private Promise<Object> handleOpen(TaskContext ctx) {
        rejectedRequests.incrementAndGet()
        
        long resetTimeMs = resetAt.get()
        int failures = failureCount.get()
        
        def exception = new CircuitBreakerOpenException(
            "Circuit breaker is OPEN for task ${wrappedTask?.id ?: id} - failing fast. " +
            "Circuit opened after ${failures} consecutive failures. " +
            "Will attempt recovery after reset timeout.",
            wrappedTask?.id ?: id,
            CircuitBreakerState.OPEN,
            failures,
            resetTimeMs
        )
        
        log.warn "CircuitBreaker ${id}: OPEN - rejecting request. ${exception.resetMessage}"
        
        return ctx.promiseFactory.executeAsync {
            throw exception
        }
    }
    
    /**
     * Handle request when circuit is HALF_OPEN (testing recovery).
     */
    private Promise<Object> handleHalfOpen(TaskContext ctx, Object prevValue) {
        log.debug "CircuitBreaker ${id}: HALF_OPEN - allowing test request through"
        return executeWrappedTask(ctx, prevValue)
    }
    
    /**
     * Execute the wrapped task and handle success/failure.
     */
    private Promise<Object> executeWrappedTask(TaskContext ctx, Object prevValue) {
        def currentState = state.get()
        
        return ctx.promiseFactory.executeAsync {
            try {
                // Execute wrapped task
                def result = wrappedTask.execute(ctx.promiseFactory.createPromise(prevValue)).get()
                
                // Success!
                onSuccess(currentState)
                
                return result
                
            } catch (Exception e) {
                // Check if this counts as a failure
                if (shouldRecordFailure(e)) {
                    onFailure(currentState, e)
                } else {
                    log.debug "CircuitBreaker ${id}: exception not counted as failure: ${e.class.simpleName}"
                }
                
                throw e
            }
        }
    }
    
    /**
     * Handle successful execution.
     */
    private void onSuccess(CircuitBreakerState previousState) {
        successfulRequests.incrementAndGet()
        
        if (previousState == CircuitBreakerState.HALF_OPEN) {
            // Increment half-open success counter
            int successes = halfOpenSuccessCount.incrementAndGet()
            
            log.debug "CircuitBreaker ${id}: HALF_OPEN success ${successes}/${halfOpenSuccessThreshold}"
            
            // Check if we should close the circuit
            if (successes >= halfOpenSuccessThreshold) {
                transitionToClosed()
            }
        } else {
            // In CLOSED state, reset failure count on success
            failureCount.set(0)
        }
    }
    
    /**
     * Handle failed execution.
     */
    private void onFailure(CircuitBreakerState previousState, Exception exception) {
        failedRequests.incrementAndGet()
        
        int failures = failureCount.incrementAndGet()
        
        log.warn "CircuitBreaker ${id}: failure ${failures}/${failureThreshold} - ${exception.class.simpleName}"
        
        if (previousState == CircuitBreakerState.HALF_OPEN) {
            // Failure in HALF_OPEN -> back to OPEN
            log.warn "CircuitBreaker ${id}: HALF_OPEN test failed - reopening circuit"
            transitionToOpen()
        } else if (failures >= failureThreshold) {
            // Too many failures in CLOSED -> open circuit
            transitionToOpen()
        }
    }
    
    /**
     * Determine if an exception should be recorded as a failure.
     */
    private boolean shouldRecordFailure(Exception exception) {
        if (failureDetector) {
            return failureDetector.call(exception)
        }
        
        // Default: all exceptions are failures
        return true
    }
    
    // =========================================================================
    // State Transitions
    // =========================================================================
    
    /**
     * Check state and potentially transition based on timeout.
     */
    private CircuitBreakerState checkAndTransitionState() {
        def currentState = state.get()
        
        if (currentState == CircuitBreakerState.OPEN) {
            // Check if reset timeout has expired
            long now = System.currentTimeMillis()
            long resetTime = resetAt.get()
            
            if (now >= resetTime) {
                // Transition to HALF_OPEN
                if (state.compareAndSet(CircuitBreakerState.OPEN, CircuitBreakerState.HALF_OPEN)) {
                    halfOpenSuccessCount.set(0)
                    log.info "CircuitBreaker ${id}: Transitioning OPEN -> HALF_OPEN (testing recovery)"
                    
                    if (onHalfOpenCallback) {
                        try {
                            onHalfOpenCallback.call(ctx)
                        } catch (Exception e) {
                            log.error "CircuitBreaker ${id}: error in onHalfOpen callback", e
                        }
                    }
                    
                    return CircuitBreakerState.HALF_OPEN
                }
            }
        }
        
        return currentState
    }
    
    /**
     * Transition to OPEN state.
     */
    private void transitionToOpen() {
        if (state.compareAndSet(CircuitBreakerState.CLOSED, CircuitBreakerState.OPEN) ||
            state.compareAndSet(CircuitBreakerState.HALF_OPEN, CircuitBreakerState.OPEN)) {
            
            long now = System.currentTimeMillis()
            openedAt.set(now)
            resetAt.set(now + resetTimeout.toMillis())
            
            openCount.incrementAndGet()
            
            int failures = failureCount.get()
            
            log.error "CircuitBreaker ${id}: Transitioning to OPEN after ${failures} failures. " +
                     "Will attempt recovery in ${resetTimeout.toSeconds()} seconds"
            
            if (onOpenCallback) {
                try {
                    onOpenCallback.call(ctx, failures)
                } catch (Exception e) {
                    log.error "CircuitBreaker ${id}: error in onOpen callback", e
                }
            }
        }
    }
    
    /**
     * Transition to CLOSED state.
     */
    private void transitionToClosed() {
        if (state.compareAndSet(CircuitBreakerState.HALF_OPEN, CircuitBreakerState.CLOSED)) {
            failureCount.set(0)
            halfOpenSuccessCount.set(0)
            
            log.info "CircuitBreaker ${id}: Transitioning HALF_OPEN -> CLOSED (service recovered)"
            
            if (onCloseCallback) {
                try {
                    onCloseCallback.call(ctx)
                } catch (Exception e) {
                    log.error "CircuitBreaker ${id}: error in onClose callback", e
                }
            }
        }
    }
    
    // =========================================================================
    // Public API - State Inspection
    // =========================================================================
    
    /**
     * Get current circuit breaker state.
     */
    CircuitBreakerState getCircuitState() {
        return state.get()
    }
    
    /**
     * Check if circuit is open.
     */
    boolean isCircuitOpen() {
        return state.get() == CircuitBreakerState.OPEN
    }
    
    /**
     * Check if circuit is closed.
     */
    boolean isCircuitClosed() {
        return state.get() == CircuitBreakerState.CLOSED
    }
    
    /**
     * Check if circuit is half-open.
     */
    boolean isCircuitHalfOpen() {
        return state.get() == CircuitBreakerState.HALF_OPEN
    }
    
    /**
     * Get current failure count.
     */
    int getFailureCount() {
        return failureCount.get()
    }
    
    /**
     * Get statistics.
     */
    Map<String, Object> getStats() {
        return [
            state: state.get(),
            failureCount: failureCount.get(),
            halfOpenSuccessCount: halfOpenSuccessCount.get(),
            totalRequests: totalRequests.get(),
            successfulRequests: successfulRequests.get(),
            failedRequests: failedRequests.get(),
            rejectedRequests: rejectedRequests.get(),
            openCount: openCount.get(),
            successRate: calculateSuccessRate(),
            rejectionRate: calculateRejectionRate()
        ]
    }
    
    private double calculateSuccessRate() {
        long total = totalRequests.get()
        if (total == 0) return 0.0
        return (successfulRequests.get() / (double) total) * 100.0
    }
    
    private double calculateRejectionRate() {
        long total = totalRequests.get()
        if (total == 0) return 0.0
        return (rejectedRequests.get() / (double) total) * 100.0
    }
    
    /**
     * Manually reset the circuit breaker (for testing/admin).
     */
    void reset() {
        state.set(CircuitBreakerState.CLOSED)
        failureCount.set(0)
        halfOpenSuccessCount.set(0)
        openedAt.set(0)
        resetAt.set(0)
        log.info "CircuitBreaker ${id}: manually reset to CLOSED"
    }
}
