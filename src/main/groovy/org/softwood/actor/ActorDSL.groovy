package org.softwood.actor

import groovy.transform.CompileDynamic

/**
 * Tiny dynamic DSL for building actors:
 *
 *   import static org.softwood.actor.ActorDSL.actor
 *
 *   def a = actor(system) {
 *       name "Printer"
 *       onMessage { msg, ctx -> ctx.reply("OK:$msg") }
 *   }
 */
@CompileDynamic
class ActorDSL {

    static ScopedValueActor actor(ActorSystem system,
                                  @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = ActorBuilder) Closure<?> spec) {
        def builder = new ActorBuilder(system)
        spec.delegate = builder
        spec.resolveStrategy = Closure.DELEGATE_FIRST
        spec.call()
        return builder.build()
    }

    static class ActorBuilder {
        final ActorSystem system
        String name
        Closure handler

        ActorBuilder(ActorSystem system) {
            this.system = system
        }

        void name(String n) { this.name = n }

        void onMessage(Closure c) {
            // Always adapt to a 2-arg (msg, ctx) handler
            if (c.maximumNumberOfParameters == 1) {
                this.handler = { msg, ctx -> c.call(msg) }
            } else {
                this.handler = { msg, ctx -> c.call(msg, ctx) }
            }
        }

        ScopedValueActor build() {
            assert name != null : "Actor name must be set"
            assert handler != null : "onMessage handler must be set"
            return system.createActor(name, handler)
        }
    }
}
