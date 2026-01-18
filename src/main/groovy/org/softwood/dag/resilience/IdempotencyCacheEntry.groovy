package org.softwood.dag.resilience

import java.time.Instant

/**
 * Represents a cached result in the idempotency cache.
 * 
 * <p>Stores the result of a task execution along with metadata
 * for TTL management and diagnostics.</p>
 * 
 * <h3>Fields:</h3>
 * <ul>
 *   <li>key - The idempotency key</li>
 *   <li>result - The cached result value</li>
 *   <li>timestamp - When the result was cached</li>
 *   <li>expiresAt - When the result expires (null = never)</li>
 *   <li>hitCount - Number of cache hits</li>
 * </ul>
 */
class IdempotencyCacheEntry {
    
    /** The idempotency key */
    final IdempotencyKey key
    
    /** The cached result value */
    final Object result
    
    /** When this entry was created */
    final Instant timestamp
    
    /** When this entry expires (null = no expiration) */
    final Instant expiresAt
    
    /** Number of times this cached result was used */
    volatile int hitCount = 0
    
    /**
     * Constructor.
     */
    IdempotencyCacheEntry(IdempotencyKey key, Object result, Instant expiresAt = null) {
        this.key = key
        this.result = result
        this.timestamp = Instant.now()
        this.expiresAt = expiresAt
    }
    
    /**
     * Check if this entry has expired.
     */
    boolean isExpired() {
        if (expiresAt == null) {
            return false
        }
        return Instant.now().isAfter(expiresAt)
    }
    
    /**
     * Record a cache hit.
     */
    void recordHit() {
        hitCount++
    }
    
    /**
     * Get the age of this entry.
     */
    java.time.Duration getAge() {
        return java.time.Duration.between(timestamp, Instant.now())
    }
    
    @Override
    String toString() {
        return "CacheEntry[key=${key}, hits=${hitCount}, age=${age}, expired=${isExpired()}]"
    }
}
