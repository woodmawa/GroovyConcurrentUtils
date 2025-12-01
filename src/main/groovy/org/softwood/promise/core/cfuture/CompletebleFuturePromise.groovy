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
import java.util.function.Predicate
import java.util.function.Supplier

/**
 * {@link Promise} implementation backed by {@link CompletableFuture}.
 *
 * <p>This adapter provides a full compliance layer between the enriched
 * {@link org.softwood.promise.Promise} API and Java's standard
 * {@link CompletableFuture}.</p>
 *
 * <h2>Supported operations:</h2>
 * <ul>
 *   <li>Direct completion ({@link #accept})</li>
 *   <li>Lazy supplier completion ({@link #accept})</li>
 *   <li>Completion via another Promise or CompletableFuture</li>
 *   <li>Blocking {@link #get} and timed {@link #get}</li>
 *   <li>Functional transforms: {@link #then}, {@link #map}, {@link #flatMap}, {@link #recover}</li>
 *   <li>Filtering: {@link #filter}</li>
 *   <li>Error observation via {@link #onError}</li>
 * </ul>
 *
 * <p>This implementation performs no special threading behaviour—
 * all async chaining uses the default execution rules of {@link CompletableFuture}.</p>
 *
 * @param <T> value type
 */
@Slf4j
@ToString(includeNames = true)
class CompletableFuturePromise<T> implements Promise<T> {

    /** Wrapped Java {@link CompletableFuture}. */
    private final CompletableFuture<T> future

    CompletableFuturePromise(CompletableFuture<T> future) {
        this.future = future
    }

    // -------------------------------------------------------------------------
    // Completion
    // -------------------------------------------------------------------------

    @Override
    Promise<T> accept(T value) {
        future.complete(value)
        return this
    }

    @Override
    Promise<T> accept(Supplier<T> supplier) {
        try {
            future.complete(supplier.get())
        } catch (Throwable e) {
            future.completeExceptionally(e)
        }
        return this
    }

    @Override
    Promise<T> accept(CompletableFuture<T> other) {
        other.whenComplete { T val, Throwable err ->
            if (err != null) future.completeExceptionally(err)
            else future.complete(val)
        }
        return this
    }

    @Override
    Promise<T> accept(Promise<T> other) {
        other.onComplete { T v -> future.complete(v) }
        other.onError { Throwable e -> future.completeExceptionally(e) }
        return this
    }

    // -------------------------------------------------------------------------
    // Blocking Getters
    // -------------------------------------------------------------------------

    @Override
    T get() throws Exception {
        return future.get()
    }

    @Override
    T get(long timeout, TimeUnit unit) throws TimeoutException {
        try {
            return future.get(timeout, unit)
        } catch (TimeoutException te) {
            throw te
        }
    }

    @Override
    boolean isDone() {
        return future.isDone()
    }

    @Override
    boolean isCompleted() {
        return future.isDone()
    }

    // -------------------------------------------------------------------------
    // Callbacks
    // -------------------------------------------------------------------------

    @Override
    Promise<T> onComplete(Consumer<T> callback) {
        future.thenAccept(callback)
        return this
    }

    @Override
    Promise<T> onError(Consumer<Throwable> callback) {
        future.exceptionally { Throwable err ->
            callback.accept(err)
            return null
        }
        return this
    }

    // -------------------------------------------------------------------------
    // Basic transforms: then / recover
    // -------------------------------------------------------------------------

    @Override
    <R> Promise<R> then(Function<T, R> fn) {
        CompletableFuture<R> mapped = future.thenApply(fn)
        return new CompletableFuturePromise<R>(mapped)
    }

    @Override
    Promise<T> fail(Throwable error) {
        future.completeExceptionally(error)
        return this
    }

    @Override
    <R> Promise<R> recover(Function<Throwable, R> fn) {
        CompletableFuture<R> recovered = future.handle { T val, Throwable err ->
            if (err == null) {
                return (R) val
            } else {
                Throwable cause = (err instanceof CompletionException && err.cause != null)
                        ? err.cause
                        : err
                return fn.apply(cause)
            }
        }
        return new CompletableFuturePromise<R>(recovered)
    }

    // -------------------------------------------------------------------------
    // Functional API: map / flatMap / filter
    // -------------------------------------------------------------------------

    /**
     * @see Promise#map(Function)
     */
    @Override
    <R> Promise<R> map(Function<? super T, ? extends R> mapper) {
        CompletableFuture<R> mapped = future.thenApply { T v ->
            mapper.apply(v)
        }
        return new CompletableFuturePromise<R>(mapped)
    }

    /**
     * @see Promise#flatMap(Function)
     */
    @Override
    <R> Promise<R> flatMap(Function<? super T, Promise<R>> mapper) {
        CompletableFuture<R> chained = future.thenCompose { T v ->
            Promise<R> inner
            try {
                inner = mapper.apply(v)
                if (inner == null)
                    throw new NullPointerException("flatMap mapper returned null Promise")
            } catch (Throwable t) {
                return CompletableFuture.failedFuture(t)
            }

            // Extract inner CompletableFuture
            if (!(inner instanceof CompletableFuturePromise)) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException("flatMap: Promise must be a CompletableFuturePromise")
                )
            }

            CompletableFuturePromise<R> cfInner = (CompletableFuturePromise<R>) inner
            return cfInner.future
        }

        return new CompletableFuturePromise<R>(chained)
    }

    /**
     * @see Promise#filter(Predicate)
     */
    @Override
    Promise<T> filter(Predicate<? super T> predicate) {
        CompletableFuture<T> filtered = future.thenCompose { T v ->
            try {
                if (predicate.test(v))
                    return CompletableFuture.completedFuture(v)
                else
                    return CompletableFuture.failedFuture(new NoSuchElementException("Predicate not satisfied"))
            } catch (Throwable t) {
                return CompletableFuture.failedFuture(t)
            }
        }
        return new CompletableFuturePromise<T>(filtered)
    }

    // -------------------------------------------------------------------------
    // asType
    // -------------------------------------------------------------------------

    @Override
    CompletableFuture<T> asType(Class clazz) {
        if (clazz == CompletableFuture) {
            return future
        }
        throw new RuntimeException("conversion to type $clazz not supported")
    }
}
