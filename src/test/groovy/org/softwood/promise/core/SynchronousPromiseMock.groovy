package org.softwood.promise.core

import org.softwood.promise.Promise

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Predicate
import java.util.function.Supplier

/**
 * Synchronous deterministic mock Promise implementation.
 * Executes all continuations immediately on the calling thread.
 */
class SynchronousPromiseMock<T> implements Promise<T> {

    private boolean completed = false
    private boolean failed = false
    private boolean cancelled = false
    private T value
    private Throwable error

    @Override
    Promise<T> accept(T value) {
        if (completed) return this
        this.completed = true
        this.value = value
        return this
    }

    @Override
    Promise<T> accept(Supplier<T> supplier) {
        return accept(supplier.get())
    }

    @Override
    Promise<T> accept(CompletableFuture<T> future) {
        try {
            return accept(future.get())
        } catch (Throwable t) {
            return fail(t)
        }
    }

    @Override
    Promise<T> accept(Promise<T> otherPromise) {
        try {
            return accept(otherPromise.get())
        } catch (Throwable t) {
            return fail(t)
        }
    }

    @Override
    T get() throws Exception {
        if (failed) {
            if (error instanceof Exception) throw (Exception) error
            if (error instanceof RuntimeException) throw (RuntimeException) error
            throw new RuntimeException(error)
        }
        return value
    }

    @Override
    T get(long timeout, TimeUnit unit) throws TimeoutException {
        return get()
    }

    @Override
    boolean isDone() { completed || failed || cancelled }

    @Override
    boolean isCompleted() { isDone() }

    // ----------------------
    // Callbacks
    // ----------------------

    @Override
    Promise<T> onComplete(Consumer<T> callback) {
        if (completed) callback.accept(value)
        return this
    }

    @Override
    Promise<T> onError(Consumer<Throwable> errorHandler) {
        if (failed) errorHandler.accept(error)
        return this
    }

    // ----------------------
    // Transformations
    // ----------------------

    @Override
    <R> Promise<R> then(Function<T, R> function) {
        if (failed) {
            def p = new SynchronousPromiseMock<R>()
            p.fail(error)
            return p
        }
        return new SynchronousPromiseMock<R>().accept(function.apply(value))
    }

    @Override
    Promise<T> fail(Throwable error) {
        if (completed || cancelled) return this
        this.failed = true
        this.error = error
        return this
    }

    @Override
    <R> Promise<R> recover(Function<Throwable, R> recovery) {
        if (!failed) {
            def p = new SynchronousPromiseMock<R>()
            p.fail(error)
            return p
        }
        return new SynchronousPromiseMock<R>().accept(recovery.apply(error))
    }

    @Override
    CompletableFuture<T> asType(Class clazz) {
        def cf = new CompletableFuture<T>()
        if (failed) cf.completeExceptionally(error)
        else cf.complete(value)
        return cf
    }

    @Override
    <R> Promise<R> map(Function<? super T, ? extends R> mapper) {
        if (failed) {
            return new SynchronousPromiseMock<R>().fail(error)
        }
        return new SynchronousPromiseMock<R>().accept(mapper.apply(value))
    }

    @Override
    <R> Promise<R> flatMap(Function<? super T, Promise<R>> mapper) {
        if (failed) {
            return new SynchronousPromiseMock<R>().fail(error)
        }
        try {
            return mapper.apply(value)
        } catch (Throwable t) {
            return new SynchronousPromiseMock<R>().fail(t)
        }
    }

    @Override
    Promise<T> filter(Predicate<? super T> predicate) {
        if (!failed && !predicate.test(value)) {
            // Create a new failed promise instead of modifying this one
            def failedPromise = new SynchronousPromiseMock<T>()
            failedPromise.failed = true
            failedPromise.error = new IllegalStateException("Predicate rejected value: $value")
            return failedPromise
        }
        return this
    }

    @Override
    boolean cancel(boolean mayInterruptIfRunning) {
        cancelled = true
        return true
    }

    @Override
    boolean isCancelled() {
        return cancelled
    }
}
