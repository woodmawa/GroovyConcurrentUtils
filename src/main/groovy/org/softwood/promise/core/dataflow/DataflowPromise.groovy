package org.softwood.promise.core.dataflow

import groovy.transform.ToString
import groovy.util.logging.Slf4j
import org.softwood.dataflow.DataflowVariable
import org.softwood.promise.Promise

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Predicate
import java.util.function.Supplier

/**
 * {@link Promise} implementation backed by a {@link DataflowVariable}.
 *
 * <p>This class adapts Softwood Dataflow primitives to the general Promise API.</p>
 *
 * @param <T> value type of this promise
 * @see DataflowVariable
 * @see Promise
 */
@Slf4j
@ToString
class DataflowPromise<T> implements Promise<T> {

    /** Backing dataflow variable (single-assignment semantics). */
    private final DataflowVariable<T> variable

    /**
     * Construct a Promise over a specific dataflow variable.
     *
     * @param variable dataflow backing variable (must not be null)
     */
    DataflowPromise(DataflowVariable<T> variable) {
        this.variable = variable
    }

    // -------------------------------------------------------------------------
    // Completion Operations
    // -------------------------------------------------------------------------

    /** {@inheritDoc} */
    @Override
    Promise<T> accept(T value) {
        variable.bind(value)
        return this
    }

    /**
     * {@inheritDoc}
     *
     * <p>The supplier is always executed asynchronously on the executor associated with the
     * backing {@link DataflowVariable}, and its result is bound to this promise.</p>
     */
    @Override
    Promise<T> accept(Supplier<T> supplier) {
        if (supplier == null) return this

        // Execute supplier asynchronously to avoid blocking the caller thread.
        variable.executor.submit({
            try {
                T value = supplier.get()
                variable.bind(value)
            } catch (Throwable error) {
                variable.bindError(error)
            }
        } as Runnable)
        return this
    }

    /** {@inheritDoc} */
    @Override
    Promise<T> accept(CompletableFuture<T> future) {
        if (future == null) return this

        future.whenComplete { T value, Throwable error ->
            if (error != null) {
                variable.bindError(error)
            } else {
                variable.bind(value)
            }
        }
        return this
    }

    /** {@inheritDoc} */
    @Override
    Promise<T> accept(Promise<T> otherPromise) {
        if (otherPromise == null) return this

        otherPromise.onComplete { T v ->
            variable.bind(v)
        }
        otherPromise.onError { Throwable e ->
            variable.bindError(e)
        }
        return this
    }

    /** {@inheritDoc} */
    @Override
    Promise<T> fail(Throwable error) {
        if (error != null) {
            variable.bindError(error)
        }
        return this
    }

    // -------------------------------------------------------------------------
    // Blocking Retrieval
    // -------------------------------------------------------------------------

    /** {@inheritDoc} */
    @Override
    T get() throws Exception {
        return variable.get()
    }

    /**
     * Promise timeout semantics: unlike {@link org.softwood.dataflow.DataflowVariable#get(long, TimeUnit)},
     * this method is required by the {@link Promise} API to throw {@link TimeoutException}
     * when the timeout elapses.
     *
     * <p>We therefore delegate to a {@link CompletableFuture} view obtained via
     * {@link DataflowVariable#toFuture()}, which preserves standard timeout behaviour.</p>
     */
    @Override
    T get(long timeout, TimeUnit unit) throws TimeoutException {
        CompletableFuture<T> future = variable.toFuture()
        try {
            return future.get(timeout, unit)
        } catch (TimeoutException e) {
            throw e
        } catch (ExecutionException e) {
            Throwable cause = e.getCause()
            if (cause instanceof Exception) {
                throw (Exception) cause
            }
            throw new RuntimeException(cause)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
            throw new RuntimeException(e)
        }
    }

    /** {@inheritDoc} */
    @Override
    boolean isDone() {
        return variable.isDone()
    }

    // -------------------------------------------------------------------------
    // Callback Registration
    // -------------------------------------------------------------------------

    /** {@inheritDoc} */
    @Override
    Promise<T> onComplete(Consumer<T> callback) {
        if (callback == null) return this
        variable.whenAvailable(callback)
        return this
    }

    /** {@inheritDoc} */
    @Override
    Promise<T> onError(Consumer<Throwable> handler) {
        if (handler == null) return this
        variable.whenError { Throwable err ->
            handler.accept(err)
        }
        return this
    }

    // -------------------------------------------------------------------------
    // Transformations
    // -------------------------------------------------------------------------

    /** {@inheritDoc} */
    @Override
    <R> Promise<R> then(Function<T, R> fn) {
        if (fn == null) {
            // Pass-through promise when no transform is supplied.
            @SuppressWarnings("unchecked")
            Promise<R> self = (Promise<R>) this
            return self
        }

        // Preserve the underlying dataflow execution context by reusing the DFV's pool.
        DataflowVariable<R> nextVar = new DataflowVariable<R>(variable.pool)

        // Success path
        variable.whenAvailable { T value ->
            if (!variable.hasError()) {
                try {
                    nextVar.bind(fn.apply(value))
                } catch (Throwable e) {
                    nextVar.bindError(e)
                }
            }
        }

        // Error path
        variable.whenError { Throwable err ->
            nextVar.bindError(err)
        }

        return new DataflowPromise<R>(nextVar)
    }

