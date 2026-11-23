package org.softwood.actor

import groovy.transform.CompileDynamic
import org.junit.jupiter.api.Test

import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger

import static org.awaitility.Awaitility.await
import static org.junit.jupiter.api.Assertions.*
import static java.util.concurrent.TimeUnit.SECONDS

/**
 * Advanced test suite for GroovyActor covering edge cases,
 * error handling, and complex scenarios.
 */
class GroovyActorAdvancedTest {

    @Test
    @CompileDynamic
    void ask_autoReply_when_handler_returns_value() {
        def actor = ActorFactory.create("auto", { msg, ctx ->
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
        def actor = ActorFactory.create("stateful", { msg, ctx ->
            ctx.state.count = (ctx.state.count ?: 0) + 1
            // no explicit reply for tell
        }, [:])

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
        def actor = ActorFactory.create("cont", { m, ctx ->
            return "ok:$m"
        })

        def holder = new AtomicReference<Object>()
        try {
            actor.sendAndContinue("go", { v ->
                holder.set(v)
            }, Duration.ofSeconds(2))

            await().atMost(2, SECONDS).until { holder.get() != null }
            assertEquals("ok:go", holder.get())
        } finally {
            actor.stop()
        }
    }

    @Test
    @CompileDynamic
    void askSync_times_out_when_handler_too_slow() {
        def actor = ActorFactory.create("slow", { m, ctx ->
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
        def actor = ActorFactory.create("boom", { m, ctx ->
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
        def actor = ActorFactory.create("stoppable", { m, ctx ->
            return "hi"
        })

        actor.stop()
        assertTrue(actor.isStopped())

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

    // ═════════════════════════════════════════════════════════════
    // Additional Advanced Tests for v1.0 Features
    // ═════════════════════════════════════════════════════════════

    @Test
    @CompileDynamic
    void test_concurrent_message_processing() {
        def actor = ActorFactory.create("concurrent", { msg, ctx ->
            ctx.state.count = (ctx.state.count ?: 0) + 1
            ctx.reply(ctx.state.count)
        }, [count: 0])

        try {
            def results = Collections.synchronizedList([])
            def counter = new AtomicInteger(0)

            // Send 10 concurrent asks
            10.times { i ->
                Thread.start {
                    try {
                        def result = actor.askSync("msg$i", Duration.ofSeconds(2))
                        results << result
                    } finally {
                        counter.incrementAndGet()
                    }
                }
            }

            await().atMost(5, SECONDS).until { counter.get() == 10 }
            assertEquals(10, results.size())
            
            // All messages should have been processed sequentially
            // so count should be 10
            assertTrue(results.contains(10))
        } finally {
            actor.stop()
        }
    }

    @Test
    @CompileDynamic
    void test_ask_with_very_short_timeout() {
        def actor = ActorFactory.create("timeout", { msg, ctx ->
            Thread.sleep(100) // Ensure timeout
            ctx.reply("late")
        })

        try {
            assertThrows(java.util.concurrent.TimeoutException) {
                actor.askSync("msg", Duration.ofMillis(10))
            }
        } finally {
            actor.stop()
        }
    }

    @Test
    @CompileDynamic
    void test_multiple_errors_tracked() {
        def actor = ActorFactory.builder("multi-error", { msg, ctx ->
            throw new RuntimeException("Error: $msg")
        })
            .maxErrorsRetained(5)
            .build()

        try {
            // Cause 5 errors
            5.times { i ->
                assertThrows(Exception) {
                    actor.askSync("fail$i", Duration.ofSeconds(1))
                }
            }

            def errors = actor.getErrors(10)
            assertEquals(5, errors.size())

            // Cause more errors than retention limit
            3.times { i ->
                assertThrows(Exception) {
                    actor.askSync("overflow$i", Duration.ofSeconds(1))
                }
            }

            // Should still only have 5 (retention limit)
            errors = actor.getErrors(10)
            assertEquals(5, errors.size())
        } finally {
            actor.stop()
        }
    }

    @Test
    @CompileDynamic
    void test_health_degrades_under_load() {
        def actor = ActorFactory.builder("degraded", { msg, ctx ->
            Thread.sleep(50) // Slow processing
            ctx.reply("ok")
        })
            .maxMailboxSize(100)
            .build()

        try {
            // Fill mailbox to 90%
            90.times { actor.tell("msg") }

            Thread.sleep(100) // Let messages queue up

            def health = actor.health()
            // Should be degraded when > 80% full
            if (health.mailboxSize > 80) {
                assertEquals("DEGRADED", health.status)
            }
        } finally {
            actor.stopNow()
        }
    }

    @Test
    @CompileDynamic
    void test_metrics_show_processing_flag() {
        def processingStarted = new AtomicReference<Boolean>(false)
        def continueProcessing = new AtomicReference<Boolean>(false)
        
        def actor = ActorFactory.create("processing", { msg, ctx ->
            processingStarted.set(true)
            await().atMost(3, SECONDS).until { continueProcessing.get() }
            ctx.reply("done")
        })

        try {
            // Start slow message in background
            Thread.start {
                actor.askSync("slow", Duration.ofSeconds(3))
            }

            // Wait for processing to start
            await().atMost(2, SECONDS).until { processingStarted.get() }

            def metrics = actor.metrics()
            assertTrue(metrics.processing, "Actor should be processing")

            continueProcessing.set(true) // Let it finish

            await().atMost(2, SECONDS).until { !actor.metrics().processing }

            metrics = actor.metrics()
            assertFalse(metrics.processing, "Actor should not be processing")
        } finally {
            continueProcessing.set(true) // Ensure we don't block
            actor.stop()
        }
    }

    @Test
    @CompileDynamic
    void test_throughput_calculation() {
        def actor = ActorFactory.create("throughput", { msg, ctx ->
            ctx.reply("ok")
        })

        try {
            // Process messages quickly
            50.times {
                actor.askSync("msg", Duration.ofSeconds(1))
            }

            def metrics = actor.metrics()
            assertTrue(metrics.throughputPerSec > 0, "Should have positive throughput")
            assertTrue(metrics.messagesProcessed >= 50)
        } finally {
            actor.stop()
        }
    }

    @Test
    @CompileDynamic
    void test_error_rate_calculation() {
        def actor = ActorFactory.create("error-rate", { msg, ctx ->
            if (msg.toString().startsWith("fail")) {
                throw new RuntimeException("Fail")
            }
            ctx.reply("ok")
        })

        try {
            // 7 successes
            7.times { actor.askSync("ok$it", Duration.ofSeconds(1)) }
            
            // 3 failures
            3.times {
                assertThrows(Exception) {
                    actor.askSync("fail$it", Duration.ofSeconds(1))
                }
            }

            def metrics = actor.metrics()
            assertTrue(metrics.messagesProcessed >= 10)
            assertEquals(3, metrics.messagesErrored)
            assertTrue(metrics.errorRatePercent > 25.0) // ~30% error rate
            assertTrue(metrics.errorRatePercent < 35.0)
        } finally {
            actor.stop()
        }
    }

    @Test
    @CompileDynamic
    void test_uptime_tracking() {
        def actor = ActorFactory.create("uptime", { msg, ctx ->
            ctx.reply("ok")
        })

        try {
            Thread.sleep(100)

            def metrics = actor.metrics()
            assertTrue(metrics.uptimeMs >= 100)
            assertTrue(metrics.createdAt > 0)
            assertTrue(metrics.timestamp > metrics.createdAt)
        } finally {
            actor.stop()
        }
    }
}
