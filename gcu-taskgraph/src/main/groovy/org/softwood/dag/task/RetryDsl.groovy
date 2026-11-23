package org.softwood.dag.task

import java.time.Duration

/**
 * DSL builder for configuring retry behavior.
 * 
 * Provides a fluent interface for setting retry policies on tasks.
 * 
 * <h3>Usage:</h3>
 * <pre>
 * serviceTask("api-call") {
 *     retry {
 *         maxAttempts 5
 *         initialDelay Duration.ofMillis(100)
 *         backoffMultiplier 2.0
 *         circuitBreaker Duration.ofMinutes(5)
 *     }
 *     action { ctx, prev -> ... }
 * }
 * </pre>
 * 
 * <h3>Presets:</h3>
 * <pre>
 * retry {
 *     preset "aggressive"  // 5 attempts, 100ms, 2x backoff
 *     circuitBreaker Duration.ofMinutes(10)  // Override specific settings
 * }
 * </pre>
 */
class RetryDsl {
    
    private final RetryPolicy policy
    
    RetryDsl(RetryPolicy policy) {
        this.policy = policy
    }
    
    /**
     * Set the maximum number of retry attempts.
     * 
     * @param attempts number of retries (0 = no retries)
     */
    void maxAttempts(int attempts) {
        if (attempts < 0) {
            throw new IllegalArgumentException("maxAttempts must be >= 0, got: $attempts")
        }
        policy.maxAttempts = attempts
    }
    
    /**
     * Set the initial delay before the first retry.
     * 
     * @param delay duration to wait before first retry
     */
    void initialDelay(Duration delay) {
        if (delay.isNegative()) {
            throw new IllegalArgumentException("initialDelay must be non-negative")
        }
        policy.initialDelay = delay
    }
    
    /**
     * Set the backoff multiplier for exponential backoff.
     * Each retry waits: initialDelay * (backoffMultiplier ^ attemptNumber)
     * 
     * @param multiplier backoff multiplier (1.0 = constant, 2.0 = double each time)
     */
    void backoffMultiplier(double multiplier) {
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("backoffMultiplier must be >= 1.0, got: $multiplier")
        }
        policy.backoffMultiplier = multiplier
    }
    
    /**
     * Set the circuit breaker timeout duration.
     * After exhausting retries, the circuit opens for this duration.
     * 
     * @param duration how long to keep circuit open
     */
    void circuitBreaker(Duration duration) {
        if (duration.isNegative()) {
            throw new IllegalArgumentException("circuitBreaker duration must be non-negative")
        }
        policy.circuitOpenDuration = duration
    }
    
    /**
     * Apply a predefined retry preset.
     * Presets can be overridden by calling other methods after preset().
     * 
     * Available presets:
     * - "aggressive": 5 attempts, 100ms initial delay, 2.0x backoff
     * - "moderate": 3 attempts, 500ms initial delay, 1.5x backoff
     * - "conservative": 2 attempts, 1s initial delay, 1.0x backoff (no backoff)
     * - "none": 0 attempts (disable retries)
     * 
     * @param name preset name (case-insensitive)
     */
    void preset(String name) {
        switch (name.toLowerCase()) {
            case 'aggressive':
                policy.maxAttempts = 5
                policy.initialDelay = Duration.ofMillis(100)
                policy.backoffMultiplier = 2.0
                policy.circuitOpenDuration = Duration.ofSeconds(30)
                break
                
            case 'moderate':
                policy.maxAttempts = 3
                policy.initialDelay = Duration.ofMillis(500)
                policy.backoffMultiplier = 1.5
                policy.circuitOpenDuration = Duration.ofMinutes(1)
                break
                
            case 'conservative':
                policy.maxAttempts = 2
                policy.initialDelay = Duration.ofSeconds(1)
                policy.backoffMultiplier = 1.0  // No exponential backoff
                policy.circuitOpenDuration = Duration.ofMinutes(5)
                break
                
            case 'none':
                policy.maxAttempts = 0
                policy.initialDelay = Duration.ZERO
                policy.backoffMultiplier = 1.0
                policy.circuitOpenDuration = Duration.ZERO
                break
                
            default:
                throw new IllegalArgumentException(
                    "Unknown retry preset: '$name'. " +
                    "Available presets: aggressive, moderate, conservative, none"
                )
        }
    }
    
    /**
     * Convenience method: set both maxAttempts and initialDelay at once.
     * 
     * @param attempts number of retry attempts
     * @param delay initial delay before first retry
     */
    void attempts(int attempts, Duration delay) {
        maxAttempts(attempts)
        initialDelay(delay)
    }
    
    /**
     * Convenience method: configure exponential backoff with multiplier.
     * 
     * @param multiplier backoff multiplier
     */
    void exponentialBackoff(double multiplier = 2.0) {
        backoffMultiplier(multiplier)
    }
    
    /**
     * Convenience method: disable retries.
     */
    void disabled() {
        preset('none')
    }
}
