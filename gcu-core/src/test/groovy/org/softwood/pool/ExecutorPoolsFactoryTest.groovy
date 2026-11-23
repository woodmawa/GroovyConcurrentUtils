package org.softwood.pool;

import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class ExecutorPoolsFactoryTest {

    // --------------------------------------------------------------
    // wrap(ExecutorService)
    // --------------------------------------------------------------

    @Test
    void testWrapUsesProvidedExecutor() throws Exception {
        ExecutorService es = Executors.newFixedThreadPool(2);
        ExecutorPool pool = ExecutorPoolFactory.wrap(es);

        AtomicReference<String> threadName = new AtomicReference<>();

        var future = pool.execute(() -> {
            threadName.set(Thread.currentThread().getName());
            return "ok";
        });

        String result = future.get(1, TimeUnit.SECONDS);
        assertEquals("ok", result);
        assertTrue(threadName.get().contains("pool"),
                "Execution should run on user-provided ExecutorService");

        // Shutdown user executor manually (pool should NOT own it)
        es.shutdown();
    }


    // --------------------------------------------------------------
    // builder(): custom executor
    // --------------------------------------------------------------

    @Test
    void testBuilderWithCustomExecutor() throws Exception {
        ExecutorService es = Executors.newSingleThreadExecutor();
        ExecutorPool pool = ExecutorPoolFactory.builder()
                .executor(es)
                .name("custom-pool")
                .build();

        var future = pool.execute(() -> Thread.currentThread().getName());

        String threadName = future.get(1, TimeUnit.SECONDS);
        assertTrue(threadName.contains("pool"),
                "Task should execute on provided ExecutorService");

        es.shutdown();
    }


    // --------------------------------------------------------------
    // builder(): custom scheduler
    // --------------------------------------------------------------

    @Test
    void testBuilderWithCustomScheduler() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ExecutorPool pool = ExecutorPoolFactory.builder()
                .scheduler(scheduler)
                .name("scheduled-pool")
                .build();

        AtomicBoolean fired = new AtomicBoolean(false);

        pool.scheduleExecution(50, TimeUnit.MILLISECONDS, () -> fired.set(true));

        await()
                .atMost(1, TimeUnit.SECONDS)
                .untilTrue(fired);

        assertTrue(fired.get(), "Scheduled task should run using provided scheduler");

        scheduler.shutdown();
    }


    // --------------------------------------------------------------
    // builder(): default behavior (use ConcurrentPool defaults)
    // --------------------------------------------------------------

    @Test
    void testBuilderDefaultUsesConcurrentPoolDefaults() throws Exception {
        ExecutorPool pool = ExecutorPoolFactory.builder()
                .name("default-test")
                .build();

        var future = pool.execute(() -> 42);

        Integer result = future.get(1, TimeUnit.SECONDS);
        assertEquals(42, result);

        // default concurrent pool may be virtual-thread based → check no failure
        assertNotNull(result);
    }


    // --------------------------------------------------------------
    // builder(): name propagation
    // --------------------------------------------------------------

    @Test
    void testBuilderSetsName() {
        ExecutorPool pool = ExecutorPoolFactory.builder()
                .name("my-awesome-pool")
                .build();

        assertEquals("my-awesome-pool", pool.getName());
    }


    // --------------------------------------------------------------
    // builder(): executor + scheduler combined
    // --------------------------------------------------------------

    @Test
    void testBuilderWithExecutorAndScheduler() throws Exception {
        ExecutorService es = Executors.newFixedThreadPool(2);
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

        ExecutorPool pool = ExecutorPoolFactory.builder()
                .executor(es)
                .scheduler(scheduler)
                .name("combo")
                .build();

        // verify async execution uses executor
        var future = pool.execute(() -> "x");
        assertEquals("x", future.get(1, TimeUnit.SECONDS));

        // verify scheduling uses scheduler
        AtomicBoolean ran = new AtomicBoolean(false);
        pool.scheduleExecution(30, TimeUnit.MILLISECONDS, () -> ran.set(true));

        await()
                .atMost(1, TimeUnit.SECONDS)
                .untilTrue(ran);

        assertTrue(ran.get());

        es.shutdown();
        scheduler.shutdown();
    }
}
