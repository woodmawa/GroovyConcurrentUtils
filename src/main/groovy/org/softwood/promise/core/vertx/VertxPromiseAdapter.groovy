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
import org.softwood.promise.core.PromiseState

import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Predicate
import java.util.function.Supplier

/**
 * Vert.x-backed implementation of the {@link SoftPromise} interface.
 *
 * <h2>Overview</h2>
 * <p>
 * This adapter wraps a Vert.x {@link Future} and/or {@link VertxPromise},
 * providing a SoftPromise-compatible API while respecting Vert.x's execution model:
 * </p>
 *
 * <ul>
 *   <li>All callbacks and completions are <b>hop-returned</b> onto the original
 *       creation {@link Context}.</li>
 *   <li>Blocking work is executed using Vert.x's <b>worker pool</b>
 *       via {@code executeBlocking}.</li>
 *   <li>The adapter supports both "owning" mode (with a backing {@link VertxPromise})
 *       and "non-owning" mode (wrapping an external {@link Future}).</li>
 * </ul>
 *
 * <h2>Key Behaviours</h2>
 * <ul>
 *   <li>A non-owning adapter (wrapping only a {@link Future}) cannot be completed
 *       by user code; completion methods become no-ops.</li>
 *   <li>All chaining operations ({@code then}, {@code map}, {@code recover},
 *       {@code flatMap}) allocate new VertxPromiseAdapter instances.</li>
 *   <li>Blocking calls ({@code get}, {@code get(timeout)}) are emulated using
 *       {@link CountDownLatch} since Vert.x Futures are non-blocking.</li>
 * </ul>
 *
 * <h2>Intended Usage</h2>
 * <pre>
 * def p = PromiseFactory.vertx().createPromise()
 * p.accept(42)
 * p.then { it * 2 }.onComplete { println it }
 * </pre>
 *
 * @param <T> value type for this promise
 */
@Slf4j
@CompileStatic
@ToString(includeNames = true, excludes = ["vertx", "context"])
class VertxPromiseAdapter<T> implements SoftPromise<T> {

    /** Vert.x runtime instance (shared). */
    private final Vertx vertx

    /** Vert.x context captured at creation time. */
    private final Context context

    /** CRITICAL: Explicitly manage the promise lifecycle state. */
    private final AtomicReference<PromiseState> state = new AtomicReference<>(PromiseState.PENDING)

    /**
     * Backing Vert.x promise (null when wrapping external Future only).
     *
     * If non-null, this instance "owns" completion.
     * If null, completion methods become no-ops.
     */
    private final VertxPromise<T> promise

    /** Underlying Vert.x Future that represents the async result. */
    private final Future<T> future


    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * Construct an "owning" adapter from a new Vert.x Promise.
     *
     * @param promise Vert.x promise to wrap
     * @param vertx   Vert.x instance
     */
    VertxPromiseAdapter(VertxPromise<T> promise, Vertx vertx) {
        this.promise = promise
        this.future = promise.future()
        this.vertx = vertx
        this.context = vertx.getOrCreateContext()

        // Use the Vertx Future to update the state when it completes
        // Check initial state and set up handler for completion
        if (future.isComplete()) {
            if (future.succeeded()) { state.set(PromiseState.COMPLETED) }
            else if (future.cause() instanceof CancellationException) { state.set(PromiseState.CANCELLED) }
            else { state.set(PromiseState.FAILED) }
        } else {
            future.onComplete(
                    ({ AsyncResult<T> ar ->
                        if (ar.succeeded()) {
                            state.set(PromiseState.COMPLETED)
                        } else if (ar.cause() instanceof CancellationException) {
                            state.set(PromiseState.CANCELLED)
                        } else {
                            state.set(PromiseState.FAILED)
                        }
                    } as Handler<AsyncResult<T>>)
            )
        }
    }

