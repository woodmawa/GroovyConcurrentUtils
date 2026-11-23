package org.softwood.dataflow

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.softwood.pool.ConcurrentPool
import org.softwood.gstream.Gstream

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

import static org.junit.jupiter.api.Assertions.*
import static org.awaitility.Awaitility.await

/**
 * Enhanced test suite for DataflowVariable hardening improvements.
 * Tests critical race condition fixes, new observability features,
 * Gstream integration, and timeout support.
 *
 * Uses JUnit 5 + Awaitility (no Spock for Groovy 5 compatibility).
 */
class DataflowVariableEnhancedTest {

    private List<ConcurrentPool> pools = []

    @BeforeEach
    void setup() {
        DataflowExpression.clearListenerErrors()
    }

    @AfterEach
    void cleanup() {
        pools.each { pool ->
            try {
                pool.shutdown()
                if (!pool.awaitTermination(2, TimeUnit.SECONDS)) {
                    // Force shutdown if graceful shutdown times out
                    pool.shutdownNow()
                    pool.awaitTermination(1, TimeUnit.SECONDS)
                }
            } catch (Exception e) {
                // Ignore cleanup errors
                try {
                    pool.shutdownNow()
                } catch (Exception ignored) {}
            }
        }
        pools.clear()
        DataflowExpression.clearListenerErrors()
    }

    private ConcurrentPool createPool(String name = "test-pool") {
        def pool = new ConcurrentPool(name)
        pools << pool
        return pool
    }

    // =========================================================================
    // Task Execution & Auto-Binding Tests
    // =========================================================================

    @Test
    void testTaskWithClosure() {
        // Given: A DataflowVariable
        def pool = createPool()
        def dfv = new DataflowVariable<Integer>(pool)

        // When: Executing task with closure
        dfv.task { ->
            Thread.sleep(100)
            return 42
        }

        // Then: Should auto-bind result
        assertEquals(42, dfv.get())
        assertTrue(dfv.isSuccess())
    }

    @Test
    void testTaskWithCallable() {
        // Given: A DataflowVariable
        def pool = createPool()
        def dfv = new DataflowVariable<String>(pool)

        // When: Executing task with Callable (Java lambda)
        dfv.task({ -> "result from callable" } as java.util.concurrent.Callable)

        // Then: Should auto-bind result
        assertEquals("result from callable", dfv.get())
    }

    @Test
    void testTaskWithSupplier() {
        // Given: A DataflowVariable
        def pool = createPool()
        def dfv = new DataflowVariable<String>(pool)

        // When: Executing task with Supplier
        dfv.task({ -> "result from supplier" } as java.util.function.Supplier)

        // Then: Should auto-bind result
        assertEquals("result from supplier", dfv.get())
    }

    @Test
    void testTaskWithError() {
        // Given: A DataflowVariable
        def pool = createPool()
        def dfv = new DataflowVariable<Integer>(pool)

        // When: Task throws exception
        dfv.task { ->
            throw new RuntimeException("Task failed!")
        }

        // Then: Should auto-bind error
        await().atMost(2, TimeUnit.SECONDS).until { dfv.isBound() }

        assertTrue(dfv.hasError())
        assertTrue(dfv.error.message.contains("Task failed!"))
    }

    @Test
    void testTaskFluentChaining() {
        // Given: A DataflowVariable
        def pool = createPool()

        // When: Using fluent chaining
        def dfv = new DataflowVariable<Integer>(pool)
            .task { -> 10 }

        // Then: Should return same instance and execute
        assertNotNull(dfv)
        assertEquals(10, dfv.get())
    }

    // =========================================================================
    // Critical Race Condition Fix Tests
    // =========================================================================

