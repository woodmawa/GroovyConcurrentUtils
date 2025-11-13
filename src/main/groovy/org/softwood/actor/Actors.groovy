package org.softwood.actor

import groovy.transform.CompileStatic
import groovy.transform.stc.ClosureParams
import groovy.transform.stc.FromString

/**
 * GPars-like DSL for building virtual-thread actors.
 */
@CompileStatic
class Actors<T> {

    // -------------------------------------------------------------------------
    // Entry static DSL function
    // -------------------------------------------------------------------------

    static <X> ScopedValueActor<X> actor(
            @DelegatesTo(strategy = Closure.DELEGATE_FIRST,
                    value = Actors.ActorBuilder) Closure<?> spec) {

        def dsl = new Actors<X>()
        return dsl.buildActor(spec)
    }

    // -------------------------------------------------------------------------
    // Instance DSL root
    // -------------------------------------------------------------------------

    ScopedValueActor<T> buildActor(Closure<?> spec) {
        def builder = new ActorBuilder()
        spec.delegate = builder
        spec.resolveStrategy = Closure.DELEGATE_FIRST
        spec.call()
        return builder.build()
    }

    // -------------------------------------------------------------------------
    // Handler wrapper (instance method — IMPORTANT)
    // -------------------------------------------------------------------------
    ScopedValueActor.MessageHandler<T> wrapHandler(
            @ClosureParams(
                    value = FromString,
                    options = "T, org.softwood.actor.ScopedValueActor.ActorContext")
                    Closure<?> c) {

        return new ScopedValueActor.MessageHandler<T>() {
            @Override
            Object handle(T msg, ScopedValueActor.ActorContext ctx) {
                return c.call(msg, ctx)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Actor builder (non-static inner class — inherits T)
    // -------------------------------------------------------------------------

    class ActorBuilder {
        String name = "Anonymous"
        int mailboxSize = 1000
        ScopedValueActor.MessageHandler<T> handler

        void name(String n) { this.name = n }
        void mailbox(int n) { this.mailboxSize = n }

        void onMessage(
                @ClosureParams(
                        value = FromString,
                        options = "T, org.softwood.actor.ScopedValueActor.ActorContext")
                        Closure<?> c) {
            this.handler = wrapHandler(c)
        }

        void react(
                @ClosureParams(
                        value = FromString,
                        options = "T, org.softwood.actor.ScopedValueActor.ActorContext")
                        Closure<?> c) {
            this.handler = wrapHandler(c)
        }

        void loop(
                @DelegatesTo(strategy = Closure.DELEGATE_FIRST,
                        value = Actors.ActorBuilder.LoopBuilder)
                        Closure<?> c) {

            def lb = new LoopBuilder()
            c.delegate = lb
            c.resolveStrategy = Closure.DELEGATE_FIRST
            c.call()

            if (lb.handler == null)
                throw new IllegalStateException("loop { } requires react { } inside")

            this.handler = lb.handler
        }

        ScopedValueActor<T> build() {
            if (handler == null)
                throw new IllegalStateException("Actor requires onMessage{ } or loop{ }")

            return new ScopedValueActor<T>(name, handler, mailboxSize)
        }

        // -------------------------------------------------------------
        // LoopBuilder — also non-static, inherits T
        // -------------------------------------------------------------
        class LoopBuilder {
            ScopedValueActor.MessageHandler<T> handler

            void react(
                    @ClosureParams(
                            value = FromString,
                            options = "T, org.softwood.actor.ScopedValueActor.ActorContext")
                            Closure<?> c) {
                this.handler = wrapHandler(c)
            }
        }
    }
}
