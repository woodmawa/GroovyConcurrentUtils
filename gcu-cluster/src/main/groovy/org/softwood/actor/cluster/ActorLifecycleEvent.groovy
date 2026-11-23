package org.softwood.actor.cluster

import groovy.transform.CompileStatic

import java.time.Instant

/**
 * Type of actor lifecycle event.
 */
@CompileStatic
enum ActorLifecycleEventType {
    CREATED,     // Actor was created
    STARTED,     // Actor started processing messages
    SUSPENDED,   // Actor was suspended
    RESUMED,     // Actor was resumed
    STOPPED,     // Actor was stopped
    FAILED       // Actor failed
}

/**
 * Represents an actor lifecycle event broadcast across the cluster.
 * Used to notify other cluster nodes about actor state changes.
 * 
 * @author Will Woodman
 * @since 1.0
 */
@CompileStatic
class ActorLifecycleEvent implements Serializable {
    
    private static final long serialVersionUID = 1L
    
    /** Actor identifier */
    String actorId
    
    /** Type of lifecycle event */
    ActorLifecycleEventType eventType
    
    /** Node where event occurred */
    String sourceNode
    
    /** When event occurred */
    Instant timestamp
    
    /** Error message (if eventType is FAILED) */
    String errorMessage
    
    /** Additional event metadata */
    Map<String, String> metadata = [:]
    
    /**
     * Default constructor for serialization
     */
    ActorLifecycleEvent() {}
    
    /**
     * Create a new actor lifecycle event.
     * 
     * @param actorId Actor identifier
     * @param eventType Type of event
     * @param sourceNode Node where event occurred
     */
    ActorLifecycleEvent(String actorId, ActorLifecycleEventType eventType, String sourceNode) {
        this.actorId = actorId
        this.eventType = eventType
        this.sourceNode = sourceNode
        this.timestamp = Instant.now()
    }
    
    /**
     * Create a new actor lifecycle event with error message.
     * 
     * @param actorId Actor identifier
     * @param eventType Type of event
     * @param sourceNode Node where event occurred
     * @param errorMessage Error message
     */
    ActorLifecycleEvent(String actorId, ActorLifecycleEventType eventType, String sourceNode, String errorMessage) {
        this(actorId, eventType, sourceNode)
        this.errorMessage = errorMessage
    }
    
    @Override
    String toString() {
        return "ActorLifecycleEvent[actor=$actorId, type=$eventType, node=$sourceNode, time=$timestamp]"
    }
}