    /**
     * Construct a "non-owning" adapter that wraps an external Vert.x Future.
     *
     * @param future external future
     * @param vertx  Vert.x instance
     */
    VertxPromiseAdapter(Future<T> future, Vertx vertx) {
        this.promise = null
        this.future = future
        this.vertx = vertx
        this.context = vertx.getOrCreateContext()
    }


    // -------------------------------------------------------------------------
    // Factories
    // -------------------------------------------------------------------------

    /**
     * Create a new, empty "owning" promise adapter.
     *
     * @param vertx Vert.x instance
     * @param <T>   value type
     * @return new owning adapter
     */
    static <T> VertxPromiseAdapter<T> create(Vertx vertx) {
        VertxPromise<T> p = VertxPromise.promise()
        return new VertxPromiseAdapter<>(p, vertx)
    }

    /**
     * Create a pre-succeeded promise adapter from a value.
     *
     * @param vertx Vert.x instance
     * @param value success value
     * @param <T>   value type
     * @return succeeded adapter
     */
    static <T> VertxPromiseAdapter<T> succeededPromise(Vertx vertx, T value) {
        return new VertxPromiseAdapter<>(Future.succeededFuture(value), vertx)
    }

    /**
     * Create a pre-failed promise adapter from an error.
     *
     * @param vertx Vert.x instance
     * @param t     error cause
     * @param <T>   value type
     * @return failed adapter
     */
    static <T> VertxPromiseAdapter<T> failedPromise(Vertx vertx, Throwable t) {
        return new VertxPromiseAdapter<>(Future.failedFuture(t), vertx)
    }


    // -------------------------------------------------------------------------
    // Completion API
    // -------------------------------------------------------------------------

    /**
     * Complete this promise with a value.
     *
     * <p>If this adapter is "non-owning", this is a no-op.</p>
     *
     * @param value value to complete with
     * @return this promise
     */
    @Override
    SoftPromise<T> accept(T value) {
        if (promise == null) return this

        if (state.compareAndSet(PromiseState.PENDING, PromiseState.COMPLETED)) {
            if (Vertx.currentContext() == context) {
                promise.tryComplete(value)
            } else {
                context.runOnContext { Void v -> promise.tryComplete(value) }
            }
        }
        return this
    }

    /**
     * Complete this promise by executing a Supplier asynchronously.
     *
     * <p>Executes via Vert.x worker pool, completes on original Context.</p>
     *
     * @param supplier blocking supplier for the value
     * @return this promise
     */
    @Override
    SoftPromise<T> accept(Supplier<T> supplier) {
        if (promise == null) {
            log.warn("accept(Supplier) called on non-owning adapter – ignoring")
            return this
        }

        Future<T> f = vertx.executeBlocking(
                { -> supplier.get() } as Callable<T>
        )

        f.onComplete({ AsyncResult<T> ar ->
            context.runOnContext { Void v ->
                if (ar.succeeded()) {
                    promise.tryComplete(ar.result())
                } else {
                    promise.tryFail(ar.cause())
                }
            }
        } as Handler<AsyncResult<T>>)

        return this
    }

    /**
     * Complete this promise by adopting the result of a {@link CompletableFuture}.
     *
     * @param external external future
     * @return this promise
     */
    @Override
    SoftPromise<T> accept(CompletableFuture<T> external) {
        if (promise == null) return this

        external.whenComplete { T val, Throwable err ->
            context.runOnContext { Void v ->
                if (err != null) {
                    promise.tryFail(err)
                } else {
                    promise.tryComplete(val)
                }
            }
        }
        return this
    }

    /**
     * Complete this promise by adopting another SoftPromise's completion.
     *
     * @param other another promise
     * @return this promise
     */
    @Override
    SoftPromise<T> accept(SoftPromise<T> other) {
        if (promise == null) return this

        other.onComplete { T v ->
            context.runOnContext { Void ignore -> promise.tryComplete(v) }
        }
        other.onError { Throwable e ->
            context.runOnContext { Void ignore -> promise.tryFail(e) }
        }

        return this
    }