    @Test
    void testToFutureRaceCondition() {
        // Given: A pool and DataflowVariable
        def pool = createPool()
        def dfv = new DataflowVariable<Integer>(pool)

        // When: Multiple threads race to call toFuture() while another binds
        def futures = []
        def completions = new AtomicInteger(0)

        // Start 10 threads that will call toFuture()
        (1..10).each { i ->
            futures << pool.execute {
                def cf = dfv.toFuture()
                cf.thenAccept { value ->
                    completions.incrementAndGet()
                }
            }
        }

        // Give threads time to start and call toFuture()
        Thread.sleep(100)

        // Bind value after futures are created (race condition)
        dfv.bind(42)

        // Then: Wait for all futures to complete
        futures*.get(3, TimeUnit.SECONDS)

        // All 10 CompletableFutures should complete successfully
        await().atMost(3, TimeUnit.SECONDS).until {
            completions.get() == 10
        }

        assertEquals(10, completions.get(), "All futures should complete")
    }

    @Test
    void testToFutureAlreadyBound() {
        // Given: An already-bound DataflowVariable
        def pool = createPool()
        def dfv = new DataflowVariable<String>(pool)
        dfv.bind("test-value")

        // When: toFuture() is called after binding
        def cf = dfv.toFuture()

        // Then: CompletableFuture should complete immediately
        assertTrue(cf.isDone(), "Future should be done")
        assertEquals("test-value", cf.get())
    }

    @Test
    void testToFutureWithError() {
        // Given: A DataflowVariable with an error
        def pool = createPool()
        def dfv = new DataflowVariable<Integer>(pool)
        dfv.bindError(new RuntimeException("Test error"))

        // When: toFuture() is called
        def cf = dfv.toFuture()

        // Then: CompletableFuture should complete exceptionally
        assertTrue(cf.isCompletedExceptionally(), "Future should complete exceptionally")

        def exception = assertThrows(Exception) {
            cf.get()
        }
        assertTrue(exception.message.contains("Test error"))
    }

    // =========================================================================
    // Metrics Support Tests
    // =========================================================================

    @Test
    void testMetricsPending() {
        // Given: A pending DataflowVariable
        def pool = createPool("metrics-pool")
        def dfv = new DataflowVariable<String>(pool)

        // When: Getting metrics
        def metrics = dfv.metrics

        // Then: Metrics should reflect pending state
        assertNotNull(metrics.type)
        assertFalse(metrics.bound)
        assertFalse(metrics.success)
        assertFalse(metrics.hasError)
        assertEquals("metrics-pool", metrics.poolName)
    }

    @Test
    void testMetricsSuccess() {
        // Given: A successfully bound DataflowVariable
        def pool = createPool()
        def dfv = new DataflowVariable<String>(pool)
        dfv.bind("success")

        // When: Getting metrics
        def metrics = dfv.metrics

        // Then: Metrics should reflect success state
        assertTrue(metrics.bound)
        assertTrue(metrics.success)
        assertFalse(metrics.hasError)
        assertNotNull(metrics.completedAt)
    }

    @Test
    void testMetricsError() {
        // Given: A DataflowVariable with an error
        def pool = createPool()
        def dfv = new DataflowVariable<Integer>(pool)
        def error = new IllegalArgumentException("Test error")
        dfv.bindError(error)

        // When: Getting metrics
        def metrics = dfv.metrics

        // Then: Metrics should reflect error state
        assertTrue(metrics.bound)
        assertFalse(metrics.success)
        assertTrue(metrics.hasError)
        assertEquals("Test error", metrics.errorMessage)
        assertEquals("IllegalArgumentException", metrics.errorType)
    }

    @Test
    void testMetricsCancelled() {
        // Given: A cancelled DataflowVariable
        def pool = createPool()
        def dfv = new DataflowVariable<Integer>(pool)
        dfv.bindCancelled()

        // When: Getting metrics
        def metrics = dfv.metrics

        // Then: Metrics should reflect cancelled state
        assertTrue(metrics.bound)
        assertFalse(metrics.success)
        assertTrue(metrics.hasError)
    }

    // =========================================================================
    // Listener Error Collection Tests
    // =========================================================================

