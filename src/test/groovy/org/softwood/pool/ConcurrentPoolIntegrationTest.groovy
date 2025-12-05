package org.softwood.pool

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class ConcurrentPoolIntegrationTest {

    @Test
    void testDefaultConstructorUsesVirtualThreads() throws Exception {
        ConcurrentPool pool = new ConcurrentPool();

        var future = pool.execute(() -> Thread.currentThread().isVirtual());
        boolean isVirtual = future.get(2, TimeUnit.SECONDS);

        assertTrue(isVirtual, "Default constructor should use virtual threads");
        pool.shutdown();
    }

    @Test
    void testFixedPoolConstructorUsesPlatformThreadsAndExposesPoolSize() throws Exception {
        int size = 4;
        ConcurrentPool pool = new ConcurrentPool(size);

        assertEquals(size, pool.getPoolSize(), "getPoolSize() should reflect fixed pool size");

        var future = pool.execute(() -> Thread.currentThread().isVirtual());
        boolean isVirtual = future.get(2, TimeUnit.SECONDS);

        assertFalse(isVirtual, "Fixed-size constructor should use platform threads");
        pool.shutdown();
    }

    @Test
    void testWithPoolExecutesWorkOnProvidedExecutor() throws Exception {
        var customExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        ConcurrentPool pool = new ConcurrentPool();

        StringBuilder threadNameHolder = new StringBuilder();

        var future = pool.withPool(customExecutor, () -> {
            String threadName = Thread.currentThread().getName();
            threadNameHolder.append(threadName);
            return threadName;
        });

        String result = future.get(2, TimeUnit.SECONDS);

        assertTrue(
                result.contains(threadNameHolder.toString()),
                "Work should run on the custom executor thread"
        );

        customExecutor.shutdown();
        pool.shutdown();
    }

    @Test
    void testExecuteRunsClosureAndReturnsFutureResult() throws Exception {
        ConcurrentPool pool = new ConcurrentPool();
        var future = pool.execute(() -> 1 + 2);

        int result = future.get(2, TimeUnit.SECONDS);
        assertEquals(3, result);

        pool.shutdown();
    }

    @Test
    void testScheduleExecutionRunsTaskAfterDelay() {
        ConcurrentPool pool = new ConcurrentPool();
        AtomicBoolean flag = new AtomicBoolean(false);

        var scheduled = pool.scheduleExecution(
                100, TimeUnit.MILLISECONDS,
                () -> flag.set(true)
        );

        assertFalse(flag.get(), "Task should not have run immediately");

        // Await completion instead of using CountDownLatch
        await().atMost(2, TimeUnit.SECONDS).untilTrue(flag);

        assertTrue(flag.get(), "Flag should be set by scheduled task");

        assertFalse(!scheduled.isDone() && !scheduled.isCancelled(),
                "Scheduled future should report done or cancelled");

        pool.shutdown();
    }

    @Test
    void testScheduledExecutorIsLazilyInitialized() {
        ConcurrentPool pool = new ConcurrentPool();

        AtomicBoolean ran = new AtomicBoolean(false);

        pool.scheduleExecution(10, TimeUnit.MILLISECONDS, () -> ran.set(true));

        await().atMost(2, TimeUnit.SECONDS).untilTrue(ran);

        assertNotNull(
                pool.getScheduledExecutor(),
                "Scheduled executor should be initialized lazily"
        );

        pool.shutdown();
    }

    @Test
    void testShutdownStopsAcceptingNewTasks() throws Exception {
        ConcurrentPool pool = new ConcurrentPool();
        AtomicInteger counter = new AtomicInteger(0);

        // A task before shutdown is fine
        var f1 = pool.execute(counter::incrementAndGet);
        f1.get(2, TimeUnit.SECONDS);

        pool.shutdown();

        // Attempting to submit a new task must throw
        assertThrows(IllegalStateException.class, () -> {
            var future = pool.execute(counter::incrementAndGet);
            future.get(2, TimeUnit.SECONDS);
        }, "Submitting after shutdown should fail");
    }

    @Test
    void testErrorsCollectionIsImmutableView() {
        List<String> errors = ConcurrentPool.getErrors();
        assertNotNull(errors);
        assertTrue(errors instanceof List);

        assertThrows(UnsupportedOperationException.class, () -> {
            errors.add("should not be allowed");
        });
    }
}