package org.softwood.pool

import groovy.util.logging.Slf4j
import groovyjarjarantlr4.v4.runtime.misc.NotNull

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A pool abstraction that supports both virtual threads (Java 21+) and traditional platform thread pools.
 *
 * For virtual threads: Uses a shared application-wide executor that creates a new virtual thread per task.
 * For platform threads: Creates a fixed-size thread pool for this specific instance.
 */
@Slf4j
class ConcurrentPool {
    private static ExecutorService sharedVirtualThreadExecutor
    private static ScheduledExecutorService sharedScheduledExecutor
    private static AtomicBoolean virtualThreadsAvailable = new AtomicBoolean(false)
    private static ConcurrentLinkedQueue errors = new ConcurrentLinkedQueue<>()

    private ExecutorService executor
    private ScheduledExecutorService scheduledExecutor
    private boolean ownsExecutor = false  // Track if we created the executor and should shut it down
    private boolean ownsScheduledExecutor = false
    private AtomicBoolean closed = new AtomicBoolean(false)  // Track if this pool instance is closed

    String name

    static {
        // Initialize shared virtual thread executor if available
        try {
            sharedVirtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor()
            virtualThreadsAvailable.set(true)
        } catch (Exception e) {
            // Virtual threads not available (Java < 21), will fall back per instance
            virtualThreadsAvailable.set(false)
            errors << "Virtual threads not available: ${e.message}".toString()
        }

        // Shared scheduled executor - uses platform threads for the scheduling mechanism
        // Even though we prefer virtual threads, scheduled executors need persistent threads
        // to manage the scheduling queue and dispatch tasks. The actual scheduled tasks
        // can still run on virtual threads if needed via execute() after scheduling.
        /*
            pool.scheduleAtFixedRate(0, 1, TimeUnit.MINUTES) {
                // This runs on platform thread (scheduler)
                // But can spawn virtual threads for actual work
                pool.execute {
                // Heavy I/O work on virtual thread
                    fetchAndProcessData()
                }
            }
         */
        try {
            sharedScheduledExecutor = Executors.newScheduledThreadPool(
                    Math.max(1, Runtime.getRuntime().availableProcessors() / 4) as int
            )
        } catch (Exception e) {
            sharedScheduledExecutor = Executors.newSingleThreadScheduledExecutor()
            errors << "Failed to initialize scheduled executor: ${e.message}".toString()
        }
    }

    /**
     * Creates a pool using virtual threads if available, otherwise creates a cached thread pool.
     * This is the recommended constructor for most use cases.
     */
    ConcurrentPool() {
        if (virtualThreadsAvailable.get()) {
            // Use shared virtual thread executor - no need to create a new one
            this.executor = sharedVirtualThreadExecutor
            this.ownsExecutor = false
        } else {
            // Fall back to cached thread pool for platform threads
            this.executor = Executors.newCachedThreadPool()
            this.ownsExecutor = true
        }
    }

    /**
     * Creates a pool with a fixed number of platform threads.
     * Use this when you explicitly want platform threads with a specific size.
     *
     * @param poolSize - number of platform threads in the pool
     */
    ConcurrentPool(int poolSize) {
        assert poolSize > 0, "Pool size must be greater than 0"
        this.executor = Executors.newFixedThreadPool(poolSize)
        this.ownsExecutor = true
    }

    /**
     * Creates a pool with an explicitly provided executor.
     * Useful for testing or advanced configurations.
     *
     * @param executor - the executor service to use
     * @param scheduledExecutor - optional scheduled executor (uses shared if null)
     */
    ConcurrentPool(ExecutorService executor, ScheduledExecutorService scheduledExecutor = null) {
        assert executor != null, "Executor cannot be null"
        this.executor = executor
        this.ownsExecutor = false  // Don't shut down externally provided executors

        if (scheduledExecutor != null) {
            this.scheduledExecutor = scheduledExecutor
            this.ownsScheduledExecutor = false
        }
    }

    /**
     * Returns the shared virtual thread executor if available, otherwise null.
     */
    static ExecutorService getSharedVirtualThreadExecutor() {
        sharedVirtualThreadExecutor
    }

    /**
     * Returns the shared scheduled executor.
     */
    static ScheduledExecutorService getSharedScheduledExecutor() {
        sharedScheduledExecutor
    }

