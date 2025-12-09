package org.softwood.promise.core.cfuture

import groovy.transform.ToString
import groovy.util.logging.Slf4j
import org.softwood.promise.Promise
import org.softwood.promise.core.PromiseState

import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
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

    /** CRITICAL: Explicitly manage the promise lifecycle state. */
    private final AtomicReference<PromiseState> state = new AtomicReference<>(PromiseState.PENDING) // New Field

    CompletableFuturePromise(CompletableFuture<T> future) {
        this.future = future
        // Initialize state for already resolved futures (optional, but robust)
        if (future.isDone()) {
            if (future.isCancelled()) {
                state.set(PromiseState.CANCELLED)
            } else if (future.isCompletedExceptionally()) {
                state.set(PromiseState.FAILED)
            } else {
                state.set(PromiseState.COMPLETED)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Completion
    // -------------------------------------------------------------------------

    @Override
    Promise<T> accept(T value) {
        if (state.compareAndSet(PromiseState.PENDING, PromiseState.COMPLETED)) {
            // Complete the underlying CompletableFuture
            future.complete(value)
        }
        return this
    }

    @Override
    Promise<T> accept(Supplier<T> supplier) {
        // Execute only if PENDING (the base accept/fail handles the final state transition)
        if (state.get() == PromiseState.PENDING) {
            try {
                T value = supplier.get()
                accept(value) // Calls state-managed accept(T value)
            } catch (Throwable error) {
                fail(error) // Calls state-managed fail(Throwable error)
            }
        }
        return this
    }

    @Override
    Promise<T> accept(CompletableFuture<T> other) {
        // Register callbacks that call this Promise's state-managed methods.
        other.whenComplete { value, error ->
            if (error != null) {
                // Unwrap error and call fail, which handles CancellationException
                Throwable actualError = (error instanceof CompletionException)
                        ? error.cause ?: error : error
                fail(actualError)
            } else {
                accept(value)
            }
        }
        return this
    }

    @Override
    Promise<T> accept(Promise<T> other) {
        // Register callbacks that call this Promise's state-managed methods.
        other.onComplete { T v ->
            accept(v) // Calls state-managed accept(T value)
        }
        other.onError { Throwable e ->
            fail(e)   // Calls state-managed fail(Throwable error), which correctly handles cancellation
        }
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

    // -------------------------------------------------------------------------
    // Cancellation and State Check Operations (NEW)
    // -------------------------------------------------------------------------

    @Override
    boolean cancel(boolean mayInterruptIfRunning) {
        if (state.compareAndSet(PromiseState.PENDING, PromiseState.CANCELLED)) {
            // Cancel the underlying CompletableFuture
            future.cancel(mayInterruptIfRunning)
            return true
        }

        // Return true if already cancelled, false otherwise (COMPLETED/FAILED)
        return isCancelled()
    }

    @Override
    boolean isCancelled() {
        // Check the explicit state, which is guaranteed to be set by cancel() or fail() interception
        return state.get() == PromiseState.CANCELLED
    }

    @Override
    boolean isDone() {
        return state.get() != PromiseState.PENDING
    }

    @Override
    boolean isCompleted() {
        return state.get() == PromiseState.COMPLETED
    }

    // ... all other methods (then, map, flatMap, get, etc.) remain the same,
    // relying on the underlying `future` or the new state checks (`isDone`, `isCancelled`)
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
        // CRITICAL CHECK: If the error is CancellationException, delegate to cancel()
        if (error instanceof CancellationException) {
            cancel(false)
            return this
        }

        if (state.compareAndSet(PromiseState.PENDING, PromiseState.FAILED)) {
            // Complete the underlying CompletableFuture with an exception
            future.completeExceptionally(error)
        }
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
                return CompletableFuture.failedFuture(t) as CompletableFuture<T>
            }

            // Extract inner CompletableFuture
            if (!(inner instanceof CompletableFuturePromise)) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException("flatMap: Promise must be a CompletableFuturePromise")
                ) as CompletableFuture<T>
            }

            CompletableFuturePromise<R> cfInner = (CompletableFuturePromise<R>) inner
            return cfInner.future as CompletableFuture<T>
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
                    return CompletableFuture.completedFuture(v) as CompletableFuture<T>
                else
                    return CompletableFuture.failedFuture(new NoSuchElementException("Predicate not satisfied")) as CompletableFuture<T>
            } catch (Throwable t) {
                return CompletableFuture.failedFuture(t) as CompletableFuture<T>
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

    // =========================================================================
    // TIER 1 Enhancements - Implemented for CompletableFuture backend
    // =========================================================================

    @Override
    Promise<T> whenComplete(java.util.function.BiConsumer<T, Throwable> callback) {
        future.whenComplete(callback)
        return this
    }

    @Override
    Promise<T> tap(Consumer<T> action) {
        future.thenAccept { T value ->
            try {
                action.accept(value)
            } catch (Throwable t) {
                log.warn("Exception in tap: ${t.message}", t)
            }
        }
        return this
    }

    @Override
    Promise<T> timeout(long timeout, TimeUnit unit) {
        // CompletableFuture has built-in orTimeout that throws TimeoutException
        CompletableFuture<T> timeoutFuture = future.orTimeout(timeout, unit)
        return new CompletableFuturePromise<T>(timeoutFuture)
    }

    @Override
    Promise<T> timeout(long timeout, TimeUnit unit, T fallbackValue) {
        // Use completeOnTimeout which provides fallback instead of exception
        CompletableFuture<T> timeoutFuture = future.completeOnTimeout(fallbackValue, timeout, unit)
        return new CompletableFuturePromise<T>(timeoutFuture)
    }

    @Override
    Promise<T> orTimeout(long timeout, TimeUnit unit) {
        // Mutates this promise to timeout
        future.orTimeout(timeout, unit)
        return this
    }

    @Override
    <U, R> Promise<R> zip(Promise<U> other, java.util.function.BiFunction<T, U, R> combiner) {
        // Extract the CompletableFuture from the other promise
        CompletableFuture<U> otherFuture
        if (other instanceof CompletableFuturePromise) {
            otherFuture = ((CompletableFuturePromise<U>) other).future
        } else {
            // Convert to CompletableFuture if it's a different implementation
            otherFuture = new CompletableFuture<U>()
            other.onComplete { U value -> otherFuture.complete(value) }
            other.onError { Throwable error -> otherFuture.completeExceptionally(error) }
        }

        // Use thenCombine which waits for both to complete
        CompletableFuture<R> combined = future.thenCombine(otherFuture, combiner)
        return new CompletableFuturePromise<R>(combined)
    }
}