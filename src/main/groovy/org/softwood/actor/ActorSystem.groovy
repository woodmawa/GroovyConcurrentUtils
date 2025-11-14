package org.softwood.actor

import groovy.transform.CompileDynamic

/**
 * Minimal dynamic ActorSystem: registry + lifecycle.
 */
@CompileDynamic
class ActorSystem implements Closeable {

    final String name
    private final Map<String, ScopedValueActor> actors = [:]

    ActorSystem(String name) {
        this.name = name
        println "ActorSystem '" + name + "' created"
    }

    ScopedValueActor createActor(String actorName, Closure handler) {
        def actor = new ScopedValueActor(actorName, handler)
        actors[actorName] = actor
        return actor
    }

    void register(ScopedValueActor actor) {
        if (actor) {
            actors[actor.name] = actor
        }
    }

    int getActorCount() { actors.size() }

    Set<String> getActorNames() { actors.keySet() }

    ScopedValueActor getActor(String name) { actors[name] }

    void shutdown() {
        println "Shutting down '" + name + "' with " + actors.size() + " actors"
        actors.values().each { it.stop() }
        actors.clear()
        println "ActorSystem '" + name + "' shutdown complete"
    }

    @Override
    void close() {
        shutdown()
    }

    /**
     * Convenience DSL entry so you can do:
     *   system.actor {
     *       name "A1"
     *       onMessage { msg, ctx -> ... }
     *   }
     */
    ScopedValueActor actor(@DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = ActorDSL.ActorBuilder) Closure<?> spec) {
        return ActorDSL.actor(this, spec)
    }
}
