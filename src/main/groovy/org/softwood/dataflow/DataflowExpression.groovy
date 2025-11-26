package org.softwood.dataflow

import groovy.beans.Bindable
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.softwood.pool.ConcurrentPool

import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import java.time.LocalDateTime
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import java.util.function.Consumer
import java.util.function.Supplier

/**
 * A {@code DataflowExpression} represents a single-assignment, asynchronously-completing value.
 *
 * <p>It is conceptually similar to a {@link CompletableFuture}, but with a Groovy-friendly API and
 * additional support for:</p>
 *
 * <ul>
 *     <li>Single-assignment semantics enforced at runtime.</li>
 *     <li>Asynchronous listeners registered via {@code #whenBound(groovy.lang.Closure)} and
 *         {@code #whenBound(String, groovy.lang.Closure)}.</li>
 *     <li>Integration with a {@link ConcurrentPool} for thread execution.</li>
 *     <li>Property change events for tooling / UI via {@link PropertyChangeSupport}.</li>
 * </ul>
 *
 * <p>The typical life-cycle of a {@code DataflowExpression} is:</p>
 *
 * <ol>
 *     <li>Create it, optionally specifying a value type.</li>
 *     <li>Register one or more listeners using {@code whenBound(...)}.</li>
 *     <li>Bind it once using {@code #setValue(Object)} or record an error using {@code #setError(Throwable)}.</li>
 *     <li>Read the value (blocking) via {@code #getValue()} or {@code #getValue(long, TimeUnit)}.</li>
 * </ol>
 *
 * <p>Instances are thread-safe.</p>
 *
 * @param <T> type of the value produced by this expression
 */
@CompileStatic
@Slf4j
class DataflowExpression<T> {

    /**
     * Possible completion states of the expression.
     */
    enum State {
        /** The expression has not yet been completed. */
        PENDING,
        /** The expression completed successfully with a value. */
        SUCCESS,
        /** The expression completed with an error. */
        ERROR
    }

    /** Pool used for asynchronous callback execution. */
    final ConcurrentPool pool

    /** Backing future representing completion of this expression. */
    private final CompletableFuture<T> value

    /** Lock to enforce single-assignment semantics. */
    private final ReentrantLock completionLock = new ReentrantLock()

    /** Human-friendly type information (optional). */
    final Class<T> type

    /** Property change support for tooling / UI / binding frameworks. */
    private final PropertyChangeSupport pcs = new PropertyChangeSupport(this)

    /** Current state of the expression. */
    @Bindable
    State state = State.PENDING

    /** Timestamp of when the expression was completed (successfully or exceptionally). */
    @Bindable
    LocalDateTime timestamp

    /**
     * Creates a new expression backed by the given pool.
     *
     * @param pool concurrency pool used for asynchronous work
     */
    DataflowExpression(ConcurrentPool pool) {
        this(pool, null)
    }

    /**
     * Creates a new expression backed by the given pool with an explicit value type.
     *
     * @param pool concurrency pool used for asynchronous work
     * @param type type of values produced by this expression (optional, may be {@code null})
     */
    DataflowExpression(ConcurrentPool pool, Class<T> type) {
        this.pool = pool
        this.type = type
        this.value = new CompletableFuture<>()
    }

    /**
     * Adds a property change listener.
     *
     * @param listener the listener to add
     */
    void addPropertyChangeListener(PropertyChangeListener listener) {
        pcs.addPropertyChangeListener(listener)
    }

    /**
     * Removes a previously added property change listener.
     *
     * @param listener the listener to remove
     */
    void removePropertyChangeListener(PropertyChangeListener listener) {
        pcs.removePropertyChangeListener(listener)
    }

    /**
     * Fires a property change event.
     *
     * @param propertyName name of the changed property
     * @param oldValue     old value
     * @param newValue     new value
     */
    protected void firePropertyChange(String propertyName, Object oldValue, Object newValue) {
        pcs.firePropertyChange(propertyName, oldValue, newValue)
    }

