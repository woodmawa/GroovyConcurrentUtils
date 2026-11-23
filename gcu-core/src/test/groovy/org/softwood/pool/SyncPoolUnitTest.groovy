package org.softwood.pool

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract-level tests for the SyncPool test double.
 * Ensures deterministic synchronous behaviour for unit tests.
 */

class SyncPoolUnitTest {

    private ExecutorPool pool;

    @BeforeEach
    void setUp() {
        // always obtain a fresh synchronous fake pool
        pool = SynchronousPoolFactoryMock.builder().name("test-sync").build();
    }

    // -------------------------------------------------------------
    // Basic execution
    // -------------------------------------------------------------

    @Test
    void testExecuteClosureRunsImmediatelyAndReturnsValue() throws Exception {
        CompletableFuture<Integer> future = pool.execute(() -> 1 + 2);
        assertEquals(3, future.get(), "SyncPool should execute immediately and return result");
    }

    @Test
    void testExecuteCallableRunsSynchronously() throws Exception {
        CompletableFuture<String> future = pool.execute(() -> "hello");
        assertEquals("hello", future.get());
    }

    @Test
    void testExecuteRunnableRunsSynchronously() throws Exception {
        AtomicBoolean ran = new AtomicBoolean(false);
        CompletableFuture<?> future = pool.execute(() -> ran.set(true));

        assertTrue(ran.get(), "Runnable must run immediately");
        assertNull(future.get(), "Runnable returns null result");
    }

    @Test
    void testExecuteWithArgsRunsSynchronously() throws Exception {
        CompletableFuture<String> future = pool.execute(args -> "X:" + args[0], new Object[]{"abc"});
        assertEquals("X:abc", future.get());
    }

    @Test
    void testTryExecuteRunsTaskAndReturnsTrue() {
        AtomicBoolean ran = new AtomicBoolean(false);

        boolean accepted = pool.tryExecute(() -> ran.set(true));

        assertTrue(accepted);
        assertTrue(ran.get(), "tryExecute must also run immediately");
    }

    // -------------------------------------------------------------
    // Scheduling (synchronous fake: immediate execution)
    // -------------------------------------------------------------

    @Test
    void testScheduleExecutionRunsImmediately() {
        AtomicBoolean ran = new AtomicBoolean(false);

        var future = pool.scheduleExecution(100, TimeUnit.MILLISECONDS, () -> ran.set(true));

        assertTrue(ran.get(), "Scheduled execution in SyncPool runs immediately");
        assertTrue(future.isDone(), "Future should be immediately completed");
    }

    @Test
    void testScheduleAtFixedRateRunsImmediately() {
        AtomicBoolean ran = new AtomicBoolean(false);

        var future = pool.scheduleAtFixedRate(0, 10, TimeUnit.MILLISECONDS, () -> ran.set(true));

        assertTrue(ran.get());
        assertTrue(future.isDone());
    }

    @Test
    void testScheduleWithFixedDelayRunsImmediately() {
        AtomicBoolean ran = new AtomicBoolean(false);

        var future = pool.scheduleWithFixedDelay(0, 10, TimeUnit.MILLISECONDS, () -> ran.set(true));

        assertTrue(ran.get());
        assertTrue(future.isDone());
    }

    // -------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------

    @Test
    void testShutdownPreventsNewTasks() throws Exception {
        AtomicInteger count = new AtomicInteger(0);

        // Before shutdown: OK
        //pool.execute returns a CF which can contain a null
        int v = pool.execute(count::incrementAndGet).get() ?: count.get();
        assertEquals(1, v);

        // Shutdown the mock pool
        ((SynchronousPoolMock) pool).shutdown();

        assertThrows(IllegalStateException.class,
                () -> pool.execute(count::incrementAndGet),
                "Submitting after shutdown should fail");
    }

    @Test
    void testIsClosedReflectsShutdownState() {
        assertFalse(pool.isClosed());

        ((SynchronousPoolMock) pool).shutdown();

        assertTrue(pool.isClosed());
    }

    // -------------------------------------------------------------
    // Introspection
    // -------------------------------------------------------------

    @Test
    void testGetNameReturnsConfiguredName() {
        assertEquals("test-sync", pool.getName());
    }

    @Test
    void testUsingVirtualThreadsIsFalse() {
        assertFalse(pool.isUsingVirtualThreads(), "SyncPool never uses virtual threads");
    }

}
