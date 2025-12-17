package org.softwood.actor.hierarchy

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.softwood.actor.Actor

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Registry tracking parent-child relationships between actors.
 * 
 * <p>Enables actor hierarchies for supervision trees and lifecycle management.</p>
 * 
 * <h2>Features</h2>
 * <ul>
 *   <li>Track parent-child relationships</li>
 *   <li>Automatic cleanup when actors stop</li>
 *   <li>Support for ESCALATE supervision directive</li>
 *   <li>Thread-safe concurrent access</li>
 * </ul>
 * 
 * @since 2.0.0
 */
@Slf4j
@CompileStatic
class ActorHierarchyRegistry {
    
    // Map: child actor name -> parent actor name
    private final ConcurrentHashMap<String, String> childToParent = new ConcurrentHashMap<>()
    
    // Map: parent actor name -> set of child actor names
    private final ConcurrentHashMap<String, CopyOnWriteArraySet<String>> parentToChildren = new ConcurrentHashMap<>()
    
    /**
     * Register a parent-child relationship.
     * 
     * @param parent the parent actor
     * @param child the child actor
     */
    void registerChild(Actor parent, Actor child) {
        String parentName = parent.name
        String childName = child.name
        
        if (parentName == childName) {
            log.warn("Actor {} attempted to be its own parent - ignoring", parentName)
            return
        }
        
        // Check for circular relationships
        if (isAncestor(childName, parentName)) {
            throw new IllegalArgumentException(
                "Cannot register ${childName} as child of ${parentName} - would create circular hierarchy")
        }
        
        // Set parent for child
        String previousParent = childToParent.put(childName, parentName)
        if (previousParent != null && previousParent != parentName) {
            log.warn("Actor {} changed parent from {} to {}", childName, previousParent, parentName)
            // Remove from old parent's children
            CopyOnWriteArraySet<String> oldParentChildren = parentToChildren.get(previousParent)
            if (oldParentChildren != null) {
                oldParentChildren.remove(childName)
            }
        }
        
        // Add to parent's children
        parentToChildren.computeIfAbsent(parentName, { new CopyOnWriteArraySet<String>() })
                .add(childName)
        
        log.debug("Registered actor hierarchy: {} -> {}", parentName, childName)
    }
    
    /**
     * Remove a child from its parent.
     * 
     * @param child the child actor
     */
    void unregisterChild(Actor child) {
        String childName = child.name
        String parentName = childToParent.remove(childName)
        
        if (parentName != null) {
            CopyOnWriteArraySet<String> children = parentToChildren.get(parentName)
            if (children != null) {
                children.remove(childName)
                if (children.isEmpty()) {
                    parentToChildren.remove(parentName)
                }
            }
            log.debug("Unregistered child {} from parent {}", childName, parentName)
        }
    }
    
    /**
     * Get the parent of an actor.
     * 
     * @param actor the actor
     * @return the parent actor name, or null if no parent
     */
    String getParent(Actor actor) {
        return childToParent.get(actor.name)
    }
    
    /**
     * Get all children of an actor.
     * 
     * @param actor the parent actor
     * @return set of child actor names (never null)
     */
    Set<String> getChildren(Actor actor) {
        CopyOnWriteArraySet<String> children = parentToChildren.get(actor.name)
        return children != null ? new HashSet<String>(children) : new HashSet<String>()
    }
    
    /**
     * Check if an actor has a parent.
     * 
     * @param actor the actor
     * @return true if actor has a parent
     */
    boolean hasParent(Actor actor) {
        return childToParent.containsKey(actor.name)
    }
    
    /**
     * Check if an actor has children.
     * 
     * @param actor the actor
     * @return true if actor has children
     */
    boolean hasChildren(Actor actor) {
        CopyOnWriteArraySet<String> children = parentToChildren.get(actor.name)
        return children != null && !children.isEmpty()
    }
    
    /**
     * Check if one actor is an ancestor of another.
     * Used to prevent circular hierarchies.
     * 
     * @param potentialAncestor potential ancestor name
     * @param descendant descendant name
     * @return true if potentialAncestor is an ancestor of descendant
     */
    private boolean isAncestor(String potentialAncestor, String descendant) {
        String current = descendant
        Set<String> visited = new HashSet<>()
        
        while (current != null) {
            if (current == potentialAncestor) {
                return true
            }
            if (!visited.add(current)) {
                // Circular reference detected
                log.warn("Circular reference detected in hierarchy: {}", visited)
                return false
            }
            current = childToParent.get(current)
        }
        
        return false
    }
    
    /**
     * Remove all relationships for an actor (as both parent and child).
     * Called when an actor terminates.
     * 
     * @param actor the actor to clean up
     */
    void removeActor(Actor actor) {
        String actorName = actor.name
        
        // Remove as child
        unregisterChild(actor)
        
        // Remove as parent (but keep children registered - they still exist)
        CopyOnWriteArraySet<String> children = parentToChildren.remove(actorName)
        if (children != null) {
            // Orphan the children (remove their parent references)
            for (String childName : children) {
                childToParent.remove(childName)
                log.debug("Orphaned child {} after parent {} terminated", childName, actorName)
            }
        }
        
        log.debug("Removed all hierarchy relationships for actor {}", actorName)
    }
    
    /**
     * Get statistics about the hierarchy.
     * 
     * @return map with counts
     */
    Map<String, Object> getStats() {
        return [
            totalParents: (Object)parentToChildren.size(),
            totalChildren: (Object)childToParent.size(),
            totalRelationships: (Object)childToParent.size()
        ] as Map<String, Object>
    }
    
    /**
     * Clear all hierarchies (for testing).
     */
    void clear() {
        childToParent.clear()
        parentToChildren.clear()
        log.debug("Cleared all actor hierarchies")
    }
}
