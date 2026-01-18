package org.softwood.dag.resilience

import java.time.Duration

/**
 * Configuration policy for task idempotency.
 * 
 * <p>Defines how idempotency should be handled including key generation,
 * caching, and TTL management.</p>
 * 
 * <h3>Usage:</h3>
 * <pre>
 * def policy = new IdempotencyPolicy(
 *     enabled: true,
 *     ttl: Duration.ofMinutes(30),
 *     keyGenerator: { input -> input.requestId }
 * )
 * </pre>
 */
class IdempotencyPolicy {
    
    // =========================================================================
    // Enabled Flag
    // =========================================================================
    
    /** Whether idempotency is enabled (default: false) */
    boolean enabled = false
    
    // =========================================================================
    // TTL Configuration
    // =========================================================================
    
    /** Time-to-live for cached results (null = use cache default) */
    Duration ttl = null
    
    // =========================================================================
    // Key Generation
    // =========================================================================
    
    /**
     * Custom key generator function.
     * 
     * <p>Signature: (Object input) -> String</p>
     * 
     * <p>If provided, this function is used to generate the custom key
     * component of the idempotency key.</p>
     * 
     * <h3>Example:</h3>
     * <pre>
     * keyGenerator = { input -> input.requestId }
     * </pre>
     */
    Closure<String> keyGenerator = null
    
    /**
     * Fields to include in automatic key generation.
     * 
     * <p>If keyGenerator is not provided and keyFields is specified,
     * the key will be generated from these fields of the input.</p>
     * 
     * <h3>Example:</h3>
     * <pre>
     * keyFields = ['userId', 'requestId']
     * </pre>
     */
    List<String> keyFields = null
    
    // =========================================================================
    // Callbacks
    // =========================================================================
    
    /** Callback when cache hit occurs: (IdempotencyKey, Object result) -> void */
    Closure onCacheHit = null
    
    /** Callback when cache miss occurs: (IdempotencyKey) -> void */
    Closure onCacheMiss = null
    
    /** Callback when result is cached: (IdempotencyKey, Object result) -> void */
    Closure onResultCached = null
    
    // =========================================================================
    // DSL Configuration Methods
    // =========================================================================
    
    /**
     * Set TTL for cached results.
     */
    IdempotencyPolicy ttl(Duration ttl) {
        this.ttl = ttl
        return this
    }
    
    /**
     * Set custom key generator.
     */
    IdempotencyPolicy keyFrom(Closure<String> generator) {
        this.keyGenerator = generator
        return this
    }
    
    /**
     * Set fields to include in key generation.
     */
    IdempotencyPolicy keyFields(List<String> fields) {
        this.keyFields = fields
        return this
    }
    
    /**
     * Set cache hit callback.
     */
    IdempotencyPolicy onCacheHit(Closure callback) {
        this.onCacheHit = callback
        return this
    }
    
    /**
     * Set cache miss callback.
     */
    IdempotencyPolicy onCacheMiss(Closure callback) {
        this.onCacheMiss = callback
        return this
    }
    
    /**
     * Set result cached callback.
     */
    IdempotencyPolicy onResultCached(Closure callback) {
        this.onResultCached = callback
        return this
    }
    
    // =========================================================================
    // Key Generation
    // =========================================================================
    
    /**
     * Generate an idempotency key for the given task and input.
     * 
     * @param taskId the task identifier
     * @param input the task input
     * @return the generated key
     */
    IdempotencyKey generateKey(String taskId, Object input) {
        String customKey = null
        
        // Use custom key generator if provided
        if (keyGenerator) {
            try {
                customKey = keyGenerator.call(input)
            } catch (Exception e) {
                throw new IllegalStateException("Failed to generate idempotency key: ${e.message}", e)
            }
        }
        // Use field-based key if specified
        else if (keyFields && input != null) {
            customKey = extractFieldsKey(input)
        }
        
        return IdempotencyKey.from(taskId, input, customKey)
    }
    
    /**
     * Extract key from specified fields of the input.
     */
    private String extractFieldsKey(Object input) {
        List<String> values = []
        
        keyFields.each { field ->
            try {
                Object value = input[field]
                values << (value?.toString() ?: "null")
            } catch (Exception e) {
                values << "undefined"
            }
        }
        
        return values.join(":")
    }
    
    // =========================================================================
    // Presets
    // =========================================================================
    
    /**
     * Create a short-lived cache policy (5 minutes).
     */
    static IdempotencyPolicy shortLived() {
        def policy = new IdempotencyPolicy(
            ttl: Duration.ofMinutes(5)
        )
        policy.enabled = true
        return policy
    }
    
    /**
     * Create a standard cache policy (30 minutes).
     */
    static IdempotencyPolicy standard() {
        def policy = new IdempotencyPolicy(
            ttl: Duration.ofMinutes(30)
        )
        policy.enabled = true
        return policy
    }
    
    /**
     * Create a long-lived cache policy (2 hours).
     */
    static IdempotencyPolicy longLived() {
        def policy = new IdempotencyPolicy(
            ttl: Duration.ofHours(2)
        )
        policy.enabled = true
        return policy
    }
    
    /**
     * Create a permanent cache policy (no expiration).
     */
    static IdempotencyPolicy permanent() {
        def policy = new IdempotencyPolicy(
            ttl: null
        )
        policy.enabled = true
        return policy
    }
}
