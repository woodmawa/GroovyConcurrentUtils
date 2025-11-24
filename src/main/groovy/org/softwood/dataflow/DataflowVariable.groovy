package org.softwood.dataflow

import groovy.util.logging.Slf4j

import java.util.concurrent.Callable
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.function.Function

/**
 * Concrete implementation of a dataflow variable with single-assignment semantics
 * and operator overloading.
 *
 * <p>DataflowVariable extends {@link DataflowExpression} to provide a practical,
 * user-friendly interface for dataflow concurrent programming. It adds convenience
 * methods, operator overloading, and enhanced error handling while maintaining the core
 * single-assignment guarantee.</p>
 *
 * <h3>Key Features:</h3>
 * <ul>
 *   <li><b>Simplified API:</b> Provides get()/set() as alternatives to getValue()/setValue()</li>
 *   <li><b>Operator Overloading:</b> Supports arithmetic operators for dataflow composition</li>
 *   <li><b>Left Shift Assignment:</b> {@code dfv << value} as sugar for {@code #set(Object)}</li>
 *   <li><b>Right Shift Callback:</b> {@code dfv >> closure} to register callbacks fluently</li>
 *   <li><b>Error Recovery:</b> {@code #recover(Function)} for handling errors</li>
 *   <li><b>Non-blocking Access:</b> {@code #getValNonBlocking(Object)}, {@code #getIfAvailable()}</li>
 * </ul>
 *
 * @param <T> the type of value held by this dataflow variable
 */
@Slf4j
class DataflowVariable<T> extends DataflowExpression<T> {

    /** Create an unbound dataflow variable. */
    DataflowVariable() { super() }

    /**
     * Create and bind immediately.
     *
     * @param value initial value (or Closure/Callable to compute it)
     */
    DataflowVariable(value) { super(value) }

    /**
     * Bind a value (single assignment).
     *
     * @param newValue value to bind
     */
    void set(T newValue) {
        setValue(newValue)
        log.debug "df now set to ${getValue()}"
    }

    /** Alias for set. */
    void setVal(T newValue) { set(newValue) }

    /** Alias for set. */
    void bind(T newValue) { set(newValue) }

    /** Bind in error. */
    void setError(Throwable e) { super.setError(e) }

    /** Alias for setError. */
    void bindError(Throwable e) { super.setError(e) }

    /**
     * Non-blocking access: returns bound value or default.
     *
     * @param valueIfAbsent default if unbound
     * @return value or default
     */
    T getValNonBlocking(T valueIfAbsent=null) {
        return isBound() ? (T) getValue() : valueIfAbsent
    }

    /**
     * Blocking get with error propagation.
     *
     * @return bound value
     */
    T get() throws Exception {
        try {
            if (hasError()) throw error
            T result = (T) getValue()
            if (hasError()) throw error
            return result
        } catch (Throwable ex) {
            if (error == null) setError(ex)
            throw ex
        }
    }

    /** Alias for get. */
    T getVal() { get() }

    /** @return stored error */
    Throwable getError() { super.error }

    /** @return true if bound with error */
    boolean hasError() { isBound() && super.error != null }

    /** @return true if completed (value or error) */
    boolean isDone() { isBound() }

    /**
     * Timed get with error propagation.
     *
     * @param timeout max wait
     * @param unit time unit
     * @return bound value
     */
    T get(long timeout, TimeUnit unit) {
        try {
            return (T) getValue(timeout, unit)
        } catch (Exception ex) {
            setError(ex)
            throw ex
        }
    }

    /** Alias. */
    T getVal(long timeout, TimeUnit unit) { get(timeout, unit) }

    /**
     * Left shift operator overload for binding.
     *
     * @param value value or computation
     * @return this
     */
    DataflowVariable leftShift(value) {
        setValue(value)
        return this
    }

    /**
     * @return Optional of value if available
     */
    Optional<T> getIfAvailable() {
        return isBound()
                ? Optional.ofNullable((T) this.get())
                : (Optional<T>) Optional.empty()
    }

    /**
     * Right shift operator overload for callbacks.
     *
     * @param callable Closure/Consumer to call with value
     * @return this
     */
    DataflowVariable rightShift(callable) {
        if (isBound()) {
            callable.call(get())
        } else {
            whenBound("", callable as Closure)
        }
        return this
    }

    /**
     * Register a Java Consumer to be invoked on completion.
     *
     * @param callback consumer of value
     */
    void whenAvailable(Consumer<T> callback) {
        if (isBound()) {
            try { callback.accept(get()) } catch (ignored) {}
        } else {
            whenBound("", { v -> callback.accept(v) })
        }
    }

    /**
     * Register a Groovy Closure to be invoked on completion.
     *
     * @param callbackClosure closure of value
     */
    void whenAvailable(Closure callbackClosure) {
        if (isBound()) {
            try { callbackClosure.call(get()) } catch (ignored) {}
        } else {
            whenBound("newValue", callbackClosure)
        }
    }

    /**
     * Create a new DataflowVariable whose value is the transformation
     * of this variable's eventual value.
     *
     * <p>Supports Closure, Function, Consumer, Callable.</p>
     *
     * @param callable transformation
     * @param <R> output type
     * @return new DataflowVariable<R>
     */
    @Override
    DataflowVariable then(final Callable callable) {
        Closure closureCallable
        if (callable instanceof Function) {
            closureCallable = { v -> callable.apply(v) }
        } else if (callable instanceof Consumer) {
            closureCallable = { v -> callable.accept(v); v }
        } else if (callable instanceof Callable) {
            closureCallable = { v -> callable.call() }
        } else {
            closureCallable = callable as Closure
        }

        // call parent's then() (which returns DataflowExpression),
        // then wrap into a DataflowVariable
        def expr = super.then(closureCallable)
        def dfv = new DataflowVariable()
        expr.toFuture().whenComplete { v, e ->
            if (e != null) dfv.bindError(e instanceof CompletionException ? e.cause : e)
            else dfv.bind(v as Object)
        }
        return dfv
    }

    /**
     * Recover from an error by mapping it to a value.
     *
     * @param recovery throwable -> value
     * @param <R> recovered type
     * @return new recovered DataflowVariable<R>
     */
    <R> DataflowVariable<R> recover(Function<Throwable, R> recovery) {
        DataflowVariable<R> recoveredDataflow = new DataflowVariable<>()

        this.toFuture()
                .whenComplete { val, err ->
                    if (err == null) {
                        try {
                            recoveredDataflow.bind((R) val)
                        } catch (Throwable e) {
                            recoveredDataflow.bindError(e)
                        }
                    } else {
                        try {
                            recoveredDataflow.bind(recovery.apply(err instanceof CompletionException ? err.cause : err))
                        } catch (Throwable e) {
                            recoveredDataflow.bindError(new CompletionException("Error during recovery", e))
                        }
                    }
                }

        return recoveredDataflow
    }

    // --- Your operator / methodMissing / propertyMissing block can remain as-is ---
    // (not repeated here to keep this file readable in the answer)
}
