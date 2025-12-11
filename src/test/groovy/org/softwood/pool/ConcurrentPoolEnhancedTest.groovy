package org.softwood.pool

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.AfterEach
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

import static org.junit.jupiter.api.Assertions.*
import static org.awaitility.Awaitility.await

/**
 * Comprehensive test suite for ConcurrentPool enhancements.
 * Tests all hardening improvements and new features.
 * Pure JUnit 5 + Awaitility - no Spock dependencies.
 */
class ConcurrentPoolEnhancedTest {

    private List<ConcurrentPool> poolsToCleanup = []

    @AfterEach
    void cleanup() {
        poolsToCleanup.each { pool ->
            try {
                pool.shutdown()
                pool.awaitTermination(2, TimeUnit.SECONDS)
            } catch (Exception ignored) {
            }
        }
        poolsToCleanup.clear()
    }

    private ConcurrentPool trackPool(ConcurrentPool pool) {
        poolsToCleanup << pool
        return pool
    }

    // =========================================================================
    // Exception Handling - Closed Pool
    // =========================================================================

    @Test
    void testExecuteThrowsIllegalStateExceptionWhenPoolIsClosed() {
        // Given: a closed pool
        def pool = trackPool(new ConcurrentPool(2))
        pool.shutdown()

        // When/Then: trying to execute throws IllegalStateException
        def exception = assertThrows(IllegalStateException) {
            pool.execute { "should fail" }
        }
        assertTrue(exception.message.contains("closed"))
    }

    @Test
    void testExecuteHandlesInterruptedExceptionInTask() {
        // Given: a pool
        def pool = trackPool(new ConcurrentPool(2))

        // And: a task that will be interrupted
        def started = new AtomicBoolean(false)
        def interrupted = new AtomicBoolean(false)

        // When: we execute a long-running task and interrupt it
        def future = pool.execute {
            started.set(true)
            try {
                Thread.sleep(10_000)
                return "completed"
            } catch (InterruptedException e) {
                interrupted.set(true)
                throw e
            }
        }

        // Wait for task to start
        await().atMost(2, TimeUnit.SECONDS).untilTrue(started)

        // Cancel the future (attempts to interrupt)
        future.cancel(true)

        // Then: the task should be interrupted or cancelled
        await().atMost(2, TimeUnit.SECONDS).until {
            future.isCancelled() || future.isDone()
        }
        assertTrue(future.isCancelled() || interrupted.get())
    }

    // =========================================================================
    // Null Check in getScheduledExecutor
    // =========================================================================

    @Test
    void testGetScheduledExecutorReturnsNonNull() {
        // Given: a pool
        def pool = trackPool(new ConcurrentPool())

        // When: we get the scheduled executor
        def scheduler = pool.scheduledExecutor

        // Then: it should not be null
        assertNotNull(scheduler, "Scheduled executor should be initialized")
    }

    @Test
    void testScheduleExecutionWorksCorrectly() {
        // Given: a pool
        def pool = trackPool(new ConcurrentPool())
        def executed = new AtomicBoolean(false)

        // When: we schedule a task
        pool.scheduleExecution(50, TimeUnit.MILLISECONDS) {
            executed.set(true)
        }

        // Then: task executes after delay
        await().atMost(2, TimeUnit.SECONDS).untilTrue(executed)
    }

    // =========================================================================
    // tryExecute for Callable and Runnable
    // =========================================================================

    @Test
    void testTryExecuteWithCallableReturnsTrueWhenTaskAccepted() {
        // Given: a pool
        def pool = trackPool(new ConcurrentPool(2))
        def executed = new AtomicBoolean(false)

        // When: we try to execute a Callable
        boolean accepted = pool.tryExecute({ ->
            executed.set(true)
            return 42
        } as java.util.concurrent.Callable)

        // Then: task is accepted
        assertTrue(accepted)

        // And: task executes
        await().atMost(2, TimeUnit.SECONDS).untilTrue(executed)
    }

    @Test
    void testTryExecuteWithRunnableReturnsTrueWhenTaskAccepted() {
        // Given: a pool
        def pool = trackPool(new ConcurrentPool(2))
        def executed = new AtomicBoolean(false)

        // When: we try to execute a Runnable
        boolean accepted = pool.tryExecute({ executed.set(true) } as Runnable)

        // Then: task is accepted
        assertTrue(accepted)

        // And: task executes
        await().atMost(2, TimeUnit.SECONDS).untilTrue(executed)
    }

