package org.softwood.dataflow

import groovy.util.logging.Slf4j
import org.softwood.pool.ExecutorPool
import org.softwood.pool.ExecutorPoolFactory
import org.softwood.promise.core.PromisePoolContext
import org.softwood.gstream.Gstream

import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.Function
import java.util.function.Supplier

/**
 * Factory for creating dataflow tasks and variables with a configurable executor
 */
@Slf4j
class DataflowFactory {

    private final ExecutorPool pool

    /*DataflowFactory() {
        this.pool = new ConcurrentPool("dataflow")
    }*/

    /**
     * Create factory using current context pool.
     */
    DataflowFactory() {
        this.pool = PromisePoolContext.getCurrentPool()
    }

    DataflowFactory(ExecutorPool  pool) {
        this.pool = pool
    }

    //wrap a user provided executor service if really required.  better to use the default virtual threads model though
    DataflowFactory(ExecutorService executor) {
        this.pool = ExecutorPoolFactory.wrap (executor)
    }

    <T> DataflowVariable<T> createDataflowVariable() {
        // Use the same pool for DFV so its async listeners share this pool
        return new DataflowVariable<T>(pool)
    }

    <T> DataflowVariable<T> createDataflowVariable(T initialValue) {
        def variable = new DataflowVariable<T>(pool)
        variable.bind(initialValue)
        return variable
    }

    <T> DataflowVariable<T> task(Closure<T> task) {
        DataflowVariable<T> variable = new DataflowVariable<T>(pool)
        // 1.2.0 (DF3): keep the Future so DataflowPromise.cancel(true) can interrupt the running work.
        // 1.2.0 (DF6): a variable already completed elsewhere (cancelled, timed out) must not turn this
        // task's own late completion into a second exception that is lost inside the discarded Future.
        def future = pool.executor.submit {
            try {
                T result = task.call()
                variable.setValue(result)
            } catch (Throwable e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt()
                try {
                    if (variable.isBound()) {
                        log.debug("Task(): finished after its variable was already completed: {}", e.toString())
                    } else {
                        variable.bindError(e)
                        log.error("Task(): Task execution failed", e)
                    }
                } catch (IllegalStateException alreadyBound) {
                    log.debug("Task(): variable completed concurrently: {}", alreadyBound.message)
                }
            }
        }
        variable.attachTask(future)
        return variable
    }

    // -------------------------------------------------------------------------
    // New Java Lambda overloads
    // -------------------------------------------------------------------------

    /**
     * GCU 1.2.2 (DF15): the one path behind the Callable, Supplier, Runnable and Function overloads,
     * given the treatment task(Closure) got in 1.2.0. The Future is kept on the variable, so
     * DataflowPromise.cancel(true) interrupts the work (DF3); and work that ends after its variable was
     * completed elsewhere is logged at debug where it used to throw a second exception into a Future
     * nobody held (DF6).
     *
     * Like task(Closure) this submits to the pool's raw executor, not through ConcurrentPool.execute:
     * see DF10 in the dataflow chapter for why that is left as it is.
     */
    private <T> DataflowVariable<T> submitWork(String kind, Callable<T> work) {
        DataflowVariable<T> variable = new DataflowVariable<T>(pool)
        def future = pool.executor.submit({
            try {
                variable.setValue(work.call())
            } catch (Throwable e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt()
                try {
                    if (variable.isBound()) {
                        log.debug("DataflowFactory.task({}): finished after its variable was already completed: {}", kind, e.toString())
                    } else {
                        variable.bindError(e)
                        log.error("DataflowFactory.task(${kind}): execution failed", e)
                    }
                } catch (IllegalStateException alreadyBound) {
                    log.debug("DataflowFactory.task({}): variable completed concurrently: {}", kind, alreadyBound.message)
                }
            }
        } as Runnable)
        variable.attachTask(future)
        return variable
    }

    /**
     * Execute a Callable asynchronously.
     */
    def <T> DataflowVariable<T> task(Callable<T> callable) {
        return submitWork('Callable', callable)
    }

    /**
     * Execute a Supplier asynchronously.
     */
    def <T> DataflowVariable<T> task(Supplier<T> supplier) {
        return submitWork('Supplier', { supplier.get() } as Callable<T>)
    }

    /**
     * Execute a Runnable asynchronously.
     * Produces a DataflowVariable<Void>.
     */
    DataflowVariable<Void> task(Runnable runnable) {
        return submitWork('Runnable', { runnable.run(); return null } as Callable<Void>)
    }

    /**
     * Execute a Function<T,R> asynchronously with a provided input.
     */
    def <T, R> DataflowVariable<R> task(Function<T, R> fn, T input) {
        return submitWork('Function', { fn.apply(input) } as Callable<R>)
    }

    ExecutorService getExecutor() {
        return pool.executor
    }

    /**
     * Get the pool used by this factory.
     */
    ExecutorPool getPool() {
        return pool
    }

    // -------------------------------------------------------------------------
    // Timeout Support
    // -------------------------------------------------------------------------

