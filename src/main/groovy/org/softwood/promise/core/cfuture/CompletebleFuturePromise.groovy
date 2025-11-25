package org.softwood.promise.core.cfuture

import groovy.transform.ToString
import groovy.util.logging.Slf4j
import org.softwood.promise.Promise

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Supplier

/**
 * {@link Promise} implementation backed by {@link CompletableFuture}.
 *
 * <p>{@code CompletableFuturePromise} is a minimal adapter that forwards all
 * completion and composition operations to an underlying {@link CompletableFuture}.
 * It provides interoperability with Java's standard async abstraction while
 * conforming to the {@link Promise} API.</p>
 *
 * @param <T> type of the promised value
 */
@Slf4j
@ToString
class CompletableFuturePromise<T> implements Promise<T> {

    /** Underlying Java {@link CompletableFuture}. */
    private final CompletableFuture<T> future

    /**
     * Construct a new adapter over an existing {@link CompletableFuture}.
     *
     * @param future future to wrap; must not be null
     */
    CompletableFuturePromise(CompletableFuture<T> future) {
        this.future = future
    }

    /** {@inheritDoc} */
    @Override
    Promise<T> accept(T value) {
        future.complete(value)
        return this
    }

    /** {@inheritDoc} */
    @Override
    Promise<T> accept(Supplier<T> supplier) {
        future.complete(supplier.get())
        return this
    }

    /**
     * Wire this promise to complete when the given {@link CompletableFuture} completes.
     *
     * <p>Success and failure are forwarded to this adapter's underlying future
     * via {@link CompletableFuture#complete(Object)} and
     * {@link CompletableFuture#completeExceptionally(Throwable)} respectively.</p>
     *
     * @param otherFuture source future
     * @return this promise for fluent chaining
     */
    @Override
    Promise<T> accept(CompletableFuture<T> otherFuture) {
        otherFuture.whenComplete { T value, Throwable error ->
            if (error != null) {
                future.completeExceptionally(error)
            } else {
                future.complete(value)
            }
        }
        return this
    }

    /**
     * Wire this promise to complete when the given {@link Promise} completes.
     *
     * <p>Success is forwarded to {@link CompletableFuture#complete(Object)};
     * failure is forwarded to {@link CompletableFuture#completeExceptionally(Throwable)}.</p>
     *
     * @param otherPromise source promise
     * @return this promise for fluent chaining
     */
    @Override
    Promise<T> accept(Promise<T> otherPromise) {
        otherPromise.onComplete { T v ->
            future.complete(v)
        }
        otherPromise.onError { Throwable e ->
            future.completeExceptionally(e)
        }
        return this
    }

    /** {@inheritDoc} */
    @Override
    T get() throws Exception {
        return future.get()
    }

    /** {@inheritDoc} */
    @Override
    T get(long timeout, TimeUnit unit) throws TimeoutException {
        // Note: underlying CompletableFuture.get may also throw checked exceptions
        // such as ExecutionException or InterruptedException; those will be surfaced
        // according to the Groovy/bytecode view of this signature.
        return future.get(timeout, unit)
    }

    /** {@inheritDoc} */
    @Override
    boolean isDone() {
        return future.isDone()
    }

    /** {@inheritDoc} */
    @Override
    Promise<T> onComplete(Consumer<T> callback) {
        future.thenAccept(callback)
        return this
    }

    /**
     * Transform the underlying {@link CompletableFuture} via {@link CompletableFuture#thenApply(Function)}.
     *
     * @param function transform from T to R
     * @param <R> new result type
     * @return a new {@link CompletableFuturePromise} wrapping the transformed future
     */
    @Override
    <R> Promise<R> then(Function<T, R> function) {
        CompletableFuture<R> mapped = future.thenApply(function)
        return new CompletableFuturePromise<R>(mapped)
    }

    /** {@inheritDoc} */
    @Override
    Promise<T> onError(Consumer<Throwable> errorHandler) {
        // We attach an exceptionally stage that only invokes the handler;
        // the returned future is ignored so as not to alter the main future's pipeline.
        future.exceptionally { Throwable error ->
            errorHandler.accept(error)
            return null
        }
        return this
    }

    /** {@inheritDoc} */
    @Override
    Promise<T> fail(Throwable error) {
        future.completeExceptionally(error)
        return this
    }

    /**
     * Recover from failure using {@code CompletableFuture#handle(BiFunction)} semantics.
     *
     * <p>If this promise completes successfully, the recovered promise completes with
     * the same value (cast to {@code R}). If it completes with an error, the
     * {@code recovery} function is invoked with that error (unwrapping
     * {@link CompletionException} when present) and its result is used as the
     * recovered value.</p>
     *
     * @param recovery mapping from {@link Throwable} to recovered value
     * @param <R> recovered result type
     * @return a new {@link CompletableFuturePromise} of {@code R}
     */
    @Override
    <R> Promise<R> recover(Function<Throwable, R> recovery) {
        CompletableFuture<R> recovered = future.handle { T value, Throwable error ->
            if (error == null) {
                // Success path – pass through value, cast to R
                return (R) value
            } else {
                // Unwrap CompletionException to expose underlying cause where possible
                Throwable cause = (error instanceof CompletionException && error.cause != null)
                        ? error.cause
                        : error
                return recovery.apply(cause)
            }
        }
        return new CompletableFuturePromise<R>(recovered)
    }

    /**
     * Groovy coercion hook. Supports coercion to {@link CompletableFuture}.
     *
     * @param clazz requested coercion class; must be {@link CompletableFuture}.class
     * @return the underlying {@link CompletableFuture}
     * @throws RuntimeException if clazz is not {@link CompletableFuture}.class
     */
    @Override
    CompletableFuture<T> asType(Class clazz) {
        if (clazz == CompletableFuture) {
            return this.future
        } else {
            throw new RuntimeException("conversion to type $clazz not supported")
        }
    }
}
