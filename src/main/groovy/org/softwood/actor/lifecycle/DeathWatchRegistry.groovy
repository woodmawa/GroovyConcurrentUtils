package org.softwood.actor.lifecycle

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.softwood.actor.Actor

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Manages death watch relationships between actors.
 * 
 * <p>Tracks which actors are watching which other actors, and sends
 * Terminated messages when watched actors stop.</p>
 * 
 * <p>This class is thread-safe and designed for concurrent access.</p>
 * 
 * @since 2.0.0
 */
@Slf4j
@CompileStatic
class DeathWatchRegistry {
    
    // Map: watched actor name -> set of watcher actor names
    private final ConcurrentHashMap<String, CopyOnWriteArraySet<String>> watchers = new ConcurrentHashMap<>()
    
    // Map: watcher actor name -> set of watched actor names
    private final ConcurrentHashMap<String, CopyOnWriteArraySet<String>> watching = new ConcurrentHashMap<>()
    
    /**
     * Register that one actor is watching another.
     * 
     * @param watcher the actor doing the watching
     * @param watched the actor being watched
     */
    void watch(Actor watcher, Actor watched) {
        String watcherName = watcher.name
        String watchedName = watched.name
        
        if (watcherName == watchedName) {
            log.warn("Actor {} attempted to watch itself - ignoring", watcherName)
            return
        }
        
        // Add to watchers map
        watchers.computeIfAbsent(watchedName, { new CopyOnWriteArraySet<String>() })
                .add(watcherName)
        
        // Add to watching map
        watching.computeIfAbsent(watcherName, { new CopyOnWriteArraySet<String>() })
                .add(watchedName)
        
        log.debug("Actor {} is now watching {}", watcherName, watchedName)
    }
    
    /**
     * Unregister a watch relationship.
     * 
     * @param watcher the actor doing the watching
     * @param watched the actor being watched
     */
    void unwatch(Actor watcher, Actor watched) {
        String watcherName = watcher.name
        String watchedName = watched.name
        
        // Remove from watchers map
        CopyOnWriteArraySet<String> watcherSet = watchers.get(watchedName)
        if (watcherSet != null) {
            watcherSet.remove(watcherName)
            if (watcherSet.isEmpty()) {
                watchers.remove(watchedName)
            }
        }
        
        // Remove from watching map
        CopyOnWriteArraySet<String> watchingSet = watching.get(watcherName)
        if (watchingSet != null) {
            watchingSet.remove(watchedName)
            if (watchingSet.isEmpty()) {
                watching.remove(watcherName)
            }
        }
        
        log.debug("Actor {} is no longer watching {}", watcherName, watchedName)
    }
    
    /**
     * Get all actors watching the specified actor.
     * 
     * @param watched the actor being watched
     * @return set of watcher names (never null)
     */
    Set<String> getWatchers(Actor watched) {
        CopyOnWriteArraySet<String> result = watchers.get(watched.name)
        return result != null ? new HashSet<>(result) : Collections.emptySet()
    }
    
    /**
     * Get all actors that the specified actor is watching.
     * 
     * @param watcher the actor doing the watching
     * @return set of watched actor names (never null)
     */
    Set<String> getWatching(Actor watcher) {
        CopyOnWriteArraySet<String> result = watching.get(watcher.name)
        return result != null ? new HashSet<>(result) : Collections.emptySet()
    }
    
    /**
     * Check if one actor is watching another.
     * 
     * @param watcher the actor doing the watching
     * @param watched the actor being watched
     * @return true if watching relationship exists
     */
    boolean isWatching(Actor watcher, Actor watched) {
        CopyOnWriteArraySet<String> watchingSet = watching.get(watcher.name)
        return watchingSet != null && watchingSet.contains(watched.name)
    }
    
    /**
     * Remove all watch relationships for an actor.
     * Called when an actor terminates.
     * 
     * @param actor the actor to clean up
     */
    void removeActor(Actor actor) {
        String actorName = actor.name
        
        // Remove as a watched actor
        watchers.remove(actorName)
        
        // Remove as a watcher
        CopyOnWriteArraySet<String> watchingSet = watching.remove(actorName)
        if (watchingSet != null) {
            // Remove from all watched actors' watcher lists
            for (String watchedName : watchingSet) {
                CopyOnWriteArraySet<String> watcherSet = watchers.get(watchedName)
                if (watcherSet != null) {
                    watcherSet.remove(actorName)
                    if (watcherSet.isEmpty()) {
                        watchers.remove(watchedName)
                    }
                }
            }
        }
        
        log.debug("Removed all watch relationships for actor {}", actorName)
    }
    
    /**
     * Get statistics about watch relationships.
     * 
     * @return map with counts
     */
    Map<String, Object> getStats() {
        return [
            totalWatched: watchers.size(),
            totalWatchers: watching.size(),
            totalRelationships: watchers.values().sum(0) { it.size() }
        ]
    }
    
    /**
     * Clear all watch relationships (for testing).
     */
    void clear() {
        watchers.clear()
        watching.clear()
        log.debug("Cleared all watch relationships")
    }
}
