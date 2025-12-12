package org.softwood.actor

import com.hazelcast.core.Hazelcast
import groovy.util.logging.Slf4j
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
        def clusterName = ConfigLoader.getString(config, 'hazelcast.cluster.name', 'default-cluster')
        def port = ConfigLoader.getInt(config, 'hazelcast.port', 5701)

        log.info "Using Hazelcast distributed map (cluster: $clusterName, port: $port)"

        def hz = Hazelcast.newHazelcastInstance()
        return hz.getMap("actors")
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
    Map<String, ScopedValueActor> getRegistry() {
        storage
    }
}