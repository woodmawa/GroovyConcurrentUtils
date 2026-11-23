package org.softwood.dag.resilience

import groovy.util.logging.Slf4j

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thread-safe cache for idempotent task results.
 * 
 * <p>Stores task results by idempotency key to enable safe retries
 * and duplicate request handling.</p>
 * 
 * <h3>Features:</h3>
 * <ul>
 *   <li>Thread-safe concurrent access</li>
 *   <li>TTL-based expiration</li>
 *   <li>LRU eviction when size limit reached</li>
 *   <li>Cache hit/miss tracking</li>
 *   <li>Automatic cleanup of expired entries</li>
 * </ul>
 * 
 * <h3>Usage:</h3>
 * <pre>
 * def cache = new IdempotencyCache(
 *     maxSize: 1000,
 *     defaultTtl: Duration.ofMinutes(30)
 * )
 * 
 * // Store result
 * def key = IdempotencyKey.from("task-1", input)
 * cache.put(key, result)
 * 
 * // Retrieve result
 * def cached = cache.get(key)
 * if (cached) {
 *     println "Cache hit: ${cached.result}"
 * }
 * </pre>
 */
@Slf4j
class IdempotencyCache {
    
    // =========================================================================
    // Configuration
    // =========================================================================
    
    /** Maximum number of entries (0 = unlimited) */
    int maxSize = 10000
    
    /** Default TTL for cached entries (null = no expiration) */
    Duration defaultTtl = Duration.ofMinutes(30)
    
    /** Enable automatic cleanup of expired entries */
    boolean autoCleanup = true
    
    // =========================================================================
    // Callbacks
    // =========================================================================
    
    /** Callback when cache hit occurs: (IdempotencyKey, Object result) -> void */
    Closure onCacheHit = null
    
    /** Callback when cache miss occurs: (IdempotencyKey) -> void */
    Closure onCacheMiss = null
    
    /** Callback when entry is evicted: (IdempotencyKey, Object result) -> void */
    Closure onEviction = null
    
    /** Callback when entry expires: (IdempotencyKey) -> void */
    Closure onExpiration = null
    
    // =========================================================================
    // Internal State
    // =========================================================================
    
    /** The cache storage */
    private final ConcurrentHashMap<String, IdempotencyCacheEntry> cache = new ConcurrentHashMap<>()
    
    /** Statistics */
    private final AtomicInteger totalHits = new AtomicInteger(0)
    private final AtomicInteger totalMisses = new AtomicInteger(0)
    private final AtomicInteger totalEvictions = new AtomicInteger(0)
    private final AtomicInteger totalExpirations = new AtomicInteger(0)
    
    // =========================================================================
    // Public API
    // =========================================================================
    
    /**
     * Get a cached result by key.
     * 
     * @param key the idempotency key
     * @return the cache entry, or null if not found or expired
     */
    IdempotencyCacheEntry get(IdempotencyKey key) {
        String keyStr = key.toKey()
        IdempotencyCacheEntry entry = cache.get(keyStr)
        
        if (entry == null) {
            totalMisses.incrementAndGet()
            invokeCallback(onCacheMiss, key)
            return null
        }
        
        // Check if expired
        if (entry.isExpired()) {
            log.debug "Cache entry expired: ${key}"
            remove(key)
            totalExpirations.incrementAndGet()
            invokeCallback(onExpiration, key)
            totalMisses.incrementAndGet()
            invokeCallback(onCacheMiss, key)
            return null
        }
        
        // Cache hit
        entry.recordHit()
        totalHits.incrementAndGet()
        invokeCallback(onCacheHit, key, entry.result)
        
        log.debug "Cache hit: ${key} (hits: ${entry.hitCount})"
        return entry
    }
    
