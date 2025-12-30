package org.softwood.dag.persistence

import groovy.transform.ToString
import groovy.transform.builder.Builder

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Complete snapshot of a TaskGraph execution state.
 * This is the root object persisted by MicroStream.
 */
@ToString(includeNames = true, includePackage = false, excludes = ['taskStates'])
@Builder
class GraphStateSnapshot implements Serializable {
    private static final long serialVersionUID = 1L
    
    // Graph identification
    String graphId
    String runId        // UUID for this specific run
    Long threadId       // Thread ID that executed this run
    String threadName   // Thread name for debugging
    
    // Execution timing
    Instant startTime
    Instant endTime
    
    // Task state tracking (thread-safe map)
    Map<String, TaskStateSnapshot> taskStates = new ConcurrentHashMap<>()
    
    // Context globals (serializable values only)
    Map<String, Object> contextGlobals = new ConcurrentHashMap<>()
    
    // Final execution status
    GraphExecutionStatus finalStatus = GraphExecutionStatus.RUNNING
    
    // Failure information
    String failureTaskId    // ID of the first task that failed
    String failureMessage
    String failureStackTrace
    
    // Retry tracking
    int retryAttempt = 0
    
    /**
     * Calculate graph execution duration in milliseconds
     */
    Long getDurationMillis() {
        if (startTime && endTime) {
            return endTime.toEpochMilli() - startTime.toEpochMilli()
        }
        return null
    }
    
    /**
     * Get count of completed tasks
     */
    int getCompletedTaskCount() {
        taskStates.values().count { it.isCompleted() }
    }
    
    /**
     * Get count of failed tasks
     */
    int getFailedTaskCount() {
        taskStates.values().count { it.isFailed() }
    }
    
    /**
     * Get count of skipped tasks
     */
    int getSkippedTaskCount() {
        taskStates.values().count { it.isSkipped() }
    }
    
    /**
     * Check if graph completed successfully
     */
    boolean isSuccessful() {
        return finalStatus == GraphExecutionStatus.COMPLETED
    }
    
    /**
     * Check if graph failed
     */
    boolean hasFailed() {
        return finalStatus == GraphExecutionStatus.FAILED
    }
}
