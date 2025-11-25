package org.softwood.promise.core.vertx

import groovy.transform.CompileStatic
import groovy.transform.ToString
import groovy.util.logging.Slf4j
import io.vertx.core.AsyncResult
import io.vertx.core.Context
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
 * Vert.x 5.x implementation of the {@link org.softwood.promise.Promise} interface.
 *
 * <p>This adapter bridges the custom {@code SoftPromise} abstraction onto Vert.x 5's
 * {@link io.vertx.core.Future} and {@link io.vertx.core.Promise} primitives.
 * It allows code written against {@code org.softwood.promise.Promise}
 * to transparently leverage Vert.x's asynchronous, event-driven model.</p>
 *
 * <h2>Key Characteristics</h2>
 * <ul>
 *   <li>Backed by Vert.x 5's {@link Future} and {@link VertxPromise}.</li>
 *   <li>Provides blocking {@code get()} methods for interoperability outside Vert.x
 *       (must not be used on event-loop threads).</li>
 *   <li>Supports functional-style composition via {@code #then(Function)} and {@code #recover(Function)}.</li>
 *   <li>Callbacks ({@code #onComplete(Consumer)}, {@code #onError(Consumer)}) execute on Vert.x contexts.</li>
 * </ul>
 *
 * <h2>Threading / Context Rules</h2>
 * <p>
 * Vert.x promises must be completed on a Vert.x context (event loop or worker).
 * Therefore, this adapter always completes/fails <em>owned</em> promises via
 * {@link Context#runOnContext(Handler)}.
 * </p>
 *
 * <h2>Ownership Model</h2>
 * <ul>
 *   <li>If constructed from a {@link VertxPromise}, this adapter <b>owns</b> completion.</li>
 *   <li>If constructed from a {@link Future}, completion is external and {@code accept()/fail()} log warnings.</li>
 * </ul>
 *
 * @param <T> the type of the value produced by this promise
 */
@Slf4j
@ToString(includes = ['future'])
@CompileStatic
class VertxPromiseAdapter<T> implements SoftPromise<T> {

    /**
     * Underlying Vert.x promise, if this adapter owns completion.
     * Null when wrapping a pre-existing {@link Future}.
     */
    private final VertxPromise<T> promise

    /** Underlying Vert.x future representing completion. */
    private final Future<T> future

    /** Vert.x instance used for async operations. */
    private final Vertx vertx

    /** Captured Vert.x context for safe completion. */
    private final Context context

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

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
        this.context = vertx.getOrCreateContext()
    }

    /**
     * Creates a new adapter that wraps an existing Vert.x {@link Future}.
     *
     * <p>In this mode the adapter does <b>not</b> own completion and {@code accept()/fail()}
     * will log warnings instead of attempting to complete the underlying future.</p>
     *
     * @param future the Vert.x future to wrap
     * @param vertx  the Vert.x instance used for asynchronous work
     */
    VertxPromiseAdapter(Future<T> future, Vertx vertx) {
        this.promise = null
        this.future = future
        this.vertx = vertx
        this.context = vertx.getOrCreateContext()
    }

    // -------------------------------------------------------------------------
    // Static factories
    // -------------------------------------------------------------------------

    /**
     * Creates a new, incomplete adapter bound to the given Vert.x instance.
     *
     * @param vertx Vert.x runtime
     * @param <T>   value type
     * @return new incomplete {@link VertxPromiseAdapter}
     */
    static <T> VertxPromiseAdapter<T> create(Vertx vertx) {
        VertxPromise<T> p = VertxPromise.<T>promise()
        return new VertxPromiseAdapter<T>(p, vertx)
    }

    /**
     * Creates an adapter already completed successfully.
     *
     * @param vertx Vert.x runtime
     * @param value completion value
     * @param <T> value type
     * @return succeeded adapter
     */
    static <T> VertxPromiseAdapter<T> succeededPromise(Vertx vertx, T value) {
        return new VertxPromiseAdapter<T>(Future.<T>succeededFuture(value), vertx)
    }

    /**
     * Creates an adapter already completed with failure.
     *
     * @param vertx Vert.x runtime
     * @param cause failure cause
     * @param <T> value type
     * @return failed adapter
     */
    static <T> VertxPromiseAdapter<T> failedPromise(Vertx vertx, Throwable cause) {
        return new VertxPromiseAdapter<T>(Future.<T>failedFuture(cause), vertx)
    }

    // -------------------------------------------------------------------------
    // Completion methods (accept / fail)
    // -------------------------------------------------------------------------

    /**
     * Completes this promise with the given value.
     *
     * <p>If this adapter does not own completion (i.e. it wraps an external {@link Future}),
     * a warning is logged and the call is ignored.</p>
     *
     * @param value value to bind
     * @return this adapter for fluent chaining
     */
    @Override
    SoftPromise<T> accept(T value) {
        if (promise == null) {
            log.warn("Cannot accept(T) on already completed / externally owned Vert.x Future")
            return this
        }
        context.runOnContext({ Void v ->
            promise.tryComplete(value)
        } as Handler<Void>)
        return this
    }

    /**
     * Executes the supplier on a Vert.x worker thread using {@link Vertx#executeBlocking},
     * then completes this promise with its result or failure.
     *
     * @param supplier supplier to execute asynchronously
     * @return this adapter for fluent chaining
     */
    @Override
    SoftPromise<T> accept(Supplier<T> supplier) {
        if (promise == null) {
            log.warn("Cannot accept(Supplier) on already completed / externally owned Vert.x Future")
            return this
        }

        Callable<T> callable = { -> supplier.get() } as Callable<T>

        vertx.executeBlocking(callable, false)
                .onComplete({ AsyncResult<T> ar ->
                    context.runOnContext({ Void v ->
                        if (ar.succeeded()) {
                            promise.tryComplete(ar.result())
                        } else {
                            promise.tryFail(ar.cause())
                        }
                    } as Handler<Void>)
                } as Handler<AsyncResult<T>>)

        return this
    }

    /**
     * Binds this adapter to a Java {@link CompletableFuture}.
     *
     * <p>Completion is forwarded onto the captured Vert.x context. If this adapter
     * does not own completion, a warning is logged and the call is ignored.</p>
     *
     * @param externalFuture source future
     * @return this adapter for fluent chaining
     */
    @Override
    SoftPromise<T> accept(CompletableFuture<T> externalFuture) {
        if (promise == null) {
            log.warn("Cannot accept(CompletableFuture) on already completed / externally owned Vert.x Future")
            return this
        }

        externalFuture.whenComplete { T value, Throwable error ->
            context.runOnContext({ Void v ->
                if (error != null) {
                    promise.tryFail(error)
                } else {
                    promise.tryComplete(value)
                }
            } as Handler<Void>)
        }

        return this
    }

    /**
     * Binds this adapter to another {@link SoftPromise}.
     *
     * <p>Success and failure are forwarded to this Vert.x promise using the captured
     * Vert.x context. If this adapter does not own completion, a warning is logged
     * and the call is ignored.</p>
     *
     * @param otherPromise source promise
     * @return this adapter for fluent chaining
     */
    @Override
    SoftPromise<T> accept(SoftPromise<T> otherPromise) {
        if (promise == null) {
            log.warn("Cannot accept(Promise) on already completed / externally owned Vert.x Future")
            return this
        }

        otherPromise.onComplete({ T value ->
            context.runOnContext({ Void v ->
                promise.tryComplete(value)
            } as Handler<Void>)
        } as Consumer<T>)

        otherPromise.onError({ Throwable err ->
            context.runOnContext({ Void v ->
                promise.tryFail(err)
            } as Handler<Void>)
        } as Consumer<Throwable>)

        return this
    }

    /**
     * Fail this promise with the given error.
     *
     * <p>If this adapter does not own completion (i.e. it wraps an external
     * {@link Future}), a warning is logged and the call is ignored.</p>
     *
     * @param error failure cause to set on the underlying Vert.x promise
     * @return this adapter for fluent chaining
     */
    @Override
    SoftPromise<T> fail(Throwable error) {
        if (promise == null) {
            log.warn("Cannot fail() on an externally owned Vert.x Future")
            return this
        }

        context.runOnContext({ Void v ->
            promise.tryFail(error)
        } as Handler<Void>)

        return this
    }

    // -------------------------------------------------------------------------
    // Blocking getters
    // -------------------------------------------------------------------------

    /**
     * Blocks until completion and returns the result.
     *
     * <p><b>Important:</b> must NOT be called on Vert.x event-loop threads.
     * Intended for interoperability with non-Vert.x code.</p>
     *
     * @return the successful result
     * @throws Exception if the underlying future completed with failure
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
                if (err instanceof Exception) throw (Exception) err
                throw new RuntimeException(err)
            }
            return result.get()
        }

        // Vert.x 5: no future.succeeded(); check cause instead
        if (future.cause() == null) {
            return future.result()
        }

        Throwable err = future.cause()
        if (err instanceof Exception) throw (Exception) err
        throw new RuntimeException(err)
    }

    /**
     * Blocks until completion or timeout and returns the result.
     *
     * <p><b>Important:</b> must NOT be called on Vert.x event-loop threads.</p>
     *
     * @param timeout maximum time to wait
     * @param unit time unit of the timeout
     * @return the successful result
     * @throws TimeoutException if the promise did not complete in time
     * @throws RuntimeException if the underlying future completed with failure
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
            throw new RuntimeException(err)
        }
        return result.get()
    }

    // -------------------------------------------------------------------------
    // State + callbacks
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if the underlying Vert.x future is complete
     * (either success or failure).
     *
     * @return completion flag
     */
    @Override
    boolean isDone() {
        return future.isComplete()
    }

    /**
     * Register a success callback.
     *
     * @param callback consumer of successful value
     * @return this adapter for fluent chaining
     */
    @Override
    SoftPromise<T> onComplete(Consumer<T> callback) {
        future.onSuccess({ T value ->
            callback.accept(value)
        } as Handler<T>)
        return this
    }

    /**
     * Register a failure callback.
     *
     * @param callback consumer of {@link Throwable}
     * @return this adapter for fluent chaining
     */
    @Override
    SoftPromise<T> onError(Consumer<Throwable> callback) {
        future.onFailure({ Throwable err ->
            callback.accept(err)
        } as Handler<Throwable>)
        return this
    }

    // -------------------------------------------------------------------------
    // then() and recover()
    // -------------------------------------------------------------------------

    /**
     * Map successful completion to a new promise using {@link Future#map(Function)}.
     *
     * @param fn mapping from T to R
     * @param <R> result type
     * @return new {@link VertxPromiseAdapter} wrapping the mapped future
     */
    @Override
    <R> SoftPromise<R> then(Function<T, R> fn) {
        Future<R> mapped = future.map(
                { T value -> fn.apply(value) } as Function<T, R>
        )
        return new VertxPromiseAdapter<R>(mapped, vertx)
    }

    /**
     * Recover from failure using a {@code Throwable -> value} function.
     *
     * <p>If this promise succeeds, its value passes through unchanged (cast to R).
     * If it fails, the {@code fn} is applied to the cause to produce a recovered
     * value. If the recovery function itself throws, the resulting promise fails
     * with that new error.</p>
     *
     * @param fn recovery function from error to recovered value
     * @param <R> result type
     * @return new {@link VertxPromiseAdapter} wrapping the recovered future
     */
    @Override
    <R> SoftPromise<R> recover(Function<Throwable, R> fn) {
        Future<R> recovered = future.transform(
                { AsyncResult<T> ar ->
                    if (ar.succeeded()) {
                        return Future.succeededFuture((R) ar.result())
                    } else {
                        try {
                            return Future.succeededFuture(fn.apply(ar.cause()))
                        } catch (Throwable t) {
                            return Future.failedFuture(t)
                        }
                    }
                } as Function<AsyncResult<T>, Future<R>>
        )

        return new VertxPromiseAdapter<R>(recovered, vertx)
    }

    // -------------------------------------------------------------------------
    // Coercion to CompletableFuture
    // -------------------------------------------------------------------------

    /**
     * Groovy coercion hook. Supports coercion to {@link CompletableFuture}.
     *
     * @param clazz requested coercion class; must be {@link CompletableFuture}.class
     * @return a {@link CompletableFuture} view of this promise
     * @throws RuntimeException if clazz is not {@link CompletableFuture}.class
     */
    @Override
    CompletableFuture<T> asType(Class clazz) {
        if (clazz == CompletableFuture) {
            return future.toCompletionStage().toCompletableFuture()
        }
        throw new RuntimeException("conversion to type $clazz is not supported")
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /**
     * @return underlying Vert.x {@link Future} instance
     */
    Future<T> getFuture() { future }

    /**
     * @return Vert.x runtime instance backing this adapter
     */
    Vertx getVertx() { vertx }

    /**
     * @return captured Vert.x {@link Context} used for completion
     */
    Context getContext() { context }
}
