package org.softwood.dag.task

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import static org.junit.jupiter.api.Assertions.*

import org.softwood.dag.TaskGraph
import java.time.Duration

/**
 * Tests for Retry DSL functionality
 */
class RetryDslTest {

    TaskContext ctx
    
    @BeforeEach
    void setup() {
        ctx = new TaskContext()
    }

    // =========================================================================
    // RetryDsl Direct Tests
    // =========================================================================

    @Test
    void testRetryDslMaxAttempts() {
        def policy = new RetryPolicy()
        def dsl = new RetryDsl(policy)
        
        dsl.maxAttempts(5)
        
        assertEquals(5, policy.maxAttempts)
    }

    @Test
    void testRetryDslMaxAttemptsValidation() {
        def policy = new RetryPolicy()
        def dsl = new RetryDsl(policy)
        
        assertThrows(IllegalArgumentException) {
            dsl.maxAttempts(-1)
        }
    }

    @Test
    void testRetryDslInitialDelay() {
        def policy = new RetryPolicy()
        def dsl = new RetryDsl(policy)
        
        dsl.initialDelay(Duration.ofMillis(500))
        
        assertEquals(Duration.ofMillis(500), policy.initialDelay)
    }

    @Test
    void testRetryDslInitialDelayValidation() {
        def policy = new RetryPolicy()
        def dsl = new RetryDsl(policy)
        
        assertThrows(IllegalArgumentException) {
            dsl.initialDelay(Duration.ofMillis(-100))
        }
    }

    @Test
    void testRetryDslBackoffMultiplier() {
        def policy = new RetryPolicy()
        def dsl = new RetryDsl(policy)
        
        dsl.backoffMultiplier(2.5)
        
        assertEquals(2.5, policy.backoffMultiplier, 0.001)
    }

    @Test
    void testRetryDslBackoffMultiplierValidation() {
        def policy = new RetryPolicy()
        def dsl = new RetryDsl(policy)
        
        assertThrows(IllegalArgumentException) {
            dsl.backoffMultiplier(0.5)
        }
    }

    @Test
    void testRetryDslCircuitBreaker() {
        def policy = new RetryPolicy()
        def dsl = new RetryDsl(policy)
        
        dsl.circuitBreaker(Duration.ofMinutes(10))
        
        assertEquals(Duration.ofMinutes(10), policy.circuitOpenDuration)
    }

    @Test
    void testRetryDslPresetAggressive() {
        def policy = new RetryPolicy()
        def dsl = new RetryDsl(policy)
        
        dsl.preset("aggressive")
        
        assertEquals(5, policy.maxAttempts)
        assertEquals(Duration.ofMillis(100), policy.initialDelay)
        assertEquals(2.0, policy.backoffMultiplier, 0.001)
        assertEquals(Duration.ofSeconds(30), policy.circuitOpenDuration)
    }

    @Test
    void testRetryDslPresetModerate() {
        def policy = new RetryPolicy()
        def dsl = new RetryDsl(policy)
        
        dsl.preset("moderate")
        
        assertEquals(3, policy.maxAttempts)
        assertEquals(Duration.ofMillis(500), policy.initialDelay)
        assertEquals(1.5, policy.backoffMultiplier, 0.001)
        assertEquals(Duration.ofMinutes(1), policy.circuitOpenDuration)
    }

    @Test
    void testRetryDslPresetConservative() {
        def policy = new RetryPolicy()
        def dsl = new RetryDsl(policy)
        
        dsl.preset("conservative")
        
        assertEquals(2, policy.maxAttempts)
        assertEquals(Duration.ofSeconds(1), policy.initialDelay)
        assertEquals(1.0, policy.backoffMultiplier, 0.001)
        assertEquals(Duration.ofMinutes(5), policy.circuitOpenDuration)
    }

    @Test
    void testRetryDslPresetNone() {
        def policy = new RetryPolicy()
        def dsl = new RetryDsl(policy)
        
        dsl.preset("none")
        
        assertEquals(0, policy.maxAttempts)
        assertEquals(Duration.ZERO, policy.initialDelay)
    }

    @Test
    void testRetryDslPresetInvalid() {
        def policy = new RetryPolicy()
        def dsl = new RetryDsl(policy)
        
        assertThrows(IllegalArgumentException) {
            dsl.preset("invalid")
        }
    }

    @Test
    void testRetryDslPresetWithOverrides() {
        def policy = new RetryPolicy()
        def dsl = new RetryDsl(policy)
        
        // Start with preset, then override specific values
        dsl.preset("aggressive")
        dsl.circuitBreaker(Duration.ofMinutes(15))
        
        assertEquals(5, policy.maxAttempts)  // From preset
        assertEquals(Duration.ofMillis(100), policy.initialDelay)  // From preset
        assertEquals(Duration.ofMinutes(15), policy.circuitOpenDuration)  // Overridden
    }

    @Test
    void testRetryDslConvenienceMethods() {
        def policy = new RetryPolicy()
        def dsl = new RetryDsl(policy)
        
        dsl.attempts(3, Duration.ofMillis(200))
        assertEquals(3, policy.maxAttempts)
        assertEquals(Duration.ofMillis(200), policy.initialDelay)
        
        dsl.exponentialBackoff(3.0)
        assertEquals(3.0, policy.backoffMultiplier, 0.001)
        
        dsl.disabled()
        assertEquals(0, policy.maxAttempts)
    }

    // =========================================================================
    // TaskBase Integration Tests
    // =========================================================================

