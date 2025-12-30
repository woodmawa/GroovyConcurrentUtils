package org.softwood.dag.persistence

import groovy.transform.ToString
import groovy.transform.builder.Builder

import java.time.Instant

/**
 * Snapshot of a single task's state during graph execution.
 * Captures all relevant information for recovery and debugging.
 */
@ToString(includeNames = true, includePackage = false)
@Builder(excludes = ['durationMillis', 'failed', 'completed', 'skipped'])
class TaskStateSnapshot implements Serializable {
    private static final long serialVersionUID = 1L
    
    String taskId
    String taskName
    TaskState state
    
    Instant startTime
    Instant endTime
    
    // Serializable result - we'll handle non-serializable objects separately
    Object result
    
    // Error information
    String errorMessage
    String errorStackTrace
    
    // Retry tracking
    int retryAttempts = 0
    
    /**
     * Calculate task duration in milliseconds
     */
    Long getDurationMillis() {
        if (startTime && endTime) {
            return endTime.toEpochMilli() - startTime.toEpochMilli()
        }
        return null
    }
    
    /**
     * Check if this task failed
     */
    boolean isFailed() {
        return state == TaskState.FAILED
    }
    
    /**
     * Check if this task completed successfully
     */
    boolean isCompleted() {
        return state == TaskState.COMPLETED
    }
    
    /**
     * Check if this task was skipped
     */
    boolean isSkipped() {
        return state == TaskState.SKIPPED
    }
}
