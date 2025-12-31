package org.softwood.dag.cluster

import groovy.transform.CompileStatic

import java.time.Instant

/**
 * Status of graph execution in the cluster.
 */
@CompileStatic
enum GraphExecutionStatus {
    SCHEDULED,   // Graph has been scheduled
    RUNNING,     // Graph is currently executing
    COMPLETED,   // All tasks completed successfully
    FAILED,      // One or more tasks failed
    CANCELLED    // Graph execution was cancelled
}

/**
 * Represents the distributed state of a graph execution.
 * This is replicated across the Hazelcast cluster.
 * 
 * @author Will Woodman
 * @since 1.0
 */
@CompileStatic
class GraphExecutionState implements Serializable {
    
    private static final long serialVersionUID = 1L
    
    /** Unique identifier for the graph definition */
    String graphId
    
    /** Unique identifier for this specific execution run */
    String runId
    
    /** Node that initiated/owns this graph execution */
    String ownerNode
    
    /** Current execution status */
    GraphExecutionStatus status
    
    /** Map of task IDs to their current state names */
    Map<String, String> taskStates = [:]
    
    /** When this graph execution started */
    Instant startTime
    
    /** Last heartbeat from the owner node */
    Instant lastHeartbeat
    
    /** When this graph execution completed (if applicable) */
    Instant completionTime
    
    /** ID of the task that caused failure (if status is FAILED) */
    String failedTaskId
    
    /** Error message if graph failed */
    String errorMessage
    
    /**
     * Default constructor for serialization
     */
    GraphExecutionState() {}
    
    /**
     * Create a new graph execution state.
     * 
     * @param graphId Graph definition ID
     * @param runId Unique run ID
     * @param ownerNode Node executing this graph
     */
    GraphExecutionState(String graphId, String runId, String ownerNode) {
        this.graphId = graphId
        this.runId = runId
        this.ownerNode = ownerNode
        this.status = GraphExecutionStatus.SCHEDULED
        this.startTime = Instant.now()
        this.lastHeartbeat = Instant.now()
    }
    
    /**
     * Update task state within this graph.
     */
    void updateTaskState(String taskId, String state) {
        taskStates[taskId] = state
        lastHeartbeat = Instant.now()
    }
    
    /**
     * Mark graph as running.
     */
    void markRunning() {
        this.status = GraphExecutionStatus.RUNNING
        this.lastHeartbeat = Instant.now()
    }
    
    /**
     * Mark graph as completed successfully.
     */
    void markCompleted() {
        this.status = GraphExecutionStatus.COMPLETED
        this.completionTime = Instant.now()
        this.lastHeartbeat = Instant.now()
    }
    
    /**
     * Mark graph as failed.
     */
    void markFailed(String taskId, String errorMsg) {
        this.status = GraphExecutionStatus.FAILED
        this.failedTaskId = taskId
        this.errorMessage = errorMsg
        this.completionTime = Instant.now()
        this.lastHeartbeat = Instant.now()
    }
    
    /**
     * Update heartbeat timestamp.
     */
    void heartbeat() {
        this.lastHeartbeat = Instant.now()
    }
    
    @Override
    String toString() {
        return "GraphExecutionState[graphId=$graphId, runId=$runId, status=$status, owner=$ownerNode, tasks=${taskStates.size()}]"
    }
}
