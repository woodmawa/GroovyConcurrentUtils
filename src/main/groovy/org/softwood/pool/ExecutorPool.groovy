package org.softwood.pool

import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Unified pool interface that provides both basic execution
 * and scheduled execution capabilities.
 *
 * This is the ONLY pool interface - no need for WorkerPool.
 * It can be easily mocked and injected throughout the stack.
 *
 * <h3>Design Goals</h3>
 * <ul>
 *   <li>Single contract for all pool operations</li>
 *   <li>Easy to mock for testing</li>
 *   <li>Supports both sync and async execution patterns</li>
 *   <li>Compatible with standard Java executor semantics</li>
 * </ul>
 */
interface ExecutorPool {


    // -------------------------------------------------------------------------
    // Advanced Execution
    // -------------------------------------------------------------------------

    ExecutorService getExecutor()
    ScheduledExecutorService getScheduledExecutor()

    // --- Primary Groovy Closure support ---
    CompletableFuture execute(Closure task)
    CompletableFuture execute(Closure task, Object[] args)
    boolean tryExecute(Closure task)

    // --- NEW Java-friendly overloads ---
    CompletableFuture execute(Callable task)
    CompletableFuture execute(Runnable task)   // runnable returning no result

    // -------------------------------------------------------------------------
    // Scheduled Execution
    // -------------------------------------------------------------------------

    ScheduledFuture scheduleExecution(int delay, TimeUnit unit, Closure task)
    ScheduledFuture scheduleWithFixedDelay(int initialDelay, int delay, TimeUnit unit, Closure task)
    ScheduledFuture scheduleAtFixedRate(int initialDelay, int period, TimeUnit unit, Closure task)

    // -------------------------------------------------------------------------
    // Lifecycle & Introspection
    // -------------------------------------------------------------------------

    boolean isClosed()
    String getName()
    boolean isUsingVirtualThreads()
}