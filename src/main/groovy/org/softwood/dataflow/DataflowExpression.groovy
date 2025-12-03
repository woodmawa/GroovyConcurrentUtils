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
 * <p>Lifecycle states:</p>
 * <ul>
 *     <li>{@link DataflowExpression.State#PENDING} – not yet completed</li>
 *     <li>{@link DataflowExpression.State#SUCCESS} – completed with a value</li>
 *     <li>{@link DataflowExpression.State#ERROR} – completed with an error</li>
 *     <li>{@link DataflowExpression.State#CANCELLED} – completed by cancellation
 *         (represented as a {@link CancellationException})</li>
 * </ul>
 *
 * @param <T> type of the value produced by this expression
 */
@CompileStatic
@Slf4j
class DataflowExpression<T> {

    /** Completion state of the expression. */
    enum State {
        /** The expression has not yet been completed. */
        PENDING,
        /** The expression completed successfully with a value. */
        SUCCESS,
        /** The expression completed with an error. */
        ERROR,
        /** The expression was cancelled. */
        CANCELLED
    }

    /** Pool used for asynchronous callback execution. */
    final ConcurrentPool pool

    /** Backing future representing completion of this expression. */
    private final CompletableFuture<T> future = new CompletableFuture<>()

    /** Lock to enforce single-assignment semantics. */
    private final ReentrantLock completionLock = new ReentrantLock()

    /** Current completion state. */
    private final AtomicReference<State> state = new AtomicReference<>(State.PENDING)

    /** Error recorded if the expression completed exceptionally or was cancelled. */
    private volatile Throwable error

    /** Timestamp of successful or failed completion. */
    private volatile LocalDateTime completedAt

    /** Optional type information for tooling / logging. */
    final Class<T> type

    /** Property change support for frameworks / tooling. */
    private final PropertyChangeSupport pcs = new PropertyChangeSupport(this)

    /** Listeners invoked when the expression is bound (scheduled on the pool). */
    private final List<Closure> whenBoundListeners = new CopyOnWriteArrayList<Closure>()

    /** Asynchronous listeners invoked on the pool (legacy alias). */
    private final List<Closure> asyncWhenBoundListeners = new CopyOnWriteArrayList<Closure>()

    /**
     * Create a new expression backed by the given pool.
     *
     * @param pool worker pool used for listener execution
     */
    DataflowExpression(ConcurrentPool pool) {
        this(pool, (Class<T>) Object)
    }

    /**
     * Create a new expression backed by the given pool and explicit type token.
     *
     * @param pool worker pool used for listener execution
     * @param type runtime type token for the value (purely informational)
     */
    DataflowExpression(ConcurrentPool pool, Class<T> type) {
        this.pool = pool
        this.type = type
    }

    /**
     * Expose the underlying {@link ConcurrentPool} used to schedule asynchronous listeners.
     *
     * @return the {@link ConcurrentPool} backing this expression
     */
    ConcurrentPool getPool() {
        return pool
    }

    // --------------------------------------------------------------------------------------------
    // Completion
    // --------------------------------------------------------------------------------------------

    /**
     * Complete this expression successfully with the given value.
     *
     * <p>This is a single-assignment operation: subsequent calls will throw
     * {@link IllegalStateException}.</p>
     *
     * @param newValue value to store (may be {@code null})
     * @throws IllegalStateException if the expression has already been completed
     */
    void setValue(T newValue) {
        completionLock.lock()
        try {
            if (state.get() != State.PENDING) {
                throw new IllegalStateException("DataflowExpression can only be completed once (state=" +
                        state.get() + ", attempted setValue(" + newValue + "))")
            }
            state.set(State.SUCCESS)
            completedAt = LocalDateTime.now()
            future.complete(newValue)
            pcs.firePropertyChange("value", null, newValue)
        } finally {
            completionLock.unlock()
        }
        // Notify listeners outside the lock to avoid re-entrancy issues.
        notifyWhenBound(newValue)
    }

    /**
     * Complete this expression with an error.
     *
     * <p>This is a single-assignment operation: subsequent calls will throw
     * {@link IllegalStateException}.</p>
     *
     * <p>Listeners are still invoked on error; they receive {@code null} and may inspect
     * {@link #hasError()} / {@link #getError()}.</p>
     *
     * @param t error to record (must not be {@code null})
     * @throws IllegalStateException if the expression has already been completed
     */
    void setError(Throwable t) {
        if (t == null) throw new IllegalArgumentException("Error must not be null")
        completionLock.lock()
        try {
            if (state.get() != State.PENDING) {
                throw new IllegalStateException("DataflowExpression can only be completed once (state=" +
                        state.get() + ", attempted setError(" + t + "))")
            }
            state.set(State.ERROR)
            completedAt = LocalDateTime.now()
            error = t
            future.completeExceptionally(new CompletionException(t))
            pcs.firePropertyChange("error", null, t)
        } finally {
            completionLock.unlock()
        }
        // Listeners still fire on error; they receive null and can inspect hasError()/getError().
        notifyWhenBound(null)
    }

    /**
     * Complete this expression by cancellation.
     *
     * <p>Cancellation is represented as a {@link CancellationException} and treated as a
     * terminal completion distinct from SUCCESS and ERROR.</p>
     *
     * @param cause optional cancellation cause; if {@code null} a default
     *              {@link CancellationException} is created
     * @throws IllegalStateException if the expression has already been completed
     */
    void setCancelled(Throwable cause = null) {
        CancellationException cancelled =
                (cause instanceof CancellationException)
                        ? (CancellationException) cause
                        : new CancellationException(cause?.message ?: "DataflowExpression cancelled")

        completionLock.lock()
        try {
            if (state.get() != State.PENDING) {
                throw new IllegalStateException("DataflowExpression can only be completed once (state=" +
                        state.get() + ", attempted setCancelled(" + cancelled + "))")
            }
            state.set(State.CANCELLED)
            completedAt = LocalDateTime.now()
            error = cancelled
            future.completeExceptionally(new CompletionException(cancelled))
            pcs.firePropertyChange("error", null, cancelled)
        } finally {
            completionLock.unlock()
        }
        // Listeners still fire on cancel; they receive null and can inspect hasError()/getError().
        notifyWhenBound(null)
    }

    /**
     * @return {@code true} if the expression has completed (successfully, with error, or cancelled)
     */
    boolean isBound() {
        state.get() != State.PENDING
    }

    /**
     * @return {@code true} if the expression completed with an error or was cancelled
     */
    boolean hasError() {
        def s = state.get()
        return (s == State.ERROR || s == State.CANCELLED)
    }

    /**
     * @return the error recorded for this expression (including cancellation), or {@code null}
     * if it completed successfully
     */
    Throwable getError() {
        error
    }

    /**
     * @return timestamp when the expression completed, or {@code null} if not yet completed
     */
    LocalDateTime getCompletedAt() {
        completedAt
    }

    /**
     * Block until the value is available or an error/cancellation occurs.
     *
     * @return completed value
     * @throws Exception if the expression completed with an error or was cancelled
     */
    T getValue() throws Exception {
        try {
            return future.get()
        } catch (ExecutionException e) {
            Throwable cause = e.cause ?: e
            if (cause instanceof Exception) throw (Exception) cause
            throw new RuntimeException(cause)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
            throw e
        }
    }

    /**
     * Block until the value is available, an error/cancellation occurs, or timeout elapses.
     *
     * @param timeout timeout value
     * @param unit    timeout unit
     * @return completed value
     * @throws TimeoutException if timeout elapses before completion
     * @throws Exception        if the expression completed with an error or cancellation
     */
    T getValue(long timeout, TimeUnit unit) throws Exception {
        try {
            return future.get(timeout, unit)
        } catch (TimeoutException e) {
            throw e
        } catch (ExecutionException e) {
            Throwable cause = e.cause ?: e
            if (cause instanceof Exception) throw (Exception) cause
            throw new RuntimeException(cause)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
            throw e
        }
    }

    // --------------------------------------------------------------------------------------------
    // Listener registration
    // --------------------------------------------------------------------------------------------

    /**
     * Register a listener to be invoked when the expression is bound
     * (successfully, with error, or cancelled).
     *
     * <p>If already bound, the listener is invoked asynchronously on the backing pool. If pending,
     * the listener is queued and will be scheduled on the pool once a value or error is recorded.</p>
     *
     * <p>Listeners are always invoked asynchronously to avoid surprising re-entrancy on the caller
     * thread.</p>
     *
     * @param listener closure receiving the bound value (or {@code null} if error/cancel occurred)
     * @return this expression (for fluent chaining)
     */
    DataflowExpression<T> whenBound(Closure listener) {
        if (listener == null) return this
        if (isBound()) {
            // Run asynchronously on the pool to avoid surprising synchronous re-entrancy.
            scheduleListener(listener, getSafeValueForListeners(), "whenBound")
        } else {
            whenBoundListeners.add(listener)
        }
        this
    }

    /**
     * Register a listener to be invoked asynchronously when the expression is bound.
     *
     * <p>This is a legacy alias for {@link #whenBound(Closure)} that preserves the
     * intent of explicitly asynchronous callbacks. Both methods schedule callbacks on
     * the same backing pool.</p>
     *
     * @param listener closure receiving the bound value (or {@code null} if error/cancel occurred)
     * @return this expression
     */
    DataflowExpression<T> whenBoundAsync(Closure listener) {
        if (listener == null) return this
        if (isBound()) {
            scheduleListener(listener, getSafeValueForListeners(), "whenBoundAsync")
        } else {
            asyncWhenBoundListeners.add(listener)
        }
        this
    }

    /**
     * Convenience overload that supplies an additional {@code message} parameter to the listener.
     *
     * <p>The closure may declare one or two parameters:</p>
     * <ul>
     *     <li>1 parameter: receives only the value.</li>
     *     <li>2 parameters: receives {@code (value, message)}.</li>
     * </ul>
     *
     * @param message human-readable label, useful for logging
     * @param listener listener closure
     * @return this expression
     */
    DataflowExpression<T> whenBound(String message, Closure listener) {
        if (listener == null) return this
        Closure wrapped = { v ->
            invokeListener(listener, v, message)
        }
        return whenBound(wrapped)
    }

    /**
     * Internal helper used to obtain a value for listeners without throwing.
     *
     * <p>If the expression completed successfully, returns the value; otherwise returns {@code null}.</p>
     */
    private T getSafeValueForListeners() {
        if (!isBound() || hasError()) {
            return null
        }
        try {
            return getValue()
        } catch (Exception ignored) {
            return null
        }
    }

    /**
     * Notify all registered listeners that the expression has been bound.
     *
     * @param value value to pass to listeners (possibly {@code null} if error/cancel)
     */
    private void notifyWhenBound(T value) {
        // Copy to avoid concurrent modification
        def sync = new ArrayList<Closure>(whenBoundListeners)
        def async = new ArrayList<Closure>(asyncWhenBoundListeners)

        // All listeners are scheduled asynchronously on the backing pool.
        sync.each { Closure c ->
            scheduleListener(c, value, "whenBound")
        }
        async.each { Closure c ->
            scheduleListener(c, value, "whenBoundAsync")
        }
    }

    /**
     * Schedule a listener on the backing pool.
     */
    private void scheduleListener(Closure listener, T value, String message) {
        pool.executor.execute({
            invokeListener(listener, value, message)
        } as Runnable)
    }

    /**
     * Invoke a listener, respecting its declared arity.
     *
     * <p>Rules:</p>
     * <ul>
     *     <li>If it declares 0 parameters, it is simply called with no arguments.</li>
     *     <li>If it declares 1 parameter, it receives the value.</li>
     *     <li>If it declares 2 parameters, it receives {@code (value, message)}.</li>
     *     <li>For 3+ parameters, it is called with the value only.</li>
     * </ul>
     *
     * @param listener listener closure
     * @param value    value to pass
     * @param message  optional message, used only for 2-arg closures
     */
    @CompileDynamic
    private void invokeListener(Closure listener, Object value, String message) {
        try {
            int paramCount = listener.maximumNumberOfParameters
            switch (paramCount) {
                case 0:
                    listener.call()
                    break
                case 1:
                    listener.call(value)
                    break
                case 2:
                    listener.call(value, message)
                    break
                default:
                    listener.call(value)
            }
        } catch (Throwable t) {
            log.error("Error in whenBound listener", t)
        }
    }

    // --------------------------------------------------------------------------------------------
    // Executor / property change support
    // --------------------------------------------------------------------------------------------

    /**
     * Expose the underlying executor used to schedule asynchronous listeners.
     *
     * @return the executor used by this expression's {@link ConcurrentPool}
     */
    ExecutorService getExecutor() {
        return pool.executor
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
}
