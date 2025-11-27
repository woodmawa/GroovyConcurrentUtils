package org.softwood.dataflow

import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.softwood.pool.ConcurrentPool

import java.time.Duration
import java.util.Optional
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.BiConsumer
import java.util.function.BiFunction
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Supplier

/**
 * A specialised {@link DataflowExpression} that behaves like a single-assignment
 * value container with full dataflow semantics and promise-compatible convenience API.
 *
 * <h2>Main Features:</h2>
 * <ul>
 *     <li>Thread-safe single assignment</li>
 *     <li>Synchronous and asynchronous listeners (whenBound)</li>
 *     <li>Promise-compatible success/error listeners:
 *         <ul>
 *             <li>{@code #whenAvailable(Consumer)}</li>
 *             <li>{@code #whenError(Consumer)}</li>
 *         </ul>
 *     </li>
 *     <li>Groovy operator support via {@code #methodMissing(String, Object)}</li>
 *     <li>Chaining via {@code #then(Function)} and {@code #recover(Function)}</li>
 *     <li>Non-blocking value inspection via {@code #getIfAvailable()}</li>
 * </ul>
 *
 * <p>This class is the foundation underlying {@code DataflowPromise}.</p>
 *
 * @param <T> type of value carried by this variable
 */
@CompileStatic
@Slf4j
class DataflowVariable<T> extends DataflowExpression<T> {

    // =============================================================================================
    // Constructors
    // =============================================================================================

    /**
     * Create a DFV backed by a default worker pool.
     */
    DataflowVariable() {
        super(new ConcurrentPool())
    }

    /**
     * Create a DFV backed by a specific worker pool.
     */
    DataflowVariable(ConcurrentPool pool) {
        super(pool)
    }

    /**
     * Create a DFV with an explicit type and pool.
     */
    DataflowVariable(ConcurrentPool pool, Class<T> type) {
        super(pool, type)
    }

    // =============================================================================================
    // Binding (success/error)
    // =============================================================================================

    /**
     * Bind this variable to a value, synchronously notifying all listeners.
     *
     * @param val new value
     * @return this variable
     */
    DataflowVariable<T> set(T val) {
        setValue(val)     // synchronous notification
        log.debug "df now set to $val"
        return this
    }

    /**
     * Alias for {@link #set(Object)} for Groovy convenience.
     */
    void bind(Object v) {
        set((T) v)
    }

    /**
     * Bind an exception to this DFV.
     *
     * @param t error
     * @return this DFV
     */
    DataflowVariable<T> bindError(Throwable t) {
        super.setError(t)
        log.debug "df now set to error: ${t.message}"
        return this
    }

    // =============================================================================================
    // Getting values (sync)
    // =============================================================================================

    /**
     * Get the value, blocking until available.
     *
     * @return value if successful
     * @throws DataflowException    if the DFV was completed with error
     * @throws InterruptedException if interrupted while waiting
     */
    T get() {
        return (T) super.getValue()
    }

    /**
     * Get the value with timeout.
     *
     * <p>DataflowVariable semantics:
     * <ul>
     *     <li>If timeout elapses, return {@code null}</li>
     *     <li>Record a timeout error in the DFV</li>
     * </ul>
     * </p>
     */
    T get(long timeout, TimeUnit unit) {
        try {
            return (T) super.getValue(timeout, unit)
        } catch (TimeoutException e) {
            bindError(e)
            return null
        }
    }

    /**
     * Duration-based timeout version.
     */
    T get(Duration duration) {
        return get(duration.toMillis(), TimeUnit.MILLISECONDS)
    }

    /**
     * Non-blocking read: returns null if pending or if error.
     */
    T getNonBlocking() {
        if (isSuccess()) return get()
        return null
    }

    /**
     * Groovy call-operator support: {@code dfv()} <=> {@link #get()}.
     */
    T call() { return get() }

    // =============================================================================================
    // DFV state inspection
    // =============================================================================================

    /**
     * Whether the DFV is complete (success or error).
     */
    boolean isDone() {
        return isBound()
    }

    /**
     * A DFV is successful if:
     * <ul>
     *     <li>It is bound</li>
     *     <li>It has no error</li>
     * </ul>
     */
    boolean isSuccess() {
        return isBound() && !hasError()
    }

    /**
     * Whether the DFV has an error.
     */
    boolean hasError() {
        return super.hasError()
    }

    /**
     * Optional getter for testing and convenience.
     */
    Optional<T> getIfAvailable() {
        if (!isSuccess()) return Optional.empty()
        try { return Optional.ofNullable(get()) }
        catch (Throwable ignore) { return Optional.empty() }
    }

    /**
     * Expose this variable as a {@link CompletableFuture} for interoperability with
     * standard Future/Promise-based APIs (e.g. {@code DataflowPromise}).
     *
     * <p>Semantics:</p>
     * <ul>
     *     <li>If the DFV is already successful, the returned future is completed with the value.</li>
     *     <li>If the DFV is already in error, the returned future is completed exceptionally
     *         with that error.</li>
     *     <li>If the DFV is still pending, the future is completed when the DFV completes,
     *         either normally or exceptionally.</li>
     *     <li>Each call returns a new, independent future view.</li>
     * </ul>
     *
     * @return a {@link CompletableFuture} reflecting this variable's completion
     */
    CompletableFuture<T> toFuture() {
        CompletableFuture<T> cf = new CompletableFuture<>()

        // Already completed?
        if (isBound()) {
            if (hasError()) {
                cf.completeExceptionally(getError())
            } else {
                try {
                    cf.complete(get())
                } catch (Throwable t) {
                    cf.completeExceptionally(t)
                }
            }
            return cf
        }

        // Still pending: wire listeners.
        whenAvailable { T v ->
            if (!cf.isDone()) {
                cf.complete(v)
            }
        }

        whenError { Throwable t ->
            if (!cf.isDone()) {
                cf.completeExceptionally(t)
            }
        }

        return cf
    }

