// ═════════════════════════════════════════════════════════════
// ActorSystem.groovy
// ═════════════════════════════════════════════════════════════
package org.softwood.actor

import com.hazelcast.map.IMap
import com.hazelcast.topic.ITopic
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.softwood.actor.cluster.ActorLifecycleEvent
import org.softwood.actor.cluster.ActorLifecycleEventType
import org.softwood.actor.cluster.ActorRegistryEntry
import org.softwood.actor.cluster.ActorStatus
import org.softwood.actor.lifecycle.DeathWatchRegistry
import org.softwood.actor.hierarchy.ActorHierarchyRegistry
import org.softwood.actor.remote.RemotingTransport
import org.softwood.actor.remote.RemoteActorRef
import org.softwood.actor.scheduling.ActorScheduler
import org.softwood.cluster.HazelcastManager

/**
 * ActorSystem: Primary entry point for creating and managing actors in production.
 *
 * <p>The ActorSystem is the recommended way to create actors. It provides:
 * <ul>
 *   <li><b>Automatic registration:</b> Actors are tracked and managed by the system</li>
 *   <li><b>System reference injection:</b> Enables ctx.spawn(), ctx.watch(), ctx.scheduleOnce()</li>
 *   <li><b>Coordinated shutdown:</b> All actors stopped gracefully via system.shutdown()</li>
 *   <li><b>Scheduler:</b> Delayed and periodic message delivery</li>
 *   <li><b>DeathWatch:</b> Lifecycle monitoring and Terminated messages</li>
 *   <li><b>Hierarchy:</b> Parent-child relationships and supervision</li>
 *   <li><b>Remoting:</b> Optional distributed actor communication</li>
 * </ul>
 *
 * <h2>✅ Basic Usage (Recommended)</h2>
 * <pre>
 * // 1. Create the system
 * def system = new ActorSystem("my-app")
 * 
 * // 2. Create actors through the system
 * def greeter = system.actor {
 *     name 'Greeter'
 *     onMessage { msg, ctx -&gt;
 *         println "Hello, \${msg}!"
 *         ctx.reply("Greeted: \${msg}")
 *     }
 * }
 * 
 * // 3. Send messages
 * greeter.tell("World")
 * def response = greeter.askSync("Alice", Duration.ofSeconds(2))
 * 
 * // 4. Coordinated shutdown
 * system.shutdown()
 * </pre>
 *
 * <h2>Advanced Features</h2>
 * <pre>
 * def parent = system.actor {
 *     name 'Parent'
 *     onMessage { msg, ctx -&gt;
 *         if (msg.type == 'spawn-children') {
 *             // Create child actors
 *             ctx.spawn('child-1') { childMsg, childCtx -&gt;
 *                 println "Child processing: \${childMsg}"
 *             }
 *             
 *             // Or bulk spawn with spawnForEach
 *             ctx.spawnForEach(items, 'worker') { item, idx, workerMsg, workerCtx -&gt;
 *                 // Process item
 *             }
 *         }
 *         
 *         if (msg.type == 'watch-actor') {
 *             // Monitor another actor
 *             ctx.watch(otherActor)
 *         }
 *         
 *         if (msg.type == 'schedule') {
 *             // Schedule delayed message
 *             ctx.scheduleOnce(Duration.ofSeconds(5), 'timeout')
 *         }
 *     }
 * }
 * </pre>
 *
 * <h2>Short Syntax</h2>
 * <pre>
 * // Concise actor creation
 * def actor = system.actor("MyActor") { msg, ctx -&gt;
 *     println "[\${ctx.actorName}] \${msg}"
 * }
 * </pre>
 *
 * <h2>Why Use ActorSystem Instead of ActorFactory?</h2>
 * <p>ActorSystem-created actors have full feature support, while ActorFactory.create()
 * produces standalone actors with limited capabilities (no spawn, watch, schedule, etc.).
 * Use ActorFactory only for testing or advanced use cases.</p>
 *
 * <p><b>Responsibilities:</b>
 * <ul>
 *  <li>Actor creation and registration</li>
 *  <li>System-wide lifecycle (startup/shutdown)</li>
 *  <li>Registry coordination (delegates to ActorRegistry)</li>
 *  <li>Scheduler, DeathWatch, Hierarchy management</li>
 * </ul>
 *
 * @see ActorFactory For testing and advanced configuration only
 * @see ActorContext For context methods available in message handlers
 */
