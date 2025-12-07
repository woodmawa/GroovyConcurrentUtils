package org.softwood.promise

import groovy.util.logging.Slf4j
import org.softwood.dataflow.DataflowVariable
import org.softwood.pool.ExecutorPool
import org.softwood.promise.core.PromiseConfiguration
import org.softwood.promise.core.PromisePoolContext
import org.softwood.promise.core.dataflow.DataflowPromiseFactory

import java.util.concurrent.CompletableFuture
import java.util.function.Supplier

/**
 * Static facade and primary end-user API for creating and working with Promises.
 *
 * <p>This class hides the implementation details of the underlying asynchronous
 * engine (Dataflow, Vert.x, CompletableFuture, etc.). All Promise creation is
 * delegated to the active {@link PromiseFactory} obtained from
 * {@link PromiseConfiguration}.</p>
 *
 * <h2>Design Goals</h2>
 * <ul>
 *   <li>Provide a simple, Groovy-friendly API for working with Promises.</li>
 *   <li>Expose every capability promised by the {@link PromiseFactory} interface.</li>
 *   <li>Make all operations implementation-pluggable at runtime.</li>
 *   <li>Offer convenience overloads for common use cases.</li>
 * </ul>
 *
 * <h2>Examples</h2>
 *
 * <pre>
 * // Basic usage
 * def p = Promises.newPromise()
 * p.accept(42)
 * p.then { it * 2 }.onComplete { println it }
 *
 * // Async work
 * Promises.async {
 *     expensiveOperation()
 * }.onComplete { println "done" }
 *
 * // Failed promise
 * def error = Promises.failed(new RuntimeException("boom"))
 *
 * // With a specific implementation
 * def dfPromise = Promises.newPromise(PromiseImplementation.DATAFLOW, 123)
 * </pre>
 *
 * @author Will Woodman
 * @since 2025
 */
@Slf4j
class Promises {

    /**
    * Execute a closure with a specific pool active.
    * All promises created within this scope will use the given pool.
    *
    * Example:
    * <pre>
    * def customPool = new ConcurrentPool(4)
            * Promises.withPool(customPool) {
        *     def p = Promises.async { ... }  // uses customPool
                *     return p.get()
                * }
            * </pre>
     */
    static <T> T withPool(ExecutorPool pool, Closure<T> closure) {
        return PromisePoolContext.withPool(pool, closure)
    }

    /**
     * Set the default pool used by all promises when no thread-local pool is set.
     * This is a global setting that affects all threads.
     */
    static void setDefaultPool(ExecutorPool pool) {
        PromisePoolContext.setDefaultPool(pool)
    }

    /**
     * Get the current pool being used in this context.
     */
    static ExecutorPool getCurrentPool() {
        return PromisePoolContext.getCurrentPool()
    }

    // -------------------------------------------------------------------------
    // Basic creation
    // -------------------------------------------------------------------------

    /**
     * Create a new, uncompleted promise using the default implementation.
     *
     * @param <T> value type
     * @return new empty Promise
     */
    static <T> Promise<T> newPromise() {
        return PromiseConfiguration.getFactory().createPromise()
    }

    /**
     * Create a new promise already completed with a value using
     * the default implementation.
     *
     * @param <T> value type
     * @param value initial completion value
     * @return completed Promise
     */
    static <T> Promise<T> newPromise(T value) {
        return PromiseConfiguration.getFactory().createPromise(value)
    }

    /**
     * Create a new, uncompleted promise using a specific implementation.
     *
     * @param impl implementation key
     * @param <T> value type
     * @return new empty Promise
     */
    static <T> Promise<T> newPromise(PromiseImplementation impl) {
        return PromiseConfiguration.getFactory(impl).createPromise()
    }

    /**
     * Create a new promise already completed with a value using
     * a specific implementation.
     *
     * @param impl implementation key
     * @param value completion value
     * @param <T> value type
     * @return completed Promise
     */
    static <T> Promise<T> newPromise(PromiseImplementation impl, T value) {
        return PromiseConfiguration.getFactory(impl).createPromise(value)
    }

    // -------------------------------------------------------------------------
    // Failed promises
    // -------------------------------------------------------------------------

