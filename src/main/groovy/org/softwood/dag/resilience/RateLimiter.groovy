package org.softwood.dag.resilience

import groovy.util.logging.Slf4j

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * Rate limiter implementation with multiple strategy support.
 * 
 * <p>Thread-safe rate limiter that can enforce request limits using different
 * algorithms (token bucket, sliding window, fixed window).</p>
 * 
 * <h3>Thread Safety:</h3>
 * <ul>
 *   <li>All operations are thread-safe</li>
 *   <li>Uses atomic operations and locks where needed</li>
 *   <li>Can be shared across multiple tasks safely</li>
 * </ul>
 * 
 * <h3>Usage Example:</h3>
 * <pre>
 * def limiter = new RateLimiter(
 *     name: "api-limiter",
 *     maxRequests: 100,
 *     timeWindow: Duration.ofMinutes(1),
 *     strategy: RateLimiterStrategy.SLIDING_WINDOW
 * )
 * 
 * if (limiter.tryAcquire()) {
 *     // Execute request
 * } else {
 *     // Rate limited - back off
 * }
 * </pre>
 */
@Slf4j
class RateLimiter {
    
    // =========================================================================
    // Configuration
    // =========================================================================
    
    /** Unique name for this rate limiter */
    String name
    
    /** Maximum number of requests allowed in the time window */
    int maxRequests = 100
    
    /** Time window for rate limiting */
    Duration timeWindow = Duration.ofMinutes(1)
    
    /** Rate limiting strategy */
    RateLimiterStrategy strategy = RateLimiterStrategy.SLIDING_WINDOW
    
    // =========================================================================
    // Strategy-Specific State
    // =========================================================================
    
    // TOKEN_BUCKET state
    private final AtomicInteger tokenCount = new AtomicInteger(0)
    private final AtomicLong lastRefillTime = new AtomicLong(System.currentTimeMillis())
    
    // SLIDING_WINDOW state
    private final ConcurrentLinkedQueue<Long> requestTimestamps = new ConcurrentLinkedQueue<>()
    
    // FIXED_WINDOW state
    private final AtomicInteger windowRequestCount = new AtomicInteger(0)
    private final AtomicLong windowStartTime = new AtomicLong(System.currentTimeMillis())
    
    // Lock for critical sections
    private final ReentrantLock lock = new ReentrantLock()
    
    // =========================================================================
    // Statistics
    // =========================================================================
    
    private final AtomicLong totalRequests = new AtomicLong(0)
    private final AtomicLong rejectedRequests = new AtomicLong(0)
    
    // =========================================================================
    // Initialization
    // =========================================================================
    
    /**
     * Initialize the rate limiter based on strategy.
     * Must be called after construction if using default constructor.
     */
    void initialize() {
        log.debug "Initializing rate limiter '$name' with strategy $strategy, max=$maxRequests, window=$timeWindow"
        
        switch (strategy) {
            case RateLimiterStrategy.TOKEN_BUCKET:
                tokenCount.set(maxRequests)  // Start with full bucket
                break
                
            case RateLimiterStrategy.SLIDING_WINDOW:
                requestTimestamps.clear()
                break
                
            case RateLimiterStrategy.FIXED_WINDOW:
                windowRequestCount.set(0)
                windowStartTime.set(System.currentTimeMillis())
                break
        }
    }
    
    // =========================================================================
    // Core Operations
    // =========================================================================
    
    /**
     * Try to acquire permission to execute a request.
     * 
     * <p>Non-blocking operation that immediately returns whether the request
     * can proceed or should be rejected due to rate limiting.</p>
     * 
     * @return true if request is allowed, false if rate limited
     */
    boolean tryAcquire() {
        totalRequests.incrementAndGet()
        
        boolean acquired = false
        switch (strategy) {
            case RateLimiterStrategy.TOKEN_BUCKET:
                acquired = tryAcquireTokenBucket()
                break
                
            case RateLimiterStrategy.SLIDING_WINDOW:
                acquired = tryAcquireSlidingWindow()
                break
                
            case RateLimiterStrategy.FIXED_WINDOW:
                acquired = tryAcquireFixedWindow()
                break
        }
        
        if (!acquired) {
            rejectedRequests.incrementAndGet()
            log.debug "Rate limiter '$name': Request rejected (${getCurrentCount()}/$maxRequests in window)"
        } else {
            log.trace "Rate limiter '$name': Request allowed (${getCurrentCount()}/$maxRequests)"
        }
        
        return acquired
    }
    
    /**
     * Get current request count in the window.
     * 
     * @return current count
     */
    long getCurrentCount() {
        switch (strategy) {
            case RateLimiterStrategy.TOKEN_BUCKET:
                return maxRequests - tokenCount.get()
                
            case RateLimiterStrategy.SLIDING_WINDOW:
                cleanupExpiredTimestamps()
                return requestTimestamps.size()
                
            case RateLimiterStrategy.FIXED_WINDOW:
                return windowRequestCount.get()
                
            default:
                return 0
        }
    }
    
