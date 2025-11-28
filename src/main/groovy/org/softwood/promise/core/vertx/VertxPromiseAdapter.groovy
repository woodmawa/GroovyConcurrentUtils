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
import java.util.function.Predicate
import java.util.function.Supplier

/**
 * Vert.x-backed implementation of the SoftPromise API.
 *
 * Runs blocking work via Vert.x worker pool and always returns results on
 * the original captured Vert.x Context.
 */
@Slf4j
@CompileStatic
@ToString(includeNames = true, excludes = ["vertx", "context"])
class VertxPromiseAdapter<T> implements SoftPromise<T> {

    private final Vertx vertx
    private final Context context
    private final VertxPromise<T> promise       // null when wrapping external Future
    private final Future<T> future

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    VertxPromiseAdapter(VertxPromise<T> promise, Vertx vertx) {
        this.promise = promise
        this.future = promise.future()
        this.vertx = vertx
        this.context = vertx.getOrCreateContext()
    }

    VertxPromiseAdapter(Future<T> future, Vertx vertx) {
        this.promise = null
        this.future = future
        this.vertx = vertx
        this.context = vertx.getOrCreateContext()
    }

    // -------------------------------------------------------------------------
    // Factories
    // -------------------------------------------------------------------------

    static <T> VertxPromiseAdapter<T> create(Vertx vertx) {
        VertxPromise<T> p = VertxPromise.promise()
        return new VertxPromiseAdapter<>(p, vertx)
    }

    static <T> VertxPromiseAdapter<T> succeededPromise(Vertx vertx, T value) {
        return new VertxPromiseAdapter<>(Future.succeededFuture(value), vertx)
    }

    static <T> VertxPromiseAdapter<T> failedPromise(Vertx vertx, Throwable t) {
        return new VertxPromiseAdapter<>(Future.failedFuture(t), vertx)
    }

    // -------------------------------------------------------------------------
    // Completion API
    // -------------------------------------------------------------------------

    @Override
    SoftPromise<T> accept(T value) {
        if (promise == null) return this

        if (Vertx.currentContext() == context) {
            promise.tryComplete(value)
        } else {
            context.runOnContext { Void v -> promise.tryComplete(value) }
        }
        return this
    }

    @Override
    SoftPromise<T> accept(Supplier<T> supplier) {
        if (promise == null) return this

        if (promise == null) {
            log.warn("accept(Supplier) called on non-owning adapter – ignoring")
            return this
        }

        // Vert.x 5: executeBlocking(Callable<T>) -> Future<T>
        Future<T> f = vertx.executeBlocking(
                { ->
                    // This runs on a Vert.x worker thread, NOT the event loop
                    supplier.get()
                } as Callable<T>
        )

        // Hop the result back onto our captured context (event loop or worker)
        f.onComplete { AsyncResult<T> ar ->
            context.runOnContext { Void v ->
                if (ar.succeeded()) {
                    promise.tryComplete(ar.result())
                } else {
                    promise.tryFail(ar.cause())
                }
            }
        }

        return this
    }

    @Override
    SoftPromise<T> accept(CompletableFuture<T> external) {
        if (promise == null) return this

        external.whenComplete { T val, Throwable err ->
            context.runOnContext { Void v ->
                if (err != null) promise.tryFail(err)
                else promise.tryComplete(val)
            }
        }
        return this
    }

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

    @Override
    SoftPromise<T> fail(Throwable error) {
        if (promise == null) return this
        context.runOnContext { Void v -> promise.tryFail(error) }
        return this
    }

    // -------------------------------------------------------------------------
    // Blocking get
    // -------------------------------------------------------------------------

    @Override
    T get() throws Exception {
        if (!future.isComplete()) {
            CountDownLatch latch = new CountDownLatch(1)
            AtomicReference<T> result = new AtomicReference<>()
            AtomicReference<Throwable> err = new AtomicReference<>()

            future.onComplete({ AsyncResult<T> ar ->
                if (ar.succeeded()) result.set(ar.result())
                else err.set(ar.cause())
                latch.countDown()
            })

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

    @Override
    T get(long timeout, TimeUnit unit) throws TimeoutException {
        CountDownLatch latch = new CountDownLatch(1)
        AtomicReference<T> result = new AtomicReference<>()
        AtomicReference<Throwable> err = new AtomicReference<>()

        future.onComplete({ AsyncResult<T> ar ->
            if (ar.succeeded()) result.set(ar.result())
            else err.set(ar.cause())
            latch.countDown()
        })

        if (!latch.await(timeout, unit)) {
            throw new TimeoutException("Promise timed out after $timeout $unit")
        }

        if (err.get() != null) throw new RuntimeException(err.get())
        return result.get()
    }

    // -------------------------------------------------------------------------
    // State / Callbacks
    // -------------------------------------------------------------------------

    @Override
    boolean isDone() {
        return future.isComplete()
    }

    @Override
    SoftPromise<T> onComplete(Consumer<T> callback) {
        future.onSuccess({ T v -> callback.accept(v) })
        return this
    }

    @Override
    SoftPromise<T> onError(Consumer<Throwable> callback) {
        future.onFailure({ Throwable e -> callback.accept(e) })
        return this
    }

    // -------------------------------------------------------------------------
    // then / recover
    // -------------------------------------------------------------------------

    @Override
    <R> SoftPromise<R> then(Function<T, R> fn) {
        Future<R> mapped = future.map({ T v -> fn.apply(v) })
        return new VertxPromiseAdapter<>(mapped, vertx)
    }

    @Override
    <R> SoftPromise<R> recover(Function<Throwable, R> recovery) {
        Future<R> rec = future.transform({ AsyncResult<T> ar ->
            if (ar.succeeded()) {
                Future.succeededFuture((R) ar.result())
            } else {
                try {
                    Future.succeededFuture(recovery.apply(ar.cause()))
                } catch (Throwable t) {
                    Future.failedFuture(t)
                }
            }
        })
        return new VertxPromiseAdapter<>(rec, vertx)
    }

    // -------------------------------------------------------------------------
    // Functional API: map / flatMap / filter
    // -------------------------------------------------------------------------

    @Override
    <R> SoftPromise<R> map(Function<? super T, ? extends R> mapper) {
        Future<R> mapped = future.map({ T v -> mapper.apply(v) })
        return new VertxPromiseAdapter<>(mapped, vertx)
    }

    @Override
    <R> SoftPromise<R> flatMap(Function<? super T, SoftPromise<R>> mapper) {
        VertxPromiseAdapter<R> out = VertxPromiseAdapter.create(vertx)

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

    @Override
    SoftPromise<T> filter(Predicate<? super T> predicate) {
        VertxPromiseAdapter<T> out = VertxPromiseAdapter.create(vertx)

        this.onComplete { T v ->
            try {
                if (predicate.test(v)) out.accept(v)
                else out.fail(new NoSuchElementException("Predicate not satisfied"))
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

    Future<T> getFuture() { future }
    Vertx getVertx() { vertx }
    Context getContext() { context }
}
