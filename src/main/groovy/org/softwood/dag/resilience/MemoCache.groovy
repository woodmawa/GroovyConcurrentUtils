package org.softwood.dag.resilience

import groovy.util.logging.Slf4j

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Graph-scoped memoisation cache for task results.
 *
 * <p>Distinct from {@link IdempotencyCache} in intent:</p>
 * <ul>
 *   <li>Idempotency = correctness: prevent duplicate side effects within a run</li>
 *   <li>Memo = performance: skip re-computing expensive results across tasks or runs</li>
 * </ul>
 *
 * <p>The cache is stored in {@link org.softwood.dag.task.TaskContext} and shared
 * across all tasks in a graph execution. When {@code crossRun=true} on the
 * {@link MemoPolicy}, the cache survives graph restarts (backed by EclipseStore
 * when available).</p>
 *
 * <h3>Usage:</h3>
 * <pre>
 * task("expensive-lookup") {
 *     memo {
 *         keyFrom { ctx, input -> "user:${input.userId}" }
 *         ttl Duration.ofMinutes(10)
 *     }
 *     action { ctx, prev -> slowUserService.findById(prev.userId) }
 * }
 * </pre>
 */
@Slf4j
class MemoCache {

    // =========================================================================
    // Configuration
    // =========================================================================

    /** Maximum entries (0 = unlimited) */
    int maxSize = 500

    /** Default TTL — null means session-lived (cleared when graph finishes) */
    Duration defaultTtl = null

    // =========================================================================
    // Callbacks
    // =========================================================================

    Closure onHit   = null   // { String key, Object value -> }
    Closure onMiss  = null   // { String key -> }
    Closure onStore = null   // { String key, Object value -> }

    // =========================================================================
    // Internal State
    // =========================================================================

    private final ConcurrentHashMap<String, MemoCacheEntry> store = new ConcurrentHashMap<>()
    private final AtomicInteger totalHits      = new AtomicInteger(0)
    private final AtomicInteger totalMisses    = new AtomicInteger(0)
    private final AtomicInteger totalEvictions = new AtomicInteger(0)

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Retrieve a cached value. Returns null on miss or expiry.
     */
    Object get(String key) {
        MemoCacheEntry entry = store.get(key)

        if (entry == null) {
            totalMisses.incrementAndGet()
            fire(onMiss, key)
            return null
        }

        if (entry.isExpired()) {
            store.remove(key)
            totalMisses.incrementAndGet()
            fire(onMiss, key)
            return null
        }

        entry.recordHit()
        totalHits.incrementAndGet()
        fire(onHit, key, entry.value)
        log.debug "MemoCache hit: key='${key}' (hitCount=${entry.hitCount})"
        return entry.value
    }

    /**
     * Store a value. Uses policy TTL; falls back to defaultTtl; null = no expiry.
     */
    void put(String key, Object value, Duration ttl = null) {
        if (maxSize > 0 && store.size() >= maxSize) evictOldest()

        Duration effectiveTtl = ttl ?: defaultTtl
        Instant expiresAt     = effectiveTtl ? Instant.now().plus(effectiveTtl) : null

        store.put(key, new MemoCacheEntry(key, value, expiresAt))
        fire(onStore, key, value)
        log.debug "MemoCache stored: key='${key}' ttl=${effectiveTtl}"
    }

    void invalidate(String key) {
        store.remove(key)
        log.debug "MemoCache invalidated: key='${key}'"
    }

    void invalidateAll() {
        int n = store.size()
        store.clear()
        log.debug "MemoCache cleared: ${n} entries removed"
    }

    boolean contains(String key) {
        MemoCacheEntry e = store.get(key)
        e != null && !e.isExpired()
    }

    int size() { store.size() }

    Map<String, Object> stats() {
        int hits   = totalHits.get()
        int misses = totalMisses.get()
        int total  = hits + misses
        [
            size         : store.size(),
            hits         : hits,
            misses       : misses,
            total        : total,
            hitRate      : total > 0 ? (hits / (double) total * 100).round(1) : 0.0,
            evictions    : totalEvictions.get()
        ]
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private void evictOldest() {
        if (store.isEmpty()) return
        def oldest = store.values().min { it.createdAt }
        if (oldest) {
            store.remove(oldest.key)
            totalEvictions.incrementAndGet()
            log.debug "MemoCache evicted oldest: key='${oldest.key}'"
        }
    }

    private void fire(Closure cb, Object... args) {
        if (!cb) return
        try { cb.call(*args) } catch (Exception e) {
            log.warn "MemoCache callback error: ${e.message}"
        }
    }
}