    /**
     * Get time until next request can be made (if rate limited).
     * 
     * @return duration until retry, or null if not rate limited
     */
    Duration getRetryAfter() {
        long currentCount = getCurrentCount()
        if (currentCount < maxRequests) {
            return null  // Not rate limited
        }
        
        switch (strategy) {
            case RateLimiterStrategy.TOKEN_BUCKET:
                // Calculate time until next token refill
                long refillInterval = timeWindow.toMillis() / maxRequests
                return Duration.ofMillis(refillInterval)
                
            case RateLimiterStrategy.SLIDING_WINDOW:
                // Time until oldest request expires
                Long oldest = requestTimestamps.peek()
                if (oldest == null) return null
                
                long windowMillis = timeWindow.toMillis()
                long expiryTime = oldest + windowMillis
                long now = System.currentTimeMillis()
                long waitTime = expiryTime - now
                
                return waitTime > 0 ? Duration.ofMillis(waitTime) : Duration.ZERO
                
            case RateLimiterStrategy.FIXED_WINDOW:
                // Time until window resets
                long windowStart = windowStartTime.get()
                long windowEnd = windowStart + timeWindow.toMillis()
                long now = System.currentTimeMillis()
                long waitTime = windowEnd - now
                
                return waitTime > 0 ? Duration.ofMillis(waitTime) : Duration.ZERO
                
            default:
                return timeWindow
        }
    }
    
    // =========================================================================
    // Token Bucket Implementation
    // =========================================================================
    
    private boolean tryAcquireTokenBucket() {
        lock.lock()
        try {
            refillTokens()
            
            int tokens = tokenCount.get()
            if (tokens > 0) {
                tokenCount.decrementAndGet()
                return true
            }
            
            return false
        } finally {
            lock.unlock()
        }
    }
    
    private void refillTokens() {
        long now = System.currentTimeMillis()
        long lastRefill = lastRefillTime.get()
        long elapsed = now - lastRefill
        
        if (elapsed > 0) {
            // Calculate tokens to add based on elapsed time
            long windowMillis = timeWindow.toMillis()
            double tokensToAdd = (elapsed * maxRequests) / (double) windowMillis
            
            if (tokensToAdd >= 1.0) {
                int tokens = (int) tokensToAdd
                int current = tokenCount.get()
                int newCount = Math.min(current + tokens, maxRequests)
                tokenCount.set(newCount)
                lastRefillTime.set(now)
            }
        }
    }
    
    // =========================================================================
    // Sliding Window Implementation
    // =========================================================================
    
    private boolean tryAcquireSlidingWindow() {
        long now = System.currentTimeMillis()
        
        lock.lock()
        try {
            cleanupExpiredTimestamps()
            
            if (requestTimestamps.size() < maxRequests) {
                requestTimestamps.offer(now)
                return true
            }
            
            return false
        } finally {
            lock.unlock()
        }
    }
    
    private void cleanupExpiredTimestamps() {
        long now = System.currentTimeMillis()
        long windowMillis = timeWindow.toMillis()
        long cutoff = now - windowMillis
        
        // Remove expired timestamps
        while (!requestTimestamps.isEmpty()) {
            Long timestamp = requestTimestamps.peek()
            if (timestamp != null && timestamp < cutoff) {
                requestTimestamps.poll()
            } else {
                break
            }
        }
    }
    
    // =========================================================================
    // Fixed Window Implementation
    // =========================================================================
    
    private boolean tryAcquireFixedWindow() {
        long now = System.currentTimeMillis()
        
        lock.lock()
        try {
            resetWindowIfNeeded(now)
            
            int count = windowRequestCount.get()
            if (count < maxRequests) {
                windowRequestCount.incrementAndGet()
                return true
            }
            
            return false
        } finally {
            lock.unlock()
        }
    }
    
    private void resetWindowIfNeeded(long now) {
        long windowStart = windowStartTime.get()
        long windowEnd = windowStart + timeWindow.toMillis()
        
        if (now >= windowEnd) {
            // Start new window
            windowStartTime.set(now)
            windowRequestCount.set(0)
        }
    }
    
    // =========================================================================
    // Statistics
    // =========================================================================
    
    /**
     * Get total requests attempted.
     * 
     * @return total request count
     */
    long getTotalRequests() {
        totalRequests.get()
    }
    
    /**
     * Get total requests rejected.
     * 
     * @return rejected count
     */
    long getRejectedRequests() {
        rejectedRequests.get()
    }
    
    /**
     * Get rejection rate (0.0 to 1.0).
     * 
     * @return rejection rate
     */
    double getRejectionRate() {
        long total = totalRequests.get()
        if (total == 0) return 0.0
        
        return rejectedRequests.get() / (double) total
    }
    
    /**
     * Reset statistics counters.
     */
    void resetStats() {
        totalRequests.set(0)
        rejectedRequests.set(0)
    }
    
    // =========================================================================
    // Debug & Monitoring
    // =========================================================================
    
    @Override
    String toString() {
        return "RateLimiter[name=$name, max=$maxRequests, window=$timeWindow, " +
               "strategy=$strategy, current=${getCurrentCount()}, " +
               "total=${getTotalRequests()}, rejected=${getRejectedRequests()}]"
    }
}