    /**
     * Fail this promise with an error.
     *
     * @param error failure cause
     * @return this promise
     */
    @Override
    SoftPromise<T> fail(Throwable error) {
        if (promise == null) return this
        if (promise == null) return this

        // CRITICAL: Intercept cancellation and route to the cancel method for state update
        if (error instanceof java.util.concurrent.CancellationException) {
            cancel(false)
            return this
        }

        // 1. ATOMIC STATE CHECK: Only transition from PENDING to FAILED once
        if (state.compareAndSet(PromiseState.PENDING, PromiseState.FAILED)) {

            // 2. CONTEXT CHECK: Fail the VertxPromise on the correct context
            if (io.vertx.core.Vertx.currentContext() == context) {
                // Already on the correct context, fail immediately
                promise.fail(error)
            } else {
                // Hop to the original context to fail
                context.runOnContext {
                    promise.fail(error)
                }
            }
        }
        return this
    }


    // -------------------------------------------------------------------------
    // Blocking get
    // -------------------------------------------------------------------------

    /**
     * Block until the promise is complete, then return the result or throw the error.
     *
     * <p>Vert.x Futures do not support blocking operations, so a countdown latch
     * is used to simulate blocking semantics.</p>
     *
     * @return value
     * @throws Exception propagated error
     */
    @Override
    T get() throws Exception {
        if (!future.isComplete()) {
            CountDownLatch latch = new CountDownLatch(1)
            AtomicReference<T> result = new AtomicReference<>()
            AtomicReference<Throwable> err = new AtomicReference<>()

            future.onComplete({ AsyncResult<T> ar ->
                if (ar.succeeded()) {
                    result.set(ar.result())
                } else {
                    err.set(ar.cause())
                }
                latch.countDown()
            } as Handler<AsyncResult<T>>)

            latch.await()

            if (err.get() != null) {
                if (err.get() instanceof Exception) throw (Exception) err.get()
                throw new RuntimeException(err.get())
            }

            return result.get()
        }

        if (future.failed()) {
            Throwable t = future.cause()
            if (t instanceof Exception) throw (Exception) t
            throw new RuntimeException(t)
        }

        return future.result()
    }

    /**
     * Block until the promise is complete or the timeout expires.
     *
     * @param timeout duration
     * @param unit    time unit
     * @return value
     * @throws TimeoutException if timeout reached
     */
    @Override
    T get(long timeout, TimeUnit unit) throws TimeoutException {
        CountDownLatch latch = new CountDownLatch(1)
        AtomicReference<T> result = new AtomicReference<>()
        AtomicReference<Throwable> err = new AtomicReference<>()

        future.onComplete({ AsyncResult<T> ar ->
            if (ar.succeeded()) {
                result.set(ar.result())
            } else {
                err.set(ar.cause())
            }
            latch.countDown()
        } as Handler<AsyncResult<T>>)

        if (!latch.await(timeout, unit)) {
            throw new TimeoutException("Promise timed out after $timeout $unit")
        }

        if (err.get() != null) throw new RuntimeException(err.get())
        return result.get()
    }

    @Override
    boolean cancel(boolean mayInterruptIfRunning) {
        if (promise == null) return isCancelled()

        // 1. ATOMIC STATE CHECK: Only transition from PENDING to CANCELLED once
        if (state.compareAndSet(PromiseState.PENDING, PromiseState.CANCELLED)) {

            // 2. Vert.x Cancellation: Complete the Promise with CancellationException
            // This is how Vert.x flags a Future as cancelled.
            java.util.concurrent.CancellationException cancellationError =
                    new java.util.concurrent.CancellationException("Promise was cancelled.")

            if (Vertx.currentContext() == context) {
                // Already on the correct context
                promise.fail(cancellationError)
            } else {
                // Hop to the original context to fail
                context.runOnContext {
                    promise.fail(cancellationError)
                }
            }
            // Return true if the state transition was successful
            return true
        }

        // Already completed/failed/cancelled
        return isCancelled()
    }

    // -------------------------------------------------------------------------
    // State / Callbacks
    // -------------------------------------------------------------------------

