package org.softwood.dag.persistence

/**
 * Represents the execution state of a task in the DAG.
 * Used for persistence and state tracking.
 */
enum TaskState {
    SCHEDULED,   // Task has been scheduled but not yet started
    RUNNING,     // Task is currently executing
    COMPLETED,   // Task completed successfully
    FAILED,      // Task failed with an error
    SKIPPED      // Task was skipped (e.g., due to upstream failure or routing decision)
}
