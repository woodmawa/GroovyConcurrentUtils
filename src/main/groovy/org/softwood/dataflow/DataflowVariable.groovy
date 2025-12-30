package org.softwood.dataflow

import groovy.transform.CompileStatic
import groovy.transform.CompileDynamic
import groovy.transform.ToString
import groovy.util.logging.Slf4j
import org.codehaus.groovy.runtime.InvokerHelper
import org.softwood.pool.ExecutorPool
import org.softwood.gstream.Gstream
import org.softwood.promise.core.PromisePoolContext

import java.time.Duration
import java.util.Optional
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Supplier

/**
 * A {@code DataflowVariable} is a specialised {@link DataflowExpression} that behaves similarly to a
 * single-assignment cell with asynchronous listener notification and promise-like transformation
 * utilities.
 *
 * <p>The DFV provides:</p>
 * <ul>
 *   <li>Thread-safe single-assignment semantics</li>
 *   <li>Completion via success, error, or cancellation</li>
 *   <li>Blocking and non-blocking access to values</li>
 *   <li>Integration with {@link CompletableFuture} via {@link DataflowVariable#toFuture()}</li>
 *   <li>Success and error/cancellation listeners</li>
 *   <li>Convenience chaining transformations such as {@code then}, {@code recover}, etc.</li>
 *   <li>Groovy operators such as {@code <<}, {@code >>}, {@code *}, {@code +}, etc.</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 *
 * The DFV transitions exactly once from {@link DataflowExpression.State#PENDING} to one terminal state:
 * <ul>
 *   <li>{@link DataflowExpression.State#SUCCESS}</li>
 *   <li>{@link DataflowExpression.State#ERROR}</li>
 *   <li>{@link DataflowExpression.State#CANCELLED}</li>
 * </ul>
 *
 * <h2>Cancellation</h2>
 *
 * Cancellation is a first-class completion type. A cancelled DFV:
 * <ul>
 *   <li>is considered {@code hasError() == true}</li>
 *   <li>stores a {@link CancellationException} as its error</li>
 *   <li>completes its {@link CompletableFuture} exceptionally</li>
 *   <li>invokes {@code whenError} callbacks but not {@code whenAvailable} callbacks</li>
 * </ul>
 *
 * @param <T> the value type
 */
@Slf4j
@CompileStatic
@ToString(includeNames = true, includeFields = true)
class DataflowVariable<T> extends DataflowExpression<T> {

    // =====================================================================================================
    // Constructors
    // =====================================================================================================

    /** Construct using a default {@link org.softwood.pool.ConcurrentPool}. */
    DataflowVariable() {
        super(PromisePoolContext.getCurrentPool())
    }

    /** Construct backed by a specific pool. */
    DataflowVariable(ExecutorPool  pool) {
        super(pool)
    }

    /** Construct with explicit value type and pool. */
    DataflowVariable(ExecutorPool  pool, Class<T> type) {
        super(pool, type)
    }


    // =====================================================================================================
    // Binding Operations (Success / Error / Cancellation)
    // =====================================================================================================

    /**
     * Bind this variable to a final value.
     *
     * <p>Equivalent to calling {@link DataflowExpression#setValue(Object)}.</p>
     *
     * @param v value to bind
     */
    void bind(Object v) {
        setValue((T) v)
    }

    /**
     * Bind an error to this DFV.
     *
     * <p>Equivalent to {@link DataflowExpression#setError(Throwable)}.</p>
     */
    DataflowVariable<T> bindError(Throwable t) {
        setError(t)
        return this
    }

    /**
     * Bind a cancellation into this DFV.
     *
     * <p>Equivalent to {@link DataflowExpression#setCancelled(Throwable)}.</p>
     *
     * @param cause optional cause for cancellation
     * @return this DFV
     */
    DataflowVariable<T> bindCancelled(Throwable cause = null) {
        setCancelled(cause)
        return this
    }


