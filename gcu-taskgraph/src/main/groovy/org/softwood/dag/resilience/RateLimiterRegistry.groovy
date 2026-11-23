package org.softwood.dag.resilience

import groovy.util.logging.Slf4j

import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry for managing shared rate limiters.
 * 
 * <p>Centralized registry that creates and manages rate limiter instances.
 * Rate limiters can be shared across multiple tasks to enforce global
 * rate limits on APIs or services.</p>
 * 
 * <h3>Thread Safety:</h3>
 * <ul>
 *   <li>All operations are thread-safe</li>
 *   <li>Limiters can be accessed concurrently</li>
 *   <li>Safe to use from multiple tasks simultaneously</li>
 * </ul>
 * 
 * <h3>Usage Example:</h3>
 * <pre>
 * def registry = new RateLimiterRegistry()
 * 
 * // Create a rate limiter
 * def limiter = registry.create("api-limiter") {
 *     maxRequests 100
 *     timeWindow Duration.ofMinutes(1)
 *     strategy RateLimiterStrategy.SLIDING_WINDOW
 * }
 * 
 * // Get existing limiter
 * def limiter2 = registry.get("api-limiter")
 * 
 * // Check if exists
 * if (registry.exists("api-limiter")) {
 *     // Use it
 * }
 * </pre>
 */
@Slf4j
class RateLimiterRegistry {
    
    /** Thread-safe map of rate limiters by name */
    private final Map<String, RateLimiter> limiters = new ConcurrentHashMap<>()
    
    /**
     * Create a new rate limiter with DSL configuration.
     * 
     * <p>If a limiter with the same name already exists, returns the existing one
     * and logs a warning. Use {@link #remove(String)} first if you want to replace.</p>
     * 
     * @param name unique name for the rate limiter
     * @param config DSL configuration closure
     * @return the created (or existing) rate limiter
     * @throws IllegalArgumentException if name is null or empty
     */
    RateLimiter create(String name, @DelegatesTo(RateLimiterConfigDsl) Closure config) {
        if (!name) {
            throw new IllegalArgumentException("Rate limiter name cannot be null or empty")
        }
        
        // Check if already exists
        if (limiters.containsKey(name)) {
            log.warn "Rate limiter '$name' already exists, returning existing instance"
            return limiters[name]
        }
        
        // Create and configure
        def limiter = new RateLimiter(name: name)
        def dsl = new RateLimiterConfigDsl(limiter)
        
        if (config) {
            config.delegate = dsl
            config.resolveStrategy = Closure.DELEGATE_FIRST
            config.call()
        }
        
        // Initialize based on strategy
        limiter.initialize()
        
        // Store and return
        limiters[name] = limiter
        log.debug "Created rate limiter '$name': max=${limiter.maxRequests}, window=${limiter.timeWindow}, strategy=${limiter.strategy}"
        
        return limiter
    }
    
    /**
     * Create a rate limiter with basic configuration (no DSL).
     * 
     * @param name unique name
     * @param maxRequests maximum requests per window
     * @param timeWindow time window duration
     * @param strategy rate limiting strategy
     * @return the created rate limiter
     */
    RateLimiter create(String name, int maxRequests, Duration timeWindow, RateLimiterStrategy strategy) {
        return create(name) {
            maxRequests maxRequests
            timeWindow timeWindow
            strategy strategy
        }
    }
    
    /**
     * Get an existing rate limiter by name.
     * 
     * @param name limiter name
     * @return the rate limiter, or null if not found
     */
    RateLimiter get(String name) {
        limiters[name]
    }
    
    /**
     * Check if a rate limiter exists.
     * 
     * @param name limiter name
     * @return true if exists
     */
    boolean exists(String name) {
        limiters.containsKey(name)
    }
    
    /**
     * Remove a rate limiter.
     * 
     * @param name limiter name
     * @return the removed limiter, or null if not found
     */
    RateLimiter remove(String name) {
        def limiter = limiters.remove(name)
        if (limiter) {
            log.debug "Removed rate limiter '$name'"
        }
        return limiter
    }
    
    /**
     * Get all rate limiter names.
     * 
     * @return set of limiter names
     */
    Set<String> getNames() {
        new HashSet<>(limiters.keySet())
    }
    
    /**
     * Get all rate limiters.
     * 
     * @return map of limiters by name (defensive copy)
     */
    Map<String, RateLimiter> getAll() {
        new HashMap<>(limiters)
    }
    
    /**
     * Remove all rate limiters.
     */
    void clear() {
        limiters.clear()
        log.debug "Cleared all rate limiters"
    }
    
    /**
     * Get total number of rate limiters.
     * 
     * @return count
     */
    int size() {
        limiters.size()
    }
    
    @Override
    String toString() {
        return "RateLimiterRegistry[size=${size()}, limiters=${getNames()}]"
    }
}

/**
 * DSL for configuring rate limiters.
 * 
 * <h3>Example:</h3>
 * <pre>
 * registry.create("api-limiter") {
 *     maxRequests 100
 *     timeWindow Duration.ofMinutes(1)
 *     strategy RateLimiterStrategy.SLIDING_WINDOW
 *     strategy "sliding-window"  // String also works
 * }
 * </pre>
 */
class RateLimiterConfigDsl {
    private final RateLimiter limiter
    
    RateLimiterConfigDsl(RateLimiter limiter) {
        this.limiter = limiter
    }
    
    /**
     * Set maximum requests per time window.
     */
    void maxRequests(int max) {
        limiter.maxRequests = max
    }
    
    /**
     * Set time window duration.
     */
    void timeWindow(Duration duration) {
        limiter.timeWindow = duration
    }
    
    /**
     * Set time window with value and unit.
     * 
     * @param value numeric value
     * @param unit ChronoUnit (SECONDS, MINUTES, HOURS, etc.)
     */
    void timeWindow(long value, java.time.temporal.ChronoUnit unit) {
        limiter.timeWindow = Duration.of(value, unit)
    }
    
    /**
     * Set rate limiting strategy (enum).
     */
    void strategy(RateLimiterStrategy strategy) {
        limiter.strategy = strategy
    }
    
    /**
     * Set rate limiting strategy (string).
     * 
     * Accepts:
     * - "token-bucket" or "TOKEN_BUCKET"
     * - "sliding-window" or "SLIDING_WINDOW"
     * - "fixed-window" or "FIXED_WINDOW"
     */
    void strategy(String strategyName) {
        def normalized = strategyName.toUpperCase().replace('-', '_')
        limiter.strategy = RateLimiterStrategy.valueOf(normalized)
    }
}
