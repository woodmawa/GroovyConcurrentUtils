package org.softwood.dag.task

/**
 * The typed outcome of a {@link ControlTask} policy evaluation.
 *
 * <p>The runtime in {@code mcp-agentic-workflow} interprets the action
 * as follows:</p>
 * <ul>
 *   <li><b>CONTINUE</b>      – Schedule all normal successors and proceed.</li>
 *   <li><b>ROUTE</b>         – Activate only the named branch ({@link #target});
 *                              other successors remain unscheduled.</li>
 *   <li><b>RETRY_SCOPE</b>   – Re-execute the named scope ({@link #target});
 *                              respects the scope's RetryPolicy.</li>
 *   <li><b>ESCALATE</b>      – Emit an INTERACTION_REQUEST event and park the
 *                              run until a human responds or timeout fires.</li>
 *   <li><b>ABORT</b>         – Fail the run immediately with {@link #reason}.</li>
 * </ul>
 *
 * Instances are created via the static factory methods, not the constructor,
 * to keep call-sites readable.
 *
 * <h3>Example policy rule result:</h3>
 * <pre>
 *   return ControlAction.route("rollback-branch")
 *   return ControlAction.escalate("Confidence below threshold: ${confidence}")
 *   return ControlAction.abort("Risk level is critical")
 * </pre>
 */
class ControlAction {

    enum Type { CONTINUE, ROUTE, RETRY_SCOPE, ESCALATE, ABORT }

    final Type type

    /** For ROUTE / RETRY_SCOPE: the branch name or scopeId to activate. */
    final String target

    /** For ESCALATE / ABORT: human-readable explanation. */
    final String reason

    private ControlAction(Type type, String target = null, String reason = null) {
        this.type   = type
        this.target = target
        this.reason = reason
    }

    // ---- factory methods ---------------------------------------------------

    static ControlAction continueFlow() {
        new ControlAction(Type.CONTINUE)
    }

    static ControlAction route(String branchName) {
        if (!branchName) throw new IllegalArgumentException("route() requires a non-null branch name")
        new ControlAction(Type.ROUTE, branchName)
    }

    static ControlAction retryScope(String scopeId) {
        if (!scopeId) throw new IllegalArgumentException("retryScope() requires a non-null scopeId")
        new ControlAction(Type.RETRY_SCOPE, scopeId)
    }

    static ControlAction escalate(String reason = "Policy escalation") {
        new ControlAction(Type.ESCALATE, null, reason)
    }

    static ControlAction abort(String reason = "Policy abort") {
        new ControlAction(Type.ABORT, null, reason)
    }

    // ---- convenience predicates --------------------------------------------

    boolean isContinue()   { type == Type.CONTINUE }
    boolean isRoute()      { type == Type.ROUTE }
    boolean isRetryScope() { type == Type.RETRY_SCOPE }
    boolean isEscalate()   { type == Type.ESCALATE }
    boolean isAbort()      { type == Type.ABORT }

    @Override
    String toString() {
        switch (type) {
            case Type.CONTINUE:    return "ControlAction(CONTINUE)"
            case Type.ROUTE:       return "ControlAction(ROUTE -> $target)"
            case Type.RETRY_SCOPE: return "ControlAction(RETRY_SCOPE -> $target)"
            case Type.ESCALATE:    return "ControlAction(ESCALATE: $reason)"
            case Type.ABORT:       return "ControlAction(ABORT: $reason)"
            default:               return "ControlAction(UNKNOWN)"
        }
    }
}
