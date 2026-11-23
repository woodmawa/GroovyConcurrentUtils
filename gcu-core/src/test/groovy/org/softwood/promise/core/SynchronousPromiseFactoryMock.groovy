package org.softwood.promise.core

import org.softwood.promise.Promise
import org.softwood.promise.PromiseFactory

import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.function.Function
import java.util.function.Supplier

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
    def <T> Promise<T> executeAsync(Callable<T> task) {
        try {
            return new SynchronousPromiseMock<T>().accept(task.call())
        } catch (Throwable t) {
            return new SynchronousPromiseMock<T>().fail(t)
        }
    }

    @Override
    Promise<Void> executeAsync(Runnable task) {
        try {
            task.run()
            return new SynchronousPromiseMock<Void>().accept(null)
        } catch (Throwable t) {
            return new SynchronousPromiseMock<Void>().fail(t)
        }
    }

    @Override
    def <T> Promise<T> executeAsync(Supplier<T> task) {
        try {
            return new SynchronousPromiseMock<T>().accept(task.get())
        } catch (Throwable t) {
            return new SynchronousPromiseMock<T>().fail(t)
        }
    }

    @Override
    def <T, R> Promise<R> executeAsync(Function<T, R> fn, T input) {
        try {
            return new SynchronousPromiseMock<R>().accept(fn.apply(input))
        } catch (Throwable t) {
            return new SynchronousPromiseMock<R>().fail(t)
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
