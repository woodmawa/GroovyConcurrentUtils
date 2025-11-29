package org.softwood.dag.task

enum TaskState {
    PENDING,
    SCHEDULED,
    RUNNING,
    COMPLETED,
    FAILED,
    SKIPPED,
    CIRCUIT_OPEN
}