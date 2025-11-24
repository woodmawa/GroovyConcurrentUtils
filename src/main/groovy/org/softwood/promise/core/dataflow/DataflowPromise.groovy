package org.softwood.promise.core.dataflow

import groovy.transform.ToString
import groovy.util.logging.Slf4j
import org.softwood.dataflow.DataflowVariable
import org.softwood.promise.Promise

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Supplier

/**
 * DataflowVariable-backed implementation of {@link Promise}.
 *
 * <p>A DataflowPromise is a thin wrapper over a {@link DataflowVariable} that
 * provides the Promise abstraction. Transformations create new DataflowPromises
 * with their own DataflowVariables.</p>
 *
 * <h3>Threading / execution</h3>
 * <p>Binding a value is single-assignment and thread-safe. Callback and chaining
 * operations are executed by the underlying dataflow machinery.</p>
 *
 * @param <T> bound value type
 */
@Slf4j
@ToString
class DataflowPromise<T> implements Promise<T> {

    /** Underlying dataflow primitive for storage and callback dispatch. */
    private final DataflowVariable<T> variable

    /**
     * Construct a promise over a specific dataflow variable.
     *
     * @param variable backing DataflowVariable
     */
    DataflowPromise(DataflowVariable<T> variable) {
        this.variable = variable
    }

    /** {@inheritDoc} */
    @Override
    Promise<T> accept(T value) {
        variable.bind(value)
        return this
    }

    /** {@inheritDoc} */
    @Override
    Promise<T> accept(Supplier<T> supplier) {
        variable.bind(supplier.get())
        return this
    }

    /** {@inheritDoc} */
    @Override
    Promise<T> accept(CompletableFuture<T> future) {
        future.whenComplete { value, error ->
            if (error != null) variable.bindError(error)
            else variable.bind(value)
        }
        return this
    }

    /** {@inheritDoc} */
    @Override
    Promise<T> accept(Promise<T> otherPromise) {
        otherPromise.onComplete { v -> variable.bind(v) }
        otherPromise.onError { e -> variable.bindError(e) }
        return this
    }

    /** {@inheritDoc} */
    @Override
    T get() throws Exception {
        return variable.get()
    }

    /** {@inheritDoc} */
    @Override
    T get(long timeout, TimeUnit unit) throws TimeoutException {
        return variable.get(timeout, unit)
    }

    /** {@inheritDoc} */
    @Override
    boolean isDone() {
        return variable.isDone()
    }

    /** {@inheritDoc} */
    @Override
    Promise<T> onComplete(Consumer<T> callback) {
        variable.whenAvailable(callback)
        return this
    }

    /**
     * Transform this promise’s successful completion into a new promise.
     *
     * <p>We don't use DataflowVariable.then() directly because it returns a
     * DataflowExpression in the base API; instead we create a fresh DataflowVariable
     * and bind into it.</p>
     *
     * @param function mapping T -> R
     * @param <R> result type
     * @return new Promise<R>
     */
    @Override
    <R> Promise<R> then(Function<T, R> function) {
        DataflowVariable<R> nextVar = new DataflowVariable<R>()

        variable.whenAvailable { T value ->
            try {
                nextVar.bind(function.apply(value))
            } catch (Throwable e) {
                nextVar.bindError(e)
            }
        }

        return new DataflowPromise<R>(nextVar)
    }

    /** {@inheritDoc} */
    @Override
    Promise<T> onError(Consumer<Throwable> errorHandler) {
        variable.whenAvailable {
            if (variable.hasError()) errorHandler.accept(variable.getError())
        }
        return this
    }

    /** {@inheritDoc} */
    @Override
    <R> Promise<R> recover(Function<Throwable, R> recovery) {
        DataflowVariable<R> nextVar = new DataflowVariable<R>()

        variable.whenAvailable { val ->
            if (!variable.hasError()) {
                try {
                    nextVar.bind((R) val)
                } catch (Throwable e) {
                    nextVar.bindError(e)
                }
            } else {
                try {
                    nextVar.bind(recovery.apply(variable.getError()))
                } catch (Throwable e) {
                    nextVar.bindError(e)
                }
            }
        }

        return new DataflowPromise<R>(nextVar)
    }

    /**
     * Convert to CompletableFuture for interop with Java APIs.
     *
     * @param ignored Groovy asType parameter
     * @return a CompletableFuture view of this promise
     */
    @Override
    CompletableFuture<T> asType(Class clazz) {
        if (clazz == CompletableFuture)
            return variable.toFuture()
        else {
            throw new RuntimeException ("conversion to type $clazz is not supported")
        }
    }
}