    /** {@inheritDoc} */
    @Override
    <R> Promise<R> recover(Function<Throwable, R> recovery) {
        if (recovery == null) {
            @SuppressWarnings("unchecked")
            Promise<R> self = (Promise<R>) this
            return self
        }

        // Preserve the underlying dataflow execution context by reusing the DFV's pool.
        DataflowVariable<R> nextVar = new DataflowVariable<R>(variable.pool)

        // Success path: pass through unchanged
        variable.whenAvailable { Object value ->
            if (!variable.hasError()) {
                try {
                    @SuppressWarnings("unchecked")
                    R castValue = (R) value
                    nextVar.bind(castValue)
                } catch (Throwable e) {
                    nextVar.bindError(e)
                }
            }
        }

        // Error path: map error to value
        variable.whenError { Throwable error ->
            try {
                R recovered = recovery.apply(error)
                nextVar.bind(recovered)
            } catch (Throwable e) {
                nextVar.bindError(e)
            }
        }

        return new DataflowPromise<R>(nextVar)
    }

    // -------------------------------------------------------------------------
    //  Additional fluent transformations
    // -------------------------------------------------------------------------

    /**
     * Syntactic sugar for {@link #then(Function)}.
     *
     * <p>Transforms the successful value of this promise using {@code fn}. Errors are
     * propagated unchanged.</p>
     *
     * @param fn mapping function
     * @param <R> result type
     * @return a new {@link DataflowPromise} representing the transformed value
     */


    @Override
    <R> Promise<R> map(Function<? super T, ? extends R> mapper) {
        if (mapper == null) {
            @SuppressWarnings("unchecked")
            Promise<R> self = (Promise<R>) this
            return self
        }
        return this.then { T v -> mapper.apply(v) }
    }
    /**
     * Monadic flatMap / bind.
     *
     * <p>Transforms the successful value of this promise into another {@link Promise} and
     * <em>flattens</em> the result so that callers observe a single-level promise.</p>
     *
     * <pre>
     * DataflowPromise&lt;User&gt; userPromise = ...
     * DataflowPromise&lt;Account&gt; account =
     *     userPromise.flatMap(u -&gt; accountService.lookup(u.id))
     * </pre>
     *
     * @param fn function mapping the successful value to another {@link Promise}
     * @param <R> result type of the inner promise
     * @return a new {@link DataflowPromise} that completes when the inner promise completes
     */
    @Override
    <R> Promise<R> flatMap(Function<? super T, Promise<R>> mapper) {
        if (mapper == null) {
            @SuppressWarnings("unchecked")
            Promise<R> self = (Promise<R>) this
            return self
        }

        DataflowVariable<R> nextVar = new DataflowVariable<R>(variable.pool)

        this.onComplete { T v ->
            try {
                Promise<R> inner = mapper.apply(v)
                if (inner == null) {
                    nextVar.bindError(new NullPointerException("flatMap mapper returned null promise"))
                    return
                }

                inner.onComplete { R r ->
                    nextVar.bind(r)
                }

                inner.onError { Throwable e ->
                    nextVar.bindError(e)
                }
            } catch (Throwable t) {
                nextVar.bindError(t)
            }
        }

        this.onError { Throwable e -> nextVar.bindError(e) }

        return new DataflowPromise<R>(nextVar)
    }

    /**
     * Filters the successful value of this promise with a predicate.
     *
     * <p>If the predicate returns {@code true}, the value is propagated. If it returns
     * {@code false}, the returned promise is completed exceptionally with a
     * {@link java.util.NoSuchElementException}. Errors from this promise are propagated
     * unchanged.</p>
     *
     * @param predicate predicate to apply to the value
     * @return a new {@link DataflowPromise} that either propagates the value or fails
     */
    DataflowPromise<T> filter(Function<T, Boolean> predicate) {
        if (predicate == null) {
            return this
        }

        DataflowVariable<T> nextVar = new DataflowVariable<T>(variable.pool)

        variable.whenAvailable { T value ->
            if (!variable.hasError()) {
                try {
                    Boolean keep = predicate.apply(value)
                    if (Boolean.TRUE.equals(keep)) {
                        nextVar.bind(value)
                    } else {
                        nextVar.bindError(new NoSuchElementException("Predicate rejected value: " + value))
                    }
                } catch (Throwable e) {
                    nextVar.bindError(e)
                }
            }
        }

        variable.whenError { Throwable err ->
            nextVar.bindError(err)
        }

        return new DataflowPromise<T>(nextVar)
    }

    @Override
    Promise<T> filter(Predicate<? super T> predicate) {  // Return Promise<T> instead of DataflowPromise<T>
        if (predicate == null) {
            return this
        }

        DataflowVariable<T> nextVar = new DataflowVariable<T>(variable.pool)

        this.onComplete { T v ->
            try {
                if (predicate.test(v)) {
                    nextVar.bind(v)  // Could use bind() directly on nextVar
                } else {
                    nextVar.bindError(new NoSuchElementException("Predicate not satisfied"))
                }
            } catch (Throwable t) {
                nextVar.bindError(t)
            }
        }

        this.onError { Throwable e ->
            nextVar.bindError(e)
        }

        return new DataflowPromise<T>(nextVar)
    }

    // -------------------------------------------------------------------------
    // Type Conversion
    // -------------------------------------------------------------------------

    /**
     * Groovy type-coercion hook.
     *
     * <p>Currently only conversion to {@link CompletableFuture} is supported. For example:</p>
     *
     * <pre>
     * CompletableFuture<String> cf = (CompletableFuture<String>) promise.asType(CompletableFuture)
     * </pre>
     *
     * @param clazz target type (only {@link CompletableFuture} is currently supported)
     * @return converted instance
     */
    @Override
    CompletableFuture<T> asType(Class clazz) {
        if (clazz == CompletableFuture) {
            return variable.toFuture()
        }
        throw new RuntimeException("conversion to type $clazz is not supported")
    }
}
