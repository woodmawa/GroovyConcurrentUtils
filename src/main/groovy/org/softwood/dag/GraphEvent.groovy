package org.softwood.dag

import org.softwood.dag.task.TaskEvent
import java.time.Instant

/**
 * Event representing a significant occurrence during TaskGraph execution.
 *
 * Provides comprehensive context for monitoring and observability:
 * - Event type (graph-level, task-level, resilience, resource)
 * - Graph and run identification
 * - Associated task event (if applicable)
 * - Metrics and metadata
 *
 * Used by {@link GraphEventListener} implementations for:
 * - Metrics collection (Prometheus, Micrometer)
 * - Distributed tracing (OpenTelemetry, Jaeger)
 * - Logging and alerting
 * - Custom monitoring
 */
class GraphEvent {

    final GraphEventType type
    final String graphId
    final String runId
    final Instant timestamp

    // Optional: associated task event (for task-level events)
    final TaskEvent taskEvent

    // Metrics and metadata
    final Map<String, Object> metrics
    final Map<String, Object> metadata

    /**
     * Create a graph-level event (no task association).
     */
    GraphEvent(GraphEventType type, String graphId, String runId) {
        this(type, graphId, runId, null, [:], [:])
    }

    /**
     * Create a graph-level event with metrics.
     */
    GraphEvent(GraphEventType type, String graphId, String runId, Map<String, Object> metrics) {
        this(type, graphId, runId, null, metrics, [:])
    }

    /**
     * Create a task-level event.
     */
    GraphEvent(GraphEventType type, String graphId, String runId, TaskEvent taskEvent) {
        this(type, graphId, runId, taskEvent, [:], [:])
    }

    /**
     * Full constructor with all fields.
     */
    GraphEvent(
        GraphEventType type,
        String graphId,
        String runId,
        TaskEvent taskEvent,
        Map<String, Object> metrics,
        Map<String, Object> metadata
    ) {
        this.type = type
        this.graphId = graphId
        this.runId = runId
        this.timestamp = Instant.now()
        this.taskEvent = taskEvent
        this.metrics = metrics ?: [:]
        this.metadata = metadata ?: [:]
    }

    /**
     * Check if this is a task-level event.
     */
    boolean isTaskEvent() {
        return taskEvent != null
    }

    /**
     * Check if this is a graph-level event.
     */
    boolean isGraphEvent() {
        return type in [
            GraphEventType.GRAPH_STARTED,
            GraphEventType.GRAPH_COMPLETED,
            GraphEventType.GRAPH_FAILED,
            GraphEventType.GRAPH_CANCELLED
        ]
    }

    /**
     * Check if this is a resilience-related event.
     */
    boolean isResilienceEvent() {
        return type in [
            GraphEventType.CIRCUIT_OPENED,
            GraphEventType.CIRCUIT_CLOSED,
            GraphEventType.CIRCUIT_HALF_OPEN,
            GraphEventType.RATE_LIMITED,
            GraphEventType.TIMEOUT_WARNING,
            GraphEventType.TIMEOUT_EXCEEDED
        ]
    }

    /**
     * Check if this is a resource-related event.
     */
    boolean isResourceEvent() {
        return type in [
            GraphEventType.RESOURCE_WARNING,
            GraphEventType.RESOURCE_EXHAUSTED
        ]
    }

    /**
     * Check if this event represents a failure.
     */
    boolean isFailure() {
        return type in [
            GraphEventType.GRAPH_FAILED,
            GraphEventType.TASK_FAILED,
            GraphEventType.TIMEOUT_EXCEEDED,
            GraphEventType.RESOURCE_EXHAUSTED
        ] || (taskEvent?.hasError() ?: false)
    }

    @Override
    String toString() {
        def parts = ["GraphEvent(type=$type"]
        if (graphId) parts << "graph=$graphId"
        if (runId) parts << "run=$runId"
        if (taskEvent) parts << "task=${taskEvent.taskId}"
        if (!metrics.isEmpty()) parts << "metrics=${metrics.size()}"
        return parts.join(", ") + ")"
    }

    /**
     * Builder for creating GraphEvents with optional fields.
     */
    static Builder builder(GraphEventType type, String graphId, String runId) {
        return new Builder(type, graphId, runId)
    }

    static class Builder {
        private GraphEventType type
        private String graphId
        private String runId
        private TaskEvent taskEvent
        private Map<String, Object> metrics = [:]
        private Map<String, Object> metadata = [:]

        Builder(GraphEventType type, String graphId, String runId) {
            this.type = type
            this.graphId = graphId
            this.runId = runId
        }

        Builder taskEvent(TaskEvent event) { this.taskEvent = event; return this }
        Builder metrics(Map<String, Object> metrics) { this.metrics = metrics; return this }
        Builder addMetric(String key, Object value) { this.metrics[key] = value; return this }
        Builder metadata(Map<String, Object> metadata) { this.metadata = metadata; return this }
        Builder addMetadata(String key, Object value) { this.metadata[key] = value; return this }

        GraphEvent build() {
            return new GraphEvent(type, graphId, runId, taskEvent, metrics, metadata)
        }
    }
}
