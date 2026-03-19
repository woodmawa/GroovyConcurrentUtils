package org.softwood.dag.resilience

import java.time.Duration

/**
 * Configuration policy for task-level memoisation.
 *
 * <p>Attach to any task via the {@code memo {}} DSL block:</p>
 * <pre>
 * task("heavy-compute") {
 *     memo {
 *         keyFrom { ctx, input -> "compute:${input.param}" }
 *         ttl Duration.ofMinutes(5)
 *     }
 *     action { ctx, prev -> expensiveWork(prev) }
 * }
 * </pre>
 *
 * <p>Key derivation order:</p>
 * <ol>
 *   <li>If {@code staticKey} is set, use it verbatim.</li>
 *   <li>If {@code keyFrom} closure is set, call it with {@code (ctx, input)}.</li>
 *   <li>Otherwise, derive from {@code taskId + ":" + input?.hashCode()}.</li>
 * </ol>
 */
class MemoPolicy {

    boolean enabled    = false

    /** Optional fixed cache key — overrides all other key derivation */
    String staticKey   = null

    /** Dynamic key generator: { TaskContext ctx, Object input -> String } */
    Closure<String> keyFrom = null

    /** TTL for this task's memoised result. Null = session-lived. */
    Duration ttl       = null

    /**
     * When true, the result is stored in a persistent MemoCache (backed by
     * EclipseStore when available) so it survives graph restarts.
     */
    boolean crossRun   = false

    // =========================================================================
    // Key derivation
    // =========================================================================

    String deriveKey(String taskId, Object ctx, Object input) {
        if (staticKey) return staticKey
        if (keyFrom) {
            try {
                return keyFrom.call(ctx, input)?.toString() ?: defaultKey(taskId, input)
            } catch (Exception e) {
                throw new IllegalStateException("MemoPolicy.keyFrom failed for task '${taskId}': ${e.message}", e)
            }
        }
        return defaultKey(taskId, input)
    }

    private static String defaultKey(String taskId, Object input) {
        "${taskId}:${input?.hashCode() ?: 'null'}"
    }

    // =========================================================================
    // Presets
    // =========================================================================

    static MemoPolicy sessionLived() {
        new MemoPolicy(enabled: true, ttl: null)
    }

    static MemoPolicy shortLived() {
        new MemoPolicy(enabled: true, ttl: Duration.ofMinutes(5))
    }

    static MemoPolicy longLived() {
        new MemoPolicy(enabled: true, ttl: Duration.ofHours(1))
    }

    static MemoPolicy persistent() {
        new MemoPolicy(enabled: true, ttl: null, crossRun: true)
    }
}
