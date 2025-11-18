package org.softwood.promise.core.cfuture

import groovy.util.logging.Slf4j
import org.softwood.promise.Promise
import org.softwood.promise.PromiseFactory

/**
 * CompletableFuture-based implementation of PromiseFactory
 */
@Slf4j
class CompletableFuturePromiseFactory implements PromiseFactory {

    @Override
    <T> Promise<T> createPromise() {
        return new CompletableFuturePromise<T>(new java.util.concurrent.CompletableFuture<T>())
    }

    @Override
    <T> Promise<T> createPromise(T value) {
        return new CompletableFuturePromise<T>(
                java.util.concurrent.CompletableFuture.completedFuture(value)
        )
    }

    @Override
    <T> Promise<T> executeAsync(Closure<T> task) {
        return new CompletableFuturePromise<T>(
                java.util.concurrent.CompletableFuture.supplyAsync({ task.call() })
        )
    }
}
