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
        pool.executor.submit {
            try {
                T result = task.call()
                variable.setValue(result)
            } catch (Throwable e) {
                variable.bindError(e)
                log.error("Task(): Task execution failed", e)
            }
        }
        return variable
    }

    // -------------------------------------------------------------------------
    // New Java Lambda overloads
    // -------------------------------------------------------------------------

    /**
     * Execute a Callable asynchronously.
     */
    def <T> DataflowVariable<T> task(Callable<T> callable) {
        DataflowVariable<T> variable = new DataflowVariable<T>(pool)
        pool.executor.submit {
            try {
                T result = callable.call()
                variable.setValue(result)
            } catch (Throwable e) {
                variable.bindError(e)
                log.error("DataflowFactory.task(Callable): execution failed", e)
            }
        }
        return variable
    }

    /**
     * Execute a Supplier asynchronously.
     */
    def <T> DataflowVariable<T> task(Supplier<T> supplier) {
        DataflowVariable<T> variable = new DataflowVariable<T>(pool)
        pool.executor.submit {
            try {
                T result = supplier.get()
                variable.setValue(result)
            } catch (Throwable e) {
                variable.bindError(e)
                log.error("DataflowFactory.task(Supplier): execution failed", e)
            }
        }
        return variable
    }

    /**
     * Execute a Runnable asynchronously.
     * Produces a DataflowVariable<Void>.
     */
    DataflowVariable<Void> task(Runnable runnable) {
        DataflowVariable<Void> variable = new DataflowVariable<Void>(pool)
        pool.executor.submit {
            try {
                runnable.run()
                variable.setValue(null)
            } catch (Throwable e) {
                variable.bindError(e)
                log.error("DataflowFactory.task(Runnable): execution failed", e)
            }
        }
        return variable
    }

    /**
     * Execute a Function<T,R> asynchronously with a provided input.
     */
    def <T, R> DataflowVariable<R> task(Function<T, R> fn, T input) {
        DataflowVariable<R> variable = new DataflowVariable<R>(pool)
        pool.executor.submit {
            try {
                R result = fn.apply(input)
                variable.setValue(result)
            } catch (Throwable e) {
                variable.bindError(e)
                log.error("DataflowFactory.task(Function,input): execution failed", e)
            }
        }
        return variable
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

        def future = pool.executor.submit(callable)

        pool.executor.submit {
            try {
                T result = future.get(timeout, unit)
                variable.setValue(result)
            } catch (TimeoutException e) {
                future.cancel(true)
                variable.bindError(new TimeoutException("Task timed out after ${timeout} ${unit}"))
                log.warn("DataflowFactory.taskWithTimeout: Task timed out after ${timeout} ${unit}")
            } catch (CancellationException e) {
                variable.bindCancelled(e)
            } catch (Throwable e) {
                variable.bindError(e)
                log.error("DataflowFactory.taskWithTimeout: execution failed", e)
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
     * @param tasks closures to execute concurrently
     * @return Gstream of results in completion order
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