    /**
     * Store a result in the cache.
     * 
     * @param key the idempotency key
     * @param result the result to cache
     * @param ttl optional TTL (overrides default)
     * @return the created cache entry
     */
    IdempotencyCacheEntry put(IdempotencyKey key, Object result, Duration ttl = null) {
        // Determine expiration time
        Duration effectiveTtl = ttl ?: defaultTtl
        Instant expiresAt = effectiveTtl ? Instant.now().plus(effectiveTtl) : null
        
        // Create entry
        IdempotencyCacheEntry entry = new IdempotencyCacheEntry(key, result, expiresAt)
        
        // Check size limit
        if (maxSize > 0 && cache.size() >= maxSize) {
            evictOldest()
        }
        
        // Store entry
        String keyStr = key.toKey()
        cache.put(keyStr, entry)
        
        log.debug "Cached result: ${key} (ttl: ${effectiveTtl})"
        return entry
    }
    
    /**
     * Remove an entry from the cache.
     * 
     * @param key the idempotency key
     * @return the removed entry, or null if not found
     */
    IdempotencyCacheEntry remove(IdempotencyKey key) {
        String keyStr = key.toKey()
        IdempotencyCacheEntry entry = cache.remove(keyStr)
        
        if (entry) {
            log.debug "Removed cache entry: ${key}"
        }
        
        return entry
    }
    
    /**
     * Clear all entries from the cache.
     */
    void clear() {
        int size = cache.size()
        cache.clear()
        log.debug "Cleared cache (${size} entries removed)"
    }
    
    /**
     * Check if a key exists in the cache (and is not expired).
     * 
     * @param key the idempotency key
     * @return true if the key exists and is not expired
     */
    boolean contains(IdempotencyKey key) {
        IdempotencyCacheEntry entry = get(key)
        return entry != null
    }
    
    /**
     * Get the current cache size.
     */
    int size() {
        return cache.size()
    }
    
    /**
     * Check if the cache is empty.
     */
    boolean isEmpty() {
        return cache.isEmpty()
    }
    
    /**
     * Remove all expired entries from the cache.
     * 
     * @return number of entries removed
     */
    int removeExpired() {
        int removed = 0
        
        // Find expired entries
        List<String> expiredKeys = []
        cache.each { keyStr, entry ->
            if (entry.isExpired()) {
                expiredKeys << keyStr
            }
        }
        
        // Remove expired entries
        expiredKeys.each { keyStr ->
            IdempotencyCacheEntry entry = cache.remove(keyStr)
            if (entry) {
                totalExpirations.incrementAndGet()
                invokeCallback(onExpiration, entry.key)
                removed++
            }
        }
        
        if (removed > 0) {
            log.debug "Removed ${removed} expired cache entries"
        }
        
        return removed
    }
    
    /**
     * Get cache statistics.
     */
    Map<String, Object> getStats() {
        int size = cache.size()
        int hits = totalHits.get()
        int misses = totalMisses.get()
        int total = hits + misses
        double hitRate = total > 0 ? (hits / (double) total) * 100 : 0
        
        return [
            size: size,
            totalHits: hits,
            totalMisses: misses,
            totalRequests: total,
            hitRate: hitRate,
            totalEvictions: totalEvictions.get(),
            totalExpirations: totalExpirations.get()
        ]
    }
    
    /**
     * Get all cache entries (for inspection/debugging).
     */
    List<IdempotencyCacheEntry> getAllEntries() {
        return new ArrayList<>(cache.values())
    }
    
    // =========================================================================
    // Private Helpers
    // =========================================================================
    
    /**
     * Evict the oldest entry from the cache.
     */
    private void evictOldest() {
        if (cache.isEmpty()) {
            return
        }
        
        // Find oldest entry
        IdempotencyCacheEntry oldest = null
        String oldestKey = null
        
        cache.each { keyStr, entry ->
            if (oldest == null || entry.timestamp.isBefore(oldest.timestamp)) {
                oldest = entry
                oldestKey = keyStr
            }
        }
        
        // Remove oldest
        if (oldestKey) {
            cache.remove(oldestKey)
            totalEvictions.incrementAndGet()
            invokeCallback(onEviction, oldest.key, oldest.result)
            log.debug "Evicted oldest cache entry: ${oldest.key}"
        }
    }
    
    /**
     * Safely invoke a callback.
     */
    private void invokeCallback(Closure callback, Object... args) {
        if (callback) {
            try {
                callback.call(*args)
            } catch (Exception e) {
                log.error "Error in cache callback: ${e.message}", e
            }
        }
    }
}
