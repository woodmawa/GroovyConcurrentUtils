package org.softwood.actor

import com.hazelcast.core.Hazelcast
import com.hazelcast.core.HazelcastInstance
import groovy.util.logging.Slf4j
import org.softwood.actor.cluster.HazelcastConfigBuilder
import org.softwood.config.ConfigLoader

import java.util.concurrent.ConcurrentHashMap

@Slf4j
class ActorRegistry {
    private final Map<String, Actor> storage
    private final boolean isDistributed

    ActorRegistry() {
        def config = ConfigLoader.loadConfig()
        logConfiguration(config)

        this.isDistributed = ConfigLoader.getBoolean(config, 'distributed', false)

        if (isDistributed) {
            this.storage = createDistributedStorage(config)
        } else {
            this.storage = createLocalStorage()
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Uniform Registry API (works for both local & distributed)
    // ─────────────────────────────────────────────────────────────

    void register(String name, Actor actor) {
        storage.put(name, actor)
        log.debug "Registered actor: $name (distributed: $isDistributed)"
    }

    void unregister(String name) {
        storage.remove(name)
        log.debug "Unregistered actor: $name"
    }

    Actor get(String name) {
        storage.get(name)
    }

    boolean contains(String name) {
        storage.containsKey(name)
    }

    Set<String> getActorNames() {
        storage.keySet()
    }

    Collection<Actor> getAllActors() {
        storage.values()
    }

    int size() {
        storage.size()
    }

    void clear() {
        storage.clear()
        log.info "Cleared all actors from registry"
    }

    boolean isDistributed() {
        isDistributed
    }

    // ─────────────────────────────────────────────────────────────
    // Storage Strategy Creation
    // ─────────────────────────────────────────────────────────────

    private Map<String, Actor> createLocalStorage() {
        log.info "Using local ConcurrentHashMap for actor registry"
        return new ConcurrentHashMap<>()
    }

    private Map<String, Actor> createDistributedStorage(Map config) {
        log.info "Initializing Hazelcast distributed actor registry..."
        
        try {
            // Build Hazelcast config using HazelcastConfigBuilder
            def hzConfig = HazelcastConfigBuilder.buildConfig(config)
            
            // Create Hazelcast instance
            HazelcastInstance hz = Hazelcast.newHazelcastInstance(hzConfig)
            
            log.info "Hazelcast instance started: ${hz.getName()}"
            log.info "Cluster size: ${hz.getCluster().getMembers().size()} members"
            
            // Return distributed map
            return hz.getMap("actors")
            
        } catch (Exception e) {
            log.error("Failed to initialize Hazelcast cluster", e)
            throw new RuntimeException("Hazelcast initialization failed", e)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Diagnostics
    // ─────────────────────────────────────────────────────────────

    private void logConfiguration(Map config) {
        def configSummary = config.collect { k, v -> "  $k = $v" }.join('\n')
        log.debug """
=== Actor Registry Configuration ===
$configSummary
===================================="""
    }

    /**
     * For backward compatibility - direct map access.
     * Prefer using the typed methods above.
     */
    @Deprecated
    Map<String, Actor> getRegistry() {
        storage.asImmutable ()
    }
}