    /**
     * Returns {@code true} if this expression has completed (successfully or with error).
     *
     * @return whether the expression is completed
     */
    boolean isBound() {
        return value.isDone()
    }

    /**
     * Returns {@code true} if this expression completed successfully with a value.
     *
     * @return whether the expression completed successfully
     */
    boolean isSuccess() {
        return state == State.SUCCESS
    }

    /**
     * Returns {@code true} if this expression completed with an error.
     *
     * @return whether the expression completed with an error
     */
    boolean isError() {
        return state == State.ERROR
    }

    boolean hasError() {
        return state == State.ERROR
    }

    /**
     * Returns the current state of the expression.
     *
     * @return current state
     */
    State getState() {
        return state
    }

    /**
     * Completes this expression with the given value.
     *
     * <p>This is a single-assignment operation: subsequent calls will throw
     * {@link IllegalStateException}.</p>
     *
     * @param newValue value to complete the expression with (may be {@code null})
     * @throws IllegalStateException if the expression has already been completed
     */
    void setValue(T newValue) {
        setValue(newValue, false)
    }

    /**
     * Completes this expression with the given value.
     *
     * @param newValue value to complete the expression with (may be {@code null})
     * @param async    if {@code true}, callbacks will be scheduled asynchronously via the pool
     * @throws IllegalStateException if the expression has already been completed
     */
    void setValue(T newValue, boolean async) {
        completionLock.lock()
        try {
            if (value.isDone()) {
                throw new IllegalStateException("DataflowExpression can only be completed once")
            }
            timestamp = LocalDateTime.now()
            log.debug "set DataFlow value to $newValue "
            value.complete(newValue)
            state = State.SUCCESS
            firePropertyChange("state", State.PENDING, State.SUCCESS)
            log.debug "DataFlowExpression: notify clients, that value has been set to (${newValue}) at $timestamp"
            if (async) {
                notifyWhenBoundAsync(newValue)
            } else {
                notifyWhenBound(newValue)
            }
        } finally {
            completionLock.unlock()
        }
    }

    /**
     * Completes this expression with the given error.
     *
     * <p>This is a single-assignment operation: subsequent calls will throw
     * {@link IllegalStateException}.</p>
     *
     * @param t error to complete the expression with
     * @throws IllegalStateException if the expression has already been completed
     */
    void setError(Throwable t) {
        completionLock.lock()
        try {
            if (value.isDone()) {
                throw new IllegalStateException("DataflowExpression can only be completed once")
            }
            timestamp = LocalDateTime.now()
            log.debug "set DataFlow error to ${t.message} "
            value.completeExceptionally(t)
            state = State.ERROR
            firePropertyChange("state", State.PENDING, State.ERROR)

            // IMPORTANT: notify listeners for error completions as well.
            // We follow the same convention as whenBound(String, Closure):
            // for error completions we still invoke listeners but pass null as value.
            notifyWhenBoundAsync(null)
        } finally {
            completionLock.unlock()
        }
    }

    /**
     * Returns the underlying {@link CompletableFuture}.
     *
     * @return future representing the completion of this expression
     */
    CompletableFuture<T> toFuture() {
        return value
    }

    /**
     * Blocks until the expression is completed and returns the value.
     *
     * <p>If the expression completed with an error, throws {@link DataflowException} wrapping the
     * underlying cause.</p>
     *
     * @return value produced by the expression
     * @throws DataflowException if the expression completed with an error
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    T getValue() throws InterruptedException {
        try {
            return value.get()
        } catch (ExecutionException e) {
            def cause = e.cause ?: e
            if (cause instanceof DataflowException)
                throw cause
            throw new DataflowException(cause.message, cause)
        }
    }

    /**
     * Blocks until the expression is completed or the timeout elapses.
     *
     * @param timeout timeout value
     * @param unit    timeout unit
     * @return the value
     * @throws TimeoutException    if the timeout elapses before completion
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws DataflowException   if the expression completed with an error
     */
    T getValue(long timeout, TimeUnit unit) throws TimeoutException, InterruptedException {
        try {
            return value.get(timeout, unit)
        } catch (TimeoutException e) {
            throw e
        } catch (ExecutionException e) {
            throw new DataflowException("Error getting value from DataflowExpression", e.cause ?: e)
        }
    }

