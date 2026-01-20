package org.softwood.dag.task

import java.time.Instant

/**
 * Event emitted when a task changes state.
 *
 * Captures comprehensive information about task execution including:
 * - Task identity and state
 * - Timing metrics (execution time, wait time)
 * - Retry information
 * - Graph context (graphId, runId)
 * - Extensible metadata
 *
 * Used by listeners for monitoring, metrics, and observability.
 */
class TaskEvent {

    final String taskId
    final String taskName
    final TaskState taskState
    final Throwable error   // may be null

    // Timing and metrics
    final Instant timestamp
    final Long executionTimeMs      // Time spent executing (null if not yet completed)
    final Long waitTimeMs           // Time spent waiting for predecessors (null if not tracked)
    final Integer attemptNumber     // Retry attempt (1 = first attempt, 2 = first retry, etc.)

    // Context
    final String graphId            // ID of the TaskGraph
    final String runId              // Unique ID for this execution run

    // Extensible metadata
    final Map<String, Object> metadata

    /**
     * Constructor for ordinary task state changes (backward compatible).
     */
    TaskEvent(String taskId, TaskState taskState) {
        this(taskId, taskState, null)
    }

    /**
     * Constructor for task errors (backward compatible).
     */
    TaskEvent(String taskId, TaskState taskState, Throwable error) {
        this.taskId = taskId
        this.taskName = null
        this.taskState = taskState
        this.error = error
        this.timestamp = Instant.now()
        this.executionTimeMs = null
        this.waitTimeMs = null
        this.attemptNumber = 1
        this.graphId = null
        this.runId = null
        this.metadata = [:]
    }

    /**
     * Full constructor with all observability fields.
     */
    TaskEvent(
        String taskId,
        String taskName,
        TaskState taskState,
        Throwable error,
        Instant timestamp,
        Long executionTimeMs,
        Long waitTimeMs,
        Integer attemptNumber,
        String graphId,
        String runId,
        Map<String, Object> metadata
    ) {
        this.taskId = taskId
        this.taskName = taskName ?: taskId
        this.taskState = taskState
        this.error = error
        this.timestamp = timestamp ?: Instant.now()
        this.executionTimeMs = executionTimeMs
        this.waitTimeMs = waitTimeMs
        this.attemptNumber = attemptNumber ?: 1
        this.graphId = graphId
        this.runId = runId
        this.metadata = metadata ?: [:]
    }

    boolean hasError() { return error != null }

    boolean isRetry() { return attemptNumber != null && attemptNumber > 1 }

    @Override
    String toString() {
        def parts = ["TaskEvent(id=$taskId", "state=$taskState"]
        if (taskName && taskName != taskId) parts << "name=$taskName"
        if (attemptNumber > 1) parts << "attempt=$attemptNumber"
        if (executionTimeMs) parts << "execTime=${executionTimeMs}ms"
        if (error) parts << "error=${error.message}"
        if (graphId) parts << "graph=$graphId"
        if (runId) parts << "run=$runId"
        return parts.join(", ") + ")"
    }

    /**
     * Builder for creating TaskEvents with optional fields.
     */
    static Builder builder(String taskId, TaskState state) {
        return new Builder(taskId, state)
    }

    static class Builder {
        private String taskId
        private String taskName
        private TaskState taskState
        private Throwable error
        private Instant timestamp = Instant.now()
        private Long executionTimeMs
        private Long waitTimeMs
        private Integer attemptNumber = 1
        private String graphId
        private String runId
        private Map<String, Object> metadata = [:]

        Builder(String taskId, TaskState state) {
            this.taskId = taskId
            this.taskState = state
        }

        Builder taskName(String name) { this.taskName = name; return this }
        Builder error(Throwable error) { this.error = error; return this }
        Builder timestamp(Instant timestamp) { this.timestamp = timestamp; return this }
        Builder executionTimeMs(Long ms) { this.executionTimeMs = ms; return this }
        Builder waitTimeMs(Long ms) { this.waitTimeMs = ms; return this }
        Builder attemptNumber(Integer attempt) { this.attemptNumber = attempt; return this }
        Builder graphId(String id) { this.graphId = id; return this }
        Builder runId(String id) { this.runId = id; return this }
        Builder metadata(Map<String, Object> meta) { this.metadata = meta; return this }
        Builder addMetadata(String key, Object value) { this.metadata[key] = value; return this }

        TaskEvent build() {
            return new TaskEvent(
                taskId, taskName, taskState, error,
                timestamp, executionTimeMs, waitTimeMs, attemptNumber,
                graphId, runId, metadata
            )
        }
    }
}