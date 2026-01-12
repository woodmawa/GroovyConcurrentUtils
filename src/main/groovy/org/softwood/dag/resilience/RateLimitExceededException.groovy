package org.softwood.dag.resilience

import java.time.Duration

/**
 * Exception thrown when rate limit is exceeded.
 * 
 * This exception indicates that a task has exceeded its configured rate limit
 * and the request has been rejected to protect the system.
 * 
 * <h3>What This Means:</h3>
 * <ul>
 *   <li>Too many requests have been made in the time window</li>
 *   <li>The request was rejected before execution</li>
 *   <li>Client should back off and retry after the time window</li>
 * </ul>
 * 
 * <h3>Handling:</h3>
 * <pre>
 * try {
 *     task.execute()
 * } catch (RateLimitExceededException e) {
 *     log.warn("Rate limited: \${e.limiterName}, retry in \${e.retryAfter}")
 *     // Implement backoff strategy
 * }
 * </pre>
 */
class RateLimitExceededException extends RuntimeException {
    
    /** Name of the rate limiter that rejected the request */
    String limiterName
    
    /** ID of the task that was rate limited */
    String taskId
    
    /** Current request count in the time window */
    long currentCount
    
    /** Maximum allowed requests */
    int maxRequests
    
    /** Time window for the rate limit */
    Duration timeWindow
    
    /** Suggested retry-after duration */
    Duration retryAfter
    
    /**
     * Create exception with basic message.
     * 
     * @param message error message
     */
    RateLimitExceededException(String message) {
        super(message)
    }
    
    /**
     * Create exception with detailed rate limiter state.
     * 
     * @param message error message
     * @param limiterName name of the rate limiter
     * @param taskId ID of the task being limited
     * @param currentCount current request count
     * @param maxRequests maximum allowed requests
     * @param timeWindow time window for rate limit
     * @param retryAfter suggested retry delay
     */
    RateLimitExceededException(String message, String limiterName, String taskId,
                               long currentCount, int maxRequests, 
                               Duration timeWindow, Duration retryAfter) {
        super(message)
        this.limiterName = limiterName
        this.taskId = taskId
        this.currentCount = currentCount
        this.maxRequests = maxRequests
        this.timeWindow = timeWindow
        this.retryAfter = retryAfter
    }
    
    /**
     * Create exception with cause.
     * 
     * @param message error message
     * @param cause underlying cause
     */
    RateLimitExceededException(String message, Throwable cause) {
        super(message, cause)
    }
    
    /**
     * Get human-readable retry message.
     * 
     * @return formatted message
     */
    String getRetryMessage() {
        if (!retryAfter) {
            return "Retry after rate limit window expires"
        }
        
        long seconds = retryAfter.toSeconds()
        if (seconds < 60) {
            return "Retry after ${seconds} seconds"
        }
        
        long minutes = seconds / 60
        return "Retry after ${minutes} minute(s)"
    }
    
    @Override
    String toString() {
        def sb = new StringBuilder(super.toString())
        
        if (limiterName) {
            sb.append("\n  Rate Limiter: ").append(limiterName)
        }
        if (taskId) {
            sb.append("\n  Task: ").append(taskId)
        }
        if (currentCount > 0 && maxRequests > 0) {
            sb.append("\n  Rate: ").append(currentCount).append("/").append(maxRequests)
        }
        if (timeWindow) {
            sb.append("\n  Window: ").append(timeWindow)
        }
        if (retryAfter) {
            sb.append("\n  Retry: ").append(getRetryMessage())
        }
        
        return sb.toString()
    }
}
