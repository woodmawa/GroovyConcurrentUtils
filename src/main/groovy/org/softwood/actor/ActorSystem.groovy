package org.softwood.actor

import groovy.transform.CompileStatic

import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.CompletableFuture

/**
 * ActorSystem 2.0
 *
 * Responsibilities:
 *  - Registry of actors by name
 *  - Convenience factory for creating actors
 *  - Group operations (broadcast, askAny)
 *  - Coordinated shutdown of all registered actors
 *
 * Works with ScopedValueActor and the Actors DSL.
 */
@CompileStatic
class ActorSystem implements AutoCloseable {

    private final String name

    // All actors we know about
    private final Set<ScopedValueActor<?>> actors =
            Collections.newSetFromMap(new ConcurrentHashMap<ScopedValueActor<?>, Boolean>())

    // Name registry; actor names are not enforced unique, but last registration wins.
    private final ConcurrentHashMap<String, ScopedValueActor<?>> registry =
            new ConcurrentHashMap<>()

    ActorSystem(String name = "ActorSystem") {
        this.name = name
        println "ActorSystem '$name' created"
    }

    String getName() { name }

    // -------------------------------------------------------------------------
    // Registration / Lookup
    // -------------------------------------------------------------------------

    /**
     * Register an existing actor with this system.
     * Uses ScopedValueActor.getName() for the registry.
     */
    ScopedValueActor<?> register(ScopedValueActor<?> actor) {
        actors.add(actor)
        String actorName = actor.getName()   // <-- fixed: use getter, not actor.name
        if (actorName != null) {
            registry.put(actorName, actor)
        }
        return actor
    }

    /**
     * Deregister an actor by instance.
     */
    boolean deregister(ScopedValueActor<?> actor) {
        registry.values().removeIf { it.is(actor) }
        return actors.remove(actor)
    }

    /**
     * Deregister by name and return the actor if it existed.
     */
    ScopedValueActor<?> deregister(String actorName) {
        ScopedValueActor<?> a = registry.remove(actorName)
        if (a != null) {
            actors.remove(a)
        }
        return a
    }

    /**
     * Lookup actor by name.
     */
    ScopedValueActor<?> lookup(String actorName) {
        return registry.get(actorName)
    }

    /**
     * All known actor names.
     */
    Set<String> getActorNames() {
        return new LinkedHashSet<>(registry.keySet())
    }

    int getActorCount() {
        return actors.size()
    }

    // -------------------------------------------------------------------------
    // Creation convenience
    // -------------------------------------------------------------------------

    /**
     * Create and register a new actor with an explicit MessageHandler.
     */
    def <M> ScopedValueActor<M> createActor(String actorName,
                                            ScopedValueActor.MessageHandler<M> handler,
                                            int mailboxSize = 1000) {
        ScopedValueActor<M> a = new ScopedValueActor<M>(actorName, handler, mailboxSize)
        register(a)
        return a
    }

    // -------------------------------------------------------------------------
    // Group operations
    // -------------------------------------------------------------------------

    /**
     * Create a group over all actors currently in the system.
     */
    ActorGroup groupAll(String groupName = "all") {
        return new ActorGroup(groupName, new ArrayList<ScopedValueActor<?>>(actors))
    }

    /**
     * Create a group from a collection of actor names (skip missing).
     */
    ActorGroup groupByNames(String groupName, Collection<String> names) {
        List<ScopedValueActor<?>> members = new ArrayList<>()
        for (String n : names) {
            ScopedValueActor<?> a = registry.get(n)
            if (a != null) {
                members.add(a)
            }
        }
        return new ActorGroup(groupName, members)
    }

    /**
     * Create a group from explicit actor instances.
     */
    ActorGroup group(String groupName, Collection<ScopedValueActor<?>> members) {
        return new ActorGroup(groupName, new ArrayList<ScopedValueActor<?>>(members))
    }

    // -------------------------------------------------------------------------
    // System-wide broadcast / shutdown
    // -------------------------------------------------------------------------

    /**
     * Broadcast a message to all registered actors (tell-style).
     */
    void broadcast(Object message) {
        for (ScopedValueActor<?> a : actors) {
            @SuppressWarnings("unchecked")
            ScopedValueActor<Object> typed = (ScopedValueActor<Object>) a
            typed.tell(message)
        }
    }

