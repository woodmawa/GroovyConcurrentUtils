package org.softwood.dag

import groovy.transform.Canonical
import org.softwood.dag.task.ITask
import org.softwood.dag.task.TaskState

/**
 * Complete snapshot of graph execution state.
 *
 * Provides detailed runtime inspection of all tasks and their states.
 * Useful for debugging, monitoring dashboards, and troubleshooting.
 * Zero dependencies - pure data structure.
 *
 * <h3>Usage:</h3>
 * <pre>
 * def snapshot = workflow.getSnapshot()
 * snapshot.taskSnapshots.each { taskId, taskSnap ->
 *     if (taskSnap.state == TaskState.FAILED) {
 *         log.error("Task $taskId failed: ${taskSnap.errorMessage}")
 *     }
 * }
 * </pre>
 */
@Canonical
class ExecutionSnapshot {

    /** High-level health status */
    HealthStatus health

    /** Detailed task snapshots by task ID */
    Map<String, TaskSnapshot> taskSnapshots

    /** Graph topology - task dependencies */
    Map<String, Set<String>> taskDependencies

    /** Execution metrics */
    Map<String, Object> metrics

    /**
     * Get tasks in a specific state.
     */
    List<TaskSnapshot> getTasksInState(TaskState state) {
        return taskSnapshots.values().findAll { it.state == state }
    }

    /**
     * Get failed tasks with error details.
     */
    List<TaskSnapshot> getFailedTasks() {
        return getTasksInState(TaskState.FAILED)
    }

    /**
     * Get running tasks.
     */
    List<TaskSnapshot> getRunningTasks() {
        return getTasksInState(TaskState.RUNNING)
    }

    /**
     * Get completed tasks.
     */
    List<TaskSnapshot> getCompletedTasks() {
        return getTasksInState(TaskState.COMPLETED)
    }

    /**
     * Get task by ID.
     */
    TaskSnapshot getTask(String taskId) {
        return taskSnapshots[taskId]
    }

    /**
     * Find bottleneck tasks (long-running with many dependents).
     */
    List<TaskSnapshot> findBottlenecks() {
        return taskSnapshots.values().findAll { task ->
            task.state == TaskState.RUNNING &&
            task.successors.size() > 2  // Has many dependents
        }.sort { -it.successors.size() }
    }

    /**
     * Convert to map for JSON serialization.
     */
    Map<String, Object> toMap() {
        return [
            health: health.toMap(),
            tasks: taskSnapshots.collectEntries { id, snap ->
                [id, snap.toMap()]
            },
            dependencies: taskDependencies,
            metrics: metrics
        ]
    }

    @Override
    String toString() {
        return "ExecutionSnapshot[${health}, ${taskSnapshots.size()} tasks]"
    }
}

/**
 * Snapshot of individual task state.
 */
@Canonical
class TaskSnapshot {

    /** Task identifier */
    String id

    /** Task name (may differ from ID) */
    String name

    /** Current task state */
    TaskState state

    /** Error message if failed */
    String errorMessage

    /** Task predecessors (dependencies) */
    Set<String> predecessors

    /** Task successors (dependents) */
    Set<String> successors

    /** Task metadata */
    Map<String, Object> metadata

    /**
     * Convert to map for JSON serialization.
     */
    Map<String, Object> toMap() {
        def map = [
            id: id,
            name: name,
            state: state?.toString(),
            predecessors: predecessors as List,
            successors: successors as List
        ]

        if (errorMessage) {
            map.error = errorMessage
        }

        if (metadata) {
            map.metadata = metadata
        }

        return map
    }

    @Override
    String toString() {
        return "TaskSnapshot[id=$id, state=$state${errorMessage ? ', error' : ''}]"
    }
}
