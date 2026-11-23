package org.softwood.dag

import groovy.transform.Canonical
import java.time.Instant
import java.time.Duration

/**
 * Health status snapshot of a TaskGraph execution.
 *
 * Provides current state information for monitoring and observability.
 * Zero dependencies - pure data structure.
 *
 * <h3>Usage:</h3>
 * <pre>
 * def health = workflow.getHealth()
 * if (health.isHealthy()) {
 *     log.info("Graph ${health.graphId} running smoothly")
 * } else {
 *     log.error("Graph ${health.graphId} has ${health.failedTasks} failures")
 * }
 * </pre>
 */
@Canonical
class HealthStatus {

    /** Graph identifier */
    String graphId

    /** Unique run identifier */
    String runId

    /** Current graph state */
    GraphState state

    /** When the graph started */
    Instant startTime

    /** Current execution duration in milliseconds */
    Long durationMs

    /** Total number of tasks in the graph */
    int totalTasks

    /** Number of completed tasks */
    int completedTasks

    /** Number of failed tasks */
    int failedTasks

    /** Number of currently running tasks */
    int runningTasks

    /** Number of scheduled (not yet started) tasks */
    int scheduledTasks

    /** Number of skipped tasks */
    int skippedTasks

    /** Resource usage snapshot (if monitoring enabled) */
    ResourceSnapshot resourceUsage

    /**
     * Check if the graph is healthy.
     *
     * A graph is considered healthy if:
     * - No tasks have failed
     * - Graph is either RUNNING or COMPLETED
     *
     * @return true if healthy, false otherwise
     */
    boolean isHealthy() {
        return failedTasks == 0 &&
               (state == GraphState.RUNNING || state == GraphState.COMPLETED)
    }

    /**
     * Check if the graph is running.
     */
    boolean isRunning() {
        return state == GraphState.RUNNING
    }

    /**
     * Check if the graph is completed.
     */
    boolean isCompleted() {
        return state == GraphState.COMPLETED
    }

    /**
     * Check if the graph has failed.
     */
    boolean isFailed() {
        return state == GraphState.FAILED
    }

    /**
     * Get completion percentage (0-100).
     */
    double getCompletionPercentage() {
        if (totalTasks == 0) return 0.0
        return (completedTasks / (double) totalTasks) * 100.0
    }

    /**
     * Get estimated remaining tasks.
     */
    int getRemainingTasks() {
        return totalTasks - completedTasks - failedTasks - skippedTasks
    }

    /**
     * Convert to map for JSON serialization.
     */
    Map<String, Object> toMap() {
        def map = [
            graphId: graphId,
            runId: runId,
            state: state?.toString(),
            startTime: startTime?.toString(),
            durationMs: durationMs,
            totalTasks: totalTasks,
            completedTasks: completedTasks,
            failedTasks: failedTasks,
            runningTasks: runningTasks,
            scheduledTasks: scheduledTasks,
            skippedTasks: skippedTasks,
            remainingTasks: getRemainingTasks(),
            completionPercentage: getCompletionPercentage(),
            healthy: isHealthy()
        ]

        if (resourceUsage) {
            map.resourceUsage = resourceUsage.toMap()
        }

        return map
    }

    @Override
    String toString() {
        return "HealthStatus[graph=$graphId, state=$state, " +
               "tasks=$completedTasks/$totalTasks, " +
               "failed=$failedTasks, " +
               "duration=${durationMs}ms, " +
               "healthy=${isHealthy()}]"
    }
}

/**
 * Graph execution state.
 */
enum GraphState {
    NOT_STARTED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}
