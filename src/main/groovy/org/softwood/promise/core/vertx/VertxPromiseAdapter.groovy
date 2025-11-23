package org.softwood.promise.core.vertx

import groovy.transform.CompileStatic
import groovy.transform.ToString
import groovy.util.logging.Slf4j
import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.Promise as VertxPromise
import io.vertx.core.Vertx
import org.softwood.promise.Promise as SoftPromise

import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Supplier

/**
 * <p>
 * Vert.x-based implementation of the {@link org.softwood.promise.Promise} interface.
 * </p>
 *
 * <h2>Purpose</h2>
 * <p>
 * This adapter bridges the custom {@code SoftPromise} abstraction onto Vert.x 5's
 * {@link io.vertx.core.Future} and {@link io.vertx.core.Promise} primitives.
 * It allows existing code written against {@code org.softwood.promise.Promise}
 * to transparently leverage Vert.x's asynchronous, event-driven model.
 * </p>
 *
 * <h2>Key Characteristics</h2>
 * <ul>
 *   <li>Backed by Vert.x 5's {@link Future} and {@link VertxPromise}.</li>
 *   <li>Provides blocking {@code get()} methods for interoperability outside Vert.x
 *       (must not be used on event loop threads).</li>
 *   <li>Supports functional-style composition via {@code then} and {@code recover}.</li>
 *   <li>Callbacks ({@code onComplete}, {@code onError}) execute on Vert.x contexts.</li>
 * </ul>
 *
 * <h2>Thread-safety</h2>
 * <p>
 * This class is effectively thread-safe for typical usage patterns because it delegates
 * to Vert.x's own thread-safe futures and promises. However, care must still be taken
 * not to introduce blocking behavior (e.g. {{@code get()} or {@code get(long, TimeUnit)}) on event loop threads, as that can lead to deadlocks
 * or stalls in the Vert.x event loop.
 * </p>
 *
 * <h2>Usage Example</h2>
 * <pre>
 * {@code
 * Vertx vertx = Vertx.vertx()
 * VertxPromiseAdapter<String> promise = VertxPromiseAdapter.create(vertx)
 *
 * promise
 *   .accept({ ->
 *       // executed on a Vert.x worker thread
 *       return "result"
 *   } as Supplier<String>)
 *   .then({ String value -> value.toUpperCase() } as Function<String, String>)
 *   .onComplete({ String upper ->
 *       println("Completed with: " + upper)
 *   } as Consumer<String>)
 *   .onError({ Throwable err ->
 *       err.printStackTrace()
 *   } as Consumer<Throwable>)
 * }
 * </pre>
 *
 * @param <T> the type of the value produced by this promise
 */
@Slf4j
@ToString(includes = ['future'])
@CompileStatic
class VertxPromiseAdapter<T> implements SoftPromise<T> {

    /**
     * Underlying Vert.x promise, if this adapter owns the completion.
     * <p>
     * This will be {@code null} when the adapter is wrapping a pre-existing
     * {@link Future} that is already completed or is owned elsewhere.
     * </p>
     */
    private final VertxPromise<T> promise

    /**
     * Underlying Vert.x future that models the completion of this promise.
     */
    private final Future<T> future

    /**
     * Vert.x instance used by this adapter for asynchronous operations.
     */
    private final Vertx vertx

    /**
     * Creates a new adapter that owns the given Vert.x {@link VertxPromise}.
     *
     * @param promise the Vert.x promise this adapter will complete
     * @param vertx   the Vert.x instance used for asynchronous work
     */
    VertxPromiseAdapter(VertxPromise<T> promise, Vertx vertx) {
        this.promise = promise
        this.future = promise.future()
        this.vertx = vertx
    }

    /**
     * Creates a new adapter that wraps an existing Vert.x {@link Future}.
     * <p>
     * In this mode the adapter does not own the underlying completion
     * and {@link #accept(Object)} / {@link #accept(Supplier)} will log a warning.
     * </p>
     *
     * @param future the Vert.x future to wrap
     * @param vertx  the Vert.x instance used for asynchronous work
     */
    VertxPromiseAdapter(Future<T> future, Vertx vertx) {
        this.promise = null
        this.future = future
        this.vertx = vertx
    }

    /**
     * generates completableFuture from the vertx future
     */
    CompletableFuture asType (CompletableFuture) {
        future.toCompletionStage().toCompletableFuture()
        //future.toCompletionStage().
    }

    /**
     * Creates a new, incomplete {@code VertxPromiseAdapter} bound to the given Vert.x instance.
     * <p>
     * The returned adapter owns an underlying {@link VertxPromise} that can be completed via
     * {@link #accept(Object)} or {@link #accept(Supplier)}.
     * </p>
     *
     * @param vertx the Vert.x instance used for asynchronous work
     * @param <T>   the type of the promise result
     * @return a new, incomplete adapter
     */
    static <T> VertxPromiseAdapter<T> create(Vertx vertx) {
        VertxPromise<T> p = VertxPromise.<T>promise()
        return new VertxPromiseAdapter<T>(p, vertx)
    }

