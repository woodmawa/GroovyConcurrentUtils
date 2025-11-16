// ═════════════════════════════════════════════════════════════
// ActorDSL.groovy
// ═════════════════════════════════════════════════════════════
package org.softwood.actor

/**
 * DSL entry point for building actors.
 * Single responsibility: provide static factory method.
 *
 * Usage:
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
 *       state count: 0
 *       loop {
 *           react { msg, ctx ->
 *               ctx.state.count++
 *               ctx.reply(ctx.state.count)
 *           }
 *       }
 *   }
 */
class ActorDSL {

    /**
     * Create an actor using builder DSL.
     */
    static ScopedValueActor actor(
            ActorSystem system,
            @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = ActorBuilder)
                    Closure<?> spec) {

        def builder = new ActorBuilder(system)
        spec.delegate = builder
        spec.resolveStrategy = Closure.DELEGATE_FIRST
        spec.call()

        return builder.build()
    }
}