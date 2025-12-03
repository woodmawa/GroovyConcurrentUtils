package org.softwood.dataflow

import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.transform.ToString
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
@ToString
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
     * Bind this variable to a value.
     *
     * <p>This is the primary API for completing a {@code DataflowVariable}. It delegates to
     * {@link DataflowExpression#setValue(Object)} and triggers asynchronous listener
     * notification.</p>
     *
     * @param v new value
     */
    void bind(Object v) {
        setValue((T) v)
        log.debug "df now set to $v"
    }

    /**
     * Legacy alias for {@link #bind(Object)}.
     *
     * @param val new value
     * @return this variable
     * @deprecated use {@link #bind(Object)} instead
     */
    @Deprecated
    DataflowVariable<T> set(T val) {
        bind(val)
        return this
    }

    /**
     * Groovy left-shift operator alias for {@link #bind(Object)}.
     *
     * @param val new value
     * @return this variable
     * @deprecated use {@link #bind(Object)} instead
     */
    @Deprecated
    DataflowVariable<T> leftShift(T val) {
        bind(val)
        return this
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
     * <p>DataflowVariable semantics:</p>
     * <ul>
     *     <li>If timeout elapses, return {@code null}.</li>
     *     <li>Record a timeout error in the DFV.</li>
     * </ul>
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
     * Non-blocking read: returns {@code null} if pending or if error.
     */
    T getNonBlocking() {
        if (isSuccess()) return get()
        return null
    }

    /**
     * Groovy call-operator support: {@code dfv()} &lt;=&gt; {@link #get()}.
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
     * A DFV is successful if:</n>
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
     * <p>Semantics:</p>
     * <ul>
     *     <li>If already successful &rarr; the callback is scheduled asynchronously on this
     *         variable's worker pool.</li>
     *     <li>If pending &rarr; the callback is scheduled when the value binds.</li>
     *     <li>If error &rarr; the callback is never invoked.</li>
     * </ul>
     *
     * <p>The callback is always invoked asynchronously (never on the caller thread) to
     * avoid surprising re-entrancy.</p>
     *
     * @return this DFV
     */
    DataflowVariable<T> whenAvailable(Consumer<? super T> callback) {
        if (callback == null) return this

        // Always asynchronous via whenBoundAsync
        whenBoundAsync { T v ->
            if (isSuccess()) {
                try {
                    callback.accept(v)
                } catch (Throwable t) {
                    log.error("Error in whenAvailable callback", t)
                }
            }
        }
        return this
    }

    /**
     * Register an error listener (Groovy Closure version).
     *
     * <p>Semantics:</p>
     * <ul>
     *     <li>If already in error &rarr; the callback is scheduled asynchronously on this
     *         variable's worker pool.</li>
     *     <li>If pending &rarr; the callback is scheduled when an error binds.</li>
     *     <li>If success &rarr; the callback is never invoked.</li>
     * </ul>
     *
     * <p>The callback is always invoked asynchronously (never on the caller thread).</p>
     */
    DataflowVariable<T> whenError(Closure<?> handler) {
        if (handler == null) return this

        whenBoundAsync {
            if (hasError()) {
                try {
                    handler.call(getError())
                } catch (Throwable t) {
                    log.error("Error in whenError callback", t)
                }
            }
        }
        return this
    }

    /**
     * Java {@link Consumer} variant of {@link #whenError(Closure)}.
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
                out.bind(r)
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
     * Groovy right-shift operator support: {@code dfv >> callback} &lt;=&gt; {@link #whenAvailable(Consumer)}.
     *
     * @param callback A closure or Consumer to be invoked when the value is available.
     * @return this DFV
     */
    DataflowVariable<T> rightShift(Object callback) {
        // Groovy automatically coerces the Closure/Lambda into a Consumer for whenAvailable.
        return whenAvailable(callback as Consumer)
    }

    /**
     * Error recovery transform.
     */
    DataflowVariable<T> recover(Function<Throwable, T> fn) {
        DataflowVariable<T> out = new DataflowVariable<T>(pool)

        // success path
        whenAvailable { T v -> out.bind(v) }

        // error path
        whenError { Throwable ex ->
            try {
                out.bind(fn.apply(ex))
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
     * Ensure any constant operand is wrapped as a DFV using this variable's pool.
     */
    private DataflowVariable wrapOperand(Object operand) {
        if (operand instanceof DataflowVariable) {
            return (DataflowVariable) operand
        }
        DataflowVariable dfv = new DataflowVariable(pool)
        dfv.bind(operand)
        return dfv
    }

    /**
     * Internal helper for binary operations between this DFV and another operand.
     *
     * @param other  scalar value or another {@link DataflowVariable}
     * @param op     closure taking (leftValue, rightValue) and producing the result
     * @return new {@link DataflowVariable} representing the asynchronous result
     */
    private DataflowVariable binaryOp(Object other, Closure op) {
        DataflowVariable rhs = wrapOperand(other)
        DataflowVariable result = new DataflowVariable(pool)

        // this success path
        this.whenAvailable { av ->
            // rhs success path
            rhs.whenAvailable { bv ->
                try {
                    result.bind(op.call(av, bv))
                } catch (Throwable t) {
                    result.bindError(t)
                }
            }
            // rhs error path
            rhs.whenError { Throwable t ->
                result.bindError(t)
            }
        }

        // this error path
        this.whenError { Throwable t ->
            result.bindError(t)
        }

        return result
    }

    /**
     * Groovy {@code +} operator: {@code dfv1 + dfv2}, {@code dfv + 10}.
     */
    @CompileDynamic
    DataflowVariable plus(Object other) {
        return binaryOp(other) { a, b -> a + b }
    }

    /**
     * Groovy {@code -} operator: {@code dfv1 - dfv2}, {@code dfv - 10}.
     */
    @CompileDynamic
    DataflowVariable minus(Object other) {
        return binaryOp(other) { a, b -> a - b }
    }

    /**
     * Groovy {@code *} operator: {@code dfv1 * dfv2}, {@code dfv * 10}.
     */
    @CompileDynamic
    DataflowVariable multiply(Object other) {
        return binaryOp(other) { a, b -> a * b }
    }

    /**
     * Groovy {@code /} operator: {@code dfv1 / dfv2}, {@code dfv / 10}.
     */
    @CompileDynamic
    DataflowVariable div(Object other) {
        return binaryOp(other) { a, b -> a / b }
    }


    /**
     * Dynamic operator handling for Groovy DSL expressions.
     *
     * <p>For the standard arithmetic operators ({@code plus}, {@code minus},
     * {@code multiply}, {@code div}) the explicit operator overloads above are
     * preferred and will normally be used by the Groovy compiler. This method
     * remains as a generic fallback hook for any future DSL operations.</p>
     */
    @CompileDynamic
    def methodMissing(String name, Object args) {
        Object[] arr = (Object[]) args
        Object rawOther = arr.length > 0 ? arr[0] : null

        switch (name) {
            case "plus":
                return plus(rawOther)
            case "minus":
                return minus(rawOther)
            case "multiply":
                return multiply(rawOther)
            case "div":
                return div(rawOther)
            default:
                throw new MissingMethodException(name, this.class, args)
        }
    }


    // =============================================================================================
    // toString
    // =============================================================================================

    @Override
    String toString() {
        return "DataflowVariable(${super.toString()})"
    }
}
