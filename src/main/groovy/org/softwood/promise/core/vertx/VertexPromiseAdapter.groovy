package org.softwood.promise.core.vertx

import groovy.util.logging.Slf4j
import io.vertx.core.Vertx
import org.softwood.promise.Promise

import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.Consumer
import java.util.function.Function

// ==================== Vert.x Implementation ====================

/**
 * Vert.x-based implementation of Promise
 */
@Slf4j
class VertxPromiseAdapter<T> implements Promise<T> {
    private final io.vertx.core.Future<T> future
    private final Vertx vertx

    VertxPromiseAdapter(io.vertx.core.Future<T> future, Vertx vertx) {
        this.future = future
        this.vertx = vertx
    }

    @Override
    T get() throws Exception {
        if (!future.isComplete()) {
            // Block until complete
            def latch = new java.util.concurrent.CountDownLatch(1)
            def result = new java.util.concurrent.atomic.AtomicReference<T>()
            def error = new java.util.concurrent.atomic.AtomicReference<Throwable>()

            future.onComplete { ar ->
                if (ar.succeeded()) {
                    result.set(ar.result())
                } else {
                    error.set(ar.cause())
                }
                latch.countDown()
            }

            latch.await()

            if (error.get()) {
                throw error.get()
            }
            return result.get()
        }

        if (future.succeeded()) {
            return future.result()
        } else {
            throw future.cause()
        }
    }

    @Override
    T get(long timeout, TimeUnit unit) throws TimeoutException {
        def latch = new java.util.concurrent.CountDownLatch(1)
        def result = new java.util.concurrent.atomic.AtomicReference<T>()
        def error = new java.util.concurrent.atomic.AtomicReference<Throwable>()

        future.onComplete { ar ->
            if (ar.succeeded()) {
                result.set(ar.result())
            } else {
                error.set(ar.cause())
            }
            latch.countDown()
        }

        if (!latch.await(timeout, unit)) {
            throw new TimeoutException("Promise timed out after $timeout $unit")
        }

        if (error.get()) {
            throw error.get()
        }
        return result.get()
    }

    @Override
    boolean isDone() {
        return future.isComplete()
    }

    @Override
    Promise<T> onComplete(Consumer<T> callback) {
        future.onSuccess { result ->
            callback.accept(result)
        }
        return this
    }

    @Override
    <R> Promise<R> then(Function<T, R> function) {
        def mapped = future.map { T value ->
            function.apply(value)
        }
        return new VertxPromiseAdapter<R>(mapped, vertx)
    }

    @Override
    Promise<T> onError(Consumer<Throwable> errorHandler) {
        future.onFailure { error ->
            errorHandler.accept(error)
        }
        return this
    }

    @Override
    <R> Promise<R> recover(Function<Throwable, R> recovery) {
        def recovered = future.recover { error ->
            io.vertx.core.Future.succeededFuture(recovery.apply(error))
        }
        return new VertxPromiseAdapter<R>(recovered, vertx)
    }
}