    /**
     * Returns the recorded error if the expression completed exceptionally, otherwise {@code null}.
     *
     * @return the error or {@code null}
     */
    Throwable getError() {
        if (!value.isCompletedExceptionally()) {
            return null
        }
        try {
            value.get()
            return null
        } catch (ExecutionException e) {
            return e.cause ?: e
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
            return e
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Listener registration (whenBound)
    // ---------------------------------------------------------------------------------------------

    /** Registered when-bound listeners. */
    private final List<MessageClosure<T>> listeners = Collections.synchronizedList(new ArrayList<>())

    /**
     * Registers a listener that will be invoked once the expression becomes bound.
     *
     * <p>If the expression is already bound, the listener is invoked immediately. Otherwise it will be
     * invoked once when the value becomes available.</p>
     *
     * @param listener listener closure; may accept 0, 1 or 2 parameters
     * @return this expression
     */
    DataflowExpression<T> whenBound(Closure listener) {
        return whenBound("whenBound", listener)
    }

    /**
     * Registers a listener with an associated diagnostic message.
     *
     * <p>The {@code message} is used purely for logging and debugging; it is not passed to the closure unless
     * the closure declares two parameters.</p>
     *
     * @param message  textual description of the listener
     * @param listener listener closure
     * @return this expression
     */
    DataflowExpression<T> whenBound(String message, Closure listener) {
        MessageClosure<T> msg = new MessageClosure<T>(pool, this, message, listener)
        listeners.add(msg as MessageClosure<T>)

        if (isBound()) {
            // Already bound – invoke immediately
            if (isError()) {
                // For error completions we still invoke the listener, but pass null as value;
                // clients may also query {@link #getError()} directly.
                msg.sendAsyncWithValue(null)
            } else {
                msg.sendAsyncWithValue(value.getNow(null))
            }
        }
        return this
    }

    /**
     * Notifies all registered listeners synchronously.
     *
     * @param newValue the value to pass to the listeners
     */
    protected void notifyWhenBound(T newValue) {
        log.debug "notifyWhenBound(): -send to all MessageClosure listeners with value: $newValue"
        List<MessageClosure<T>> snapshot = new ArrayList<>(listeners)
        for (MessageClosure<T> mc : snapshot) {
            mc.sendWithValue(newValue)
        }
    }

    /**
     * Notifies all registered listeners asynchronously using the pool's executor.
     *
     * @param newValue the value to pass to the listeners
     */
    protected void notifyWhenBoundAsync(T newValue) {
        log.debug "notifyWhenBoundAsync(): -send to all MessageClosure listeners with value: $newValue"
        List<MessageClosure<T>> snapshot = new ArrayList<>(listeners)
        for (MessageClosure<T> mc : snapshot) {
            mc.sendAsyncWithValue(newValue)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Transformation / chaining
    // ---------------------------------------------------------------------------------------------

    /**
     * Creates a new expression that applies the given transformation to the value of this expression.
     *
     * <p>The transformation is executed once this expression is successfully bound. If this expression
     * completes exceptionally, the returned expression also completes exceptionally with the same error.</p>
     *
     * @param transform Groovy {@link groovy.lang.Closure} mapping the value of this expression to a new value
     * @return a new expression representing the transformed value
     */
    DataflowExpression then(final Closure transform) {
        log.debug "then: create new dependent DataflowExpression where value status is $value"
        def newExpression = new DataflowExpression(pool, type)

        // Use whenBound with a normal Groovy closure; the closure receives the value when it becomes bound.
        whenBound("then") { T v ->
            try {
                def transformed = transform.call(v)
                newExpression.setValue((T) transformed)
            } catch (Throwable t) {
                newExpression.setError(t)
            }
        }

        return newExpression
    }

    /**
     * String representation including state and (if bound) the value or error.
     *
     * @return string representation for debugging
     */
    String toString() {
        String core
        if (!isBound()) {
            core = "PENDING"
        } else if (isError()) {
            core = "ERROR(${getError()?.message})"
        } else {
            def v = value.get()
            core = "SUCCESS($v)"
        }
        return "DataflowExpression[$core]"
    }

    // ---------------------------------------------------------------------------------------------
    // Internal class: MessageClosure
    // ---------------------------------------------------------------------------------------------

    /**
     * Internal wrapper that associates a descriptive message string, and the underlying Groovy
     * {@link groovy.lang.Closure} to invoke when the owning {@link DataflowExpression} is bound.
     *
     * @param <T> type of value produced by the associated DataflowExpression
     */
    @CompileStatic
    class MessageClosure<T> implements Callable {

        /** Pool used for asynchronous execution. */
        final ConcurrentPool pool

        /** Owning expression that we are listening to. */
        final DataflowExpression<T> owner

        /** Human-readable message used in logging. */
        final String message

        /** Underlying Groovy closure to invoke. */
        final Closure closure

        /** Reference to the latest value to deliver to the closure. */
        private final AtomicReference<T> newValueRef = new AtomicReference<>()

        /**
         * Creates a new message closure.
         *
         * @param pool    pool used for asynchronous execution
         * @param owner   owning expression
         * @param message descriptive message for logging
         * @param closure underlying Groovy closure to invoke
         */
        MessageClosure(ConcurrentPool pool, DataflowExpression<T> owner, String message, Closure closure) {
            this.pool = pool
            this.owner = owner
            this.message = message
            this.closure = closure
        }

        /**
         * Sets the latest value that should be supplied to the closure.
         *
         * @param newValue value to deliver
         */
        void setNewValue(T newValue) {
            log.debug "MessageClosure: setNewValue() called with $newValue"
            newValueRef.set(newValue)
        }

        /**
         * Invokes the wrapped closure with the appropriate arguments based on its arity.
         *
         * <p>If the closure accepts a single argument, it receives the value. If it accepts two arguments,
         * it receives the value and the {@code message}. Additional arities are supported but discouraged.</p>
         *
         * @return result of closure invocation (if any)
         * @throws Exception if the closure throws
         */
        @Override
        @CompileDynamic
        Object call() throws Exception {
            T v = newValueRef.get()
            int paramCount = closure.maximumNumberOfParameters
            if (paramCount == 0) {
                return closure.call()
            } else if (paramCount == 1) {
                return closure.call(v)
            } else if (paramCount == 2) {
                return closure.call(v, message)
            } else {
                // fall back to passing value and message; additional parameters receive null
                List args = [v, message]
                while (args.size() < paramCount) {
                    args.add(null)
                }
                return closure.call(*args)
            }
        }

        /**
         * Synchronously invokes the closure with the given value.
         *
         * @param newValue value to send to the listener
         */
        void sendWithValue(T newValue) {
            log.debug "MessageClosure: send(): message $message, at: ${getTimestamp()}"
            setNewValue(newValue)
            try {
                call()
            } catch (Throwable t) {
                log.warn("Error executing whenBound listener: ${t.message}", t)
            }
        }

        /**
         * Asynchronously invokes the closure with the given value using the pool's executor.
         *
         * @param newValue value to send to the listener
         */
        void sendAsyncWithValue(T newValue) {
            log.debug "MessageClosure: sendAsync(): message $message, at: ${getTimestamp()}"
            setNewValue(newValue)
            pool.executor.submit({ ->
                try {
                    call()
                } catch (Throwable t) {
                    log.warn("Error executing whenBound listener asynchronously: ${t.message}", t)
                }
            } as Callable)
        }
    }
}