    @Test
    void testTaskBaseRetryBlock() {
        def task = TaskFactory.createServiceTask("test", "Test Task", ctx)
        
        task.retry {
            maxAttempts 5
            initialDelay Duration.ofMillis(100)
            backoffMultiplier 2.0
            circuitBreaker Duration.ofMinutes(5)
        }
        
        assertEquals(5, task.maxRetries)
        assertEquals(Duration.ofMillis(100), task.retryPolicy.initialDelay)
        assertEquals(2.0, task.retryPolicy.backoffMultiplier, 0.001)
        assertEquals(Duration.ofMinutes(5), task.retryPolicy.circuitOpenDuration)
    }

    @Test
    void testTaskBaseRetryPreset() {
        def task = TaskFactory.createServiceTask("test", "Test Task", ctx)
        
        task.retry("aggressive")
        
        assertEquals(5, task.maxRetries)
        assertEquals(Duration.ofMillis(100), task.retryPolicy.initialDelay)
        assertEquals(2.0, task.retryPolicy.backoffMultiplier, 0.001)
    }

    // =========================================================================
    // TaskGraph DSL Integration Tests
    // =========================================================================

    @Test
    void testRetryDslInTaskGraph() {
        def graph = TaskGraph.build {
            serviceTask("flaky") {
                retry {
                    maxAttempts 3
                    initialDelay Duration.ofMillis(50)
                    backoffMultiplier 1.5
                }
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync { "success" }
                }
            }
        }
        
        def task = graph.tasks["flaky"]
        assertEquals(3, task.maxRetries)
        assertEquals(Duration.ofMillis(50), task.retryPolicy.initialDelay)
        assertEquals(1.5, task.retryPolicy.backoffMultiplier, 0.001)
    }

    @Test
    void testRetryPresetInTaskGraph() {
        def graph = TaskGraph.build {
            serviceTask("api-call") {
                retry "moderate"
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync { "done" }
                }
            }
        }
        
        def task = graph.tasks["api-call"]
        assertEquals(3, task.maxRetries)
        assertEquals(Duration.ofMillis(500), task.retryPolicy.initialDelay)
    }

    // =========================================================================
    // Functional Retry Tests
    // =========================================================================

    @Test
    void testActualRetryBehavior() {
        def attempts = []
        
        def graph = TaskGraph.build {
            serviceTask("retry-task") {
                retry {
                    maxAttempts 3
                    initialDelay Duration.ofMillis(10)
                    backoffMultiplier 1.0
                }
                action { ctx, prev ->
                    attempts << System.currentTimeMillis()
                    if (attempts.size() < 3) {
                        ctx.promiseFactory.executeAsync {
                            throw new RuntimeException("Simulated failure")
                        }
                    } else {
                        ctx.promiseFactory.executeAsync { "success" }
                    }
                }
            }
        }
        
        def result = graph.run().get()
        
        assertEquals("success", result)
        assertEquals(3, attempts.size())
        
        // Verify delays occurred
        assertTrue(attempts[1] - attempts[0] >= 10)
        assertTrue(attempts[2] - attempts[1] >= 10)
    }

    @Test
    void testRetryExhaustion() {
        def attempts = []
        
        def graph = TaskGraph.build {
            serviceTask("always-fails") {
                retry {
                    maxAttempts 2
                    initialDelay Duration.ofMillis(10)
                }
                action { ctx, prev ->
                    attempts << attempts.size() + 1
                    ctx.promiseFactory.executeAsync {
                        throw new RuntimeException("Always fails")
                    }
                }
            }
        }
        
        assertThrows(Exception) {
            graph.run().get()
        }
        
        assertEquals(2, attempts.size())
    }

    @Test
    void testExponentialBackoff() {
        def timestamps = []
        
        def graph = TaskGraph.build {
            serviceTask("backoff-test") {
                retry {
                    maxAttempts 4
                    initialDelay Duration.ofMillis(50)
                    backoffMultiplier 2.0
                }
                action { ctx, prev ->
                    timestamps << System.currentTimeMillis()
                    if (timestamps.size() < 4) {
                        ctx.promiseFactory.executeAsync {
                            throw new RuntimeException("Fail")
                        }
                    } else {
                        ctx.promiseFactory.executeAsync { "done" }
                    }
                }
            }
        }
        
        graph.run().get()
        
        assertEquals(4, timestamps.size())
        
        // Verify exponential backoff delays
        // Delay 1: ~50ms
        // Delay 2: ~100ms
        // Delay 3: ~200ms
        long delay1 = timestamps[1] - timestamps[0]
        long delay2 = timestamps[2] - timestamps[1]
        long delay3 = timestamps[3] - timestamps[2]
        
        assertTrue(delay1 >= 40 && delay1 <= 100, "First delay should be ~50ms, was ${delay1}ms")
        assertTrue(delay2 >= 90 && delay2 <= 150, "Second delay should be ~100ms, was ${delay2}ms")
        assertTrue(delay3 >= 180 && delay3 <= 250, "Third delay should be ~200ms, was ${delay3}ms")
    }

    @Test
    void testNoRetriesWhenDisabled() {
        def attempts = []
        
        def graph = TaskGraph.build {
            serviceTask("no-retry") {
                retry {
                    disabled()
                }
                action { ctx, prev ->
                    attempts << 1
                    ctx.promiseFactory.executeAsync {
                        throw new RuntimeException("Fail")
                    }
                }
            }
        }
        
        assertThrows(Exception) {
            graph.run().get()
        }
        
        // Should fail immediately, no retries
        assertEquals(1, attempts.size())
    }

    @Test
    void testBackwardCompatibilityWithMaxRetries() {
        // Old API should still work
        def graph = TaskGraph.build {
            serviceTask("old-style") {
                maxRetries = 3
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync { "done" }
                }
            }
        }
        
        def task = graph.tasks["old-style"]
        assertEquals(3, task.maxRetries)
    }
}
