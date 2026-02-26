package org.softwood.dag.task

/**
 * Lifecycle states for a DAG task.
 *
 * <p>Valid transitions:</p>
 * <pre>
 *   CREATED -> SCHEDULED -> RUNNING -> COMPLETED
 *                                   -> FAILED
 *                                   -> SKIPPED    (upstream failed)
 *                                   -> CANCELLED  (graph timeout)
 *                        -> CIRCUIT_OPEN          (circuit breaker open)
 * </pre>
 *
 * <p>PENDING has been removed - it was declared but never used.
 * Tasks now start life as CREATED (not yet entered the graph scheduler)
 * and are transitioned to SCHEDULED when the graph picks them up.</p>
 */
enum TaskState {
    /** Task created but not yet entered the graph scheduler (initial state). */
    CREATED,
    /** Task entered the graph scheduler queue. */
    SCHEDULED,
    /** Task actively executing. */
    RUNNING,
    /** Task completed successfully. */
    COMPLETED,
    /** Task execution failed with an error. */
    FAILED,
    /** Task skipped because an upstream task failed. */
    SKIPPED,
    /** Task cancelled because the graph-level timeout expired. */
    CANCELLED,
    /** Task blocked because its circuit breaker is open. */
    CIRCUIT_OPEN;

    /**
     * Return true if this is a terminal state (no further transitions possible).
     */
    boolean isTerminal() {
        this == COMPLETED || this == FAILED || this == SKIPPED || this == CANCELLED
    }

    /**
     * Return true if transitioning FROM this state TO the next state is a valid move.
     */
    boolean canTransitionTo(TaskState next) {
        switch (this) {
            case CREATED:
                // Normal graph path: CREATED -> SCHEDULED -> RUNNING
                // Direct path: Tasks.all/sequence/any call execute() without markScheduled(),
                // so CREATED -> RUNNING must be allowed for standalone task execution.
                // CREATED -> SKIPPED/CANCELLED: upstream failure before this task was ever scheduled.
                return next == SCHEDULED || next == RUNNING || next == SKIPPED || next == CANCELLED
            case SCHEDULED:
                return next == RUNNING || next == SKIPPED || next == CANCELLED
            case RUNNING:
                return next == COMPLETED || next == FAILED || next == SKIPPED || next == CANCELLED || next == CIRCUIT_OPEN
            case CIRCUIT_OPEN:
                return next == RUNNING || next == FAILED || next == CANCELLED
            default:
                // COMPLETED, FAILED, SKIPPED, CANCELLED are terminal
                return false
        }
    }
}