    // =============================================================================================
    // Promise-compatible async notifications
    // =============================================================================================

    /**
     * Register a success listener.
     *
     * <p>Guarantees:</p>
     * <ul>
     *     <li>If already successful → invoked immediately</li>
     *     <li>If pending → invoked when value binds</li>
     *     <li>If error → never invoked</li>
     * </ul>
     *
     * @return this DFV
     */
    DataflowVariable<T> whenAvailable(Consumer<? super T> callback) {
        if (isSuccess()) {
            callback.accept(get())
        } else if (!hasError()) {
            whenBound { T v ->
                if (!hasError()) {
                    callback.accept(v)
                }
            }
        }
        return this
    }

    /**
     * Register an error listener (Groovy Closure version).
     *
     * <p>Guarantees:</p>
     * <ul>
     *     <li>If already in error → invoked immediately</li>
     *     <li>If pending → invoked when error binds</li>
     *     <li>If success → never invoked</li>
     * </ul>
     */
    DataflowVariable<T> whenError(Closure<?> handler) {
        if (hasError()) {
            handler.call(getError())
        } else {
            whenBound {
                if (hasError()) {
                    handler.call(getError())
                }
            }
        }
        return this
    }

    /**
     * Java Consumer variant of {@link #whenError(Closure)}.
     */
    DataflowVariable<T> whenError(Consumer<Throwable> handler) {
        return whenError { Throwable t -> handler.accept(t) }
    }

    // =============================================================================================
    // Chaining (then/recover)
    // =============================================================================================

    /**
     * Groovy closure transform.
     */
    def <R> DataflowVariable<R> then(Closure<R> xform) {
        DataflowVariable<R> out = new DataflowVariable<R>(pool)

        // success path
        whenAvailable { T v ->
            try {
                R r = (xform.maximumNumberOfParameters == 0 ?
                        (R) xform.call() :
                        (R) xform.call(v))
                out.set(r)
            } catch (Throwable t) {
                out.bindError(t)
            }
        }

        // error path
        whenError { Throwable ex -> out.bindError(ex) }

        return out
    }

    /**
     * Java function transform.
     */
    def <R> DataflowVariable<R> then(Function<? super T, ? extends R> fn) {
        return then({ T v -> fn.apply(v) } as Closure<R>)
    }

    /**
     * Broad transform covering Closure, Callable, Function, Consumer.
     */
    DataflowVariable then(Callable callable) {
        Closure<?> closure

        if (callable instanceof Closure) {
            Closure c = (Closure) callable
            closure = { v -> c.maximumNumberOfParameters == 0 ? c.call() : c.call(v) }
        }
        else if (callable instanceof Function) {
            closure = { v -> ((Function) callable).apply(v) }
        }
        else if (callable instanceof Consumer) {
            closure = { v -> ((Consumer) callable).accept(v); v }
        }
        else if (callable instanceof Callable) {
            closure = { v -> callable.call() }
        }
        else {
            closure = callable as Closure
        }

        return then((Closure) closure)
    }

    /**
     * Error recovery transform.
     */
    DataflowVariable<T> recover(Function<Throwable, T> fn) {
        DataflowVariable<T> out = new DataflowVariable<T>(pool)

        // success path
        whenAvailable { T v -> out.set(v) }

        // error path
        whenError { Throwable ex ->
            try {
                out.set(fn.apply(ex))
            } catch (Throwable t) {
                out.bindError(t)
            }
        }

        return out
    }

    // =============================================================================================
    // Dynamic operator support (plus, minus, multiply, div)
    // =============================================================================================

    /**
     * Ensure any constant operand is wrapped as a DVF.
     */
    private DataflowVariable wrapOperand(Object operand) {
        if (operand instanceof DataflowVariable) return operand
        DataflowVariable dfv = new DataflowVariable(pool)
        dfv.set(operand)
        return dfv
    }

    /**
     * Dynamic operator handling for Groovy DSL expressions.
     *
     * Supported ops:
     * <ul>
     *     <li>"plus"</li>
     *     <li>"minus"</li>
     *     <li>"multiply"</li>
     *     <li>"div"</li>
     * </ul>
     */
    @CompileDynamic
    def methodMissing(String name, Object args) {
        Object[] arr = (Object[]) args
        Object rawOther = arr.length > 0 ? arr[0] : null

        DataflowVariable rhs = wrapOperand(rawOther)

        Closure op
        switch (name) {
            case "plus":     op = { a, b -> a + b }; break
            case "minus":    op = { a, b -> a - b }; break
            case "multiply": op = { a, b -> a * b }; break
            case "div":      op = { a, b -> a / b }; break
            default:
                throw new MissingMethodException(name, this.class, args)
        }

        DataflowVariable result = new DataflowVariable(pool)

        this.whenAvailable { av ->
            rhs.whenAvailable { bv ->
                try { result.set(op.call(av, bv)) }
                catch (Throwable t) { result.bindError(t) }
            }
            rhs.whenError { Throwable t -> result.bindError(t) }
        }

        this.whenError { Throwable t -> result.bindError(t) }

        return result
    }

    // =============================================================================================
    // toString
    // =============================================================================================

    @Override
    String toString() {
        return "DataflowVariable(${super.toString()})"
    }
}
