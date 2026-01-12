package org.softwood.dag.task

/**
 * DSL for configuring rate limit policy on tasks.
 * 
 * <h3>Usage Example:</h3>
 * <pre>
 * serviceTask("api-call") {
 *     rateLimit {
 *         name "api-limiter"
 *         enabled true
 *         onLimitExceeded { ctx -> 
 *             log.warn("Rate limited")
 *         }
 *     }
 * }
 * </pre>
 */
class RateLimitDsl {
    private final RateLimitPolicy policy
    
    RateLimitDsl(RateLimitPolicy policy) {
        this.policy = policy
    }
    
    /**
     * Set the name of the rate limiter to use.
     * References a rate limiter in TaskContext.rateLimiters registry.
     * 
     * @param limiterName name of the rate limiter
     */
    void name(String limiterName) {
        policy.name = limiterName
    }
    
    /**
     * Enable or disable rate limiting for this task.
     * 
     * @param value true to enable, false to disable
     */
    void enabled(boolean value) {
        policy.enabled = value
    }
    
    /**
     * Set callback to invoke when rate limit is exceeded.
     * Callback receives the TaskContext as a parameter.
     * 
     * @param callback closure to execute
     */
    void onLimitExceeded(Closure callback) {
        policy.onLimitExceeded = callback
    }
}
