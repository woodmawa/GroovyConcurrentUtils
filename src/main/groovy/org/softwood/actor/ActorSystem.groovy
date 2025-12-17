// ═════════════════════════════════════════════════════════════
// ActorSystem.groovy
// ═════════════════════════════════════════════════════════════
package org.softwood.actor

import groovy.transform.CompileDynamic
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
    private final Map<String, RemotingTransport> transports = [:]

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

    Actor createActor(String actorName, Closure handler, Map initialState = [:]) {
        if (registry.contains(actorName)) {
            throw new IllegalArgumentException("Actor '$actorName' already exists in system '$name'")
        }

        def actor = ActorFactory.builder(actorName, handler)
                .initialState(initialState)
                .build()
        
        // inject back-reference to system for context-enabled operations
        if (actor instanceof GroovyActor) {
            ((GroovyActor) actor).setSystem(this)
        }
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

    Actor getActor(String actorName) {
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

        // 🚨 FIX: Use a static Java-style loop to ensure the statically-typed
        // anonymous subclass override of stop() is called correctly.
        for (Object actorRef : registry.getAllActors()) {
            try {
                // We know these objects are ScopedValueActor types
                ((Actor)actorRef).stop()
            } catch (Exception e) {
                // Assuming 'actorRef' has a 'name' property for logging
                log.warn "Error stopping actor ${actorRef}: ${e.message}"
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
    // Convenience DSL Entry Points
    // ─────────────────────────────────────────────────────────────

    /**
     * Inline actor creation via DSL.
     * 
     * <p>Usage:</p>
     * <pre>
     * system.actor {
     *     name "MyActor"
     *     onMessage { msg, ctx -&gt; ... }
     * }
     * </pre>
     */
    Actor actor(
            @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = ActorBuilder)
                    Closure<?> spec) {
        ActorFactory.actor(this, spec)
    }
    
    /**
     * Shortest actor creation syntax.
     * 
     * <p>Usage:</p>
     * <pre>
     * system.actor("MyActor") { msg, ctx -&gt;
     *     println "[\$ctx.actorName] \$msg"
     * }
     * </pre>
     * 
     * @param actorName actor name
     * @param handler message handler closure
     * @return created actor
     */
    Actor actor(String actorName, Closure handler) {
        createActor(actorName, handler)
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
        // Use a standard for loop to avoid unpredictable Groovy metaclass calls (like asBoolean)
        for (RemotingTransport t : ts) {
            if (t != null) {
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