    /** Operator alias for {@link #bind(Object)}. */
    DataflowVariable<T> leftShift(T v) {
        bind(v)
        return this
    }


    // =====================================================================================================
    // Retrieval (Blocking / Timeout / Non-Blocking)
    // =====================================================================================================

    /**
     * Blocking read of the value.
     *
     * @return the bound value
     * @throws Exception on failure or cancellation
     */
    T get() {
        try {
            return (T) getValue()
        } catch (Exception e) {
            throw e
        }
    }

    /**
     * Blocking read with timeout.
     *
     * <p>If timeout elapses, a {@link TimeoutException} is thrown.</p>
     */
    T get(long timeout, TimeUnit unit) {
        try {
            return (T) getValue(timeout, unit)
        } catch (TimeoutException te) {
            bindError(te)
            return null
        }
    }

    /** Duration-based timeout version. */
    T get(Duration duration) {
        return get(duration.toMillis(), TimeUnit.MILLISECONDS)
    }

    /**
     * Non-blocking read.
     *
     * @return value if successful, otherwise null
     */
    T getNonBlocking() {
        if (!isSuccess()) return null
        return get()
    }

    /** Groovy call operator alias for {@link #get()}. */
    T call() { return get() }


    // =====================================================================================================
    // Task Execution & Auto-Binding
    // =====================================================================================================

    /**
     * Execute a Closure asynchronously and bind the result to this DataflowVariable.
     * <p>
     * The closure is executed on the backing pool, and upon completion (success or error),
     * this DataflowVariable is automatically bound to the result or error.
     * </p>
     *
     * <p>Example usage:</p>
     * <pre>
     * def dfv = new DataflowVariable&lt;String&gt;(pool)
     * dfv.task { -&gt;
     *     // This runs async on the pool
     *     fetchDataFromAPI()
     * }
     * println dfv.get()  // Blocks until task completes
     * </pre>
     *
     * @param closure the task to execute
     * @return this DataflowVariable for fluent chaining
     */
    DataflowVariable<T> task(Closure<T> closure) {
        pool.executor.submit {
            try {
                T result = closure.call()
                this.setValue(result)
            } catch (Throwable e) {
                this.bindError(e)
                log.error("DataflowVariable.task(Closure): execution failed", e)
            }
        }
        return this
    }

    /**
     * Execute a Callable asynchronously and bind the result to this DataflowVariable.
     * <p>
     * Java lambda-friendly variant of {@link #task(Closure)}.
     * </p>
     *
     * <p>Example usage:</p>
     * <pre>
     * def dfv = new DataflowVariable&lt;Integer&gt;(pool)
     * dfv.task(() -&gt; calculateExpensiveValue())
     * </pre>
     *
     * @param callable the task to execute
     * @return this DataflowVariable for fluent chaining
     */
    DataflowVariable<T> task(Callable<T> callable) {
        pool.executor.submit {
            try {
                T result = callable.call()
                this.setValue(result)
            } catch (Throwable e) {
                this.bindError(e)
                log.error("DataflowVariable.task(Callable): execution failed", e)
            }
        }
        return this
    }

    /**
     * Execute a Supplier asynchronously and bind the result to this DataflowVariable.
     * <p>
     * Java lambda-friendly variant using Supplier functional interface.
     * </p>
     *
     * <p>Example usage:</p>
     * <pre>
     * def dfv = new DataflowVariable&lt;String&gt;(pool)
     * dfv.task(() -&gt; "computed value")
     * </pre>
     *
     * @param supplier the task to execute
     * @return this DataflowVariable for fluent chaining
     */
    DataflowVariable<T> task(Supplier<T> supplier) {
        pool.executor.submit {
            try {
                T result = supplier.get()
                this.setValue(result)
            } catch (Throwable e) {
                this.bindError(e)
                log.error("DataflowVariable.task(Supplier): execution failed", e)
            }
        }
        return this
    }


    // =====================================================================================================
    // Observability & Metrics
    // =====================================================================================================

