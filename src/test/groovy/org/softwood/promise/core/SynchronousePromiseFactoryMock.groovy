package org.softwood.promise.core

import org.softwood.promise.Promise
import org.softwood.promise.PromiseFactory

import java.util.concurrent.CompletableFuture

/**
 * Factory for creating synchronous Promise mocks.
 * Everything executes immediately on current thread.
 */
class SynchronousPromiseFactoryMock implements PromiseFactory {

    @Override
    def <T> Promise<T> createPromise() {
        return new SynchronousPromiseMock<T>()
    }

    @Override
    def <T> Promise<T> createPromise(T value) {
        return new SynchronousPromiseMock<T>().accept(value)
    }

    @Override
    def <T> Promise<T> createFailedPromise(Throwable cause) {
        return new SynchronousPromiseMock<T>().fail(cause)
    }

    @Override
    def <T> Promise<T> executeAsync(Closure<T> task) {
        // Synchronously execute closure
        try {
            return new SynchronousPromiseMock<T>().accept(task.call())
        } catch (Throwable t) {
            return new SynchronousPromiseMock<T>().fail(t)
        }
    }

    @Override
    def <T> Promise<T> from(CompletableFuture<T> future) {
        return new SynchronousPromiseMock<T>().accept(future)
    }

    @Override
    def <T> Promise<T> from(Promise<T> otherPromise) {
        return new SynchronousPromiseMock<T>().accept(otherPromise)
    }
}