    /**
     * Creates a {@code VertxPromiseAdapter} that is already completed successfully.
     *
     * @param vertx the Vert.x instance used for asynchronous work
     * @param value the value the promise should be completed with
     * @param <T>   the result type
     * @return an adapter that wraps a succeeded Vert.x {@link Future}
     */
    static <T> VertxPromiseAdapter<T> succeededPromise(Vertx vertx, T value) {
        Future<T> f = Future.<T>succeededFuture(value)
        return new VertxPromiseAdapter<T>(f, vertx)
    }

    /**
     * Creates a {@code VertxPromiseAdapter} that is already completed with a failure.
     *
     * @param vertx the Vert.x instance used for asynchronous work
     * @param cause the failure cause
     * @param <T>   the result type (unused in failure case but kept for type safety)
     * @return an adapter that wraps a failed Vert.x {@link Future}
     */
    static <T> VertxPromiseAdapter<T> failedPromise(Vertx vertx, Throwable cause) {
        Future<T> f = Future.<T>failedFuture(cause)
        return new VertxPromiseAdapter<T>(f, vertx)
    }

    /**
     * Completes this promise with the given value.
     * <p>
     * If this adapter does not own the underlying completion (i.e. it was created
     * from an existing {@link Future}), this method logs a warning and has no effect.
     * </p>
     *
     * @param value the value to complete the promise with
     * @return this promise for fluent chaining
     */
    @Override
    SoftPromise<T> accept(T value) {
        if (promise != null) {
            promise.tryComplete(value)
        } else {
            log.warn("Cannot accept value on already completed / externally owned promise")
        }
        return this
    }

    /**
     * Executes the given {@link Supplier} on a Vert.x worker thread and completes
     * this promise with its result.
     * <p>
     * This is intended for blocking or CPU-bound work that should not be executed
     * directly on an event loop. Vert.x's {@code executeBlocking} facility is used.
     * </p>
     *
     * <p><b>Note:</b> If this adapter does not own the underlying completion
     * (i.e. {@code promise} is {@code null}), this method logs a warning and
     * does nothing.</p>
     *
     * @param supplier the supplier to be executed asynchronously
     * @return this promise for fluent chaining
     */
    @Override
    SoftPromise<T> accept(Supplier<T> supplier) {
        if (promise == null) {
            log.warn("Cannot accept supplier on already completed / externally owned promise")
            return this
        }

        Callable<T> callable = { -> supplier.get() } as Callable<T>

        vertx.executeBlocking(callable, false)
                .onComplete({ AsyncResult<T> ar ->
                    if (ar.succeeded()) {
                        promise.tryComplete(ar.result())
                    } else {
                        promise.tryFail(ar.cause())
                    }
                } as Handler<AsyncResult<T>>)

        return this
    }

    /**
     * Blocks the current thread until this promise is completed and returns the result.
     * <p>
     * If the promise completes with a failure, that failure is thrown as-is. This may
     * be a checked or unchecked exception or an error.
     * </p>
     *
     * <p><b>Important:</b> This method must not be called from a Vert.x event-loop
     * thread, as it will block the event loop and may cause deadlocks or stalls.</p>
     *
     * @return the completed value
     * @throws Exception if the promise completed with a failure (whatever cause was set)
     */
    @Override
    T get() throws Exception {
        if (!future.isComplete()) {
            CountDownLatch latch = new CountDownLatch(1)
            AtomicReference<T> result = new AtomicReference<T>()
            AtomicReference<Throwable> error = new AtomicReference<Throwable>()

            future.onComplete({ AsyncResult<T> ar ->
                if (ar.succeeded()) {
                    result.set(ar.result())
                } else {
                    error.set(ar.cause())
                }
                latch.countDown()
            } as Handler<AsyncResult<T>>)

            latch.await()

            Throwable err = error.get()
            if (err != null) {
                // propagate the original failure
                if (err instanceof Exception) {
                    throw (Exception) err
                }
                // wrap non-Exception throwables
                throw new RuntimeException(err)
            }
            return result.get()
        }

        if (future.succeeded()) {
            return future.result()
        }

        Throwable err = future.cause()
        if (err instanceof Exception) {
            throw (Exception) err
        }
        throw new RuntimeException(err)
    }

    /**
     * Blocks the current thread until this promise is completed or the timeout expires.
     *
     * <p>
     * If the promise completes with a failure, that failure is thrown wrapped in a
     * {@link RuntimeException} when necessary.
     * </p>
     *
     * <p><b>Important:</b> This method must not be called from a Vert.x event-loop
     * thread, as it will block the event loop and may cause deadlocks or stalls.</p>
     *
     * @param timeout the maximum time to wait
     * @param unit    the time unit of the {@code timeout} argument
     * @return the completed value
     * @throws TimeoutException if the promise did not complete within the given timeout
     */
    @Override
    T get(long timeout, TimeUnit unit) throws TimeoutException {
        CountDownLatch latch = new CountDownLatch(1)
        AtomicReference<T> result = new AtomicReference<T>()
        AtomicReference<Throwable> error = new AtomicReference<Throwable>()

        future.onComplete({ AsyncResult<T> ar ->
            if (ar.succeeded()) {
                result.set(ar.result())
            } else {
                error.set(ar.cause())
            }
            latch.countDown()
        } as Handler<AsyncResult<T>>)

        if (!latch.await(timeout, unit)) {
            throw new TimeoutException("Promise timed out after ${timeout} ${unit}")
        }

        Throwable err = error.get()
        if (err != null) {
            if (err instanceof RuntimeException) {
                throw (RuntimeException) err
            }
            throw new RuntimeException(err)
        }
        return result.get()
    }

