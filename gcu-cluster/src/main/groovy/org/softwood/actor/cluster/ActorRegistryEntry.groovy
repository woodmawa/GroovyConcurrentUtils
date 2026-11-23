package org.softwood.actor.cluster

import groovy.transform.CompileStatic

import java.time.Instant

/**
 * Status of an actor in the cluster.
 */
@CompileStatic
enum ActorStatus {
    ACTIVE,      // Actor is running and accepting messages
    SUSPENDED,   // Actor is temporarily suspended
    STOPPED,     // Actor has been stopped
    MIGRATING    // Actor is being migrated to another node
}

/**
 * Represents an actor's registration in the distributed cluster.
 * Tracks actor location, status, and metadata across the cluster.
 * 
 * @author Will Woodman
 * @since 1.0
 */
@CompileStatic
class ActorRegistryEntry implements Serializable {
    
    private static final long serialVersionUID = 1L
    
    /** Unique actor identifier (actor name) */
    String actorId
    
    /** Actor class name */
    String actorClass
    
    /** Node where this actor is running */
    String ownerNode
    
    /** Current actor status */
    ActorStatus status
    
    /** When this actor was registered */
    Instant registeredAt
    
    /** Last heartbeat from owning node */
    Instant lastHeartbeat
    
    /** Actor-specific metadata (optional) */
    Map<String, String> metadata = [:]
    
    /**
     * Default constructor for serialization
     */
    ActorRegistryEntry() {}
    
    /**
     * Create a new actor registry entry.
     * 
     * @param actorId Unique actor identifier
     * @param actorClass Actor class name
     * @param ownerNode Node where actor is running
     */
    ActorRegistryEntry(String actorId, String actorClass, String ownerNode) {
        this.actorId = actorId
        this.actorClass = actorClass
        this.ownerNode = ownerNode
        this.status = ActorStatus.ACTIVE
        this.registeredAt = Instant.now()
        this.lastHeartbeat = Instant.now()
    }
    
    /**
     * Mark actor as active.
     */
    void markActive() {
        this.status = ActorStatus.ACTIVE
        this.lastHeartbeat = Instant.now()
    }
    
    /**
     * Mark actor as suspended.
     */
    void markSuspended() {
        this.status = ActorStatus.SUSPENDED
        this.lastHeartbeat = Instant.now()
    }
    
    /**
     * Mark actor as stopped.
     */
    void markStopped() {
        this.status = ActorStatus.STOPPED
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
        return "ActorRegistryEntry[id=$actorId, class=$actorClass, status=$status, node=$ownerNode]"
    }
}