    /**
     * Create a failed Promise using the default implementation.
     *
     * @param error failure cause
     * @param <T> type parameter
     * @return failed Promise
     */
    static <T> Promise<T> failed(Throwable error) {
        return PromiseConfiguration.getFactory().createFailedPromise(error)
    }

    /**
     * Create a failed Promise using a specific implementation.
     *
     * @param impl implementation key
     * @param error failure cause
     * @param <T> type parameter
     * @return failed Promise
     */
    static <T> Promise<T> failed(PromiseImplementation impl, Throwable error) {
        return PromiseConfiguration.getFactory(impl).createFailedPromise(error)
    }

    /**
     * Convenience alias for {@link #failed(Throwable)}.
     *
     * @param error throwable to wrap
     * @param <T> type parameter
     * @return failed Promise
     */
    static <T> Promise<T> fromError(Throwable error) {
        return failed(error)
    }

    // -------------------------------------------------------------------------
    // Async execution (Closure)
    // -------------------------------------------------------------------------

    /**
     * Execute a Groovy closure asynchronously using the default implementation.
     *
     * <p>The provided closure is executed once on an async executor, and the
     * returned Promise completes with either the closure result or the thrown
     * exception.</p>
     *
     * @param task closure to run async
     * @param <T> return type
     * @return Promise bound to task's eventual result
     */
    static <T> Promise<T> async(Closure<T> task) {
        return PromiseConfiguration.getFactory().executeAsync(task)
    }

    /**
     * Execute a Groovy closure asynchronously using a specific implementation.
     *
     * @param impl implementation key
     * @param task closure to run async
     * @param <T> return type
     * @return Promise bound to result
     */
    static <T> Promise<T> async(PromiseImplementation impl, Closure<T> task) {
        return PromiseConfiguration.getFactory(impl).executeAsync(task)
    }

    // -------------------------------------------------------------------------
    // Async execution (Supplier<T>)
    // -------------------------------------------------------------------------

    /**
     * Execute a {@link Supplier} asynchronously using the default implementation.
     *
     * <p>This is equivalent to creating a new Promise and calling
     * {@link Promise#accept(java.util.function.Supplier)}.</p>
     *
     * @param supplier supplier to compute the value
     * @param <T> return type
     * @return Promise bound to supplier's result
     */
    static <T> Promise<T> async(Supplier<T> supplier) {
        Promise<T> p = newPromise()
        return p.accept(supplier)
    }

    /**
     * Execute a {@link Supplier} asynchronously using a specific implementation.
     *
     * @param impl implementation key
     * @param supplier supplier to compute the value
     * @param <T> return type
     * @return Promise bound to supplier's result
     */
    static <T> Promise<T> async(PromiseImplementation impl, Supplier<T> supplier) {
        Promise<T> p = newPromise(impl)
        return p.accept(supplier)
    }

    // -------------------------------------------------------------------------
    // Adapters
    // -------------------------------------------------------------------------

    /**
     * Adapt a {@link CompletableFuture} into a Promise using the default implementation.
     *
     * @param future source future
     * @param <T> value type
     * @return new Promise wired to future completion
     */
    static <T> Promise<T> from(CompletableFuture<T> future) {
        return PromiseConfiguration.getFactory().from(future)
    }

    /**
     * Adapt another Promise into a new Promise using the default implementation.
     * Useful when mixing implementations.
     *
     * @param otherPromise source Promise
     * @param <T> value type
     * @return new Promise wired to the otherPromise completion
     */
    static <T> Promise<T> from(Promise<T> otherPromise) {
        return PromiseConfiguration.getFactory().from(otherPromise)
    }

