package org.softwood.dag.resilience

/**
 * Rate limiting strategies.
 * 
 * Different algorithms for controlling request rates:
 * 
 * <h3>TOKEN_BUCKET</h3>
 * <p>Tokens are added to a bucket at a fixed rate. Each request consumes a token.
 * If no tokens available, request is rejected. Allows bursts up to bucket capacity.</p>
 * <ul>
 *   <li>Pros: Smooth rate limiting, allows controlled bursts</li>
 *   <li>Cons: More complex implementation</li>
 *   <li>Use when: You want to allow occasional bursts while maintaining average rate</li>
 * </ul>
 * 
 * <h3>SLIDING_WINDOW</h3>
 * <p>Tracks exact request timestamps in a sliding time window. Most accurate but
 * memory intensive as it stores all timestamps.</p>
 * <ul>
 *   <li>Pros: Most accurate, no burst issues at window boundaries</li>
 *   <li>Cons: Higher memory usage, stores all request timestamps</li>
 *   <li>Use when: Accuracy is critical and request volume is moderate</li>
 * </ul>
 * 
 * <h3>FIXED_WINDOW</h3>
 * <p>Divides time into fixed windows (e.g., per minute). Counts requests in current window.
 * Simple but can allow bursts at window boundaries.</p>
 * <ul>
 *   <li>Pros: Simple, low memory usage</li>
 *   <li>Cons: Can allow 2x limit at window boundaries</li>
 *   <li>Use when: Simplicity is priority and approximate limiting is acceptable</li>
 * </ul>
 * 
 * <h3>Example:</h3>
 * <pre>
 * // 100 requests per minute
 * ctx.rateLimiter("api-limiter") {
 *     maxRequests 100
 *     timeWindow Duration.ofMinutes(1)
 *     strategy RateLimiterStrategy.SLIDING_WINDOW  // Most accurate
 * }
 * </pre>
 */
enum RateLimiterStrategy {
    /**
     * Token bucket algorithm - allows controlled bursts.
     */
    TOKEN_BUCKET,
    
    /**
     * Sliding window counter - most accurate, memory intensive.
     */
    SLIDING_WINDOW,
    
    /**
     * Fixed window counter - simple, approximate.
     */
    FIXED_WINDOW
}
