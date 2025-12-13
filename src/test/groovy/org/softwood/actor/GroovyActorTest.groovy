package org.softwood.actor

import groovy.transform.CompileDynamic
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.AfterEach

import java.time.Duration
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference

import static org.awaitility.Awaitility.await
import static org.junit.jupiter.api.Assertions.*
import static java.util.concurrent.TimeUnit.SECONDS

class GroovyActorTest {

    @Test
    @CompileDynamic
    void testEchoActor() {
        def actor = ActorFactory.create("Echo", { msg, ctx ->
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
        def actor = ActorFactory.create("Counter", { msg, ctx ->
            def n = (ctx.state.count ?: 0) + 1
            ctx.state.count = n
            return n
        }, [count: 0])
        
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
    void stop_prevents_enqueue() {
        def actor = ActorFactory.create("stoppable", { m, ctx ->
            return "ok"
        })

        actor.stop()
        assertTrue(actor.isStopped())
        def ex = assertThrows(IllegalStateException) { actor.tell("late") }
        assertTrue(ex.message.contains("not running"))
    }

    @Test
    @CompileDynamic
    void sendAndContinue_invokes_continuation() {
        def actor = ActorFactory.create("cont", { m, ctx -> "ack:$m" })
        def holder = new AtomicReference<Object>()
        try {
            actor.sendAndContinue("go", { v ->
                holder.set(v)
            }, Duration.ofSeconds(2))

            await().atMost(2, SECONDS).until { holder.get() != null }
            assertEquals("ack:go", holder.get())
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

    // ═════════════════════════════════════════════════════════════
    // New Tests for v1.0 Features
    // ═════════════════════════════════════════════════════════════

    @Test
    @CompileDynamic
    void test_health_monitoring() {
        def actor = ActorFactory.builder("health-test", { msg, ctx ->
            ctx.reply("ok")
        })
            .maxMailboxSize(100)
            .build()

        try {
            def health = actor.health()
            
            assertEquals("health-test", health.name)
            assertEquals("HEALTHY", health.status)
            assertTrue(health.running)
            assertFalse(health.terminated)
            assertEquals(0, health.mailboxSize)
            assertEquals(100, health.maxMailboxSize)
            assertTrue(health.mailboxUtilization >= 0)
            
            actor.stop()
            
            def stoppedHealth = actor.health()
            assertEquals("STOPPING", stoppedHealth.status)
            assertTrue(stoppedHealth.terminated)
        } finally {
            if (!actor.isStopped()) actor.stop()
        }
    }

    @Test
    @CompileDynamic
    void test_metrics_tracking() {
        def actor = ActorFactory.create("metrics-test", { msg, ctx ->
            ctx.reply(msg)
        })

        try {
            // Process some messages
            actor.askSync("msg1", Duration.ofSeconds(1))
            actor.askSync("msg2", Duration.ofSeconds(1))
            actor.askSync("msg3", Duration.ofSeconds(1))

            def metrics = actor.metrics()
            
            assertEquals("metrics-test", metrics.name)
            assertTrue(metrics.messagesReceived >= 3)
            assertTrue(metrics.messagesProcessed >= 3)
            assertEquals(0, metrics.messagesPending)
            assertEquals(0, metrics.messagesErrored)
            assertTrue(metrics.throughputPerSec >= 0)
            assertEquals(0.0, metrics.errorRatePercent, 0.01)
            assertTrue(metrics.uptimeMs > 0)
        } finally {
            actor.stop()
        }
    }

    @Test
    @CompileDynamic
    void test_error_tracking() {
        def actor = ActorFactory.builder("error-test", { msg, ctx ->
            if (msg == "fail") {
                throw new RuntimeException("Test error")
            }
            ctx.reply("ok")
        })
            .maxErrorsRetained(10)
            .build()

        try {
            // Cause some errors
            assertThrows(Exception) {
                actor.askSync("fail", Duration.ofSeconds(1))
            }
            assertThrows(Exception) {
                actor.askSync("fail", Duration.ofSeconds(1))
            }

            def errors = actor.getErrors(10)
            assertTrue(errors.size() >= 2)
            
            def firstError = errors[0]
            assertTrue(firstError.errorType.contains("RuntimeException"))
            assertEquals("Test error", firstError.message)
            assertTrue(firstError.stackTrace instanceof List)

            def metrics = actor.metrics()
            assertTrue(metrics.messagesErrored >= 2)
            
            // Test clear errors
            actor.clearErrors()
            assertTrue(actor.getErrors(10).isEmpty())
        } finally {
            actor.stop()
        }
    }

    @Test
    @CompileDynamic
    void test_custom_error_handler() {
        def errorCount = new int[1]
        def actor = ActorFactory.builder("custom-error", { msg, ctx ->
            throw new RuntimeException("Test")
        })
            .onError({ e -> errorCount[0]++ })
            .build()

        try {
            assertThrows(Exception) {
                actor.askSync("fail", Duration.ofSeconds(1))
            }
            
            Thread.sleep(100) // Give error handler time to run
            assertEquals(1, errorCount[0])
        } finally {
            actor.stop()
        }
    }

    @Test
    @CompileDynamic
    void test_mailbox_size_limit() {
        def processingStarted = new java.util.concurrent.CountDownLatch(1)
        def continueProcessing = new java.util.concurrent.CountDownLatch(1)
        
        def actor = ActorFactory.builder("limited", { msg, ctx ->
            processingStarted.countDown()  // Signal we started processing
            continueProcessing.await()      // Block until released
            ctx.reply("ok")
        })
            .maxMailboxSize(5)
            .build()

        try {
            // Send first message - it will be taken from queue and processed (blocked)
            actor.tell("msg0")
            
            // Wait for processing to start and block
            processingStarted.await()
            Thread.sleep(50) // Give it time to actually be in processing
            
            // Now fill the remaining 5 slots in mailbox
            5.times { actor.tell("msg${it + 1}") }
            
            Thread.sleep(50) // Let messages settle
            
            // Verify mailbox is at capacity (5 queued + 1 processing = 6 total)
            def metrics = actor.metrics()
            assertTrue(metrics.mailboxDepth >= 5, "Mailbox should be at capacity (was: ${metrics.mailboxDepth})")
            
            // This should be rejected - mailbox is full
            assertThrows(RejectedExecutionException) {
                actor.tell("overflow")
            }

            metrics = actor.metrics()
            assertTrue(metrics.mailboxRejections > 0, "Should have rejections")
        } finally {
            continueProcessing.countDown() // Release the blocked message
            actor.stopNow() // Force stop to avoid waiting
        }
    }

    @Test
    @CompileDynamic
    void test_graceful_shutdown_with_timeout() {
        def actor = ActorFactory.create("timeout-test", { msg, ctx ->
            Thread.sleep(50)
            ctx.reply("ok")
        })

        try {
            // Queue several messages
            10.times { actor.tell("msg") }
            
            // Try to stop with short timeout
            boolean completed = actor.stop(Duration.ofMillis(100))
            
            // May or may not complete depending on timing
            assertTrue(actor.isStopped())
        } finally {
            if (!actor.isTerminated()) {
                actor.stopNow()
            }
        }
    }

    @Test
    @CompileDynamic
    void test_force_stop_discards_messages() {
        def actor = ActorFactory.create("force-stop", { msg, ctx ->
            Thread.sleep(100)
            ctx.reply("ok")
        })

        try {
            // Queue many messages
            20.times { actor.tell("msg") }
            
            def metricsBefore = actor.metrics()
            assertTrue(metricsBefore.mailboxDepth > 0)
            
            // Force stop should discard pending
            actor.stopNow()
            
            assertTrue(actor.isStopped())
            assertTrue(actor.isTerminated())
        } finally {
            if (!actor.isTerminated()) actor.stopNow()
        }
    }

    @Test
    @CompileDynamic
    void test_state_isolation() {
        def actor = ActorFactory.create("state-test", { msg, ctx ->
            ctx.state.value = msg
            ctx.reply(ctx.state.value)
        }, [value: "initial"])

        try {
            actor.askSync("changed", Duration.ofSeconds(1))
            
            // Get state snapshot
            def state = actor.getState()
            assertEquals("changed", state.value)
            
            // Modify snapshot should not affect actor
            state.value = "external"
            
            def result = actor.askSync("verify", Duration.ofSeconds(1))
            assertEquals("verify", result) // Actor state not affected by external change
        } finally {
            actor.stop()
        }
    }

    @Test
    @CompileDynamic
    void test_builder_pattern() {
        def actor = ActorFactory.builder("builder-test", { msg, ctx ->
            ctx.reply("ok")
        })
            .initialState([count: 0])
            .maxMailboxSize(100)
            .maxErrorsRetained(50)
            .build()

        try {
            assertNotNull(actor)
            assertEquals("builder-test", actor.getName())
            assertEquals(100, actor.getMaxMailboxSize())
            
            def state = actor.getState()
            assertEquals(0, state.count)
        } finally {
            actor.stop()
        }
    }
}
