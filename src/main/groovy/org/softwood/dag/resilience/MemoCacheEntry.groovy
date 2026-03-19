package org.softwood.dag.resilience

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * A single entry in the MemoCache.
 */
class MemoCacheEntry {

    final String key
    final Object value
    final Instant createdAt
    final Instant expiresAt   // null = no expiry
    private final AtomicInteger hitCount = new AtomicInteger(0)

    MemoCacheEntry(String key, Object value, Instant expiresAt) {
        this.key       = key
        this.value     = value
        this.createdAt = Instant.now()
        this.expiresAt = expiresAt
    }

    boolean isExpired() {
        expiresAt != null && Instant.now().isAfter(expiresAt)
    }

    int recordHit() { hitCount.incrementAndGet() }

    int getHitCount() { hitCount.get() }
}