    // ------------------------------------------------------------------------
    // State Check Operations
    // ------------------------------------------------------------------------

    /**
     * @see SoftPromise#isDone()
     */
    @Override
    boolean isDone() {
        // A promise is "done" if it's in any final state: COMPLETED, FAILED, or CANCELLED.
        return state.get() != org.softwood.promise.core.PromiseState.PENDING
    }

    /**
     * @see SoftPromise#isCancelled()
     */
    @Override
    boolean isCancelled() {
        // A promise is cancelled if the state is specifically CANCELLED.
        return state.get() == org.softwood.promise.core.PromiseState.CANCELLED
    }

    /**
     * @see SoftPromise#isCompleted()
     * Note: This usually means successfully completed (succeeded).
     */
    @Override
    boolean isCompleted() {
        // A promise is completed if the state is specifically COMPLETED (success).
        return state.get() == org.softwood.promise.core.PromiseState.COMPLETED
    }

    /**
     * Register a success callback.
     *
     * @param callback consumer receiving the successful value
     * @return this promise
     */
    @Override
    SoftPromise<T> onComplete(Consumer<T> callback) {
        future.onSuccess({ T v -> callback.accept(v) })
        return this
    }

    /**
     * Register an error callback.
     *
     * @param callback consumer receiving the failure
     * @return this promise
     */
    @Override
    SoftPromise<T> onError(Consumer<Throwable> callback) {
        future.onFailure({ Throwable e -> callback.accept(e) })
        return this
    }


    // -------------------------------------------------------------------------
    // then / recover
    // -------------------------------------------------------------------------

    /**
     * Transform the successful value into another value.
     *
     * @param fn mapping function
     * @param <R> new value type
     * @return new transformed promise
     */
    @Override
    <R> SoftPromise<R> then(Function<T, R> fn) {
        VertxPromiseAdapter<R> out = VertxPromiseAdapter.<R>create(vertx)

        this.onComplete { T v ->
            try {
                out.accept(fn.apply(v))
            } catch (Throwable t) {
                out.fail(t)
            }
        }

        this.onError { Throwable e -> out.fail(e) }

        return out
    }

    /**
     * Recover from a failure by computing a replacement value.
     *
     * @param recovery error → new value function
     * @param <R> new value type
     * @return recovered promise
     */
    @Override
    <R> SoftPromise<R> recover(Function<Throwable, R> recovery) {
        VertxPromiseAdapter<R> out = VertxPromiseAdapter.<R>create(vertx)

        this.onComplete { T v ->
            out.accept((R) v)
        }

        this.onError { Throwable e ->
            try {
                out.accept(recovery.apply(e))
            } catch (Throwable t) {
                out.fail(t)
            }
        }

        return out
    }


    // -------------------------------------------------------------------------
    // Functional API: map / flatMap / filter
    // -------------------------------------------------------------------------

    /**
     * Pure functional transform of the successful value.
     *
     * @param mapper mapping function
     * @param <R> new value type
     * @return mapped promise
     */
    @Override
    <R> SoftPromise<R> map(Function<? super T, ? extends R> mapper) {
        VertxPromiseAdapter<R> out = VertxPromiseAdapter.<R>create(vertx)

        this.onComplete { T v ->
            try {
                R mapped = (R) mapper.apply(v)
                out.accept(mapped)
            } catch (Throwable t) {
                out.fail(t)
            }
        }

        this.onError { Throwable e -> out.fail(e) }

        return out
    }

    /**
     * Flat-map continuation for sequencing async computations.
     *
     * @param mapper value → next Promise
     * @param <R> new value type
     * @return chained promise
     */
    @Override
    <R> SoftPromise<R> flatMap(Function<? super T, SoftPromise<R>> mapper) {
        VertxPromiseAdapter<R> out = VertxPromiseAdapter.<R>create(vertx)

        this.onComplete { T v ->
            SoftPromise<R> inner
            try {
                inner = mapper.apply(v)
                if (inner == null) {
                    out.fail(new NullPointerException("flatMap mapper returned null"))
                    return
                }
            } catch (Throwable t) {
                out.fail(t)
                return
            }

            inner.onComplete { R r -> out.accept(r) }
            inner.onError { Throwable e -> out.fail(e) }
        }

        this.onError { Throwable e -> out.fail(e) }
        return out
    }

