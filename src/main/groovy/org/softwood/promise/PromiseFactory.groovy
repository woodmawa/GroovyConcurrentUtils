package org.softwood.promise

import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.function.Function
import java.util.function.Supplier

/**
 * Factory interface for creating promises.
 *
 * <p>This allows the Promise API to be implementation-agnostic.
 * For example, you can swap a Dataflow-based implementation for
 * another implementation without changing user code.</p>
 *
 * <h3>Creation responsibilities</h3>
 * <ul>
 *   <li>Create new empty promises.</li>
 *   <li>Create already completed promises.</li>
 *   <li>Run async tasks and return promises.</li>
 *   <li>Adapt foreign async primitives into promises.</li>
 * </ul>
 */
interface PromiseFactory {

    /**
     * Create a new, uncompleted promise.
     *
     * @param <T> promise type parameter
     * @return a new Promise
     */
    <T> Promise<T> createPromise()

    /**
     * Create a new promise already completed with a value.
     *
     * @param value completion value
     * @param <T> promise type parameter
     * @return completed Promise
     */
    <T> Promise<T> createPromise(T value)

    /**
     * Execute a closure asynchronously and complete a promise with its result.
     *
     * @param task closure to run async
     * @param <T> result type
     * @return promise bound to task's eventual result
     */

    /**
     * Create a promise already failed with the supplied error.
     *
     * @param cause cause of failure
     * @return failed Promise
     */
    <T> Promise<T> createFailedPromise(Throwable cause)

    <T> Promise<T> executeAsync(Closure<T> task)

    //enable for java lambda expressions

    <T> Promise<T> executeAsync(Callable<T> task)

    Promise<Void> executeAsync(Runnable task)

    <T> Promise<T> executeAsync(Supplier<T> task)

    <T, R> Promise<R> executeAsync(Function<T, R> fn, T input)


    /**
     * Create a new Promise that adopts completion of a CompletableFuture.
     *
     * @param future source future
     * @param <T> type of completion value
     * @return a new Promise wired to the future
     */
    <T> Promise<T> from(CompletableFuture<T> future)

    /**
     * Create a new Promise that adopts completion of another Promise.
     *
     * @param otherPromise source promise
     * @param <T> type of completion value
     * @return a new Promise wired to the other promise
     */
    <T> Promise<T> from(Promise<T> otherPromise)
}
