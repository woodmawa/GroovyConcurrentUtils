package org.softwood.dag.persistence

/**
 * Represents the overall execution status of a TaskGraph.
 */
enum GraphExecutionStatus {
    RUNNING,        // Graph is currently executing
    COMPLETED,      // Graph completed successfully (all tasks completed)
    FAILED,         // Graph failed (at least one task failed)
    PARTIAL         // Graph partially completed (some tasks completed, some failed/skipped)
}
