package org.softwood.dag.resilience

import java.time.Duration

/**
 * DSL for configuring idempotency in tasks.
 * 
 * <p>Provides a fluent interface for configuring idempotency behavior.</p>
 * 
 * <h3>Usage:</h3>
 * <pre>
 * serviceTask("api-call") {
 *     idempotent {
 *         ttl 30.minutes
 *         keyFrom { input -> input.requestId }
 *         onCacheHit { key, result ->
 *             log.info "Returning cached result for ${key}"
 *         }
 *     }
 *     action { ctx, prev -> callApi() }
 * }
 * </pre>
 */
class IdempotencyDsl {
    
    /** The policy being configured */
    private final IdempotencyPolicy policy
    
    /**
     * Constructor with existing policy.
     */
    IdempotencyDsl(IdempotencyPolicy policy) {
        this.policy = policy
    }
    
    // =========================================================================
    // TTL Configuration
    // =========================================================================
    
    /**
     * Set time-to-live for cached results.
     * 
     * Usage:
     *   ttl Duration.ofMinutes(30)
     */
    void ttl(Duration ttl) {
        policy.enabled = true
        policy.ttl(ttl)
    }
    
    // =========================================================================
    // Key Generation
    // =========================================================================
    
    /**
     * Set custom key generator function.
     * 
     * Usage:
     *   keyFrom { input -> input.requestId }
     */
    void keyFrom(Closure<String> generator) {
        policy.enabled = true
        policy.keyFrom(generator)
    }
    
    /**
     * Set fields to use for automatic key generation.
     * 
     * Usage:
     *   keyFields(['userId', 'requestId'])
     */
    void keyFields(List<String> fields) {
        policy.enabled = true
        policy.keyFields(fields)
    }
    
    /**
     * Convenience method: set key fields as varargs.
     * 
     * Usage:
     *   keyFields 'userId', 'requestId'
     */
    void keyFields(String... fields) {
        policy.enabled = true
        policy.keyFields(fields as List)
    }
    
    // =========================================================================
    // Callbacks
    // =========================================================================
    
    /**
     * Set cache hit callback.
     * 
     * Usage:
     *   onCacheHit { key, result ->
     *       println "Cache hit for ${key}"
     *   }
     */
    void onCacheHit(Closure callback) {
        policy.enabled = true
        policy.onCacheHit(callback)
    }
    
    /**
     * Set cache miss callback.
     * 
     * Usage:
     *   onCacheMiss { key ->
     *       println "Cache miss for ${key}"
     *   }
     */
    void onCacheMiss(Closure callback) {
        policy.enabled = true
        policy.onCacheMiss(callback)
    }
    
    /**
     * Set result cached callback.
     * 
     * Usage:
     *   onResultCached { key, result ->
     *       println "Cached result for ${key}"
     *   }
     */
    void onResultCached(Closure callback) {
        policy.enabled = true
        policy.onResultCached(callback)
    }
    
    // =========================================================================
    // Presets
    // =========================================================================
    
    /**
     * Apply a preset configuration.
     * 
     * Available presets:
     * - "short": 5 minutes TTL
     * - "standard": 30 minutes TTL (default)
     * - "long": 2 hours TTL
     * - "permanent": No expiration
     * 
     * Usage:
     *   preset "standard"
     */
    void preset(String presetName) {
        policy.enabled = true
        
        IdempotencyPolicy presetPolicy = null
        
        switch (presetName.toLowerCase()) {
            case "short":
            case "short-lived":
                presetPolicy = IdempotencyPolicy.shortLived()
                break
                
            case "standard":
                presetPolicy = IdempotencyPolicy.standard()
                break
                
            case "long":
            case "long-lived":
                presetPolicy = IdempotencyPolicy.longLived()
                break
                
            case "permanent":
                presetPolicy = IdempotencyPolicy.permanent()
                break
                
            default:
                throw new IllegalArgumentException(
                    "Unknown idempotency preset: '${presetName}'. " +
                    "Available presets: short, standard, long, permanent"
                )
        }
        
        // Copy preset values
        policy.enabled = presetPolicy.enabled
        policy.ttl = presetPolicy.ttl
    }
    
    // =========================================================================
    // Convenience Methods
    // =========================================================================
    
    /**
     * Enable idempotency with default settings.
     * 
     * Usage:
     *   enable()
     */
    void enable() {
        policy.enabled = true
    }
    
    /**
     * Disable idempotency.
     * 
     * Usage:
     *   disable()
     */
    void disable() {
        policy.enabled = false
    }
}
