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
 *   <li><b>Single assignment:</b> a promise is completed once (with a value or an error).</li>
 *   <li><b>Fluent chaining:</b> continuations return new promises.</li>
 *   <li><b>Implementation-pluggable:</b> Promises are created via a {@link PromiseFactory}.</li>
 * </ul>
 *
 * <h3>Completing a promise</h3>
 * You can complete a promise with:
 * <ul>
 *   <li>a direct value via {@link #accept(Object)}</li>
 *   <li>a lazy {@link Supplier} via {@link #accept(Supplier)}</li>
 *   <li>a {@link CompletableFuture} via {@link #accept(CompletableFuture)}</li>
 *   <li>another {@link Promise} via {@link #accept(Promise)}</li>
 *   <li>a failure via {@link #fail(Throwable)}</li>
 * </ul>
 *
 * <h3>Observing and transforming</h3>
 * Use:
 * <ul>
 *   <li>{@link #onComplete(Consumer)} to observe success</li>
 *   <li>{@link #onError(Consumer)} to observe failure</li>
 *   <li>{@link #then(Function)} to transform the value</li>
 *   <li>{@link #recover(Function)} to transform/replace an error into a value</li>
 * </ul>
 *
 * @param <T> type of the promised value
 */
interface Promise<T> {

    /**
     * Complete this promise with an already computed value.
     *
     * @param value the value to bind to this promise
     * @return this promise for fluent chaining
     */
    Promise<T> accept(T value)

    /**
     * Complete this promise by calling the supplier exactly once at accept-time,
     * and binding its result as the value.
     *
     * @param supplier computation producing a value
     * @return this promise for fluent chaining
     */
    Promise<T> accept(Supplier<T> supplier)

    /**
     * Complete this promise when the given {@link CompletableFuture} completes.
     *
     * <p>On success the promise is bound to the future's value.
     * On failure the promise is completed with the same error.</p>
     *
     * @param future source future
     * @return this promise for fluent chaining
     */
    Promise<T> accept(CompletableFuture<T> future)

    /**
     * Complete this promise by adopting the completion of another promise.
     *
     * <p>Success values are forwarded; errors are forwarded as errors.</p>
     *
     * @param otherPromise source promise
     * @return this promise for fluent chaining
     */
    Promise<T> accept(Promise<T> otherPromise)

    /**
     * Block the current thread until this promise completes and return the value.
     *
     * <p>If the promise completed with an error, an {@link Exception} is thrown
     * wrapping or corresponding to that error.</p>
     *
     * @return the bound value
     * @throws Exception if the promise completed with an error
     */
    T get() throws Exception

    /**
     * Block the current thread until this promise completes or the timeout expires.
     *
     * <p>If the promise completed with success, the value is returned.
     * If it completed with error, an {@link Exception} or {@link RuntimeException}
     * is thrown, depending on the implementation.
     * If the timeout expires before completion, a {@link TimeoutException} is thrown.</p>
     *
     * @param timeout maximum time to wait
     * @param unit time unit of the timeout
     * @return the bound value
     * @throws TimeoutException if not completed within the timeout
     */
    T get(long timeout, TimeUnit unit) throws TimeoutException

    /**
     * Returns whether this promise has completed (either successfully or with error).
     *
     * @return {@code true} if the promise has completed, {@code false} otherwise
     */
    boolean isDone()

    /**
     * Register a callback for successful completion.
     *
     * <p>If already completed successfully, the callback is invoked immediately.
     * If the promise completes with an error, the callback is not invoked.</p>
     *
     * @param callback consumer of the successful value
     * @return this promise for fluent chaining
     */
    Promise<T> onComplete(Consumer<T> callback)

    /**
     * Transform the promise's successful value into another value.
     *
     * <p>The returned promise completes when this promise completes successfully.
     * If this promise completes with an error, the returned promise typically
     * completes with the same error (implementation-specific).</p>
     *
     * @param function transformation function from T to R
     * @param <R> result type
     * @return a new promise of type R
     */
    <R> Promise<R> then(Function<T, R> function)

    /**
     * Register a callback for error completion.
     *
     * <p>If already completed with error, the callback is invoked immediately.
     * If the promise completes successfully, the callback is not invoked.</p>
     *
     * @param errorHandler consumer of {@link Throwable}
     * @return this promise for fluent chaining
     */
    Promise<T> onError(Consumer<Throwable> errorHandler)

    /**
     * Fail this promise with the given {@link Throwable}.
     *
     * <p>This is the symmetric counterpart to {@link #accept(Object)}: it completes
     * the promise with an error instead of a value. Implementations should ensure
     * this is single-assignment — subsequent calls after completion should be
     * ignored or logged but must not re-complete the promise.</p>
     *
     * @param error failure cause to bind
     * @return this promise for fluent chaining
     */
    Promise<T> fail(Throwable error)

    /**
     * Recover from an error by producing an alternative value.
     *
     * <p>If this promise succeeds, its value passes through unchanged
     * (casted to {@code R} where appropriate). If it errors, the
     * {@code recovery} function is applied to the {@link Throwable}
     * to produce a replacement value.</p>
     *
     * @param recovery mapping from error to value
     * @param <R> recovered value type
     * @return a new promise of {@code R}
     */
    <R> Promise<R> recover(Function<Throwable, R> recovery)

    /**
     * Convert this promise into another async type.
     *
     * <p>Groovy uses {@code asType} for coercion; implementations should support at least
     * conversion to {@link CompletableFuture}. If the target type is not supported,
     * implementations should throw a {@link RuntimeException}.</p>
     *
     * @param clazz target type (typically {@link CompletableFuture}.class)
     * @return an instance of the requested async type
     * @throws RuntimeException if the conversion is not supported
     */
    CompletableFuture<T> asType(Class clazz)
}