    /**
     * Graceful shutdown:
     *   - stop all actors
     *   - wait for completion with a timeout
     */
    void shutdown(Duration timeoutPerActor = Duration.ofSeconds(5)) {
        println "ActorSystem '$name': shutting down ${actors.size()} actors"
        for (ScopedValueActor<?> a : actors) {
            try {
                a.stopAndWait(timeoutPerActor)
            } catch (Exception e) {
                println "ActorSystem '$name': error stopping actor $a - ${e.message}"
            }
        }
        println "ActorSystem '$name' shutdown complete"
    }

    @Override
    void close() {
        shutdown()
    }

    // -------------------------------------------------------------------------
    // ActorGroup: a view over multiple actors
    // -------------------------------------------------------------------------

    @CompileStatic
    static class ActorGroup {
        final String name
        private final List<ScopedValueActor<?>> members

        ActorGroup(String name, List<ScopedValueActor<?>> members) {
            this.name = name
            this.members = members
        }

        List<ScopedValueActor<?>> getMembers() {
            return Collections.unmodifiableList(members)
        }

        int size() { members.size() }

        boolean isEmpty() { members.isEmpty() }

        /**
         * Broadcast (tell) a message to all members.
         */
        void tell(Object message) {
            for (ScopedValueActor<?> a : members) {
                @SuppressWarnings("unchecked")
                ScopedValueActor<Object> typed = (ScopedValueActor<Object>) a
                typed.tell(message)
            }
        }

        /**
         * Ask all members; return the first one that completes successfully
         * (ask-any pattern). Others are not cancelled here (simple version).
         */
        Object askAny(Object message,
                      Duration timeout = Duration.ofSeconds(5)) {

            if (members.isEmpty())
                throw new IllegalStateException("ActorGroup '$name' has no members")

            List<CompletableFuture<Object>> futures = new ArrayList<>()
            for (ScopedValueActor<?> a : members) {
                @SuppressWarnings("unchecked")
                ScopedValueActor<Object> typed = (ScopedValueActor<Object>) a
                futures.add(typed.ask(message))
            }

            @SuppressWarnings("unchecked")
            CompletableFuture<Object>[] arr =
                    (CompletableFuture<Object>[]) new CompletableFuture[futures.size()]

            CompletableFuture<Object> any =
                    (CompletableFuture<Object>) CompletableFuture.anyOf(futures.toArray(arr))

            try {
                return any.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
            } catch (TimeoutException te) {
                throw new IllegalStateException(
                        "askAny timeout in group '$name' after $timeout", te)
            }
        }

        /**
         * Stop all members (best-effort).
         */
        void stopAll(Duration timeoutPerActor = Duration.ofSeconds(5)) {
            for (ScopedValueActor<?> a : members) {
                try {
                    a.stopAndWait(timeoutPerActor)
                } catch (Exception e) {
                    println "ActorGroup '$name': error stopping actor $a - ${e.message}"
                }
            }
        }

        @Override
        String toString() {
            "ActorGroup[$name](size=${members.size()})"
        }
    }

    // -------------------------------------------------------------------------
    // Router helpers
    // -------------------------------------------------------------------------

    RouterActor createRouter(String name,
                             RouterStrategy strategy,
                             Collection<ScopedValueActor<?>> members) {
        List<ScopedValueActor<?>> list = new ArrayList<>(members)
        RouterActor router = new RouterActor(name, list, strategy)
        register(router)
        return router
    }

    RouterActor createRoundRobinRouter(String name,
                                       Collection<ScopedValueActor<?>> members) {
        return createRouter(name, RouterStrategy.ROUND_ROBIN, members)
    }

    RouterActor createBroadcastRouter(String name,
                                      Collection<ScopedValueActor<?>> members) {
        return createRouter(name, RouterStrategy.BROADCAST, members)
    }

    RouterActor createRandomRouter(String name,
                                   Collection<ScopedValueActor<?>> members) {
        return createRouter(name, RouterStrategy.RANDOM, members)
    }

    // -------------------------------------------------------------------------
    // Remote actor helpers
    // -------------------------------------------------------------------------

    RemoteActor createRemoteActor(String name, String uriString) {
        URI uri = URI.create(uriString)
        RemoteActor ra = new RemoteActor(name, uri)
        register(ra)
        return ra
    }

    RemoteActor createRemoteActor(String name, URI uri) {
        RemoteActor ra = new RemoteActor(name, uri)
        register(ra)
        return ra
    }
}
