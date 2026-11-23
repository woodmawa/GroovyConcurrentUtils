package org.softwood.dag.task

import groovy.util.logging.Slf4j

/**
 * Policy for configuring rate limiting behavior on tasks.
 * 
 * <p>Rate limit policies reference shared rate limiters by name. This allows
 * multiple tasks to share the same rate limit (e.g., all tasks calling the same
 * API share a single rate limiter).</p>
 * 
 * <h3>Thread Safety:</h3>
 * <ul>
 *   <li>Policy objects are task-specific (not shared)</li>
 *   <li>The referenced rate limiter IS shared and thread-safe</li>
 * </ul>
 * 
 * <h3>Usage Example:</h3>
 * <pre>
 * // Setup shared rate limiter in TaskContext
 * ctx.rateLimiter("api-limiter") {
 *     maxRequests 100
 *     timeWindow Duration.ofMinutes(1)
 * }
 * 
 * // Apply to task
 * serviceTask("api-call") {
 *     rateLimit {
 *         name "api-limiter"
 *         enabled true
 *         onLimitExceeded { ctx -> 
 *             log.warn("Rate limited: \${ctx.taskId}")
 *         }
 *     }
 *     action { ... }
 * }
 * </pre>
 */
@Slf4j
class RateLimitPolicy {
    
    /** 
     * Name of the rate limiter to use (references TaskContext.rateLimiters registry).
     * If null, rate limiting is disabled.
     */
    String name = null
    
    /** 
     * Whether rate limiting is enabled for this task.
     * Default: false (opt-in)
     */
    boolean enabled = false
    
    /**
     * Callback invoked when rate limit is exceeded.
     * Receives the TaskContext as a parameter.
     * 
     * <h3>Example:</h3>
     * <pre>
     * onLimitExceeded { ctx ->
     *     log.warn("Task \${ctx.taskId} rate limited")
     *     // Could publish metrics, trigger alerts, etc.
     * }
     * </pre>
     */
    Closure onLimitExceeded = null
    
    /**
     * Check if rate limiting is configured and enabled.
     * 
     * @return true if both name is set and enabled is true
     */
    boolean isActive() {
        return name != null && enabled
    }
    
    @Override
    String toString() {
        return "RateLimitPolicy[name=$name, enabled=$enabled, active=${isActive()}]"
    }
}