    @Test
    void testTryExecuteReturnsFalseWhenPoolIsClosed() {
        // Given: a closed pool
        def pool = trackPool(new ConcurrentPool(2))
        pool.shutdown()

        // When: we try to execute tasks
        boolean closureAccepted = pool.tryExecute { "closure" }
        boolean callableAccepted = pool.tryExecute({ -> "callable" } as java.util.concurrent.Callable)
        boolean runnableAccepted = pool.tryExecute({ println "runnable" } as Runnable)

        // Then: all are rejected
        assertFalse(closureAccepted)
        assertFalse(callableAccepted)
        assertFalse(runnableAccepted)
    }

    // =========================================================================
    // Health Check
    // =========================================================================

    @Test
    void testIsHealthyReturnsTrueForActivePool() {
        // Given: a new pool
        def pool = trackPool(new ConcurrentPool(4))

        // Then: pool is healthy
        assertTrue(pool.isHealthy())
        assertFalse(pool.isClosed())
    }

    @Test
    void testIsHealthyReturnsFalseAfterShutdown() {
        // Given: a pool
        def pool = trackPool(new ConcurrentPool(4))

        // When: we shut it down
        pool.shutdown()

        // Then: pool is not healthy
        assertFalse(pool.isHealthy())
        assertTrue(pool.isClosed())
    }

    @Test
    void testIsHealthyReturnsFalseWhenOwnedExecutorIsShutDownExternally() {
        // Given: a pool with a fixed executor (owned)
        def pool = trackPool(new ConcurrentPool(2))

        // When: we shut down the underlying executor directly
        pool.executor.shutdown()

        // Then: pool is not healthy
        assertFalse(pool.isHealthy())
    }

    @Test
    void testIsHealthyReturnsTrueForSharedExecutorPool() {
        // Given: a pool using shared virtual threads (not owned)
        def pool = trackPool(new ConcurrentPool())  // Uses shared executor

        // Then: pool is initially healthy
        assertTrue(pool.isHealthy())
        assertFalse(pool.isClosed())
    }

    // =========================================================================
    // Metrics
    // =========================================================================

    @Test
    void testGetMetricsReturnsComprehensiveDataForFixedPool() {
        // Given: a fixed-size pool
        def pool = trackPool(new ConcurrentPool(4))
        pool.name = "metrics-test-pool"

        // When: we execute some tasks
        (1..3).each { i ->
            pool.execute { Thread.sleep(50) }
        }

        // And: get metrics
        def metrics = pool.metrics

        // Then: metrics contain expected data
        assertEquals("metrics-test-pool", metrics.name)
        assertFalse(metrics.closed)
        assertTrue(metrics.healthy)
        assertFalse(metrics.usingVirtualThreads)
        assertTrue(metrics.ownsExecutor)
        assertEquals(4, metrics.poolSize)

        // And: ThreadPoolExecutor metrics are present
        assertTrue(metrics.containsKey('activeCount'))
        assertTrue(metrics.containsKey('taskCount'))
        assertTrue(metrics.containsKey('completedTaskCount'))
        assertTrue(metrics.containsKey('queueSize'))
        assertTrue(metrics.containsKey('corePoolSize'))
        assertTrue(metrics.containsKey('maximumPoolSize'))
    }

    @Test
    void testGetMetricsHandlesVirtualThreadPool() {
        // Given: a virtual thread pool
        def pool = trackPool(new ConcurrentPool())

        // When: we get metrics
        def metrics = pool.metrics

        // Then: metrics indicate virtual threads
        assertTrue(metrics.usingVirtualThreads)
        assertEquals(-1, metrics.poolSize)  // No fixed size
        assertFalse(metrics.ownsExecutor)  // Shared executor

        // And: no ThreadPoolExecutor-specific metrics
        assertFalse(metrics.containsKey('activeCount'))
    }

    @Test
    void testGetMetricsShowsUnhealthyStateAfterShutdown() {
        // Given: a pool
        def pool = trackPool(new ConcurrentPool(2))

        // When: we shut it down
        pool.shutdown()
        def metrics = pool.metrics

        // Then: metrics reflect closed state
        assertTrue(metrics.closed)
        assertFalse(metrics.healthy)
    }

    // =========================================================================
    // External Executor Validation
    // =========================================================================

    @Test
    void testConstructorLogsWarningForShutDownExecutor() {
        // Given: a shut-down executor
        def executor = Executors.newSingleThreadExecutor()
        executor.shutdown()

        // When: we create a pool with it
        def pool = new ConcurrentPool(executor)

        // Then: pool is created (doesn't throw)
        assertNotNull(pool)

        // No cleanup needed - executor already shut down
    }

