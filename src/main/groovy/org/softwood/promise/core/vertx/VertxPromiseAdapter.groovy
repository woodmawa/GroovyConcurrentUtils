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
 * <p>
 * Vert.x 5.x implementation of the {@link org.softwood.promise.Promise} interface.
 * </p>
 *
 * <h2>Purpose</h2>
 * <p>
 * This adapter bridges the custom {@code SoftPromise} abstraction onto Vert.x 5's
 * {@link io.vertx.core.Future} and {@link io.vertx.core.Promise} primitives.
 * It allows code written against {@code org.softwood.promise.Promise}
 * to transparently leverage Vert.x's asynchronous, event-driven model.
 * </p>
 *
 * <h2>Key Characteristics</h2>
 * <ul>
 *   <li>Backed by Vert.x 5's {@link Future} and {@link VertxPromise}.</li>
 *   <li>Provides blocking {@code get()} methods for interoperability outside Vert.x
 *       (must not be used on event-loop threads).</li>
 *   <li>Supports functional-style composition via {@code then} and {@code recover}.</li>
 *   <li>Callbacks ({@code onComplete}, {@code onError}) execute on Vert.x contexts.</li>
 * </ul>
 *
 * <h2>Threading / Context Rules</h2>
 * <p>
 * Vert.x promises must be completed on a Vert.x context (event loop or worker).
 * Therefore, this adapter always completes/fails owned promises via
 * {@link Context#runOnContext(Handler)}.
 * </p>
 *
 * <h2>Ownership Model</h2>
 * <ul>
 *   <li>If constructed from a VertxPromise, this adapter <b>owns</b> completion.</li>
 *   <li>If constructed from a Future, completion is external and accept() logs warnings.</li>
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
     * Null when wrapping a pre-existing Future.
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
     * In this mode the adapter does not own completion and accept() warns.
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
     * @return new incomplete VertxPromiseAdapter
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
    // accept(T)
    // -------------------------------------------------------------------------

    /**
     * Completes this promise with the given value.
     * Warns if adapter does not own completion.
     *
     * @param value value to bind
     * @return this adapter
     */
    @Override
    SoftPromise<T> accept(T value) {
        if (promise == null) {
            log.warn("Cannot accept(T) on already completed / externally owned promise")
            return this
        }
        context.runOnContext({ v ->
            promise.tryComplete(value)
        } as Handler<Void>)
        return this
    }

    // -------------------------------------------------------------------------
    // accept(Supplier<T>)
    // -------------------------------------------------------------------------

    /**
     * Executes the supplier on a Vert.x worker thread using executeBlocking and
     * completes this promise with its result.
     *
     * @param supplier supplier to execute async
     * @return this adapter
     */
    @Override
    SoftPromise<T> accept(Supplier<T> supplier) {
        if (promise == null) {
            log.warn("Cannot accept(Supplier) on already completed / externally owned promise")
            return this
        }

        Callable<T> callable = { -> supplier.get() } as Callable<T>

        vertx.executeBlocking(callable, false)
                .onComplete({ AsyncResult<T> ar ->
                    context.runOnContext({ v ->
                        if (ar.succeeded()) {
                            promise.tryComplete(ar.result())
                        } else {
                            promise.tryFail(ar.cause())
                        }
                    } as Handler<Void>)
                } as Handler<AsyncResult<T>>)

        return this
    }

    // -------------------------------------------------------------------------
    // accept(CompletableFuture<T>)
    // -------------------------------------------------------------------------

    /**
     * Binds this adapter to a Java {@link CompletableFuture}.
     * Completion is forwarded onto the Vert.x context.
     *
     * @param externalFuture source future
     * @return this adapter
     */
    @Override
    SoftPromise<T> accept(CompletableFuture<T> externalFuture) {
        if (promise == null) {
            log.warn("Cannot accept(CompletableFuture) on already completed / externally owned promise")
            return this
        }

        externalFuture.whenComplete { T value, Throwable error ->
            context.runOnContext({ v ->
                if (error != null) {
                    promise.tryFail(error)
                } else {
                    promise.tryComplete(value)
                }
            } as Handler<Void>)
        }

        return this
    }

    // -------------------------------------------------------------------------
    // accept(Promise<T>)
    // -------------------------------------------------------------------------

    /**
     * Binds this adapter to another {@link SoftPromise}.
     * Success and failure are forwarded to this Vert.x promise using the context.
     *
     * @param otherPromise source promise
     * @return this adapter
     */
    @Override
    SoftPromise<T> accept(SoftPromise<T> otherPromise) {
        if (promise == null) {
            log.warn("Cannot accept(Promise) on already completed / externally owned promise")
            return this
        }

        otherPromise.onComplete({ T value ->
            context.runOnContext({ v ->
                promise.tryComplete(value)
            } as Handler<Void>)
        } as Consumer<T>)

        otherPromise.onError({ Throwable err ->
            context.runOnContext({ v ->
                promise.tryFail(err)
            } as Handler<Void>)
        } as Consumer<Throwable>)

        return this
    }

    // -------------------------------------------------------------------------
    // Blocking getters
    // -------------------------------------------------------------------------

    /**
     * Blocks until completion and returns the result.
     * Must NOT be called on event-loop threads.
     */
    @Override
    T get() throws Exception {
        if (!future.isComplete()) {
            CountDownLatch latch = new CountDownLatch(1)
            AtomicReference<T> result = new AtomicReference<T>()
            AtomicReference<Throwable> error = new AtomicReference<Throwable>()

            future.onComplete({ AsyncResult<T> ar ->
                if (ar.succeeded()) result.set(ar.result())
                else error.set(ar.cause())
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
        if (future.cause() == null) return future.result()

        Throwable err = future.cause()
        if (err instanceof Exception) throw (Exception) err
        throw new RuntimeException(err)
    }

    /**
     * Blocks until completion or timeout.
     * Must NOT be called on event-loop threads.
     */
    @Override
    T get(long timeout, TimeUnit unit) throws TimeoutException {
        CountDownLatch latch = new CountDownLatch(1)
        AtomicReference<T> result = new AtomicReference<T>()
        AtomicReference<Throwable> error = new AtomicReference<Throwable>()

        future.onComplete({ AsyncResult<T> ar ->
            if (ar.succeeded()) result.set(ar.result())
            else error.set(ar.cause())
            latch.countDown()
        } as Handler<AsyncResult<T>>)

        if (!latch.await(timeout, unit)) {
            throw new TimeoutException("Promise timed out after ${timeout} ${unit}")
        }

        Throwable err = error.get()
        if (err != null) throw new RuntimeException(err)
        return result.get()
    }

    // -------------------------------------------------------------------------
    // State + Callbacks
    // -------------------------------------------------------------------------

    /** @return true if completed (success or failure). */
    @Override
    boolean isDone() {
        return future.isComplete()
    }

    /**
     * Register a success callback.
     * @param callback consumer of successful value
     * @return this adapter
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
     * @param callback consumer of Throwable
     * @return this adapter
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
     * Map successful completion to a new promise.
     */
    @Override
    <R> SoftPromise<R> then(Function<T, R> fn) {
        Future<R> mapped = future.map(
                { T value -> fn.apply(value) } as Function<T, R>
        )
        return new VertxPromiseAdapter<R>(mapped, vertx)
    }

    /**
     * Recover from failure using a Throwable -> value function.
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
     * Groovy coercion hook. Supports coercion to CompletableFuture.
     *
     * @param clazz requested coercion class
     * @return CompletableFuture view of this promise
     */
    @Override
    CompletableFuture<T> asType(Class clazz) {
        return future.toCompletionStage().toCompletableFuture()
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** @return underlying Vert.x Future. */
    Future<T> getFuture() { future }

    /** @return Vert.x runtime. */
    Vertx getVertx() { vertx }

    /** @return captured Vert.x context. */
    Context getContext() { context }
}
