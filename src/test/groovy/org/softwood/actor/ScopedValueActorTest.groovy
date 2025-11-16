package org.softwood.actor


import groovy.transform.CompileDynamic
import org.junit.jupiter.api.Test

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import static org.junit.jupiter.api.Assertions.*
class ScopedValueActorTest {

    @Test
    @CompileDynamic
    void testEchoActor() {
        def actor = new ScopedValueActor("Echo", { msg, ctx ->
            "ECHO:$msg"
        })
        try {
            def result = actor.askSync("hello", Duration.ofSeconds(2))
            assertEquals("ECHO:hello", result)
        } finally {
            actor.stop()
        }
    }

    @Test
    @CompileDynamic
    void testStatefulCounter() {
        def actor = new ScopedValueActor("Counter", [:], { msg, ctx ->
            def n = (ctx.state.count ?: 0) + 1
            ctx.state.count = n
            return n
        })
        try {
            assertEquals(1, actor.askSync("tick"))
            assertEquals(2, actor.askSync("tick"))
            assertEquals(3, actor.askSync("tick"))
        } finally {
            actor.stop()
        }
    }

    @Test
    @CompileDynamic
    void stop_prevents_enqueue_and_is_observable_via_anonymous_subclass() {
        def stopped = new boolean[1]
        def actor = new ScopedValueActor("stoppable", { m, ctx ->
            return "ok"
        }) {
            @Override
            void stop() {
                stopped[0] = true
                super.stop()
            }
        }

        actor.stop()
        assertTrue(stopped[0])
        def ex = assertThrows(IllegalStateException) { actor.tell("late") }
        assertTrue(ex.message.contains("not running"))
    }

    @Test
    @CompileDynamic
    void sendAndContinue_invokes_continuation() {
        def actor = new ScopedValueActor("cont", { m, ctx -> "ack:$m" })
        def latch = new CountDownLatch(1)
        def holder = new Object[1]
        try {
            actor.sendAndContinue("go", { v ->
                holder[0] = v
                latch.countDown()
            }, Duration.ofSeconds(2))

            assertTrue(latch.await(2, TimeUnit.SECONDS))
            assertEquals("ack:go", holder[0])
        } finally {
            actor.stop()
        }
    }

    @Test
    @CompileDynamic
    void context_forward_and_sender_tracking_via_system() {
        def system = new ActorSystem('testSys-basic')
        try {
            def worker = system.actor {
                name 'worker'
                onMessage { m, ctx -> ctx.reply("w:$m") }
            }

            def a = system.actor {
                name 'A'
                onMessage { m, ctx -> ctx.reply('A-ack') }
            }

            def b = system.actor {
                name 'B'
                state lastSender: null
                onMessage { m, ctx ->
                    if (m == 'remember') {
                        ctx.state.lastSender = ctx.sender?.name
                    } else if (m == 'who') {
                        ctx.reply(ctx.state.lastSender)
                    } else {
                        ctx.forwardAndReply('worker', m)
                    }
                }
            }

            def r = b.askSync('job', Duration.ofSeconds(2))
            assertEquals('w:job', r)

            b.tell('remember', a)
            def who = b.askSync('who', Duration.ofSeconds(2))
            assertEquals('A', who)
        } finally {
            system.close()
        }
    }
}