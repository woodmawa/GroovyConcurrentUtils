package org.softwood.promise

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.BiConsumer
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Predicate
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
 *   <li><b>Implementation-pluggable:</b> Promises are created via a {@link org.softwood.promise.PromiseFactory PromiseFactory}.</li>
 * </ul>
 *
 * <h3>Completing a promise</h3>
 * You can complete a promise with:
 * <ul>
 *   <li>a direct value via {@link #accept}</li>
 *   <li>a lazy {@link Supplier} via {@link #accept}</li>
 *   <li>a {@link CompletableFuture} via {@link #accept}</li>
 *   <li>another {@link Promise} via {@link #accept}</li>
 *   <li>a failure via {@link #fail}</li>
 * </ul>
 *
 * <h3>Observing and transforming</h3>
 * Use:
 * <ul>
 *   <li>{@link #onComplete} to observe success</li>
 *   <li>{@link #onError} to observe failure</li>
 *   <li>{@link #whenComplete} to observe both success and error</li>
 *   <li>{@link #then} to transform the value</li>
 *   <li>{@link #recover} to replace or transform an error</li>
 *   <li>{@link #map} for pure functional transforms</li>
 *   <li>{@link #flatMap} for async sequencing</li>
 *   <li>{@link #filter} to validate values</li>
 *   <li>{@link #tap} for side effects without transformation</li>
 * </ul>
 *
 * <h3>Timeout and combining</h3>
 * Use:
 * <ul>
 *   <li>{@link #timeout} to fail on timeout or provide fallback</li>
 *   <li>{@link #orTimeout} to fail this promise on timeout</li>
 *   <li>{@link #zip} to combine with another promise</li>
 * </ul>
 *
 * @param <T> type of the promised value
 */
interface Promise<T> {

    // =========================================================================
    // Core Completion Methods
    // =========================================================================

    /** Complete this promise with an already computed value. */
    Promise<T> accept(T value)

    /** Complete this promise using a supplier executed once. */
    Promise<T> accept(Supplier<T> supplier)

    /** Complete this promise when the given {@link CompletableFuture} completes. */
    Promise<T> accept(CompletableFuture<T> future)

    /** Complete this promise by adopting the completion of another promise. */
    Promise<T> accept(Promise<T> otherPromise)

    /** Fail this promise with an error. */
    Promise<T> fail(Throwable error)

    /**
     * Reject this promise with an error (alias for fail).
     * This provides more intuitive semantics for async error handling.
     */
    default Promise<T> reject(Throwable error) {
        return fail(error)
    }

    // =========================================================================
    // Retrieval Methods
    // =========================================================================

    /** Block until this promise completes, then return the value or throw its error. */
    T get() throws Exception

    /** Block until this promise completes or timeout expires. */
    T get(long timeout, TimeUnit unit) throws TimeoutException

    /** Whether the promise is complete (success or error). */
    boolean isDone()

    /** Alias for is done, Whether the promise is complete (success or error). */
    boolean isCompleted()

    /**
     * Returns true if this promise was cancelled before it completed normally.
     *
     * @return true if cancelled
     */
    boolean isCancelled()

    // =========================================================================
    // Callback Methods
    // =========================================================================

    /** Register a callback for successful completion. */
    Promise<T> onComplete(Consumer<T> callback)

    /** Register a callback for error completion. */
    Promise<T> onError(Consumer<Throwable> errorHandler)

    /**
     * Register a callback invoked on both success and error completion.
     * <p>This is similar to CompletableFuture's whenComplete. The callback receives
     * the value (if successful) and error (if failed). Exactly one will be null.</p>
     *
     * <p><b>Callback ordering guarantee:</b> Callbacks registered on the same promise
     * are invoked in registration order. However, callbacks registered via
     * different methods (onComplete, onError, whenComplete) may interleave.</p>
     *
     * @param action callback receiving (value, error) - one will be null
     * @return this promise for chaining
     */
    Promise<T> whenComplete(BiConsumer<T, Throwable> action)

    // =========================================================================
    // Transformation Methods
    // =========================================================================

    /** Transform the successful value into another value. */
    <R> Promise<R> then(Function<T, R> function)

    /** Transform the value on success. */
    <R> Promise<R> map(Function<? super T, ? extends R> mapper)

    /** Flat map: sequence asynchronous computations. */
    <R> Promise<R> flatMap(Function<? super T, Promise<R>> mapper)

    /** Filter successful values based on a predicate. */
    Promise<T> filter(Predicate<? super T> predicate)

    /** Recover from an error by computing an alternative value. */
    <R> Promise<R> recover(Function<Throwable, R> recovery)

    /**
     * Perform a side effect on success without transforming the value.
     * <p>This is useful for logging, metrics, or other side effects in a chain.</p>
     *
     * @param action side effect to perform
     * @return this promise (with same value) for chaining
     */
    Promise<T> tap(Consumer<T> action)

    // =========================================================================
    // Timeout Operations
    // =========================================================================

    /**
     * Returns a new promise that fails with {@link TimeoutException} if this
     * promise doesn't complete within the specified timeout.
     *
     * @param timeout timeout duration
     * @param unit time unit
     * @return new promise that times out
     */
    Promise<T> timeout(long timeout, TimeUnit unit)

    /**
     * Returns a new promise that returns a fallback value if this promise
     * doesn't complete within the specified timeout.
     *
     * @param timeout timeout duration
     * @param unit time unit
     * @param fallbackValue value to use on timeout
     * @return new promise with timeout and fallback
     */
    Promise<T> timeout(long timeout, TimeUnit unit, T fallbackValue)

    /**
     * Modifies THIS promise to fail with {@link TimeoutException} if it doesn't
     * complete within the specified timeout.
     * <p><b>Note:</b> This mutates the promise's completion behavior.</p>
     *
     * @param timeout timeout duration
     * @param unit time unit
     * @return this promise
     */
    Promise<T> orTimeout(long timeout, TimeUnit unit)

    // =========================================================================
    // Combining Operations
    // =========================================================================

    /**
     * Combine this promise with another, applying a combining function when
     * both complete successfully. If either fails, the result fails.
     *
     * @param other promise to combine with
     * @param combiner function to combine the two values
     * @param <U> type of other promise
     * @param <R> result type
     * @return new promise with combined result
     */
    <U, R> Promise<R> zip(Promise<U> other, java.util.function.BiFunction<T, U, R> combiner)

    // =========================================================================
    // Cancellation
    // =========================================================================

    /**
     * Attempts to cancel execution of this promise's task.
     *
     * @param mayInterruptIfRunning ignored for the Dataflow model, but kept for
     * alignment with java.util.concurrent.Future
     * @return true if the promise was successfully cancelled, false otherwise
     */
    boolean cancel(boolean mayInterruptIfRunning)

    // =========================================================================
    // Conversion
    // =========================================================================

    /** Coerce this promise into another async type (e.g. CompletableFuture). */
    CompletableFuture<T> asType(Class clazz)

}