    /**
     * Get diagnostic metrics about this DataflowVariable.
     * <p>
     * Useful for monitoring, debugging, and health checks in production systems.
     * Returns comprehensive state information without blocking.
     * </p>
     *
     * <p>Example usage:</p>
     * <pre>
     * def metrics = dfv.metrics
     * if (!metrics.success) {
     *     log.warn("DFV not successful: ${metrics.state}, error: ${metrics.errorMessage}")
     * }
     * </pre>
     *
     * @return map containing state, listener count, and completion details
     */
    Map<String, Object> getMetrics() {
        return ([
            type: type?.simpleName ?: 'Unknown',
            bound: isBound(),
            success: isSuccess(),
            hasError: hasError(),
            completedAt: getCompletedAt()?.toString(),
            errorMessage: getError()?.message,
            errorType: getError()?.class?.simpleName,
            poolName: pool.name
        ] as Map<String, Object>)
    }


    // =====================================================================================================
    // State Introspection
    // =====================================================================================================

    /** @return true if success, false if pending, failed, or cancelled */
    boolean isSuccess() {
        return isBound() && !hasError()
    }

    /** @return Optional of the value if available and successful */
    Optional<T> getIfAvailable() {
        if (!isSuccess()) return Optional.empty()
        try {
            return Optional.ofNullable(get())
        } catch (Throwable ignored) {
            return Optional.empty()
        }
    }


    // =====================================================================================================
    // CompletableFuture Interop
    // =====================================================================================================

    /**
     * Convert this DFV to a {@link CompletableFuture}.
     *
     * <p>Semantics:</p>
     * <ul>
     *   <li>On success → CF completes normally.</li>
     *   <li>On failure → CF completes exceptionally.</li>
     *   <li>On cancellation → CF completes exceptionally with {@link CancellationException}.</li>
     * </ul>
     *
     * <p>Each call returns a new, independent CF.</p>
     */
    CompletableFuture<T> toFuture() {
        CompletableFuture<T> cf = new CompletableFuture<>()

        // Already complete?
        if (isBound()) {
            if (hasError()) {
                Throwable error = getError()
                // getError() now returns a defensive error if null, so this should never be null
                // but keep the check for extra safety
                if (error == null) {
                    log.error("CRITICAL: hasError()=true but getError()=null even after defensive check!")
                    error = new IllegalStateException("DataflowVariable marked as error but error is null (defensive check failed)")
                }
                cf.completeExceptionally(error)
            } else {
                try { cf.complete(get()) }
                catch (Throwable t) { cf.completeExceptionally(t) }
            }
            return cf
        }

        // Pending → wire listeners
        whenAvailable { T v ->
            if (!cf.isDone()) cf.complete(v)
        }

        whenError { Throwable e ->
            if (!cf.isDone()) {
                Throwable error = e ?: new IllegalStateException("Error callback received null error")
                cf.completeExceptionally(error)
            }
        }

        return cf
    }

    /**
     * Convert this DataflowVariable to a {@link Gstream} containing zero or one elements.
     * <p>
     * This enables seamless integration with Gstream pipelines:
     * <ul>
     *   <li>On success → Gstream with one element (the value)</li>
     *   <li>On error/cancel → Empty Gstream (errors are logged)</li>
     *   <li>On pending → Blocks until bound, then returns appropriate stream</li>
     * </ul>
     * </p>
     *
     * <p>Example usage:</p>
     * <pre>
     * def dfv = factory.task { fetchData() }
     * def results = dfv.toGstream()
     *     .map { it * 2 }
     *     .filter { it > 10 }
     *     .toList()
     * </pre>
     *
     * <p><b>Note:</b> Null values result in an empty stream.</p>
     *
     * @return Gstream containing the value or empty
     */
    Gstream<T> toGstream() {
        if (hasError()) {
            log.debug("toGstream: DFV has error, returning empty stream (error: {})", error?.message)
            return (Gstream<T>) Gstream.empty()
        }

        try {
            T value = get()
            return value != null ? (Gstream<T>) Gstream.of(value) : (Gstream<T>) Gstream.empty()
        } catch (Exception e) {
            log.warn("toGstream: Failed to get value, returning empty stream", e)
            return (Gstream<T>) Gstream.empty()
        }
    }


