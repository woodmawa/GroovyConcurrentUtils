// ═════════════════════════════════════════════════════════════
// ActorSystem.groovy
// ═════════════════════════════════════════════════════════════
package org.softwood.actor

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.softwood.actor.remote.RemotingTransport
import org.softwood.actor.remote.RemoteActorRef

/**
 * ActorSystem: actor lifecycle coordinator.
 *
 * Responsibilities:
 *  - Actor creation and registration
 *  - System-wide lifecycle (startup/shutdown)
 *  - Registry coordination (delegates to ActorRegistry)
 *  - Provides convenient DSL entry point
 *
 * The registry handles storage strategy (local vs distributed).
 */
@Slf4j
@CompileStatic
class ActorSystem implements Closeable {
    final String name
    private final ActorRegistry registry
    // Optional remoting transports keyed by scheme
    private final Map<String, org.softwood.actor.remote.RemotingTransport> transports = [:]

    // ─────────────────────────────────────────────────────────────
    // Construction
    // ─────────────────────────────────────────────────────────────

    ActorSystem(String name) {
        this(name, new ActorRegistry())
    }

    /**
     * Constructor with injectable registry (useful for testing).
     */
    ActorSystem(String name, ActorRegistry registry) {
        this.name = name
        this.registry = registry
        log.info "ActorSystem '$name' created (distributed: ${registry.isDistributed()})"
    }

    // ─────────────────────────────────────────────────────────────
    // Actor Creation & Management
    // ─────────────────────────────────────────────────────────────

    ScopedValueActor createActor(String actorName, Closure handler, Map initialState = [:]) {
        if (registry.contains(actorName)) {
            throw new IllegalArgumentException("Actor '$actorName' already exists in system '$name'")
        }

        def actor = new ScopedValueActor(actorName, initialState, handler)
        // inject back-reference to system for context-enabled operations
        actor.setSystem(this)
        registry.register(actorName, actor)

        log.debug "Created actor: $actorName"
        return actor
    }

    /**
     * Remove an actor from the registry and stop it.
     */
    void removeActor(String actorName) {
        def actor = registry.get(actorName)
        if (actor) {
            actor.stop()
            registry.unregister(actorName)
            log.debug "Removed actor: $actorName"
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Registry Access (delegated)
    // ─────────────────────────────────────────────────────────────

    ScopedValueActor getActor(String actorName) {
        registry.get(actorName)
    }

    Set<String> getActorNames() {
        registry.getActorNames()
    }

    int getActorCount() {
        registry.size()
    }

    boolean hasActor(String actorName) {
        registry.contains(actorName)
    }

    boolean isDistributed() {
        registry.isDistributed()
    }

    // ─────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────

    void shutdown() {
        log.info "Shutting down ActorSystem '$name' with ${registry.size()} actors"

        // Stop all actors gracefully
        registry.getAllActors().each { actor ->
            try {
                actor.stop()
            } catch (Exception e) {
                log.warn "Error stopping actor ${actor.name}: ${e.message}"
            }
        }

        registry.clear()
        log.info "ActorSystem '$name' shutdown complete"
    }

    @Override
    void close() {
        shutdown()
    }

    // ─────────────────────────────────────────────────────────────
    // Convenience DSL Entry Point
    // ─────────────────────────────────────────────────────────────

    /**
     * Inline actor creation via DSL:
     *   system.actor {
     *       name "MyActor"
     *       onMessage { msg, ctx -> ... }
     *   }
     */
    ScopedValueActor actor(
            @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = ActorBuilder)
                    Closure<?> spec) {
        ActorDSL.actor(this, spec)
    }

    // ─────────────────────────────────────────────────────────────
    // Diagnostics
    // ─────────────────────────────────────────────────────────────

    String getStatus() {
        """ActorSystem: $name
  Actors: ${registry.size()}
  Distributed: ${registry.isDistributed()}
  Actor Names: ${registry.getActorNames()}
  Remoting: ${transports.keySet()}"""
    }

    // ─────────────────────────────────────────────────────────────
    // Remoting (pluggable)
    // ─────────────────────────────────────────────────────────────

    /** Register and start one or more remoting transports. */
    void enableRemoting(List<RemotingTransport> ts) {
        ts.each { RemotingTransport t ->
            if (t) {
                t.start()
                transports[t.scheme()] = t
                log.info "Enabled remoting transport: ${t.scheme()}"
            }
        }
    }

    /** Obtain a reference to a remote actor via URI (scheme://host:port/system/actor). */
    Object remote(String actorUri) {
        def scheme = parseScheme(actorUri)
        def transport = transports[scheme]
        if (!transport) {
            throw new IllegalStateException("No remoting transport registered for scheme '$scheme'")
        }
        return new RemoteActorRef(actorUri, transport)
    }

    private static String parseScheme(String uri) {
        def idx = uri?.indexOf('://') ?: -1
        if (idx <= 0) throw new IllegalArgumentException("Invalid actor URI: $uri")
        return uri.substring(0, idx).toLowerCase()
    }
}