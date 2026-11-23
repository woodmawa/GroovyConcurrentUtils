package org.softwood.dag.resilience

import java.time.Duration

/**
 * DSL builder for configuring task-level memoisation.
 *
 * <h3>Usage:</h3>
 * <pre>
 * task("lookup") {
 *     memo {
 *         keyFrom { ctx, input -> "user:${input.id}" }
 *         ttl Duration.ofMinutes(10)
 *     }
 *     action { ctx, prev -> db.find(prev.id) }
 * }
 *
 * // Preset shorthand
 * task("lookup") {
 *     memo "short"
 *     action { ctx, prev -> db.find(prev.id) }
 * }
 * </pre>
 */
class MemoDsl {

    private final MemoPolicy policy

    MemoDsl(MemoPolicy policy) {
        this.policy = policy
    }

    // -------------------------------------------------------------------------
    // Key configuration
    // -------------------------------------------------------------------------

    /** Dynamic key closure: { TaskContext ctx, Object input -> String } */
    void keyFrom(Closure<String> c) {
        policy.enabled = true
        policy.keyFrom = c
    }

    /** Fixed static key — same result cached for all inputs */
    void staticKey(String k) {
        policy.enabled = true
        policy.staticKey = k
    }

    // -------------------------------------------------------------------------
    // TTL
    // -------------------------------------------------------------------------

    void ttl(Duration d) {
        policy.enabled = true
        policy.ttl = d
    }

    // -------------------------------------------------------------------------
    // Cross-run persistence
    // -------------------------------------------------------------------------

    /** Persist the memoised result across graph runs */
    void crossRun(boolean b = true) {
        policy.enabled = true
        policy.crossRun = b
    }

    // -------------------------------------------------------------------------
    // Explicit enable / disable
    // -------------------------------------------------------------------------

    void enable()  { policy.enabled = true  }
    void disable() { policy.enabled = false }

    // -------------------------------------------------------------------------
    // Presets
    // -------------------------------------------------------------------------

    /**
     * Apply a named preset.
     * <ul>
     *   <li>"session" / "session-lived" — live for the graph run, no TTL</li>
     *   <li>"short" / "short-lived"    — 5-minute TTL</li>
     *   <li>"long"  / "long-lived"     — 1-hour TTL</li>
     *   <li>"persistent"               — no TTL, cross-run</li>
     * </ul>
     */
    void preset(String name) {
        MemoPolicy p
        switch (name.toLowerCase()) {
            case "session":
            case "session-lived": p = MemoPolicy.sessionLived(); break
            case "short":
            case "short-lived":   p = MemoPolicy.shortLived();   break
            case "long":
            case "long-lived":    p = MemoPolicy.longLived();    break
            case "persistent":    p = MemoPolicy.persistent();   break
            default:
                throw new IllegalArgumentException(
                    "Unknown memo preset '${name}'. Valid: session, short, long, persistent")
        }
        policy.enabled  = p.enabled
        policy.ttl      = p.ttl
        policy.crossRun = p.crossRun
    }
}