    /**
     * Filter the successful value using a predicate.
     * If predicate fails, this promise fails with {@link NoSuchElementException}.
     *
     * @param predicate predicate to test
     * @return filtered promise
     */
    @Override
    SoftPromise<T> filter(Predicate<? super T> predicate) {
        VertxPromiseAdapter<T> out = VertxPromiseAdapter.<T>create(vertx)

        this.onComplete { T v ->
            try {
                if (predicate.test(v)) {
                    out.accept(v)
                } else {
                    out.fail(new NoSuchElementException("Predicate not satisfied"))
                }
            } catch (Throwable t) {
                out.fail(t)
            }
        }

        this.onError { Throwable e -> out.fail(e) }
        return out
    }


    // -------------------------------------------------------------------------
    // asType / interoperability
    // -------------------------------------------------------------------------

    /**
     * Convert to another asynchronous type.
     *
     * <p>Currently supports {@link CompletableFuture} only.</p>
     *
     * @param clazz target type
     * @return converted instance
     */
    @Override
    CompletableFuture<T> asType(Class clazz) {
        if (clazz == CompletableFuture) {
            return future.toCompletionStage().toCompletableFuture()
        }
        throw new RuntimeException("conversion to type $clazz not supported")
    }


    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /**
     * Access underlying Vert.x Future.
     */
    Future<T> getFuture() { future }

    /**
     * Vert.x runtime used by this promise.
     */
    Vertx getVertx() { vertx }

    /**
     * Original Vert.x context on which callbacks run.
     */
    Context getContext() { context }

    // =========================================================================
    // TIER 1 Enhancements - Implemented for Vertx backend
    // =========================================================================

    /**
     * Register a callback that receives both the value and error.
     * One will be null, the other will have the result.
     *
     * @param callback BiConsumer receiving (value, error)
     * @return this promise
     */
    @Override
    SoftPromise<T> whenComplete(java.util.function.BiConsumer<T, Throwable> callback) {
        future.onComplete({ AsyncResult<T> ar ->
            context.runOnContext { Void v ->
                if (ar.succeeded()) {
                    try {
                        callback.accept(ar.result(), null)
                    } catch (Throwable t) {
                        log.warn("Exception in whenComplete callback: ${t.message}", t)
                    }
                } else {
                    try {
                        callback.accept(null, ar.cause())
                    } catch (Throwable t) {
                        log.warn("Exception in whenComplete callback: ${t.message}", t)
                    }
                }
            }
        } as Handler<AsyncResult<T>>)
        return this
    }

    /**
     * Execute a side effect with the successful value without changing it.
     * Useful for logging, metrics, debugging.
     *
     * @param action side effect to perform
     * @return this promise (value unchanged)
     */
    @Override
    SoftPromise<T> tap(Consumer<T> action) {
        future.onSuccess({ T v ->
            context.runOnContext { Void ignored ->
                try {
                    action.accept(v)
                } catch (Throwable t) {
                    log.warn("Exception in tap: ${t.message}", t)
                }
            }
        })
        return this
    }

    /**
     * Apply a timeout to this promise. If the promise doesn't complete
     * within the specified time, it fails with TimeoutException.
     *
     * @param timeout timeout duration
     * @param unit time unit
     * @return new promise that times out
     */
    @Override
    SoftPromise<T> timeout(long timeout, TimeUnit unit) {
        VertxPromiseAdapter<T> result = VertxPromiseAdapter.<T>create(vertx)

        // Set up timeout timer
        long timerId = vertx.setTimer(unit.toMillis(timeout)) { Long id ->
            result.fail(new TimeoutException("Promise timed out after ${timeout} ${unit}"))
        }

        // Wire the original future to the result
        future.onComplete({ AsyncResult<T> ar ->
            vertx.cancelTimer(timerId) // Cancel timeout on completion
            if (ar.succeeded()) {
                result.accept(ar.result())
            } else {
                result.fail(ar.cause())
            }
        } as Handler<AsyncResult<T>>)

        return result
    }