    /**
     * static helper method  - will check the result and ensure that the returned value is a
     * promise and that we have no leakage of implementation abstractions for consumers
     *
     *
     * @param result
     * @return
     */
    static Promise<?> ensurePromise(Object result) {
        // 1. Already a Promise? Good.
        if (result instanceof Promise) {
            return (Promise<?>) result
        }

        // 2. if result is a DataflowVariable? Adapt via DataflowPromiseFactory / DATAFLOW impl
        if (result instanceof org.softwood.dataflow.DataflowVariable) {
            // Use the DATAFLOW implementation factory to wrap it
            def implFactory = PromiseConfiguration.getFactory(PromiseImplementation.DATAFLOW)
            if (implFactory instanceof DataflowPromiseFactory) {
                DataflowPromiseFactory dfPromFactory = (DataflowPromiseFactory) implFactory
                return dfPromFactory.wrapDataflowVariable((DataflowVariable) result)
            }

            // Fallback: generic wrapping
            Promise promise = newPromise()
            ((DataflowVariable) result).whenAvailable { v -> promise.accept(v) }
            ((DataflowVariable) result).whenError { e -> promise.fail(e) }
            return promise
        }

        // 3. CompletableFuture? Use existing adapter
        if (result instanceof CompletableFuture) {
            return from((CompletableFuture) result)
        }

        // 4. Plain value? Wrap in completed Promise
        if (result != null) {
            return newPromise(result)
        }

        // 5. null? Failed Promise
        Promise p = newPromise()
        p.fail(new NullPointerException("Action returned null"))
        return p
    }

    /**
     * Wait for any of the given promises to complete successfully.
     *
     * <p>Returns a Promise that completes with the value of the first
     * Promise in the iterable to complete successfully.
     * If all promises fail, this promise will fail with an aggregation
     * of errors or the error of the last promise to fail.</p>
     *
     * @param promises iterable of promises to wait on
     * @param <T> value type
     * @return new Promise
     */
    static <T> Promise<T> any(Iterable<Promise<T>> promises) {
        Promise<T> resultPromise = newPromise()
        def errorCount = 0
        def totalPromises = 0
        // Simple list to collect errors, but typically you'd want a more
        // robust structure or just the last error for 'any' failure.
        def errors = []

        // 1. Convert to a list to count and iterate safely
        def promiseList = promises.promises.findAll { it != null }  // ✅ Filter out nulls!
        totalPromises = promiseList.size()

        if (totalPromises == 0) {
            // Resolve immediately if there are no promises
            return newPromise(null) // or newPromise(T) if T is non-void
        }

        // 2. Iterate and register completion handlers
        promiseList.each { Promise<T> p ->
            p.onComplete { T v ->
                // Fulfills immediately on first success
                resultPromise.accept(v)
            }

            p.onError { Throwable e ->
                errors.add(e)
                errorCount++

                // If all promises have failed, fail the resultPromise
                if (errorCount == totalPromises) {
                    // For simplicity, we'll fail with a generic exception wrapping the errors.
                    // A production version might use a specific 'AggregateException'.
                    resultPromise.fail(new RuntimeException("All promises failed: " + errors))
                }
            }
        }

        return resultPromise
    }

    /**
     * Wait for all of the given promises to complete successfully.
     *
     * <p>Returns a Promise that completes with a List of all successful
     * values in the same order as the input. If any promise fails,
     * the resulting promise fails immediately with that promise's error.</p>
     *
     * @param promises iterable of promises to wait on
     * @param <T> value type
     * @return new Promise<List<T>>
     */
    static <T> Promise<List<T>> all(Iterable<Promise<T>> promises) {
        Promise<List<T>> resultPromise = newPromise()

        // 1. Convert to a list and set up result storage
        def promiseList = promises.findAll { it != null }  // ✅ Filter out nulls!
        def totalPromises = promiseList.size()
        def results = new ArrayList(Collections.nCopies(totalPromises, null))
        def successCount = 0

        if (totalPromises == 0) {
            // Resolve immediately if there are no promises
            return newPromise(Collections.emptyList())
        }

        // 2. Register failure and success handlers
        promiseList.eachWithIndex { Promise<T> p, int index ->
            // Failure: Fail the result immediately
            p.onError { Throwable e ->
                resultPromise.fail(e)
            }

            // Success: Store result and check if all are complete
            p.onComplete { T v ->
                results[index] = v // Store result in the correct position
                successCount++

                // If all have succeeded, fulfill the resultPromise
                if (successCount == totalPromises) {
                    resultPromise.accept(results)
                }
            }
        }

        return resultPromise
    }

    //alias for all
    static <T> Promise<List<T>> allOf(Iterable<Promise<T>> promises) {
        return all(promises)
    }
}
