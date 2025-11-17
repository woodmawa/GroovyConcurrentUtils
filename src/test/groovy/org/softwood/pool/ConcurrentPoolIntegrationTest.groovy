package org.softwood.pool

import groovy.test.GroovyTestCase
import org.junit.jupiter.api.Test

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ConcurrentPoolIntegrationTest extends GroovyTestCase {

    @Test
    void testDefaultConstructorUsesVirtualThreads() {
        def pool = new ConcurrentPool()

        def future = pool.execute {
            Thread.currentThread().isVirtual()
        }

        def isVirtual = future.get(2, TimeUnit.SECONDS) as Boolean
        assertTrue("Default constructor should use virtual threads", isVirtual)

        pool.shutdown()
    }

    @Test
    void testFixedPoolConstructorUsesPlatformThreadsAndExposesPoolSize() {
        int size = 4
        def pool = new ConcurrentPool(size)

        assertEquals("getPoolSize() should reflect fixed pool size",
                size, pool.poolSize)

        def future = pool.execute {
            Thread.currentThread().isVirtual()
        }

        def isVirtual = future.get(2, TimeUnit.SECONDS) as Boolean
        assertFalse("Fixed-size constructor should use platform threads", isVirtual)

        pool.shutdown()
    }

    @Test
    void testWithPoolExecutesWorkOnProvidedExecutor() {
        def customExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
        def pool = new ConcurrentPool()

        def threadNameHolder = new StringBuilder()

        def future = pool.withPool(customExecutor) {
            threadNameHolder << Thread.currentThread().name
            Thread.currentThread().name
        }

        def result = future.get(2, TimeUnit.SECONDS) as String

        assertTrue("Work should run on the custom executor thread",
                result.contains(threadNameHolder.toString()))

        customExecutor.shutdown()
        pool.shutdown()
    }

    @Test
    void testExecuteRunsClosureAndReturnsFutureResult() {
        def pool = new ConcurrentPool()
        def future = pool.execute { 1 + 2 }

        def result = future.get(2, TimeUnit.SECONDS) as Integer
        assertEquals(3, result)

        pool.shutdown()
    }

    @Test
    void testScheduleExecutionRunsTaskAfterDelay() {
        def pool = new ConcurrentPool()
        def latch = new CountDownLatch(1)
        def flag = new AtomicBoolean(false)

        def scheduled = pool.scheduleExecution(100, TimeUnit.MILLISECONDS) {
            flag.set(true)
            latch.countDown()
        }

        assertFalse("Task should not have run immediately", flag.get())

        def completedInTime = latch.await(2, TimeUnit.SECONDS)
        assertTrue("Scheduled task should have run within timeout", completedInTime)
        assertTrue("Flag should be set by scheduled task", flag.get())
        assertFalse("Scheduled future should report done or cancelled",
                !scheduled.isDone() && !scheduled.isCancelled())

        pool.shutdown()
    }

    @Test
    void testScheduledExecutorIsLazilyInitialized() {
        def pool = new ConcurrentPool()

        // Access scheduled executor only via scheduleExecution()
        def latch = new CountDownLatch(1)
        pool.scheduleExecution(10, TimeUnit.MILLISECONDS) {
            latch.countDown()
        }

        assertTrue("Scheduled task should execute", latch.await(2, TimeUnit.SECONDS))
        assertNotNull("Scheduled executor should be initialized lazily",
                pool.scheduledExecutor)

        pool.shutdown()
    }

    @Test
    void testShutdownStopsAcceptingNewTasks() {
        def pool = new ConcurrentPool()
        def counter = new AtomicInteger(0)

        // Submit one task before shutdown
        def f1 = pool.execute {
            counter.incrementAndGet()
        }
        f1.get(2, TimeUnit.SECONDS)

        pool.shutdown()

        try {
            pool.execute {
                counter.incrementAndGet()
            }.get(2, TimeUnit.SECONDS)
            fail("Submitting after shutdown should fail")
        } catch (Exception ignored) {
            // Expected: underlying executor should reject new tasks
        }
    }

    @Test
    void testErrorsCollectionIsImmutableView() {
        def errors = ConcurrentPool.getErrors()
        assertNotNull(errors)
        assertTrue(errors instanceof List)

        try {
            errors << "should not be allowed"
            fail("Errors list should be immutable")
        } catch (UnsupportedOperationException ignored) {
            // expected
        }
    }
}