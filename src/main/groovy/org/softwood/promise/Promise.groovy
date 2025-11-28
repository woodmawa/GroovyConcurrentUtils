package org.softwood.promise

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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
 *   <li>{@link #then} to transform the value</li>
 *   <li>{@link #recover} to replace or transform an error</li>
 *   <li>{@link #map} for pure functional transforms</li>
 *   <li>{@link #flatMap} for async sequencing</li>
 *   <li>{@link #filter} to validate values</li>
 * </ul>
 *
 * @param <T> type of the promised value
 */
interface Promise<T> {

    /** Complete this promise with an already computed value. */
    Promise<T> accept(T value)

    /** Complete this promise using a supplier executed once. */
    Promise<T> accept(Supplier<T> supplier)

    /** Complete this promise when the given {@link CompletableFuture} completes. */
    Promise<T> accept(CompletableFuture<T> future)

    /** Complete this promise by adopting the completion of another promise. */
    Promise<T> accept(Promise<T> otherPromise)

    /** Block until this promise completes, then return the value or throw its error. */
    T get() throws Exception

    /** Block until this promise completes or timeout expires. */
    T get(long timeout, TimeUnit unit) throws TimeoutException

    /** Whether the promise is complete (success or error). */
    boolean isDone()

    /** Register a callback for successful completion. */
    Promise<T> onComplete(Consumer<T> callback)

    /** Transform the successful value into another value. */
    <R> Promise<R> then(Function<T, R> function)

    /** Register a callback for error completion. */
    Promise<T> onError(Consumer<Throwable> errorHandler)

    /** Fail this promise with an error. */
    Promise<T> fail(Throwable error)

    /** Recover from an error by computing an alternative value. */
    <R> Promise<R> recover(Function<Throwable, R> recovery)

    /** Coerce this promise into another async type (e.g. CompletableFuture). */
    CompletableFuture<T> asType(Class clazz)

    /** Transform the value on success. */
    <R> Promise<R> map(Function<? super T, ? extends R> mapper)

    /** Flat map: sequence asynchronous computations. */
    <R> Promise<R> flatMap(Function<? super T, Promise<R>> mapper)

    /** Filter successful values based on a predicate. */
    Promise<T> filter(Predicate<? super T> predicate)
}
