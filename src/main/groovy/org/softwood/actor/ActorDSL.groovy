package org.softwood.actor

import groovy.transform.CompileDynamic

/**
 * Dynamic DSL for building actors.
 *
 * Usage:
 *
 *   import static org.softwood.actor.ActorDSL.actor
 *
 *   def a = actor(system) {
 *       name "Printer"
 *       onMessage { msg, ctx ->
 *           println "[$ctx.actorName] $msg"
 *       }
 *   }
 *
 *   def b = actor(system) {
 *       name "Counter"
 *       loop {
 *           react { msg, ctx ->
 *               def n = (ctx.state.count ?: 0) + 1
 *               ctx.state.count = n
 *               ctx.reply(n)
 *           }
 *       }
 *   }
 */
class ActorDSL {

    /**
     * Create an actor within the given ActorSystem using a GPars-style builder DSL.
     */
    static ScopedValueActor actor(ActorSystem system,
                                  @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = ActorBuilder)
                                          Closure<?> spec) {
        def builder = new ActorBuilder(system)
        spec.delegate = builder
        spec.resolveStrategy = Closure.DELEGATE_FIRST
        spec.call()
        return builder.build()
    }

    // ─────────────────────────────────────────────────────────────
    // Builder
    // ─────────────────────────────────────────────────────────────

    @CompileDynamic
    static class ActorBuilder {
        final ActorSystem system
        String name
        Closure handler   // (msg, ctx) -> result

        ActorBuilder(ActorSystem system) {
            this.system = system
        }

        /**
         * Set the actor name.
         */
        void name(String n) {
            this.name = n
        }

        /**
         * Core message handler.
         * Accepts either:
         *   - { msg -> ... }
         *   - { msg, ctx -> ... }
         */
        void onMessage(Closure c) {
            if (c.maximumNumberOfParameters == 1) {
                this.handler = { msg, ctx -> c.call(msg) }
            } else {
                this.handler = { msg, ctx -> c.call(msg, ctx) }
            }
        }

        /**
         * GPars-style "react" sugar.
         *
         * In classic GPars, react is continuation-based; here, react
         * is simply an alias for onMessage, for a per-message handler.
         */
        void react(Closure c) {
            onMessage(c)
        }

        /**
         * GPars-style "loop { react { ... } }" sugar.
         * In our implementation, this is just syntax sugar: the loop
         * body is executed once at construction time and can call react().
         */
        void loop(Closure loopBody) {
            loopBody.delegate = this
            loopBody.resolveStrategy = Closure.DELEGATE_FIRST
            loopBody.call()
        }

        /**
         * Build and register the actor in the system.
         */
        ScopedValueActor build() {
            assert system != null: "ActorSystem must not be null"
            assert name != null   : "Actor name must be set"
            assert handler != null: "onMessage/react handler must be set"

            return system.createActor(name, handler)
        }
    }
}
