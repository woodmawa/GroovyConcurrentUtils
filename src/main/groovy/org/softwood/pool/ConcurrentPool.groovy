package org.softwood.pool

import groovyjarjarantlr4.v4.runtime.misc.NotNull
import java.util.concurrent.ScheduledExecutorService

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ConcurrentPool {
    private static ExecutorService defaultExecutor
    private static ExecutorService defaultScheduledExecutor
    private static AtomicBoolean virtualThreadsEnabled = new AtomicBoolean(true)
    private static ConcurrentLinkedQueue errors = new ConcurrentLinkedQueue<>()
    private ExecutorService executor
    private ScheduledExecutorService scheduledExecutor


    static {
        // Initialize with virtual threads by default if running on Java 21+
        try {
            defaultExecutor = Executors.newVirtualThreadPerTaskExecutor()
            virtualThreadsEnabled.set(true)
        } catch (Exception e) {
            // Fall back to a cached thread pool if virtual threads are not available
            int poolSize = Runtime.getRuntime().availableProcessors()
            defaultExecutor = Executors.newCachedThreadPool()
            virtualThreadsEnabled.set(false)
            errors << "Failed to initialize virtual thread executor: ${e.message}".toString()
        }

        try {
            // For scheduled executor, use virtual threads if available
            if (virtualThreadsEnabled.get()) {
                ThreadFactory factory = Thread.ofVirtual().factory()
                defaultScheduledExecutor = Executors.newScheduledThreadPool(1, factory)
            } else {
                defaultScheduledExecutor = Executors.newSingleThreadScheduledExecutor()
            }
        } catch (Exception e) {
            // Ultimate fallback to platform thread scheduled executor
            defaultScheduledExecutor = Executors.newSingleThreadScheduledExecutor()
            errors << "Failed to initialize scheduled executor with virtual threads: ${e.message}".toString()
        }
    }

    static ExecutorService getDefaultExecutor() {
        defaultExecutor
    }

    static boolean isVirtualThreadsEnabled() {
        virtualThreadsEnabled.get()
    }

    String name

    ConcurrentPool() {
        this.executor = Executors.newVirtualThreadPerTaskExecutor()
    }

    ConcurrentPool(int poolSize) {
        assert poolSize > 0, "Pool size must be greater than 0"
        // For platform threads, use fixed thread pool with specified size
        this.executor = Executors.newFixedThreadPool(poolSize)
        virtualThreadsEnabled.set (false)
    }

    /**
     * Explicitly set the executorService from outside for this pool
     * @param executor - the executor service to use (defaults to defaultExecutor if null)
     * @param scheduledExecutor - optional scheduled executor (lazily initialized if null)
     */
    ConcurrentPool(ExecutorService executor, ScheduledExecutorService scheduledExecutor = null) {
        this.executor = executor ?: defaultExecutor
        this.scheduledExecutor = scheduledExecutor
    }

    int getPoolSize() {
        if (executor instanceof ThreadPoolExecutor) {
            return ((ThreadPoolExecutor) executor).getCorePoolSize()
        }
        // For virtual-thread executors or other implementations, no fixed size
        return 0  // or -1 or throw UnsupportedOperationException
    }


    static boolean hasErrors () {
        errors.size()
    }

    static List getErrors () {
        errors.asList.asImmutable()
    }

    /**
     * overide executor with new one for this pool instance
     * @param executor
     */
    @NotNull
    void setExecutor (ExecutorService executor) {
        if (executor)
            this.executor = executor
    }

    ExecutorService getExecutor () {
        executor
    }

    @NotNull
    void setScheduledExecutor (ScheduledExecutorService scheduledExecutor) {
        if (scheduledExecutor)
            this.scheduledExecutor = scheduledExecutor
    }

    ScheduledExecutorService getScheduledExecutor() {
        // Lazy initialization of scheduledExecutor
        if (scheduledExecutor == null) {
            scheduledExecutor = defaultScheduledExecutor
        }
        scheduledExecutor
    }

    @NotNull
    def withPool (ExecutorService executor=null, @DelegatesTo (ConcurrentPool) Closure work) {
        if (executor)
            setExecutor(executor)

        work.delegate = this
        work.resolveStrategy = Closure.DELEGATE_FIRST

        execute (work)
    }

    /**
     * submits callable task on the pool executor
     *
     * @param task
     */
    @NotNull
    def execute (Closure task) {
        this.executor.submit task
    }

    /**
     * Schedule the work at future point in time and return the future for that
     * @param delay - delay before execution
     * @param unit - time unit for the delay
     * @param task - the closure to execute
     * @return ScheduledFuture for the scheduled task
     */
    ScheduledFuture scheduleExecution(int delay, TimeUnit unit, Closure task) {
        getScheduledExecutor().schedule(task, delay, unit)
    }


    /**
     * Orderly shutdown of the executor - complete all existing but take no new tasks
     */
    void shutdown() {
        this.executor?.shutdown()
        if (this.scheduledExecutor != null && this.scheduledExecutor != defaultScheduledExecutor) {
            this.scheduledExecutor.shutdown()
        }
    }
}