package org.softwood.dag.cluster

import groovy.transform.CompileStatic
import java.time.Instant

/**
 * Serializable task event for cluster-wide distribution via Hazelcast topics.
 * Unlike TaskEvent which contains Throwable (not serializable), this class
 * only contains serializable state information.
 * 
 * @author Will Woodman
 * @since 1.0
 */
@CompileStatic
class ClusterTaskEvent implements Serializable {
    
    private static final long serialVersionUID = 1L
    
    /** Task identifier */
    String taskId
    
    /** Graph run ID this task belongs to */
    String graphRunId
    
    /** Task state name */
    String state
    
    /** Node that generated this event */
    String sourceNode
    
    /** Event timestamp */
    Instant timestamp
    
    /** Error message if task failed */
    String errorMessage
    
    /**
     * Default constructor for serialization
     */
    ClusterTaskEvent() {}
    
    /**
     * Create a new cluster task event.
     * 
     * @param taskId Task identifier
     * @param graphRunId Graph run ID
     * @param state Task state name
     * @param sourceNode Node that generated this event
     */
    ClusterTaskEvent(String taskId, String graphRunId, String state, String sourceNode) {
        this.taskId = taskId
        this.graphRunId = graphRunId
        this.state = state
        this.sourceNode = sourceNode
        this.timestamp = Instant.now()
    }
    
    /**
     * Create an event with error information.
     */
    ClusterTaskEvent(String taskId, String graphRunId, String state, String sourceNode, String errorMsg) {
        this(taskId, graphRunId, state, sourceNode)
        this.errorMessage = errorMsg
    }
    
    @Override
    String toString() {
        return "ClusterTaskEvent[task=$taskId, run=$graphRunId, state=$state, node=$sourceNode]"
    }
}
