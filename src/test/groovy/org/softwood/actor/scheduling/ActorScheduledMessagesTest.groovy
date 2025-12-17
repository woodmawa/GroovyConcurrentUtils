package org.softwood.actor.scheduling

import org.junit.jupiter.api.*
import org.softwood.actor.ActorSystem

import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import static org.awaitility.Awaitility.*
import static org.junit.jupiter.api.Assertions.*

/**
 * Tests for scheduled message functionality.
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class ActorScheduledMessagesTest {

    ActorSystem system

    @BeforeEach
    void setup() {
        system = new ActorSystem('test-scheduling')
    }

    @AfterEach
    void cleanup() {
        system?.shutdown()
    }

    // ============================================================
    // One-Time Scheduled Messages
    // ============================================================

    @Test
    @DisplayName("scheduleOnce should send message after delay")
    void testScheduleOnceBasic() {
        // Given: An actor that schedules a timeout
        def messages = new CopyOnWriteArrayList<String>()
        def latch = new CountDownLatch(1)

        def actor = system.actor {
            name 'timeout-actor'
            onMessage { msg, ctx ->
                messages << msg
                if (msg == 'start') {
                    ctx.scheduleOnce(Duration.ofMillis(100), 'timeout')
                } else if (msg == 'timeout') {
                    latch.countDown()
                }
            }
        }

        // When: Send start message
        actor.tell('start')

        // Then: Timeout arrives after delay
        assertTrue(latch.await(2, TimeUnit.SECONDS))

        await().atMost(500, TimeUnit.MILLISECONDS).untilAsserted {
            assertEquals(2, messages.size())
            assertEquals('start', messages[0])
            assertEquals('timeout', messages[1])
        }
    }

    @Test
    @DisplayName("scheduleOnce can be cancelled before execution")
    void testScheduleOnceCancellation() {
        // Given: An actor that schedules but then cancels
        def messages = new CopyOnWriteArrayList<String>()
        Cancellable task = null

        def actor = system.actor {
            name 'cancel-actor'
            state task: null
            onMessage { msg, ctx ->
                messages << msg
                if (msg == 'schedule') {
                    ctx.state.task = ctx.scheduleOnce(Duration.ofMillis(200), 'timeout')
                } else if (msg == 'cancel') {
                    ctx.state.task?.cancel()
                }
            }
        }

        // When: Schedule then cancel immediately
        actor.tell('schedule')
        Thread.sleep(10)
        actor.tell('cancel')

        // Then: Timeout never arrives
        Thread.sleep(300)

        await().atMost(500, TimeUnit.MILLISECONDS).untilAsserted {
            assertEquals(2, messages.size())
            assertEquals('schedule', messages[0])
            assertEquals('cancel', messages[1])
            assertFalse(messages.contains('timeout'))
        }
    }

    @Test
    @DisplayName("scheduleOnce should not send if actor stops")
    void testScheduleOnceStoppedActor() {
        // Given: An actor that schedules then stops
        def messages = new CopyOnWriteArrayList<String>()

        def actor = system.actor {
            name 'stopping-actor'
            onMessage { msg, ctx ->
                messages << msg
                if (msg == 'schedule-and-stop') {
                    ctx.scheduleOnce(Duration.ofMillis(100), 'timeout')
                    // Stop immediately
                }
            }
        }

        // When: Schedule and stop
        actor.tell('schedule-and-stop')
        Thread.sleep(10)
        actor.stop()

        // Then: Timeout is not delivered to stopped actor
        Thread.sleep(200)
        assertEquals(1, messages.size())
        assertFalse(messages.contains('timeout'))
    }

    // ============================================================
    // Fixed-Rate Periodic Messages
    // ============================================================

    @Test
    @DisplayName("scheduleAtFixedRate should send periodic messages")
    void testFixedRatePeriodic() {
        // Given: An actor with periodic task
        def count = new AtomicInteger(0)
        def latch = new CountDownLatch(3)

        def actor = system.actor {
            name 'periodic-actor'
            onMessage { msg, ctx ->
                if (msg == 'start') {
                    ctx.scheduleAtFixedRate(
                            Duration.ofMillis(50),
                            Duration.ofMillis(50),
                            'tick'
                    )
                } else if (msg == 'tick') {
                    count.incrementAndGet()
                    latch.countDown()
                }
            }
        }

        // When: Start periodic messages
        actor.tell('start')

        // Then: Receive multiple ticks
        assertTrue(latch.await(1, TimeUnit.SECONDS))

        await().atMost(500, TimeUnit.MILLISECONDS).untilAsserted {
            assertTrue(count.get() >= 3)
        }
    }

    @Test
    @DisplayName("scheduleAtFixedRate can be cancelled")
    void testFixedRateCancellation() {
        // Given: An actor with cancellable periodic task
        def count = new AtomicInteger(0)

        def actor = system.actor {
            name 'cancellable-periodic'
            state task: null
            onMessage { msg, ctx ->
                if (msg == 'start') {
                    ctx.state.task = ctx.scheduleAtFixedRate(
                            Duration.ofMillis(30),
                            Duration.ofMillis(30),
                            'tick'
                    )
                } else if (msg == 'tick') {
                    count.incrementAndGet()
                } else if (msg == 'stop') {
                    ctx.state.task?.cancel()
                }
            }
        }

        // When: Start, wait, then stop
        actor.tell('start')
        Thread.sleep(100) // Let a few ticks happen
        actor.tell('stop')

        int countAfterStop = count.get()
        Thread.sleep(100) // Wait to ensure no more ticks

        // Then: Count doesn't increase after cancellation
        assertEquals(countAfterStop, count.get())
    }

    // ============================================================
    // Fixed-Delay Periodic Messages
    // ============================================================

    @Test
    @DisplayName("scheduleWithFixedDelay should wait between executions")
    void testFixedDelayPeriodic() {
        // Given: An actor with fixed-delay task
        def timestamps = new CopyOnWriteArrayList<Long>()
        def latch = new CountDownLatch(3)

        def actor = system.actor {
            name 'delay-actor'
            onMessage { msg, ctx ->
                if (msg == 'start') {
                    ctx.scheduleWithFixedDelay(
                            Duration.ofMillis(50),
                            Duration.ofMillis(50),
                            'tick'
                    )
                } else if (msg == 'tick') {
                    timestamps << System.currentTimeMillis()
                    latch.countDown()
                    // Simulate processing time
                    Thread.sleep(20)
                }
            }
        }

        // When: Start periodic messages
        actor.tell('start')

        // Then: Receive ticks with delays
        assertTrue(latch.await(2, TimeUnit.SECONDS))

        await().atMost(500, TimeUnit.MILLISECONDS).untilAsserted {
            assertTrue(timestamps.size() >= 3)
            // Check that there IS a delay between executions (not just fired immediately)
            // Allow generous slack for timing variations on different systems
            if (timestamps.size() >= 2) {
                long gap = timestamps[1] - timestamps[0]
                assertTrue(gap >= 40, "Gap should be at least 40ms, was ${gap}ms")
            }
        }
    }

    // ============================================================
    // Practical Use Cases
    // ============================================================

    @Test
    @DisplayName("Timeout pattern - request with timeout")
    void testTimeoutPattern() {
        // Given: An actor that implements request timeout
        def result = new CopyOnWriteArrayList<String>()
        def latch = new CountDownLatch(1)

        def actor = system.actor {
            name 'timeout-pattern'
            state pendingRequest: null, timeoutTask: null
            onMessage { msg, ctx ->
                if (msg instanceof Map && msg.type == 'request') {
                    ctx.state.pendingRequest = msg.id
                    ctx.state.timeoutTask = ctx.scheduleOnce(
                            Duration.ofMillis(100),
                            [type: 'timeout', id: msg.id]
                    )
                } else if (msg instanceof Map && msg.type == 'response') {
                    if (ctx.state.pendingRequest == msg.id) {
                        ctx.state.timeoutTask?.cancel()
                        ctx.state.pendingRequest = null
                        result << 'success'
                        latch.countDown()
                    }
                } else if (msg instanceof Map && msg.type == 'timeout') {
                    if (ctx.state.pendingRequest == msg.id) {
                        ctx.state.pendingRequest = null
                        result << 'timeout'
                        latch.countDown()
                    }
                }
            }
        }

        // When: Send request without response (timeout)
        actor.tell([type: 'request', id: 1])

        // Then: Timeout fires
        assertTrue(latch.await(1, TimeUnit.SECONDS))
        assertEquals(['timeout'], result)
    }

    @Test
    @DisplayName("Retry pattern - automatic retry on failure")
    void testRetryPattern() {
        // Given: An actor that retries failed operations
        def attempts = new AtomicInteger(0)
        def latch = new CountDownLatch(1)

        def actor = system.actor {
            name 'retry-actor'
            state retryCount: 0, maxRetries: 3
            onMessage { msg, ctx ->
                if (msg == 'start') {
                    ctx.tellSelf('attempt')
                } else if (msg == 'attempt') {
                    int attempt = attempts.incrementAndGet()
                    ctx.state.retryCount++

                    // Fail first 2 attempts
                    if (attempt < 3) {
                        if (ctx.state.retryCount < ctx.state.maxRetries) {
                            // Schedule retry
                            ctx.scheduleOnce(Duration.ofMillis(50), 'attempt')
                        }
                    } else {
                        // Success on 3rd attempt
                        latch.countDown()
                    }
                }
            }
        }

        // When: Start operation that needs retries
        actor.tell('start')

        // Then: Eventually succeeds
        assertTrue(latch.await(1, TimeUnit.SECONDS))
        assertTrue(attempts.get() >= 3)
    }

    @Test
    @DisplayName("Health check pattern - periodic monitoring")
    void testHealthCheckPattern() {
        // Given: An actor with periodic health checks
        def healthChecks = new AtomicInteger(0)
        def latch = new CountDownLatch(3)

        def actor = system.actor {
            name 'monitored-actor'
            state monitoring: false, healthTask: null
            onMessage { msg, ctx ->
                if (msg == 'start-monitoring') {
                    if (!ctx.state.monitoring) {
                        ctx.state.monitoring = true
                        ctx.state.healthTask = ctx.scheduleAtFixedRate(
                                Duration.ofMillis(50),
                                Duration.ofMillis(50),
                                'health-check'
                        )
                    }
                } else if (msg == 'health-check') {
                    healthChecks.incrementAndGet()
                    latch.countDown()
                } else if (msg == 'stop-monitoring') {
                    ctx.state.healthTask?.cancel()
                    ctx.state.monitoring = false
                }
            }
        }

        // When: Start monitoring
        actor.tell('start-monitoring')

        // Then: Health checks occur periodically
        assertTrue(latch.await(1, TimeUnit.SECONDS))
        assertTrue(healthChecks.get() >= 3)

        // When: Stop monitoring
        actor.tell('stop-monitoring')
        int checksAfterStop = healthChecks.get()
        Thread.sleep(150)

        // Then: No more health checks
        assertEquals(checksAfterStop, healthChecks.get())
    }

    // ============================================================
    // Scheduler Lifecycle
    // ============================================================

    @Test
    @DisplayName("Scheduler should track active tasks")
    void testSchedulerTaskTracking() {
        // Given: Actor with scheduled task
        def actor = system.actor {
            name 'tracking-actor'
            onMessage { msg, ctx ->
                if (msg == 'schedule') {
                    ctx.scheduleOnce(Duration.ofSeconds(10), 'timeout')
                }
            }
        }

        int initialCount = system.scheduler.activeTaskCount

        // When: Schedule a long-delay task
        actor.tell('schedule')
        Thread.sleep(50)

        // Then: Active task count increases
        await().atMost(500, TimeUnit.MILLISECONDS).untilAsserted {
            assertTrue(system.scheduler.activeTaskCount > initialCount)
        }
    }
}