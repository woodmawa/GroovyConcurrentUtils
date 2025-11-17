package org.softwood.actor

import groovy.transform.CompileDynamic
import org.junit.jupiter.api.Test

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import static org.junit.jupiter.api.Assertions.*

/**
 * A more robust test suite for ScopedValueActor that avoids Groovy MockFor/StubFor
 * (which can struggle with @CompileStatic classes) by using real instances and
 * occasional anonymous subclasses where needed.
 */
class ScopedValueActorAdvancedTest {

    @Test
    @CompileDynamic
    void ask_autoReply_when_handler_returns_value() {
        def actor = new ScopedValueActor("auto", { msg, ctx ->
            return "R:$msg"
        })

        try {
            def reply = actor.askSync("x", Duration.ofSeconds(2))
            assertEquals("R:x", reply)
        } finally {
            actor.stop()
        }
    }

    @Test
    @CompileDynamic
    void tell_updates_state_without_reply() {
        def actor = new ScopedValueActor("stateful", [:], { msg, ctx ->
            ctx.state.count = (ctx.state.count ?: 0) + 1
            // no explicit reply for tell
        })

        try {
            actor.tell("a")
            actor.tell("b")
            // probe state by ask
            def v = actor.askSync("get", Duration.ofSeconds(2))
            assertEquals(3, v) // handler increments for this ask too -> total 3
        } finally {
            actor.stop()
        }
    }

    @Test
    @CompileDynamic
    void sendAndContinue_invokes_continuation_with_reply() {
        def actor = new ScopedValueActor("cont", { m, ctx ->
            return "ok:$m"
        })

        def latch = new CountDownLatch(1)
        def holder = new Object[1]
        try {
            actor.sendAndContinue("go", { v ->
                holder[0] = v
                latch.countDown()
            }, Duration.ofSeconds(2))

            assertTrue(latch.await(2, TimeUnit.SECONDS))
            assertEquals("ok:go", holder[0])
        } finally {
            actor.stop()
        }
    }

    @Test
    @CompileDynamic
    void askSync_times_out_when_handler_too_slow() {
        def actor = new ScopedValueActor("slow", { m, ctx ->
            Thread.sleep(600)
            return "late"
        })

        try {
            assertThrows(java.util.concurrent.TimeoutException) {
                actor.askSync("now", Duration.ofMillis(200))
            }
        } finally {
            actor.stop()
        }
    }

    @Test
    @CompileDynamic
    void askSync_propagates_exception_from_handler() {
        def actor = new ScopedValueActor("boom", { m, ctx ->
            throw new IllegalStateException("explode:$m")
        })

        try {
            def ex = assertThrows(Exception) {
                actor.askSync("kaboom", Duration.ofSeconds(1))
            }
            // Either ExecutionException or directly the cause can bubble up depending on runtime
            def msg = ex.message ?: ex.cause?.message ?: ""
            assertTrue(msg.contains("explode:kaboom") || (ex.cause?.message?.contains("explode:kaboom") ?: false))
        } finally {
            actor.stop()
        }
    }

    @Test
    @CompileDynamic
    void stop_prevents_additional_messages_from_being_enqueued() {
        // Anonymous subclass to observe stop call path
        def stopped = new boolean[1]
        def actor = new ScopedValueActor("stoppable", { m, ctx ->
            return "hi"
        }) {
            @Override
            void stop() {
                stopped[0] = true
                super.stop()
            }
        }

        actor.stop()
        assertTrue(stopped[0])

        def ex = assertThrows(IllegalStateException) {
            actor.tell("after")
        }
        assertTrue(ex.message.contains("not running"))
    }

    @Test
    @CompileDynamic
    void context_forward_between_actors_via_system() {
        def system = new ActorSystem('testSys1')
        try {
            def worker = system.actor {
                name 'worker'
                onMessage { m, ctx ->
                    ctx.reply("w:$m")
                }
            }

            def proxy = system.actor {
                name 'proxy'
                onMessage { m, ctx ->
                    ctx.forwardAndReply('worker', m)
                }
            }

            def r = proxy.askSync('job', Duration.ofSeconds(2))
            assertEquals('w:job', r)
        } finally {
            system.close()
        }
    }

    @Test
    @CompileDynamic
    void sender_is_available_in_context_when_provided_via_tell() {
        def system = new ActorSystem('testSys2')
        try {
            def b = system.actor {
                name 'B'
                state lastSender: null
                onMessage { m, ctx ->
                    if (m == 'remember') {
                        ctx.state.lastSender = ctx.sender?.name
                    } else if (m == 'who') {
                        ctx.reply(ctx.state.lastSender)
                    }
                }
            }

            def a = system.actor {
                name 'A'
                onMessage { m, ctx ->
                    ctx.reply('A-ack')
                }
            }

            // fire-and-forget with sender set
            b.tell('remember', a)
            // ask for stored sender
            def who = b.askSync('who', Duration.ofSeconds(2))
            assertEquals('A', who)
        } finally {
            system.close()
        }
    }
}