    @Test
    void testListenerErrorCollection() {
        // Given: A DataflowVariable with a failing listener
        def pool = createPool()
        def dfv = new DataflowVariable<Integer>(pool)

        dfv.whenAvailable { value ->
            throw new RuntimeException("Listener explosion!")
        }

        // When: Binding a value
        dfv.bind(42)

        // Then: Error should be collected
        await().atMost(2, TimeUnit.SECONDS).until {
            !DataflowExpression.listenerErrors.isEmpty()
        }

        def errors = DataflowExpression.listenerErrors
        assertTrue(errors.size() > 0, "Should have collected error")
        assertTrue(errors.any { it.contains("Listener explosion!") })
    }

    @Test
    void testListenerErrorClear() {
        // Given: Some listener errors
        def pool = createPool()
        def dfv = new DataflowVariable<Integer>(pool)

        dfv.whenAvailable { value ->
            throw new RuntimeException("Error 1")
        }

        dfv.bind(42)

        await().atMost(2, TimeUnit.SECONDS).until {
            !DataflowExpression.listenerErrors.isEmpty()
        }

        // When: Clearing errors
        DataflowExpression.clearListenerErrors()

        // Then: Errors should be cleared
        assertTrue(DataflowExpression.listenerErrors.isEmpty())
    }

    // =========================================================================
    // Gstream Integration Tests
    // =========================================================================

    @Test
    void testToGstreamSuccess() {
        // Given: A successfully bound DataflowVariable
        def pool = createPool()
        def dfv = new DataflowVariable<Integer>(pool)
        dfv.bind(42)

        // When: Converting to Gstream
        def gstream = dfv.toGstream()
        def results = gstream.toList()

        // Then: Gstream should contain the value
        assertEquals(1, results.size())
        assertEquals(42, results[0])
    }

    @Test
    void testToGstreamWithError() {
        // Given: A DataflowVariable with an error
        def pool = createPool()
        def dfv = new DataflowVariable<Integer>(pool)
        dfv.bindError(new RuntimeException("Test error"))

        // When: Converting to Gstream
        def gstream = dfv.toGstream()
        def results = gstream.toList()

        // Then: Gstream should be empty
        assertTrue(results.isEmpty(), "Gstream should be empty on error")
    }

    @Test
    void testToGstreamWithNull() {
        // Given: A DataflowVariable bound to null
        def pool = createPool()
        def dfv = new DataflowVariable<String>(pool)
        dfv.bind(null)

        // When: Converting to Gstream
        def gstream = dfv.toGstream()
        def results = gstream.toList()

        // Then: Gstream should be empty (null values filtered)
        assertTrue(results.isEmpty(), "Gstream should be empty for null values")
    }

    @Test
    void testGstreamPipeline() {
        // Given: A DataflowVariable with a value
        def pool = createPool()
        def dfv = new DataflowVariable<Integer>(pool)
        dfv.bind(5)

        // When: Building a pipeline with Gstream
        def results = dfv.toGstream()
            .map { it * 2 }
            .filter { it > 5 }
            .toList()

        // Then: Pipeline should process correctly
        assertEquals(1, results.size())
        assertEquals(10, results[0])
    }

    // =========================================================================
    // Timeout Support Tests (via DataflowFactory)
    // =========================================================================

    @Test
    void testTaskWithTimeoutSuccess() {
        // Given: A factory with timeout support
        def pool = createPool()
        def factory = new DataflowFactory(pool)

        // When: Task completes within timeout
        def result = factory.taskWithTimeout({
            Thread.sleep(50)
            return 42
        } as java.util.concurrent.Callable, 1, TimeUnit.SECONDS)

        // Then: Should complete successfully
        assertEquals(42, result.get())
    }

    @Test
    void testTaskWithTimeoutExpires() {
        // Given: A factory with timeout support
        def pool = createPool()
        def factory = new DataflowFactory(pool)

        // When: Task exceeds timeout
        def result = factory.taskWithTimeout({
            Thread.sleep(2000)
            return "never"
        } as java.util.concurrent.Callable, 100, TimeUnit.MILLISECONDS)

        // Then: Should complete with timeout error
        await().atMost(2, TimeUnit.SECONDS).until { result.isBound() }

        assertTrue(result.hasError())
        assertTrue(result.error instanceof TimeoutException)
        assertTrue(result.error.message.contains("timed out"))
    }

