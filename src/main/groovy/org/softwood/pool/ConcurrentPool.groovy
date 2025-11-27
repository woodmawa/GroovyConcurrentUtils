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
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ConcurrentPool – a thin abstraction over executors with support for:
 * <ul>
 *   <li>A shared application-wide virtual-thread executor (when available).</li>
 *   <li>Per-instance platform thread pools (fixed or cached).</li>
 *   <li>Optional scheduled execution using a shared scheduled executor.</li>
 * </ul>
 *
 * <h2>Design goals</h2>
 * <ul>
 *   <li>Provide a convenient, high-level API for submitting tasks and scheduling work.</li>
 *   <li>Prefer virtual threads when available, falling back to platform threads transparently.</li>
 *   <li>Clearly separate <em>shared</em> executors from <em>owned</em> executors.</li>
 *   <li>Prevent accidental shutdown of shared executors that would impact unrelated code.</li>
 * </ul>
 *
 * <h2>Ownership semantics</h2>
 * <ul>
 *   <li>The default constructor uses a <strong>shared</strong> virtual-thread executor
 *       when available. This executor is <strong>never</strong> shut down by individual
 *       pool instances.</li>
 *   <li>If virtual threads are not available, the default constructor falls back to a
 *       cached platform thread pool, which <strong>is</strong> owned by the instance and
 *       is shut down by {@code #shutdown()} / {@code #shutdownNow()}.</li>
 *   <li>Constructors that explicitly create a fixed-size pool own that executor.</li>
 *   <li>Constructors that accept an external executor never own it.</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 * <ul>
 *   <li>Once {@code #shutdown()} or {@code #shutdownNow()} is called, the pool is
 *       considered <em>closed</em>. Subsequent submissions using the high-level API
 *       throw {@link IllegalStateException}.</li>
 *   <li>Shared executors are designed to live for the lifetime of the JVM and are no
 *       longer shut down by {@code #shutdownSharedExecutors()} – that method is now a
 *       no-op and exists only for backward compatibility.</li>
 * </ul>
 */
@Slf4j
class ConcurrentPool {

    // ─────────────────────────────────────────────────────────────
    // Shared application-wide resources
    // ─────────────────────────────────────────────────────────────

    private static ExecutorService sharedVirtualThreadExecutor
    private static ScheduledExecutorService sharedScheduledExecutor
    private static AtomicBoolean virtualThreadsAvailable = new AtomicBoolean(false)
    private static ConcurrentLinkedQueue errors = new ConcurrentLinkedQueue<>()

    // ─────────────────────────────────────────────────────────────
    // Instance state
    // ─────────────────────────────────────────────────────────────

    private ExecutorService executor
    private ScheduledExecutorService scheduledExecutor

    /**
     * Whether this instance owns the main executor and is therefore responsible
     * for shutting it down when {@link #shutdown()} / {@link #shutdownNow()} are called.
     */
    private boolean ownsExecutor = false

    /**
     * Whether this instance owns the scheduled executor.
     * By default, scheduled executors are shared; ownership is rare.
     */
    private boolean ownsScheduledExecutor = false

    /**
     * Indicates that this pool has been logically closed and is no longer
     * accepting new tasks through its high-level API.
     */
    private AtomicBoolean closed = new AtomicBoolean(false)

    String name

    // ─────────────────────────────────────────────────────────────
    // Static initialization
    // ─────────────────────────────────────────────────────────────

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

        // Shared scheduled executor - uses platform threads for the scheduling mechanism.
        // Actual scheduled tasks may themselves run on virtual threads if they internally
        // use a virtual-thread executor.
        try {
            int size = Math.max(1, Runtime.getRuntime().availableProcessors() / 4) as int
            sharedScheduledExecutor = Executors.newScheduledThreadPool(size)
        } catch (Exception e) {
            sharedScheduledExecutor = Executors.newSingleThreadScheduledExecutor()
            errors << "Failed to initialize scheduled executor: ${e.message}".toString()
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Construction
    // ─────────────────────────────────────────────────────────────

    /**
     * Creates a pool using virtual threads if available, otherwise creates a cached thread pool.
     * <p>
     * When virtual threads are available:
     * </p>
     * <ul>
     *   <li>Uses a shared application-wide executor that should live for the entire JVM lifetime.</li>
     *   <li>The executor is <strong>not</strong> owned by any individual ConcurrentPool instance.</li>
     * </ul>
     *
     * @param name optional logical name used only for logging/debugging
     */
    ConcurrentPool(String name = null) {
        if (name) {
            this.name = name
        }

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
     * @param poolSize number of platform threads in the pool (must be > 0)
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
     * @param executor          the executor service to use (must not be {@code null})
     * @param scheduledExecutor optional scheduled executor (uses shared if {@code null})
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

    // ─────────────────────────────────────────────────────────────
    // Static helpers & status
    // ─────────────────────────────────────────────────────────────

    /**
     * Returns the shared virtual thread executor if available, otherwise {@code null}.
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
     * Checks if this pool instance is using virtual threads (i.e. the shared virtual-thread executor).
     */
    boolean isUsingVirtualThreads() {
        executor == sharedVirtualThreadExecutor
    }

    /**
     * Returns the pool size for fixed thread pools, or -1 for virtual thread executors
     * or other executor types with no meaningful size.
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

    static void clearErrors() {
        errors.clear()
    }

    // ─────────────────────────────────────────────────────────────
    // Executor configuration
    // ─────────────────────────────────────────────────────────────

    /**
     * Override the executor for this pool instance.
     * Only recommended for testing or advanced use cases.
     * <p>
     * If this pool previously owned an executor, that executor is shut down before being replaced.
     * The newly assigned executor is considered <em>external</em> and is not owned.
     * </p>
     */
    @NotNull
    void setExecutor(ExecutorService executor) {
        assert executor != null, "Executor cannot be null"

        if (ownsExecutor && this.executor != null) {
            this.executor.shutdown()
        }

        this.executor = executor
        this.ownsExecutor = false  // Don't shut down externally provided executor
    }

    /**
     * Returns the underlying executor.
     * <p>
     * If the pool has been closed via {@link #shutdown()} or {@link #shutdownNow()},
     * this method throws {@link IllegalStateException} to help callers avoid reusing
     * an executor that conceptually belongs to a closed pool.
     * </p>
     */
    ExecutorService getExecutor() {
        if (closed.get()) {
            throw new IllegalStateException("Pool is closed; executor should no longer be used via this pool")
        }
        executor
    }

    @NotNull
    void setScheduledExecutor(ScheduledExecutorService scheduledExecutor) {
        assert scheduledExecutor != null, "Scheduled executor cannot be null"

        if (ownsScheduledExecutor && this.scheduledExecutor != null) {
            this.scheduledExecutor.shutdown()
        }

        this.scheduledExecutor = scheduledExecutor
        this.ownsScheduledExecutor = false
    }

    /**
     * Returns the scheduled executor for this pool instance.
     * Uses the shared scheduled executor if no dedicated one was configured.
     */
    ScheduledExecutorService getScheduledExecutor() {
        if (scheduledExecutor == null) {
            scheduledExecutor = sharedScheduledExecutor
        }
        scheduledExecutor
    }

    // ─────────────────────────────────────────────────────────────
    // High-level execution API
    // ─────────────────────────────────────────────────────────────

    /**
     * Executes work with an optional custom executor for this invocation only.
     *
     * @param executor optional executor to use for this work (uses instance executor if {@code null})
     * @param work     closure to execute with this pool as delegate
     * @return a {@link CompletableFuture} representing completion of the work
     * @throws IllegalStateException if this pool is closed
     */
    @NotNull
    CompletableFuture withPool(ExecutorService executor = null, @DelegatesTo(ConcurrentPool) Closure work) {
        if (this.closed.get()) {
            throw new IllegalStateException("Pool is closed and not accepting new tasks")
        }

        ExecutorService effectiveExecutor = executor ?: this.executor

        work.delegate = this
        work.resolveStrategy = Closure.DELEGATE_FIRST

        Future future = effectiveExecutor.submit(work)
        return transformFutureToCFuture(future, effectiveExecutor)
    }

    /**
     * Submits a task to the pool executor.
     *
     * @param task closure to execute
     * @return {@link CompletableFuture} representing the task
     * @throws IllegalStateException if pool is closed or executor rejects the task
     */
    @NotNull
    CompletableFuture execute(Closure task) {
        if (this.closed.get()) {
            throw new IllegalStateException("Pool is closed and not accepting new tasks")
        }
        try {
            Future future = this.executor.submit(task)
            return transformFutureToCFuture(future, this.executor)
        } catch (RejectedExecutionException rex) {
            throw new IllegalStateException("Executor rejected task; it may be shutting down or terminated", rex)
        }
    }

    /**
     * Submits a task with arguments to the pool executor.
     *
     * @param task closure to execute
     * @param args args to pass to the closure
     * @return {@link CompletableFuture} representing the task
     * @throws IllegalStateException if pool is closed or executor rejects the task
     */
    @NotNull
    CompletableFuture execute(Closure task, Object[] args) {
        if (this.closed.get()) {
            throw new IllegalStateException("Pool is closed and not accepting new tasks")
        }

        Closure workWithArgs = { task(args) }
        try {
            Future future = this.executor.submit(workWithArgs)
            return transformFutureToCFuture(future, this.executor)
        } catch (RejectedExecutionException rex) {
            throw new IllegalStateException("Executor rejected task; it may be shutting down or terminated", rex)
        }
    }

    /**
     * A "best-effort" non-throwing variant of {@link #execute(Closure)}.
     *
     * @param task closure to execute
     * @return {@code true} if the task was accepted, {@code false} if the pool is closed
     *         or the executor rejected the task
     */
    boolean tryExecute(Closure task) {
        if (this.closed.get()) {
            return false
        }
        try {
            this.executor.submit(task)
            return true
        } catch (RejectedExecutionException ignored) {
            return false
        }
    }

    /**
     * Helper function to take the {@link Future} returned by the executor submit and wrap it
     * as a {@link CompletableFuture}. The wrapping itself is performed by submitting a small
     * task to the same executor.
     *
     * @param future          original future
     * @param executorService executor that will run the bridge task
     * @return completable future that completes with the same value/exception as {@code future}
     */
    private CompletableFuture transformFutureToCFuture(Future future, ExecutorService executorService) {
        CompletableFuture cf = new CompletableFuture()
        executorService.submit(() -> {
            try {
                cf.complete(future.get())
            } catch (Exception e) {
                cf.completeExceptionally(e)
            }
        })
        return cf
    }

    // ─────────────────────────────────────────────────────────────
    // Scheduling API
    // ─────────────────────────────────────────────────────────────

    /**
     * Schedules work to execute after a delay.
     *
     * @param delay delay before execution
     * @param unit  time unit for the delay
     * @param task  closure to execute
     * @return scheduled future for the scheduled task
     * @throws IllegalStateException if pool is closed
     */
    ScheduledFuture scheduleExecution(int delay, TimeUnit unit, Closure task) {
        if (this.closed.get()) {
            throw new IllegalStateException("Pool is closed and not accepting new tasks")
        }
        return getScheduledExecutor().schedule(task, delay, unit)
    }

    /**
     * Schedules work to execute repeatedly with a fixed delay between executions.
     *
     * @param initialDelay delay before first execution
     * @param delay        delay between subsequent executions
     * @param unit         time unit
     * @param task         closure to execute
     * @return scheduled future for the scheduled task
     * @throws IllegalStateException if pool is closed
     */
    ScheduledFuture scheduleWithFixedDelay(int initialDelay, int delay, TimeUnit unit, Closure task) {
        if (this.closed.get()) {
            throw new IllegalStateException("Pool is closed and not accepting new tasks")
        }
        return getScheduledExecutor().scheduleWithFixedDelay(task, initialDelay, delay, unit)
    }

    /**
     * Schedules work to execute repeatedly at a fixed rate.
     *
     * @param initialDelay delay before first execution
     * @param period       period between executions
     * @param unit         time unit
     * @param task         closure to execute
     * @return scheduled future for the scheduled task
     * @throws IllegalStateException if pool is closed
     */
    ScheduledFuture scheduleAtFixedRate(int initialDelay, int period, TimeUnit unit, Closure task) {
        if (this.closed.get()) {
            throw new IllegalStateException("Pool is closed and not accepting new tasks")
        }
        return getScheduledExecutor().scheduleAtFixedRate(task, initialDelay, period, unit)
    }

    /**
     * Non-throwing variant of {@link #scheduleExecution(int, TimeUnit, Closure)}.
     *
     * @return {@code true} if scheduled, {@code false} otherwise
     */
    boolean tryScheduleExecution(int delay, TimeUnit unit, Closure task) {
        if (this.closed.get()) {
            return false
        }
        try {
            getScheduledExecutor().schedule(task, delay, unit)
            return true
        } catch (RejectedExecutionException ignored) {
            return false
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Shutdown & lifecycle
    // ─────────────────────────────────────────────────────────────

    /**
     * Initiates an orderly shutdown of executors owned by this instance.
     * Shared executors are not shut down.
     * Completes existing tasks but accepts no new ones (via the high-level API).
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
     * @param timeout maximum time to wait
     * @param unit    time unit
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
     * <p>
     * <strong>Hardened behaviour:</strong> This method is now a <em>no-op</em> to prevent
     * accidental global shutdown of shared executors that may be used by arbitrary code
     * across the application. It is preserved only for backward compatibility.
     * </p>
     */
    static void shutdownSharedExecutors() {
        log.warn("shutdownSharedExecutors() is now a no-op; shared executors live for the JVM lifetime")
        // Intentionally do nothing – shared executors are treated as JVM-wide resources.
    }
}
