package org.softwood.promise.core.cfuture

import groovy.util.logging.Slf4j
import org.softwood.promise.Promise
import org.softwood.promise.PromiseFactory

import java.util.concurrent.CompletableFuture

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

    @Override
    <T> Promise<T> executeAsync(Closure<T> task) {
        return new CompletableFuturePromise<T>(
                CompletableFuture.supplyAsync({ task.call() })
        )
    }
}