    /**
     * Execute a Callable with a timeout.
     * <p>
     * If the task doesn't complete within the timeout, the returned DataflowVariable
     * is bound with a {@link TimeoutException} and the underlying task is cancelled.
     * </p>
     *
     * <p>Example usage:</p>
     * <pre>
     * def result = factory.taskWithTimeout({ fetchFromSlowAPI() }, 5, TimeUnit.SECONDS)
     * try {
     *     println result.get()
     * } catch (Exception e) {
     *     if (result.error instanceof TimeoutException) {
     *         log.warn("API call timed out")
     *     }
     * }
     * </pre>
     *
     * @param callable the task to execute
     * @param timeout timeout value
     * @param unit timeout unit
     * @return DataflowVariable that will contain result or timeout error
     */
    <T> DataflowVariable<T> taskWithTimeout(Callable<T> callable, long timeout, TimeUnit unit) {
        DataflowVariable<T> variable = new DataflowVariable<T>(pool)

        // Use pool.executeWithTimeout() which uses the shared ScheduledExecutor for the timer
        // rather than blocking a second pool thread. Cleaner and avoids thread starvation
        // on fixed platform thread pools.
        CompletableFuture cf = pool.executeWithTimeout(callable, timeout, unit)
        cf.whenComplete { T result, Throwable err ->
            if (err != null) {
                Throwable actual = (err instanceof java.util.concurrent.CompletionException && err.cause != null)
                        ? err.cause : err
                if (actual instanceof TimeoutException) {
                    // Explicit timeout - bind as error so callers can inspect result.error
                    log.warn("DataflowFactory.taskWithTimeout: Task timed out after ${timeout} ${unit}")
                    variable.bindError(actual)
                } else if (actual instanceof CancellationException) {
                    // The underlying task was cancelled by the timeout timer (future.cancel(true))
                    // before the TimeoutException could win the CF race. Treat as timeout.
                    log.warn("DataflowFactory.taskWithTimeout: Task cancelled by timeout after ${timeout} ${unit}")
                    variable.bindError(new TimeoutException("Task timed out after ${timeout} ${unit}"))
                } else {
                    variable.bindError(actual)
                    log.error("DataflowFactory.taskWithTimeout: execution failed", actual)
                }
            } else {
                variable.setValue(result)
            }
        }

        return variable
    }

    /**
     * Execute a Closure with a timeout.
     *
     * @param closure the closure to execute
     * @param timeout timeout value
     * @param unit timeout unit
     * @return DataflowVariable that will contain result or timeout error
     */
    <T> DataflowVariable<T> taskWithTimeout(Closure<T> closure, long timeout, TimeUnit unit) {
        return taskWithTimeout({ -> closure.call() } as Callable<T>, timeout, unit)
    }

    // -------------------------------------------------------------------------
    // Gstream Integration
    // -------------------------------------------------------------------------

    /**
     * Execute multiple tasks and return results as a Gstream.
     * <p>
     * This method waits for ALL tasks to complete before returning the stream.
     * Results are returned in the same order as the input tasks.
     * </p>
     *
     * <p>Example usage:</p>
     * <pre>
     * def results = factory.taskStream(
     *     { fetchUser(id) },
     *     { fetchOrders(id) },
     *     { fetchPreferences(id) }
     * ).map { enrich(it) }
     *  .filter { it != null }
     *  .toList()
     * </pre>
     *
     * @param tasks closures to execute concurrently
     * @return Gstream of results in submission order
     */
    <T> Gstream<T> taskStream(Closure<T>... tasks) {
        if (tasks == null || tasks.length == 0) {
            return Gstream.empty()
        }

        // Execute all tasks
        def variables = tasks.collect { task(it) }

        // Convert to CompletableFutures
        def futures = variables.collect { it.toFuture() }

        // Wait for all to complete, then stream results
        return Gstream.from(
            CompletableFuture.allOf(futures as CompletableFuture[])
                .thenApply {
                    futures.collect { it.join() }
                }
                .join()
        )
    }

    /**
     * Execute tasks and stream results as they complete (unordered).
     * <p>
     * Unlike {@link #taskStream}, this method returns results in completion order,
     * not submission order. Useful when you want to process results as soon as
     * they're available.
     * </p>
     *
     * <p><b>Note on errors:</b> Tasks that fail with an error are silently skipped -
     * their result does not appear in the returned stream. If you need error visibility,
     * use {@link #taskStream} (which propagates errors via CompletableFuture.allOf)
     * or register {@code whenError} handlers on individual DFVs.
     * </p>
     *
     * @param tasks closures to execute concurrently
     * @return Gstream of successful results in completion order; failed tasks are omitted
     */
    <T> Gstream<T> taskStreamUnordered(Closure<T>... tasks) {
        if (tasks == null || tasks.length == 0) {
            return Gstream.empty()
        }

        def variables = tasks.collect { task(it) }

        // Collect results as they complete
        List<T> results = Collections.synchronizedList(new ArrayList<T>())

        variables.each { dfv ->
            dfv.whenAvailable { T value ->
                results.add(value)
            }
        }

        // Wait for all to complete
        variables*.toFuture()*.join()

        return Gstream.from(results)
    }
}