    /**
     * Indicates whether this promise's underlying future has completed, either
     * successfully or with a failure.
     *
     * @return {@code true} if the promise is completed, {@code false} otherwise
     */
    @Override
    boolean isDone() {
        return future.isComplete()
    }

    /**
     * Registers a callback that will be invoked when this promise completes successfully.
     * <p>
     * The callback is <b>not</b> invoked on failure; for failures use {@link #onError(Consumer)}.
     * The callback is executed on a Vert.x context thread associated with the underlying future.
     * </p>
     *
     * @param callback the callback to consume the successful result
     * @return this promise for fluent chaining
     */
    @Override
    SoftPromise<T> onComplete(Consumer<T> callback) {
        future.onSuccess({ T value ->
            callback.accept(value)
        } as Handler<T>)
        return this
    }

    /**
     * Registers a callback that will be invoked if this promise completes with a failure.
     * <p>
     * The callback is executed on a Vert.x context thread associated with the underlying future.
     * </p>
     *
     * @param callback the error handler to consume the failure cause
     * @return this promise for fluent chaining
     */
    @Override
    SoftPromise<T> onError(Consumer<Throwable> callback) {
        future.onFailure({ Throwable err ->
            callback.accept(err)
        } as Handler<Throwable>)
        return this
    }

    /**
     * Transforms the successful result of this promise using the given mapping function.
     * <p>
     * If this promise completes successfully, the provided function is applied to its
     * result and a new promise is returned containing the transformed value.
     * </p>
     *
     * <p>
     * If this promise completes with a failure, the mapping function is not called and
     * the failure is propagated to the returned promise.
     * </p>
     *
     * @param fn  the transformation function to apply to the successful result
     * @param <R> the type of the transformed result
     * @return a new promise containing either the mapped result or the original failure
     */
    @Override
    <R> SoftPromise<R> then(Function<T, R> fn) {
        Future<R> mapped = future.map(
                { T value -> fn.apply(value) } as Function<T, R>
        )
        return new VertxPromiseAdapter<R>(mapped, vertx)
    }

    /**
     * Recovers from a failure by mapping the failure cause to an alternate value.
     * <p>
     * Semantics:
     * </p>
     * <ul>
     *   <li>If this promise completes successfully, the success value is propagated
     *       to the returned promise and the recovery function is not called.</li>
     *   <li>If this promise fails, the provided {@code recovery} function is invoked
     *       with the {@link Throwable} cause and its return value is used as the
     *       successful result of the returned promise.</li>
     *   <li>If the recovery function itself throws an exception, the returned promise
     *       becomes a failed promise with that exception as its cause.</li>
     * </ul>
     *
     * <p>
     * This mirrors Vert.x 5's {@link Future#recover(Function)} behavior, which expects
     * a {@code Function<Throwable, R>} and not a nested future.
     * </p>
     *
     * @param fn  the recovery function mapping a failure cause to a fallback value
     * @param <R> the type of the recovered result
     * @return a new promise that will either:
     *         <ul>
     *           <li>contain the original success value,</li>
     *           <li>contain the recovered value, or</li>
     *           <li>fail with the recovery function's exception.</li>
     *         </ul>
     */
    @Override
    <R> SoftPromise<R> recover(Function<Throwable, R> fn) {

        Future<R> recovered = future.transform(
                { AsyncResult<T> ar ->
                    if (ar.succeeded()) {
                        // Success → propagate original result (downcast to R OK because caller declared R=T)
                        return Future.succeededFuture((R) ar.result())
                    } else {
                        try {
                            // Failure → apply recovery function
                            return Future.succeededFuture(fn.apply(ar.cause()))
                        } catch (Throwable t) {
                            // Option B: if recovery function throws → fail returned promise
                            return Future.failedFuture(t)
                        }
                    }
                } as Function<AsyncResult<T>, Future<R>>
        )

        return new VertxPromiseAdapter<R>(recovered, vertx)
    }

    /**
     * Exposes the underlying Vert.x {@link Future} for advanced integrations.
     * <p>
     * Mutating operations should generally be avoided to keep the behavior of this
     * adapter consistent with the {@code SoftPromise} abstraction.
     * </p>
     *
     * @return the underlying Vert.x future
     */
    Future<T> getFuture() {
        return future
    }

    /**
     * Returns the Vert.x instance that this adapter is associated with.
     *
     * @return the Vert.x instance used by this adapter
     */
    Vertx getVertx() {
        return vertx
    }
}
