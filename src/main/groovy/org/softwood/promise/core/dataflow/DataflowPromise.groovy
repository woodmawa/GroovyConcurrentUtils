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

    @Override
    Promise<T> accept(T value) {
        variable.bind(value)
        return this
    }

    @Override
    Promise<T> accept(Supplier<T> supplier) {
        variable.bind(supplier.get())
        return this
    }

    @Override
    Promise<T> accept(CompletableFuture<T> future) {
        future.whenComplete { T value, Throwable error ->
            if (error != null) {
                variable.bindError(error)
            } else {
                variable.bind(value)
            }
        }
        return this
    }

    @Override
    Promise<T> accept(Promise<T> otherPromise) {
        otherPromise.onComplete { T v ->
            variable.bind(v)
        }
        otherPromise.onError { Throwable e ->
            variable.bindError(e)
        }
        return this
    }

    @Override
    Promise<T> fail(Throwable error) {
        variable.bindError(error)
        return this
    }

    // -------------------------------------------------------------------------
    // Blocking Retrieval
    // -------------------------------------------------------------------------

    @Override
    T get() throws Exception {
        return variable.get()
    }

    /**
     * Promise timeout semantics: unlike {@link DataflowVariable#get(long, TimeUnit)},
     * this method is required by the {@link Promise} API to throw {@link TimeoutException}
     * when the timeout elapses.
     *
     * <p>We therefore delegate directly to the underlying {@link java.util.concurrent.CompletableFuture}
     * via {@link DataflowVariable#toFuture()}, ignoring the DFV-specific timeout behaviour
     * (which returns null and records an error).</p>
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

    @Override
    boolean isDone() {
        return variable.isDone()
    }

    // -------------------------------------------------------------------------
    // Callback Registration
    // -------------------------------------------------------------------------

    @Override
    Promise<T> onComplete(Consumer<T> callback) {
        variable.whenAvailable(callback)
        return this
    }

    @Override
    Promise<T> onError(Consumer<Throwable> handler) {
        variable.whenError { Throwable err ->
            handler.accept(err)
        }
        return this
    }

    // -------------------------------------------------------------------------
    // Transformations
    // -------------------------------------------------------------------------

    @Override
    <R> Promise<R> then(Function<T, R> fn) {
        DataflowVariable<R> nextVar = new DataflowVariable<R>()

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

    @Override
    <R> Promise<R> recover(Function<Throwable, R> recovery) {
        DataflowVariable<R> nextVar = new DataflowVariable<R>()

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
    // Type Conversion
    // -------------------------------------------------------------------------

    @Override
    CompletableFuture<T> asType(Class clazz) {
        if (clazz == CompletableFuture) {
            return variable.toFuture()
        }
        throw new RuntimeException("conversion to type $clazz is not supported")
    }
}
