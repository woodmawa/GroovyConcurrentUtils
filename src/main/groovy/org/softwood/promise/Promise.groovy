package org.softwood.promise

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Supplier

/**
 * Core Promise interface that all implementations must follow.
 *
 * <p>A Promise represents a single-assignment, asynchronously-completed value
 * with fluent continuation and recovery operations.</p>
 *
 * <h3>Key semantics</h3>
 * <ul>
 *   <li><b>Single assignment:</b> a promise is completed once (value or error).</li>
 *   <li><b>Fluent chaining:</b> continuations return new promises.</li>
 *   <li><b>Implementation-pluggable:</b> Promises are created via a PromiseFactory.</li>
 * </ul>
 *
 * <h3>Completing a promise</h3>
 * You can complete a promise with:
 * <ul>
 *   <li>a direct value via {@code #accept(Object)}</li>
 *   <li>a lazy Supplier via {@code #accept(Supplier)}</li>
 *   <li>a CompletableFuture via {@code #accept(CompletableFuture)}</li>
 *   <li>another Promise via {@code #accept(Promise)}</li>
 * </ul>
 *
 * <h3>Observing and transforming</h3>
 * Use {@code #onComplete(Consumer)} to observe success,
 * {@code #onError(Consumer)} to observe error,
 * {@code #then(Function)} to transform the value, and
 * {@code #recover(Function)} to transform/replace an error into a value.
 *
 * @param <T> type of the promised value
 */
interface Promise<T> {

    /**
     * Complete this promise with an already computed value.
     *
     * @param value the value to bind to this promise
     * @return this promise for fluent calls
     */
    Promise<T> accept(T value)

    /**
     * Complete this promise by calling the supplier exactly once at accept-time.
     *
     * @param supplier computation producing a value
     * @return this promise for fluent calls
     */
    Promise<T> accept(Supplier<T> supplier)

    /**
     * Complete this promise when the future completes.
     * <p>On success the promise is bound to the future's value.
     * On failure the promise is completed with that error.</p>
     *
     * @param future source future
     * @return this promise
     */
    Promise<T> accept(CompletableFuture<T> future)

    /**
     * Complete this promise by adopting the completion of another promise.
     * <p>Success values are forwarded; errors are forwarded as errors.</p>
     *
     * @param otherPromise source promise
     * @return this promise
     */
    Promise<T> accept(Promise<T> otherPromise)

    /**
     * Block and retrieve the value.
     *
     * @return the bound value
     * @throws Exception if the promise completed with error
     */
    T get() throws Exception

    /**
     * Block and retrieve the value within a timeout.
     *
     * @param timeout maximum time to wait
     * @param unit time unit
     * @return the bound value
     * @throws TimeoutException if not completed within timeout
     */
    T get(long timeout, TimeUnit unit) throws TimeoutException

    /**
     * @return true if this promise has completed (value or error)
     */
    boolean isDone()

    /**
     * Register a callback for successful completion.
     * <p>If already completed successfully, callback is invoked immediately.</p>
     *
     * @param callback consumer of the value
     * @return this promise
     */
    Promise<T> onComplete(Consumer<T> callback)

    /**
     * Transform the promise's value into another value.
     * <p>The returned promise completes when this promise completes successfully.</p>
     *
     * @param function transformation function
     * @param <R> result type
     * @return a new promise of type R
     */
    <R> Promise<R> then(Function<T, R> function)

    /**
     * Register a callback for error completion.
     * <p>If already completed in error, callback is invoked immediately.</p>
     *
     * @param errorHandler consumer of Throwable
     * @return this promise
     */
    Promise<T> onError(Consumer<Throwable> errorHandler)

    /**
     * Recover from an error by producing an alternative value.
     * <p>If this promise succeeds, its value passes through unchanged
     * (casted to R where appropriate). If it errors, the recovery function
     * is applied to the Throwable to produce a value.</p>
     *
     * @param recovery mapping from error to value
     * @param <R> recovered value type
     * @return a new promise of R
     */
    <R> Promise<R> recover(Function<Throwable, R> recovery)

    /**
     * Convert this promise into another async type.
     * <p>Groovy uses asType for coercion; implementations should support at least
     * conversion to {@link CompletableFuture}.</p>
     *
     * @param targetType a class token (typically CompletableFuture)
     * @return a CompletableFuture view of this promise
     */
    CompletableFuture<T> asType(Class clazz)
}
