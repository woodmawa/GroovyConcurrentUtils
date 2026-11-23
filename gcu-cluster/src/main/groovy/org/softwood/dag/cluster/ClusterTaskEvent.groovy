package org.softwood.dag.cluster

import groovy.transform.CompileStatic
import org.softwood.cluster.ClusterMessageValidator

import java.time.Instant

/**
 * Serializable task event for cluster-wide distribution via Hazelcast topics.
 * Unlike TaskEvent which contains Throwable (not serializable), this class
 * only contains serializable state information.
 * 
 * Supports message signing for integrity validation.
 * 
 * @author Will Woodman
 * @since 1.0
 */
@CompileStatic
class ClusterTaskEvent implements Serializable {
    
    private static final long serialVersionUID = 2L  // Incremented for signature field
    
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
    
    /** HMAC signature for message integrity (optional) */
    String signature
    
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
    
    /**
     * Sign this event using the provided validator.
     * Sets the signature field based on event data.
     * 
     * @param validator Message validator with signing key
     * @return this event (for method chaining)
     */
    ClusterTaskEvent sign(ClusterMessageValidator validator) {
        if (validator == null) {
            return this
        }
        
        String messageData = getSignatureData()
        this.signature = validator.sign(messageData)
        return this
    }
    
    /**
     * Verify this event's signature using the provided validator.
     * 
     * @param validator Message validator with signing key
     * @return true if signature is valid, false otherwise
     */
    boolean verify(ClusterMessageValidator validator) {
        if (validator == null) {
            return true  // No validation if validator not provided
        }
        
        String messageData = getSignatureData()
        return validator.verify(messageData, this.signature)
    }
    
    /**
     * Get the canonical string representation of this event for signing.
     * Includes all fields except signature itself.
     */
    private String getSignatureData() {
        return "${taskId}:${graphRunId}:${state}:${sourceNode}:${timestamp}:${errorMessage ?: ''}"
    }
    
    @Override
    String toString() {
        def str = "ClusterTaskEvent[task=$taskId, run=$graphRunId, state=$state, node=$sourceNode"
        if (signature) {
            str += ", signed=true"
        }
        str += "]"
        return str
    }
}