    /**
     * Apply a timeout with a fallback value. If the promise doesn't complete
     * within the specified time, it completes with the fallback value.
     *
     * @param timeout timeout duration
     * @param unit time unit
     * @param fallbackValue value to use on timeout
     * @return new promise with timeout and fallback
     */
    @Override
    SoftPromise<T> timeout(long timeout, TimeUnit unit, T fallbackValue) {
        VertxPromiseAdapter<T> result = VertxPromiseAdapter.<T>create(vertx)

        // Set up timeout timer
        long timerId = vertx.setTimer(unit.toMillis(timeout)) { Long id ->
            result.accept(fallbackValue)
        }

        // Wire the original future to the result
        future.onComplete({ AsyncResult<T> ar ->
            vertx.cancelTimer(timerId) // Cancel timeout on completion
            if (ar.succeeded()) {
                result.accept(ar.result())
            } else {
                result.fail(ar.cause())
            }
        } as Handler<AsyncResult<T>>)

        return result
    }

    /**
     * Mutates this promise to timeout after the specified duration.
     * This modifies the current promise rather than creating a new one.
     *
     * @param timeout timeout duration
     * @param unit time unit
     * @return this promise
     */
    @Override
    SoftPromise<T> orTimeout(long timeout, TimeUnit unit) {
        if (promise == null) {
            log.warn("orTimeout called on non-owning adapter – cannot mutate")
            return this
        }

        vertx.setTimer(unit.toMillis(timeout)) { Long id ->
            if (!future.isComplete()) {
                promise.tryFail(new TimeoutException("Promise timed out after ${timeout} ${unit}"))
            }
        }

        return this
    }

    /**
     * Combine this promise with another, applying a combining function
     * when both complete successfully.
     *
     * @param other other promise to combine with
     * @param combiner function to combine the two values
     * @return new promise with combined result
     */
    @Override
    <U, R> SoftPromise<R> zip(SoftPromise<U> other, java.util.function.BiFunction<T, U, R> combiner) {
        VertxPromiseAdapter<R> result = VertxPromiseAdapter.<R>create(vertx)

        // Use atomic references to store values and track completion
        def value1 = new AtomicReference<T>()
        def value2 = new AtomicReference<U>()
        def completed1 = new java.util.concurrent.atomic.AtomicBoolean(false)
        def completed2 = new java.util.concurrent.atomic.AtomicBoolean(false)
        def failed = new java.util.concurrent.atomic.AtomicBoolean(false)

        // Handler for this promise
        this.onComplete { T v ->
            value1.set(v)
            completed1.set(true)

            // Check if both are complete
            if (completed2.get() && !failed.get()) {
                context.runOnContext { Void ignored ->
                    try {
                        R combined = combiner.apply(value1.get(), value2.get())
                        result.accept(combined)
                    } catch (Throwable t) {
                        result.fail(t)
                    }
                }
            }
        }

        this.onError { Throwable e ->
            if (failed.compareAndSet(false, true)) {
                result.fail(e)
            }
        }

        // Handler for other promise
        other.onComplete { U v ->
            value2.set(v)
            completed2.set(true)

            // Check if both are complete
            if (completed1.get() && !failed.get()) {
                context.runOnContext { Void ignored ->
                    try {
                        R combined = combiner.apply(value1.get(), value2.get())
                        result.accept(combined)
                    } catch (Throwable t) {
                        result.fail(t)
                    }
                }
            }
        }

        other.onError { Throwable e ->
            if (failed.compareAndSet(false, true)) {
                result.fail(e)
            }
        }

        return result
    }
}