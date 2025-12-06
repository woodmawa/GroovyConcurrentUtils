package org.softwood.promise.core.cfuture

import groovy.util.logging.Slf4j
import org.softwood.promise.Promise
import org.softwood.promise.PromiseFactory

import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.function.Function
import java.util.function.Supplier

/**
 * CompletableFuture-based implementation of PromiseFactory
 */
@Slf4j
class CompletableFuturePromiseFactory implements PromiseFactory {

    @Override
    <T> Promise<T> createPromise() {
        return new CompletableFuturePromise<T>(new CompletableFuture<T>())
    }

    @Override
    <T> Promise<T> createPromise(T value) {
        return new CompletableFuturePromise<T>(
                CompletableFuture.completedFuture(value)
        )
    }

    <T> Promise<T> createFailedPromise(Throwable cause) {
        def cf = new CompletableFuture<T>()
        cf.completeExceptionally(cause)
        return new CompletableFuturePromise<T>(cf)
    }

    @Override
    <T> Promise<T> executeAsync(Closure<T> task) {
        return new CompletableFuturePromise<T>(
                CompletableFuture.supplyAsync({ task.call() })
        )
    }

    // ---------------------------------------------------------------------
    // Async execution implementations (Lambdas + Closure)
    // ---------------------------------------------------------------------

    @Override
    def <T> Promise<T> executeAsync(Callable<T> task) {
        return new CompletableFuturePromise<T>(
                CompletableFuture.supplyAsync({
                    try {
                        return task.call()
                    } catch (Throwable e) {
                        throw new RuntimeException(e)
                    }
                })
        )
    }

    @Override
    Promise<Void> executeAsync(Runnable task) {
        CompletableFuture<Void> cf = CompletableFuture
                        .runAsync(task)
                        .thenRun (null)

        return new CompletableFuturePromise<Void>(cf)
    }

    @Override
    def <T> Promise<T> executeAsync(Supplier<T> supplier) {
        return new CompletableFuturePromise<T>(
                CompletableFuture.supplyAsync({
                    try {
                        return supplier.get()
                    } catch (Throwable e) {
                        throw new RuntimeException(e)
                    }
                })
        )
    }

    @Override
    def <T, R> Promise<R> executeAsync(Function<T, R> fn, T input) {
        return new CompletableFuturePromise<R>(
                CompletableFuture.supplyAsync({
                    try {
                        return fn.apply(input)
                    } catch (Throwable e) {
                        throw new RuntimeException(e)
                    }
                })
        )
    }

    @Override
    def <T> Promise<T> from(CompletableFuture<T> future) {
        return null
    }

    @Override
    def <T> Promise<T> from(Promise<T> otherPromise) {
        return null
    }
}