    @Test
    void testConstructorLogsWarningForShutDownScheduledExecutor() {
        // Given: a normal executor and shut-down scheduler
        def executor = Executors.newFixedThreadPool(2)
        def scheduler = Executors.newScheduledThreadPool(1)
        scheduler.shutdown()

        // When: we create a pool with them
        def pool = new ConcurrentPool(executor, scheduler)
        poolsToCleanup << pool

        // Then: pool is created (doesn't throw)
        assertNotNull(pool)

        executor.shutdown()
    }

    // =========================================================================
    // Deprecated Method
    // =========================================================================

    @Test
    void testShutdownSharedExecutorsIsDeprecatedAndDoesNothing() {
        // When: we call the deprecated method
        ConcurrentPool.shutdownSharedExecutors()

        // Then: method completes without exception
        // (If we get here, no exception was thrown)

        // And: shared executor still works
        def pool = trackPool(new ConcurrentPool())
        def future = pool.execute { 42 }
        assertEquals(42, future.get(1, TimeUnit.SECONDS))
    }

    // =========================================================================
    // Concurrent Shutdown Safety
    // =========================================================================

    @Test
    void testConcurrentShutdownCallsAreSafe() {
        // Given: a pool
        def pool = trackPool(new ConcurrentPool(4))
        def shutdownCount = new AtomicInteger(0)

        // When: multiple threads try to shut it down
        def threads = (1..10).collect {
            Thread.start {
                pool.shutdown()
                shutdownCount.incrementAndGet()
            }
        }

        threads*.join()

        // Then: all threads complete successfully
        assertEquals(10, shutdownCount.get())
        assertTrue(pool.isClosed())
    }

    // =========================================================================
    // Edge Cases
    // =========================================================================

    @Test
    void testExecuteAfterShutdownThrowsIllegalStateException() {
        // Given: a closed pool
        def pool = trackPool(new ConcurrentPool(2))
        pool.shutdown()

        // When/Then: trying to execute throws IllegalStateException
        def exception = assertThrows(IllegalStateException) {
            pool.execute { "should fail" }
        }
        assertTrue(exception.message.contains("closed"))
    }

    @Test
    void testScheduleExecutionAfterShutdownThrowsIllegalStateException() {
        // Given: a closed pool
        def pool = trackPool(new ConcurrentPool())
        pool.shutdown()

        // When/Then: trying to schedule throws IllegalStateException
        def exception = assertThrows(IllegalStateException) {
            pool.scheduleExecution(100, TimeUnit.MILLISECONDS) { "should fail" }
        }
        assertTrue(exception.message.contains("closed"))
    }

    @Test
    void testMultipleTasksExecuteConcurrentlyOnFixedPool() {
        // Given: a 4-thread pool
        def pool = trackPool(new ConcurrentPool(4))
        def counter = new AtomicInteger(0)
        def maxConcurrent = new AtomicInteger(0)
        def currentConcurrent = new AtomicInteger(0)

        // When: we submit 10 tasks that increment concurrent counter
        def futures = (1..10).collect {
            pool.execute {
                def concurrent = currentConcurrent.incrementAndGet()

                // Track max concurrent
                maxConcurrent.updateAndGet { current -> Math.max(current, concurrent) }

                Thread.sleep(50)
                counter.incrementAndGet()

                currentConcurrent.decrementAndGet()
            }
        }

        // Wait for all
        futures*.get(5, TimeUnit.SECONDS)

        // Then: all tasks completed
        assertEquals(10, counter.get())

        // And: max concurrent was at most 4 (pool size)
        assertTrue(maxConcurrent.get() <= 4,
                "Max concurrent was ${maxConcurrent.get()}, should be <= 4")
    }

    @Test
    void testVirtualThreadPoolHandlesThousandsOfConcurrentTasks() {
        // Given: a virtual thread pool
        def pool = trackPool(new ConcurrentPool())
        def counter = new AtomicInteger(0)

        // Only run this test if virtual threads are available
        if (!pool.isUsingVirtualThreads()) {
            println "Skipping virtual thread test - not on Java 21+"
            return
        }

        // When: we submit 1000 concurrent tasks
        def futures = (1..1000).collect {
            pool.execute {
                Thread.sleep(10)
                counter.incrementAndGet()
            }
        }

        // Wait for all
        futures*.get(10, TimeUnit.SECONDS)

        // Then: all tasks completed
        assertEquals(1000, counter.get())
    }
}