package org.softwood.promise

import groovy.util.logging.Slf4j
import org.softwood.dataflow.DataflowVariable
import org.softwood.pool.ExecutorPool
import org.softwood.promise.core.PromiseConfiguration
import org.softwood.promise.core.PromisePoolContext
import org.softwood.promise.core.dataflow.DataflowPromiseFactory

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
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
        def promiseList = promises.findAll { it != null }  // ✅ Filter out nulls!
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

    // =========================================================================
    // TIER 1 Enhancements - Timeout with static helper
    // =========================================================================

    /**
     * Execute a task with a timeout. Returns the task result if it completes
     * in time, otherwise fails with TimeoutException.
     *
     * @param timeout timeout duration
     * @param unit time unit
     * @param task task to execute
     * @return promise that times out
     */
    static <T> Promise<T> timeout(long timeout, TimeUnit unit, Closure<T> task) {
        return async(task).timeout(timeout, unit)
    }

    // =========================================================================
    // TIER 2 Enhancements - Delay Operations
    // =========================================================================

    /**
     * Returns a promise that completes after the specified delay.
     * Useful for scheduling or rate limiting.
     *
     * @param delay delay duration
     * @param unit time unit
     * @return promise that completes after delay
     */
    static Promise<Void> delay(long delay, TimeUnit unit) {
        Promise<Void> promise = newPromise()
        
        // Get current pool's executor
        def pool = getCurrentPool()
        def executor = pool ? pool.getExecutor() : java.util.concurrent.ForkJoinPool.commonPool()
        
        CompletableFuture.runAsync({
            try {
                unit.sleep(delay)
                promise.accept(null)
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt()
                promise.fail(e)
            }
        }, executor)
        
        return promise
    }

    /**
     * Execute a task after a delay.
     *
     * @param delay delay duration
     * @param unit time unit  
     * @param task task to execute after delay
     * @return promise that completes with task result after delay
     */
    static <T> Promise<T> delay(long delay, TimeUnit unit, Closure<T> task) {
        return delay(delay, unit).then { task.call() }
    }

    // =========================================================================
    // TIER 2 Enhancements - Zip (Combining)
    // =========================================================================

    /**
     * Combine two promises using a combining function.
     * The result promise completes when both input promises complete successfully.
     * If either fails, the result fails.
     *
     * @param p1 first promise
     * @param p2 second promise
     * @param combiner function to combine the two values
     * @return combined promise
     */
    static <T, U, R> Promise<R> zip(Promise<T> p1, Promise<U> p2, 
                                      java.util.function.BiFunction<T, U, R> combiner) {
        return p1.zip(p2, combiner)
    }

    // =========================================================================
    // TIER 2 Enhancements - Retry Logic
    // =========================================================================

    /**
     * Execute a task with retry logic. Retries on failure up to maxAttempts.
     * No delay between retries.
     *
     * @param maxAttempts maximum number of attempts (including initial try)
     * @param task task to execute
     * @return promise that succeeds on first success or fails after all attempts
     */
    static <T> Promise<T> retry(int maxAttempts, Closure<T> task) {
        return retry(maxAttempts, 0, TimeUnit.MILLISECONDS, task)
    }

    /**
     * Execute a task with retry logic and delay between attempts.
     *
     * @param maxAttempts maximum number of attempts (including initial try)
     * @param delayBetweenAttempts delay between retry attempts
     * @param unit time unit for delay
     * @param task task to execute
     * @return promise that succeeds on first success or fails after all attempts
     */
    static <T> Promise<T> retry(int maxAttempts, long delayBetweenAttempts, TimeUnit unit, Closure<T> task) {
        if (maxAttempts < 1) {
            return failed(new IllegalArgumentException("maxAttempts must be at least 1"))
        }

        Promise<T> resultPromise = newPromise()
        def attemptCount = new java.util.concurrent.atomic.AtomicInteger(0)
        def errors = Collections.synchronizedList(new ArrayList<Throwable>())

        Closure<Void> attemptTask
        attemptTask = {
            def currentAttempt = attemptCount.incrementAndGet()
            
            async(task)
                .onComplete { T value ->
                    resultPromise.accept(value)
                }
                .onError { Throwable error ->
                    errors.add(error)
                    
                    if (currentAttempt >= maxAttempts) {
                        // All attempts failed
                        def aggregateError = new RuntimeException(
                            "Task failed after ${maxAttempts} attempts. Errors: ${errors}"
                        )
                        errors.each { aggregateError.addSuppressed(it) }
                        resultPromise.fail(aggregateError)
                    } else {
                        // Retry after delay
                        if (delayBetweenAttempts > 0) {
                            delay(delayBetweenAttempts, unit).onComplete { attemptTask.call() }
                        } else {
                            attemptTask.call()
                        }
                    }
                }
        }

        attemptTask.call()
        return resultPromise
    }

    // =========================================================================
    // TIER 3 Enhancements - Race
    // =========================================================================

    /**
     * Returns a promise that completes when the first promise completes
     * (either success or failure). Unlike {@link #any}, this doesn't wait
     * for a success - it returns the first completion of any kind.
     *
     * @param promises iterable of promises to race
     * @return promise that completes with first result
     */
    static <T> Promise<T> race(Iterable<Promise<T>> promises) {
        Promise<T> resultPromise = newPromise()
        def completed = new java.util.concurrent.atomic.AtomicBoolean(false)

        def promiseList = promises.findAll { it != null }
        
        if (promiseList.isEmpty()) {
            return newPromise(null)
        }

        promiseList.each { Promise<T> p ->
            p.onComplete { T v ->
                if (completed.compareAndSet(false, true)) {
                    resultPromise.accept(v)
                }
            }

            p.onError { Throwable e ->
                if (completed.compareAndSet(false, true)) {
                    resultPromise.fail(e)
                }
            }
        }

        return resultPromise
    }

    // =========================================================================
    // TIER 3 Enhancements - Sequential Operations
    // =========================================================================

    /**
     * Transform a collection of items into promises sequentially.
     * Each promise is started only after the previous one completes.
     * This is useful when you need to control concurrency or maintain order.
     *
     * @param items collection to transform
     * @param mapper function that creates a promise for each item
     * @return promise with list of results in order
     */
    static <T, R> Promise<List<R>> traverse(Iterable<T> items, 
                                            java.util.function.Function<T, Promise<R>> mapper) {
        def itemList = items?.toList() ?: []
        
        if (itemList.isEmpty()) {
            return newPromise(Collections.emptyList())
        }

        Promise<List<R>> resultPromise = newPromise()
        def results = Collections.synchronizedList(new ArrayList<R>())
        def index = new java.util.concurrent.atomic.AtomicInteger(0)

        Closure<Void> processNext
        processNext = {
            def currentIndex = index.getAndIncrement()
            
            if (currentIndex >= itemList.size()) {
                // All done
                resultPromise.accept(new ArrayList<R>(results))
                return
            }

            T item = itemList[currentIndex]
            Promise<R> promise = mapper.apply(item)

            promise
                .onComplete { R value ->
                    results.add(value)
                    processNext.call()
                }
                .onError { Throwable error ->
                    resultPromise.fail(error)
                }
        }

        processNext.call()
        return resultPromise
    }

    /**
     * Execute an iterable of promises sequentially (one after another).
     * Unlike {@link #all} which runs in parallel, this waits for each
     * promise to complete before starting the next.
     *
     * @param promises iterable of promises
     * @return promise with list of results in order
     */
    static <T> Promise<List<T>> sequence(Iterable<Promise<T>> promises) {
        def promiseList = promises?.findAll { it != null }?.toList() ?: []
        
        if (promiseList.isEmpty()) {
            return newPromise(Collections.emptyList())
        }

        Promise<List<T>> resultPromise = newPromise()
        def results = Collections.synchronizedList(new ArrayList<T>())
        def index = new java.util.concurrent.atomic.AtomicInteger(0)

        Closure<Void> processNext
        processNext = {
            def currentIndex = index.getAndIncrement()
            
            if (currentIndex >= promiseList.size()) {
                // All done
                resultPromise.accept(new ArrayList<T>(results))
                return
            }

            Promise<T> promise = promiseList[currentIndex]

            promise
                .onComplete { T value ->
                    results.add(value)
                    processNext.call()
                }
                .onError { Throwable error ->
                    resultPromise.fail(error)
                }
        }

        processNext.call()
        return resultPromise
    }
}