    /**
     * Checks if virtual threads are available in this JVM.
     */
    static boolean isVirtualThreadsAvailable() {
        virtualThreadsAvailable.get()
    }

    /**
     * Checks if this pool instance is using virtual threads.
     */
    boolean isUsingVirtualThreads() {
        executor == sharedVirtualThreadExecutor
    }

    /**
     * Returns the pool size for fixed thread pools, or -1 for virtual thread executors.
     */
    int getPoolSize() {
        if (executor instanceof ThreadPoolExecutor) {
            return ((ThreadPoolExecutor) executor).getCorePoolSize()
        }
        return -1  // Virtual thread executors don't have a meaningful pool size
    }

    static boolean hasErrors() {
        !errors.isEmpty()
    }

    static List getErrors() {
        errors.asList().asImmutable()
    }

    static void clearErrors () {
        errors.clear()
    }

    /**
     * Override the executor for this pool instance.
     * Only recommended for testing or advanced use cases.
     */
    @NotNull
    void setExecutor(ExecutorService executor) {
        assert executor != null, "Executor cannot be null"

        // Shut down old executor if we owned it
        if (ownsExecutor && this.executor != null) {
            this.executor.shutdown()
        }

        this.executor = executor
        this.ownsExecutor = false  // Don't shut down externally provided executor
    }

    ExecutorService getExecutor() {
        executor
    }

    @NotNull
    void setScheduledExecutor(ScheduledExecutorService scheduledExecutor) {
        assert scheduledExecutor != null, "Scheduled executor cannot be null"

        // Shut down old scheduled executor if we owned it
        if (ownsScheduledExecutor && this.scheduledExecutor != null) {
            this.scheduledExecutor.shutdown()
        }

        this.scheduledExecutor = scheduledExecutor
        this.ownsScheduledExecutor = false
    }

    ScheduledExecutorService getScheduledExecutor() {
        // Lazy initialization - use shared scheduled executor
        if (scheduledExecutor == null) {
            scheduledExecutor = sharedScheduledExecutor
        }
        scheduledExecutor
    }

    /**
     * Executes work with an optional custom executor for this invocation only.
     *
     * @param executor - optional executor to use for this work (uses instance executor if null)
     * @param work - closure to execute with this pool as delegate
     * @throws IllegalStateException if pool is closed
     */
    @NotNull
    CompletableFuture withPool(ExecutorService executor = null, @DelegatesTo(ConcurrentPool) Closure work) {
        if (this.closed.get() == true) {
            throw new IllegalStateException("Pool is closed and not accepting new tasks")
        }

        ExecutorService effectiveExecutor = executor ?: this.executor

        work.delegate = this
        work.resolveStrategy = Closure.DELEGATE_FIRST

        Future future =  effectiveExecutor.submit(work)
        def cf = transformFutureToCFuture(future, effectiveExecutor)
        return cf
    }

    /**
     * Submits a task to the pool executor.
     *
     * @param task - closure to execute
     * @return Future representing the task
     * @throws IllegalStateException if pool is closed
     */
    @NotNull
    CompletableFuture execute(Closure task) {
        if (this.closed.get() == true) {
            throw new IllegalStateException("Pool is closed and not accepting new tasks")
        }
        Future future = this.executor.submit(task)
        return transformFutureToCFuture(future, this.executor)
    }

    /**
     * Submits a task to the pool executor.
     *
     * @param task - closure to execute
     * @param args - args to pass to the closure
     * @return Future representing the task
     * @throws IllegalStateException if pool is closed
     */
    @NotNull
    CompletableFuture execute(Closure task, Object[] args) {
        if (this.closed.get() == true) {
            throw new IllegalStateException("Pool is closed and not accepting new tasks")
        }

        Closure workWithArgs = {task (args)}
        Future future =  this.executor.submit(workWithArgs)
        return transformFutureToCFuture(future, this.executor)
    }

    /**
     * helper function to take the future returned by the executor submit and return is as a completableFuture
     * takes a future and wraps the sync future.get() with lambda and submits that, sets the value int the new
     * computableFuture of the completes exceptionally
     * @param future
     * @param executorService
     * @return CompletableFuture
     */
    private CompletableFuture transformFutureToCFuture (Future future, ExecutorService executorService) {
        CompletableFuture cf = new CompletableFuture()
        executorService.submit ( () -> {
            try {
                cf.complete(future.get())
            } catch (Exception e) {
                cf.completeExceptionally(e)
            }
        })
        //return the completableFuture with the outcome of the future.get()
        return cf
    }

