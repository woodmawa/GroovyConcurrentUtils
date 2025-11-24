package org.softwood.promise.core.cfuture

import groovy.transform.ToString
import groovy.util.logging.Slf4j
import org.softwood.promise.Promise

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Supplier

/**
 * CompletableFuture-based implementation of Promise
 */
@Slf4j
@ToString
class CompletableFuturePromise<T> implements Promise<T> {
    private final CompletableFuture<T> future

    CompletableFuturePromise(CompletableFuture<T> future) {
        this.future = future
    }

    CompletableFuture asType (CompletableFuture) {
        future
    }

    @Override
    Promise<T> accept(T value) {
        future.complete(value)
        return this
    }

    @Override
    Promise<T> accept(Supplier<T> closureValue) {
        future.complete(closureValue.get())
        return this
    }

    @Override
    Promise<T> accept(CompletableFuture<T> future) {
        return null
    }

    @Override
    Promise<T> accept(Promise<T> otherPromise) {
        return null
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
    <T> Promise<T> recover(Function<Throwable, T> recovery) {
        // Explicitly cast your `future` because Groovy erases generics
        CompletableFuture<T> cf = (CompletableFuture<T>) future

        // Cast recovery to match Java signature: Function<Throwable, ? extends T>
        Function<Throwable, ? extends T> fn =
                (Function<Throwable, ? extends T>) recovery

        CompletableFuture<T> recovered = cf.exceptionally(fn)

        return new CompletableFuturePromise<T>(recovered)
    }

    @Override
    CompletableFuture<T> asType(Class clazz) {
        if (clazz == CompletableFuture)
            return this
        else {
            throw new RuntimeException("conversion to type $clazz not supported")
        }

    }
}