    // =====================================================================================================
    // Listener API (Success / Error)
    // =====================================================================================================

    /**
     * Register a success listener.
     *
     * <p>Invoked only if the DFV succeeds.</p>
     */
    DataflowVariable<T> whenAvailable(Consumer<? super T> callback) {
        if (callback == null) return this

        // CRITICAL: If already bound and successful, invoke callback immediately
        if (isBound() && !hasError()) {
            try {
                callback.accept(get())
            } catch (Throwable t) {
                String errorMsg = "Error in whenAvailable callback for ${type?.simpleName ?: 'Object'}: ${t.message}"
                log.error(errorMsg, t)
                collectListenerError(errorMsg)
            }
            return this
        }

        // Otherwise register for future completion
        whenBoundAsync { T v ->
            // Full state guard
            if (isBound() && !hasError()) {
                try {
                    callback.accept(v)
                } catch (Throwable t) {
                    String errorMsg = "Error in whenAvailable callback for ${type?.simpleName ?: 'Object'}: ${t.message}"
                    log.error(errorMsg, t)
                    collectListenerError(errorMsg)
                }
            }
        }

        return this
    }

    /**
     * Register an error/cancellation listener (Groovy closure version).
     *
     * <p>Invoked if the DFV completes with an error or is cancelled.</p>
     */
    DataflowVariable<T> whenError(Closure<?> handler) {
        if (handler == null) return this

        // CRITICAL: If already failed/cancelled, invoke handler immediately
        if (hasError()) {
            try {
                handler.call(getError())
            } catch (Throwable t) {
                log.error("Error in whenError callback", t)
            }
            return this
        }

        // Otherwise register for future error
        whenBoundAsync {
            if (hasError()) {
                try { handler.call(getError()) }
                catch (Throwable t) { log.error("Error in whenError callback", t) }
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

    /**
     * Groovy {@code >>} operator: register a success callback.
     *
     * <p>Example:</p>
     * <pre>
     *   df >> { v -> println "value = $v" }
     * </pre>
     */
    DataflowVariable<T> rightShift(Closure handler) {
        if (handler == null) return this

        whenAvailable { T v ->
            try {
                handler.call(v)
            } catch (Throwable t) {
                log.error("Error in >> handler", t)
            }
        }

        return this
    }


    // =====================================================================================================
    // Functional Transformations
    // =====================================================================================================

    /**
     * Transform success value using a function.
     *
     * <p>On success → apply fn<br>
     * On error/cancel → propagate error</p>
     */
    <R> DataflowVariable<R> then(Function<T, R> fn) {
        DataflowVariable<R> out = new DataflowVariable<>(pool)

        whenAvailable { T v ->
            try { out.bind(fn.apply(v)) }
            catch (Throwable t) { out.bindError(t) }
        }

        whenError { Throwable e ->
            out.bindError(e)
        }

        return out
    }

    /**
     * Recover from failure/cancellation using a recovery function.
     *
     * <p>On success → propagate value<br>
     * On error/cancel → apply recovery</p>
     */
    <R> DataflowVariable<R> recover(Function<Throwable, R> fn) {
        DataflowVariable<R> out = new DataflowVariable<>(pool)

        whenAvailable { T v -> out.bind((R) v) }

        whenError { Throwable e ->
            try { out.bind(fn.apply(e)) }
            catch (Throwable t) { out.bindError(t) }
        }

        return out
    }


    // =====================================================================================================
    // Groovy Operators
    // =====================================================================================================

    /**
     * Binary {@code +} operator.
     *
     * <p>Supports both {@code DFV + DFV} and {@code DFV + constant}.</p>
     * <ul>
     *   <li>DFV + DFV → waits for both to complete; on success returns numeric sum.</li>
     *   <li>DFV + constant → maps value using {@code then}.</li>
     *   <li>If either side errors/cancels → result has that error.</li>
     * </ul>
     */
    @CompileDynamic
    DataflowVariable plus(Object other) {
        if (other instanceof DataflowVariable) {
            DataflowVariable right = (DataflowVariable) other
            DataflowVariable out = new DataflowVariable(pool)

            def leftF = this.toFuture()
            def rightF = right.toFuture()

            leftF.thenCombine(rightF) { lv, rv ->
                (lv instanceof Number && rv instanceof Number) ? (lv + rv) : lv
            }.whenComplete { res, err ->
                if (err != null) {
                    Throwable actual = (err instanceof CompletionException && err.cause != null) ? err.cause : err
                    out.bindError(actual)
                } else {
                    out.bind(res)
                }
            }

            return out
        }

        // DFV + constant
        return then { T a -> (a instanceof Number && other instanceof Number) ? (a + other) : a }
    }

    /**
     * Binary {@code -} operator.
     */
    @CompileDynamic
    DataflowVariable minus(Object other) {
        if (other instanceof DataflowVariable) {
            DataflowVariable right = (DataflowVariable) other
            DataflowVariable out = new DataflowVariable(pool)

            def leftF = this.toFuture()
            def rightF = right.toFuture()

            leftF.thenCombine(rightF) { lv, rv ->
                (lv instanceof Number && rv instanceof Number) ? (lv - rv) : lv
            }.whenComplete { res, err ->
                if (err != null) {
                    Throwable actual = (err instanceof CompletionException && err.cause != null) ? err.cause : err
                    out.bindError(actual)
                } else {
                    out.bind(res)
                }
            }

            return out
        }

        // DFV - constant
        return then { T a -> (a instanceof Number && other instanceof Number) ? (a - other) : a }
    }

    /**
     * Binary {@code *} operator.
     */
    @CompileDynamic
    DataflowVariable multiply(Object other) {
        if (other instanceof DataflowVariable) {
            DataflowVariable right = (DataflowVariable) other
            DataflowVariable out = new DataflowVariable(pool)

            def leftF = this.toFuture()
            def rightF = right.toFuture()

            leftF.thenCombine(rightF) { lv, rv ->
                (lv instanceof Number && rv instanceof Number) ? (lv * rv) : lv
            }.whenComplete { res, err ->
                if (err != null) {
                    Throwable actual = (err instanceof CompletionException && err.cause != null) ? err.cause : err
                    out.bindError(actual)
                } else {
                    out.bind(res)
                }
            }

            return out
        }

        // DFV * constant
        return then { T a -> (a instanceof Number && other instanceof Number) ? (a * other) : a }
    }

    /**
     * Binary {@code /} operator.
     */
    @CompileDynamic
    DataflowVariable div(Object other) {
        if (other instanceof DataflowVariable) {
            DataflowVariable right = (DataflowVariable) other
            DataflowVariable out = new DataflowVariable(pool)

            def leftF = this.toFuture()
            def rightF = right.toFuture()

            leftF.thenCombine(rightF) { lv, rv ->
                (lv instanceof Number && rv instanceof Number) ? (lv / rv) : lv
            }.whenComplete { res, err ->
                if (err != null) {
                    Throwable actual = (err instanceof CompletionException && err.cause != null) ? err.cause : err
                    out.bindError(actual)
                } else {
                    out.bind(res)
                }
            }

            return out
        }

        // DFV / constant
        return then { T a -> (a instanceof Number && other instanceof Number) ? (a / other) : a }
    }

    /** Fallback to underlying value for other methods. */
    Object methodMissing(String name, Object args) {
        T val = get()
        return InvokerHelper.invokeMethod(val, name, args)
    }
}