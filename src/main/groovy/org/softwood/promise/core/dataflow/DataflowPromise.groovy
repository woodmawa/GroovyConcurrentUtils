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
 * {@link Promise} implementation backed by a {@link DataflowVariable}.
 *
 * <p>This class adapts Softwood Dataflow primitives to the general Promise API.
 * A {@code DataflowVariable} implements single-assignment of either:</p>
 *
 * <ul>
 *   <li>a success value</li>
 *   <li>an error (exception)</li>
 * </ul>
 *
 * <h2>Design Goals</h2>
 * <ul>
 *   <li>Simple, predictable completion semantics</li>
 *   <li>Symmetric success &amp; error callback registration</li>
 *   <li>Promise-to-Promise chaining compatible with CompletableFuture and Vert.x</li>
 *   <li>Leverages {@link DataflowVariable} for all dataflow semantics</li>
 * </ul>
 *
 * <h2>Callback model</h2>
 * <ul>
 *   <li>{@code #onComplete(Consumer)} → uses {@link DataflowVariable#whenAvailable(java.util.function.Consumer)}</li>
 *   <li>{@code #onError(Consumer)}    → uses {@code DataflowVariable#whenError(java.util.function.Consumer)}</li>
 * </ul>
 *
 * <h2>Chaining</h2>
 * <ul>
 *   <li>{@code #then(Function)} transforms success values, propagating errors unchanged</li>
 *   <li>{@code #recover(Function)} transforms errors into replacement values, propagating success unchanged</li>
 * </ul>
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

    /**
     * {@inheritDoc}
     *
     * <p>Binds the provided value as the successful result of this promise.</p>
     */
    @Override
    Promise<T> accept(T value) {
        variable.bind(value)
        return this
    }

    /**
     * {@inheritDoc}
     *
     * <p>Evaluates the supplier exactly once and binds its result as the value.</p>
     */
    @Override
    Promise<T> accept(Supplier<T> supplier) {
        variable.bind(supplier.get())
        return this
    }

    /**
     * {@inheritDoc}
     *
     * <p>Completes this promise when the given {@link CompletableFuture} completes.
     * Completion is forwarded into the underlying {@link DataflowVariable}:</p>
     *
     * <ul>
     *   <li>On success → {@code bind(value)}</li>
     *   <li>On failure → {@code bindError(error)}</li>
     * </ul>
     *
     * @param future source future
     * @return this promise for fluent chaining
     */
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

    /**
     * {@inheritDoc}
     *
     * <p>Adopts the completion result (success or error) from another promise.</p>
     *
     * @param otherPromise promise whose completion should be mirrored
     * @return this promise for fluent chaining
     */
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

    /**
     * {@inheritDoc}
     *
     * <p>Binds an error to this promise, completing it exceptionally.</p>
     *
     * @param error failure cause
     * @return this promise for fluent chaining
     */
    @Override
    Promise<T> fail(Throwable error) {
        variable.bindError(error)
        return this
    }

    // -------------------------------------------------------------------------
    // Blocking Retrieval
    // -------------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link DataflowVariable#get()}, which may throw the stored error
     * if this promise completed exceptionally.</p>
     *
     * @return the value
     * @throws Exception if the promise completed with an error
     */
    @Override
    T get() throws Exception {
        return variable.get()
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link DataflowVariable#get(long, TimeUnit)}, which may throw
     * {@link TimeoutException} if the timeout expires, or the stored error if the
     * promise completed exceptionally.</p>
     *
     * @param timeout maximum time to wait
     * @param unit time unit of timeout
     * @return the value
     * @throws TimeoutException if not completed in time
     */
    @Override
    T get(long timeout, TimeUnit unit) throws TimeoutException {
        return variable.get(timeout, unit)
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns {@code true} if this promise has completed (either with value or error).</p>
     *
     * @return completion flag
     */
    @Override
    boolean isDone() {
        return variable.isDone()
    }

    // -------------------------------------------------------------------------
    // Callback Registration
    // -------------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Registers a callback executed once a success value becomes available.</p>
     * <p>If the value is already available, the callback is invoked immediately.</p>
     *
     * @param callback consumer of the successful value
     * @return this promise for fluent chaining
     */
    @Override
    Promise<T> onComplete(Consumer<T> callback) {
        variable.whenAvailable(callback)
        return this
    }

    /**
     * {@inheritDoc}
     *
     * <p>Registers a callback executed when this promise completes with an error.</p>
     * <p>If the promise has already failed at the time of registration, the callback
     * is invoked immediately.</p>
     *
     * @param handler consumer of {@link Throwable} representing the failure
     * @return this promise for fluent chaining
     */
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

    /**
     * {@inheritDoc}
     *
     * <p>Creates a new promise whose success value is the result of applying
     * the given function to this promise’s success value. Errors propagate
     * unchanged.</p>
     *
     * <p>Semantics:</p>
     * <ul>
     *   <li>If this promise succeeds, {@code fn} is applied to the value and the result
     *       becomes the value of the returned promise.</li>
     *   <li>If this promise fails, the returned promise fails with the same error.</li>
     *   <li>If {@code fn} itself throws, the returned promise fails with that exception.</li>
     * </ul>
     *
     * @param fn mapping from T to R
     * @param <R> result type
     * @return a new {@link Promise} of type R
     */
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

    /**
     * {@inheritDoc}
     *
     * <p>If this promise succeeds, the value passes through unchanged.</p>
     * <p>If it fails, the recovery function is invoked to produce a substitute value.</p>
     *
     * <p>Semantics:</p>
     * <ul>
     *   <li>Success → value cast to R and bound into the returned promise</li>
     *   <li>Error   → {@code recovery.apply(error)} is called and the result bound into the returned promise</li>
     *   <li>If {@code recovery} throws, the returned promise fails with that new exception</li>
     * </ul>
     *
     * @param recovery mapping from {@link Throwable} to recovered value
     * @param <R> recovered type
     * @return a new {@link Promise} representing the recovered computation
     */
    @Override
    <R> Promise<R> recover(Function<Throwable, R> recovery) {
        DataflowVariable<R> nextVar = new DataflowVariable<R>()

        // Success path
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

        // Error path
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

    /**
     * {@inheritDoc}
     *
     * <p>Supports conversion to {@link CompletableFuture} via
     * {@link DataflowVariable#toFuture()}.</p>
     *
     * @param clazz requested target type (must be {@link CompletableFuture}.class)
     * @return a {@link CompletableFuture} view of this promise
     * @throws RuntimeException if {@code clazz} is not {@link CompletableFuture}.class
     */
    @Override
    CompletableFuture<T> asType(Class clazz) {
        if (clazz == CompletableFuture) {
            return variable.toFuture()
        }
        throw new RuntimeException("conversion to type $clazz is not supported")
    }
}
