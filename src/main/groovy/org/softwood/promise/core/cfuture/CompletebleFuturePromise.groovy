package org.softwood.promise.core.cfuture

import groovy.util.logging.Slf4j
import org.softwood.promise.Promise

import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.Consumer
import java.util.function.Function

/**
 * CompletableFuture-based implementation of Promise
 */
@Slf4j
class CompletableFuturePromise<T> implements Promise<T> {
    private final java.util.concurrent.CompletableFuture<T> future

    CompletableFuturePromise(java.util.concurrent.CompletableFuture<T> future) {
        this.future = future
    }

    @Override
    T get() throws Exception {
        return future.get()
    }

    @Override
    T get(long timeout, TimeUnit unit) throws TimeoutException {
        return future.get(timeout, unit)
    }

    @Override
    boolean isDone() {
        return future.isDone()
    }

    @Override
    Promise<T> onComplete(Consumer<T> callback) {
        future.thenAccept(callback)
        return this
    }

    @Override
    <R> Promise<R> then(Function<T, R> function) {
        def mapped = future.thenApply(function)
        return new CompletableFuturePromise<R>(mapped)
    }

    @Override
    Promise<T> onError(Consumer<Throwable> errorHandler) {
        future.exceptionally { error ->
            errorHandler.accept(error)
            return null
        }
        return this
    }

    @Override
    <R> Promise<R> recover(Function<Throwable, R> recovery) {
        def recovered = future.exceptionally(recovery)
        return new CompletableFuturePromise<R>(recovered)
    }
}
