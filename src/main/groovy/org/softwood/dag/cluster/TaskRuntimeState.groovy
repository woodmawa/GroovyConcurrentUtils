package org.softwood.dag.cluster

import groovy.transform.CompileStatic

import java.time.Instant

/**
 * Represents the distributed runtime state of a single task.
 * This is replicated across the Hazelcast cluster.
 * 
 * <p>NOTE: This class only stores state information, not the task definition itself.
 * Task objects contain closures which are not serializable across the cluster.</p>
 * 
 * @author Will Woodman
 * @since 1.0
 */
@CompileStatic
class TaskRuntimeState implements Serializable {
    
    private static final long serialVersionUID = 1L
    
    /** Unique task identifier */
    String taskId
    
    /** Graph run ID this task belongs to */
    String graphRunId
    
    /** Current task state name (e.g., "PENDING", "RUNNING", "COMPLETED", "FAILED", "SKIPPED") */
    String state
    
    /** Node currently executing this task */
    String executingNode
    
    /** When this task started execution */
    Instant startTime
    
    /** When this task completed (if applicable) */
    Instant completionTime
    
    /** 
     * Task result - only stored if serializable.
     * Complex non-serializable results should be stored elsewhere.
     */
    Serializable result
    
    /** 
     * Error message if task failed.
     * We store the message (String) instead of Throwable to ensure serializability.
     */
    String errorMessage
    
    /** Stack trace if task failed (stored as string) */
    String errorStackTrace
    
    /**
     * Default constructor for serialization
     */
    TaskRuntimeState() {}
    
    /**
     * Create a new task runtime state.
     * 
     * @param taskId Task identifier
     * @param graphRunId Graph run this task belongs to
     * @param state Initial state
     */
    TaskRuntimeState(String taskId, String graphRunId, String state) {
        this.taskId = taskId
        this.graphRunId = graphRunId
        this.state = state
        this.startTime = Instant.now()
    }
    
    /**
     * Update the task state.
     */
    void setState(String newState) {
        this.state = newState
    }
    
    /**
     * Mark task as running on a specific node.
     */
    void markRunning(String nodeName) {
        this.state = "RUNNING"
        this.executingNode = nodeName
        this.startTime = Instant.now()
    }
    
    /**
     * Mark task as completed with optional result.
     */
    void markCompleted(Serializable taskResult = null) {
        this.state = "COMPLETED"
        this.completionTime = Instant.now()
        this.result = taskResult
    }
    
    /**
     * Mark task as failed with error information.
     */
    void markFailed(String errorMsg, String stackTrace = null) {
        this.state = "FAILED"
        this.completionTime = Instant.now()
        this.errorMessage = errorMsg
        this.errorStackTrace = stackTrace
    }
    
    /**
     * Mark task as skipped (e.g., due to upstream failure).
     */
    void markSkipped() {
        this.state = "SKIPPED"
        this.completionTime = Instant.now()
    }
    
    @Override
    String toString() {
        return "TaskRuntimeState[taskId=$taskId, state=$state, node=$executingNode]"
    }
}