@Slf4j
@CompileStatic
class ActorSystem implements Closeable {
    final String name
    private final ActorRegistry registry
    // Optional remoting transports keyed by scheme
    private final Map<String, RemotingTransport> transports = [:]
    // Scheduler for delayed and periodic messages
    final ActorScheduler scheduler
    // Death watch registry for actor lifecycle monitoring
    final DeathWatchRegistry deathWatch
    // Hierarchy registry for parent-child relationships
    final ActorHierarchyRegistry hierarchy
    
    // Clustering support (optional)
    private boolean clusteringEnabled = false
    private IMap<String, ActorRegistryEntry> clusterActorRegistry
    private ITopic<ActorLifecycleEvent> clusterEventTopic
    private String localNodeName

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
        this.scheduler = new ActorScheduler()
        this.deathWatch = new DeathWatchRegistry()
        this.hierarchy = new ActorHierarchyRegistry()
        
        // Initialize clustering if enabled
        initializeClustering()
        
        log.info "ActorSystem '$name' created (distributed: ${registry.isDistributed()}, clustering: ${clusteringEnabled})"
    }

    // ─────────────────────────────────────────────────────────────
    // Actor Creation & Management
    // ─────────────────────────────────────────────────────────────
    
    /**
     * Initialize clustering if HazelcastManager is enabled.
     * Sets up distributed actor registry and event topic.
     */
    private void initializeClustering() {
        def hazelcastManager = HazelcastManager.instance
        
        if (!hazelcastManager.isEnabled()) {
            log.debug "Hazelcast clustering is not enabled for ActorSystem"
            return
        }
        
        try {
            // Get distributed data structures
            clusterActorRegistry = hazelcastManager.getActorRegistryMap()
            clusterEventTopic = hazelcastManager.getActorEventTopic()
            
            // Get local node name
            localNodeName = hazelcastManager.getInstance().getCluster().getLocalMember().toString()
            
            clusteringEnabled = true
            log.info "ActorSystem clustering enabled: node=$localNodeName, registry=$clusterActorRegistry"
            
        } catch (Exception e) {
            log.warn "Failed to initialize actor clustering, continuing without clustering", e
            clusteringEnabled = false
        }
    }
    
    /**
     * Register actor in cluster registry (if clustering enabled).
     */
    private void registerActorInCluster(String actorName, Actor actor) {
        if (!clusteringEnabled || clusterActorRegistry == null) {
            return
        }
        
        try {
            synchronized (this) {
                String actorClass = actor.class.simpleName
                ActorRegistryEntry entry = new ActorRegistryEntry(actorName, actorClass, localNodeName)
                // Use put() for new entries (replace() would fail if entry doesn't exist)
                clusterActorRegistry.put(actorName, entry)
                log.info "Actor '$actorName' registered in cluster: $entry"
            }
            
            // Publish CREATED event outside synchronized block to avoid holding lock during I/O
            ActorLifecycleEvent event = new ActorLifecycleEvent(
                actorName, 
                ActorLifecycleEventType.CREATED, 
                localNodeName
            )
            clusterEventTopic.publish(event)
            
            log.debug "Published CREATED event for actor '$actorName'"
        } catch (Exception e) {
            log.error "Failed to register actor '$actorName' in cluster", e
        }
    }
    
    /**
     * Unregister actor from cluster registry (if clustering enabled).
     */
    private void unregisterActorFromCluster(String actorName) {
        if (!clusteringEnabled || clusterActorRegistry == null) return
        
        try {
            synchronized (this) {
                clusterActorRegistry.remove(actorName)
            }
            
            // Publish STOPPED event outside synchronized block
            ActorLifecycleEvent event = new ActorLifecycleEvent(
                actorName,
                ActorLifecycleEventType.STOPPED,
                localNodeName
            )
            clusterEventTopic.publish(event)
            
            log.debug "Unregistered actor '$actorName' from cluster"
        } catch (Exception e) {
            log.warn "Failed to unregister actor '$actorName' from cluster", e
        }
    }
    
    /**
     * Update actor status in cluster (if clustering enabled).
     * Uses get-modify-replace pattern to ensure Hazelcast detects the change.
     */
    private void updateActorStatusInCluster(String actorName, ActorStatus newStatus) {
        if (!clusteringEnabled || clusterActorRegistry == null) return
        
        try {
            synchronized (this) {
                ActorRegistryEntry entry = clusterActorRegistry.get(actorName)
                if (entry != null) {
                    // Update status based on enum
                    switch (newStatus) {
                        case ActorStatus.ACTIVE:
                            entry.markActive()
                            break
                        case ActorStatus.SUSPENDED:
                            entry.markSuspended()
                            break
                        case ActorStatus.STOPPED:
                            entry.markStopped()
                            break
                    }
                    // Use replace() to force Hazelcast to detect the change
                    clusterActorRegistry.replace(actorName, entry)
                }
            }
        } catch (Exception e) {
            log.warn "Failed to update actor '$actorName' status in cluster", e
        }
    }
    
    /**
     * Find actor location in cluster.
     * Returns the node name where the actor is running, or null if not found.
     */
    String findActorNode(String actorName) {
        if (!clusteringEnabled || clusterActorRegistry == null) {
            return null
        }
        
        try {
            ActorRegistryEntry entry = clusterActorRegistry.get(actorName)
            return entry?.ownerNode
        } catch (Exception e) {
            log.warn "Failed to find actor '$actorName' in cluster", e
            return null
        }
    }

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
        
        // Register in cluster if clustering is enabled
        registerActorInCluster(actorName, actor)

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
            
            // Unregister from cluster if clustering is enabled
            unregisterActorFromCluster(actorName)
            
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
        
        // Shutdown scheduler
        scheduler.shutdown()
        
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
     * Create an actor using the builder DSL.
     * 
     * <p><b>✅ Recommended:</b> This is the primary way to create actors in production code.
     * The actor is automatically registered with the system and has full access to all features.</p>
     * 
     * <p>Usage:</p>
     * <pre>
     * def actor = system.actor {
     *     name 'MyActor'
     *     initialState count: 0
     *     onMessage { msg, ctx -&gt;
     *         // Full feature support: spawn, watch, schedule, etc.
     *         ctx.spawn('child') { ... }
     *         ctx.scheduleOnce(Duration.ofSeconds(5), 'timeout')
     *     }
     * }
     * </pre>
     * 
     * @param spec DSL configuration closure
     * @return fully-featured actor registered with the system
     */
    Actor actor(
            @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = ActorBuilder)
                    Closure<?> spec) {
        ActorFactory.actor(this, spec)
    }
    
    /**
     * Create an actor with concise syntax.
     * 
     * <p><b>✅ Recommended:</b> Short and clean syntax for simple actors.
     * The actor is automatically registered with the system.</p>
     * 
     * <p>Usage:</p>
     * <pre>
     * def greeter = system.actor("Greeter") { msg, ctx -&gt;
     *     println "Hello, \${msg}!"
     *     ctx.reply("Greeted: \${msg}")
     * }
     * 
     * greeter.tell("World")  // Prints: Hello, World!
     * </pre>
     * 
     * @param actorName actor name
     * @param handler message handler closure
     * @return fully-featured actor registered with the system
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