    @Test
    void testTaskWithTimeoutClosure() {
        // Given: A factory with timeout support
        def pool = createPool()
        def factory = new DataflowFactory(pool)

        // When: Using closure variant
        def result = factory.taskWithTimeout({ -> "quick" }, 1, TimeUnit.SECONDS)

        // Then: Should work with closures
        assertEquals("quick", result.get())
    }

    // =========================================================================
    // TaskStream Tests
    // =========================================================================

    @Test
    void testTaskStream() {
        // Given: A factory
        def pool = createPool()
        def factory = new DataflowFactory(pool)

        // When: Executing multiple tasks as stream
        def gstream = factory.taskStream(
            { -> 1 },
            { -> 2 },
            { -> 3 }
        )

        def results = gstream.toList()

        // Then: Should contain all results
        assertEquals(3, results.size())
        assertTrue(results.containsAll([1, 2, 3]))
    }

    @Test
    void testTaskStreamWithProcessing() {
        // Given: A factory
        def pool = createPool()
        def factory = new DataflowFactory(pool)

        // When: Processing stream results
        def results = factory.taskStream(
            { -> 1 },
            { -> 2 },
            { -> 3 }
        ).map { it * 10 }
         .filter { it > 15 }
         .toList()

        // Then: Should process correctly
        assertEquals(2, results.size())
        assertTrue(results.containsAll([20, 30]))
    }

    @Test
    void testTaskStreamUnordered() {
        // Given: A factory
        def pool = createPool()
        def factory = new DataflowFactory(pool)

        // When: Using unordered stream
        def gstream = factory.taskStreamUnordered(
            { -> Thread.sleep(50); return 1 },
            { -> Thread.sleep(10); return 2 },
            { -> Thread.sleep(30); return 3 }
        )

        def results = gstream.toList()

        // Then: Should contain all results (order may vary)
        assertEquals(3, results.size())
        assertTrue(results.containsAll([1, 2, 3]))
    }

    @Test
    void testTaskStreamEmpty() {
        // Given: A factory
        def pool = createPool()
        def factory = new DataflowFactory(pool)

        // When: Creating stream with no tasks
        def gstream = factory.taskStream()

        // Then: Should return empty stream
        assertTrue(gstream.toList().isEmpty())
    }

    // =========================================================================
    // Exception Handling Tests
    // =========================================================================

    @Test
    void testAssertionsReplacedWithExceptions() {
        // When: Creating DataflowExpression with null pool
        def exception = assertThrows(IllegalArgumentException) {
            new DataflowExpression(null)
        }

        // Then: Should throw exception (not assertion error)
        assertTrue(exception.message.contains("non-null"))
    }

    @Test
    void testDataflowVariableNullPool() {
        // When: Creating DataflowVariable with null pool
        def exception = assertThrows(IllegalArgumentException) {
            new DataflowVariable(null)
        }

        // Then: Should throw exception
        assertTrue(exception.message.contains("non-null"))
    }

    // =========================================================================
    // Integration Tests
    // =========================================================================

    @Test
    void testCompleteWorkflow() {
        // Given: A complete dataflow workflow
        def pool = createPool()
        def factory = new DataflowFactory(pool)

        // When: Executing complex workflow
        def user = factory.task { -> [id: 1, name: "Alice"] }
        def orders = factory.task { -> [[id: 101], [id: 102]] }

        def results = Gstream.from([user, orders])
            .flatMapIterable { it.toGstream().toList() }
            .filter { it != null }
            .toList()

        // Then: Should process entire workflow
        assertEquals(2, results.size())
    }

    @Test
    void testMetricsInProduction() {
        // Given: A production-like scenario
        def pool = createPool("prod-pool")
        def factory = new DataflowFactory(pool)

        // When: Executing task and monitoring
        def dfv = factory.taskWithTimeout({ -> "data" }, 5, TimeUnit.SECONDS)
        dfv.get() // Wait for completion

        def metrics = dfv.metrics

        // Then: Metrics should be available for monitoring
        assertNotNull(metrics.poolName)
        assertTrue(metrics.success)
        assertEquals("prod-pool", metrics.poolName)
    }
}