    /**
     * Schedules work to execute after a delay.
     *
     * @param delay - delay before execution
     * @param unit - time unit for the delay
     * @param task - closure to execute
     * @return ScheduledFuture for the scheduled task
     * @throws IllegalStateException if pool is closed
     */
    ScheduledFuture scheduleExecution(int delay, TimeUnit unit, Closure task) {
        if (this.closed.get() == true) {
            throw new IllegalStateException("Pool is closed and not accepting new tasks")
        }
        return getScheduledExecutor().schedule(task, delay, unit)
    }

    /**
     * Schedules work to execute repeatedly with a fixed delay between executions.
     *
     * @param initialDelay - delay before first execution
     * @param delay - delay between subsequent executions
     * @param unit - time unit
     * @param task - closure to execute
     * @return ScheduledFuture for the scheduled task
     * @throws IllegalStateException if pool is closed
     */
    ScheduledFuture scheduleWithFixedDelay(int initialDelay, int delay, TimeUnit unit, Closure task) {
        if (this.closed.get() == true) {
            throw new IllegalStateException("Pool is closed and not accepting new tasks")
        }
        return getScheduledExecutor().scheduleWithFixedDelay(task, initialDelay, delay, unit)
    }

    /**
     * Schedules work to execute repeatedly at a fixed rate.
     *
     * @param initialDelay - delay before first execution
     * @param period - period between executions
     * @param unit - time unit
     * @param task - closure to execute
     * @return ScheduledFuture for the scheduled task
     * @throws IllegalStateException if pool is closed
     */
    ScheduledFuture scheduleAtFixedRate(int initialDelay, int period, TimeUnit unit, Closure task) {
        if (this.closed.get() == true) {
            throw new IllegalStateException("Pool is closed and not accepting new tasks")
        }
        return getScheduledExecutor().scheduleAtFixedRate(task, initialDelay, period, unit)
    }

    /**
     * Initiates an orderly shutdown of executors owned by this instance.
     * Shared executors are not shut down.
     * Completes existing tasks but accepts no new ones.
     * Marks this pool as closed to prevent new task submissions.
     */
    void shutdown() {
        this.closed.set(true)

        if (ownsExecutor && executor != null) {
            executor.shutdown()
        }
        if (ownsScheduledExecutor && scheduledExecutor != null) {
            scheduledExecutor.shutdown()
        }
    }

    /**
     * Checks if this pool is closed and no longer accepting new tasks.
     */
    boolean isClosed() {
        return this.closed.get()
    }

    /**
     * Checks if owned executors are shut down.
     * Returns the closed state for shared executors.
     */
    boolean isShutdown() {
        if (ownsExecutor && executor != null) {
            return executor.isShutdown()
        }
        return this.closed.get()
    }

    /**
     * Attempts to stop all actively executing tasks and halts processing of waiting tasks.
     * Only affects executors owned by this instance.
     * Marks this pool as closed to prevent new task submissions.
     *
     * @return list of tasks that were awaiting execution
     */
    List<Runnable> shutdownNow() {
        this.closed.set(true)

        List<Runnable> pendingTasks = []

        if (ownsExecutor && executor != null) {
            pendingTasks.addAll(executor.shutdownNow())
        }
        if (ownsScheduledExecutor && scheduledExecutor != null) {
            pendingTasks.addAll(scheduledExecutor.shutdownNow())
        }

        return pendingTasks
    }

    /**
     * Blocks until all tasks complete after shutdown, or timeout occurs.
     *
     * @param timeout - maximum time to wait
     * @param unit - time unit
     * @return true if executors terminated, false if timeout elapsed
     */
    boolean awaitTermination(long timeout, TimeUnit unit) {
        boolean terminated = true

        if (ownsExecutor && executor != null) {
            terminated &= executor.awaitTermination(timeout, unit)
        }
        if (ownsScheduledExecutor && scheduledExecutor != null) {
            terminated &= scheduledExecutor.awaitTermination(timeout, unit)
        }

        return terminated
    }

    /**
     * Shuts down the shared application-wide executors.
     * Should only be called during application shutdown.
     */
    static void shutdownSharedExecutors() {
        sharedVirtualThreadExecutor?.shutdown()
        sharedScheduledExecutor?.shutdown()
    }
}