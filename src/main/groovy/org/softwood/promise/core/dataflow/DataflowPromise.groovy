package org.softwood.promise.core.dataflow

import groovy.util.logging.Slf4j
import org.softwood.dataflow.DataflowVariable
import org.softwood.pool.ConcurrentPool
import org.softwood.promise.Promise
import org.softwood.promise.core.PromiseState

import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.*

/**
 * Promise implementation backed by a Softwood {@link DataflowVariable}.
 *
 * <p>This class adapts DataflowVariable (DFV) semantics to the generic Promise API:
 * single-assignment, cancellation, completion, flatMap, chain propagation, and
 * full cancellation/error propagation across dependent promises.</p>
 *
 * <h2>Lifecycle</h2>
 * <ul>
 *   <li>{@link PromiseState#PENDING}</li>
 *   <li>{@link PromiseState#COMPLETED}</li>
 *   <li>{@link PromiseState#FAILED}</li>
 *   <li>{@link PromiseState#CANCELLED}</li>
 * </ul>
 *
 * All completions are mirrored onto the backing {@link DataflowVariable}, which
 * controls the event delivery and produces the underlying {@link CompletableFuture}.
 */
@Slf4j
class DataflowPromise<T> implements Promise<T> {

    /** Backing single-assignment variable */
    private final DataflowVariable<T> variable

    // GCU 1.2.2 (DF7): there is one state, the variable's. This class used to keep a second
    // AtomicReference<PromiseState> beside it, moved by accept/fail/cancel before the variable was bound
    // and by two listeners afterwards. The two could disagree: isDone() true while get() still blocked.

    /** Async task handle created by accept(Supplier) (for cancellation) */
    private volatile CompletableFuture<?> taskHandle = null

    /**
     * Dependent promises that should be cancelled/failed immediately when
     * this promise completes in a terminal state.
     */
    private final CopyOnWriteArrayList<Promise<?>> dependents = new CopyOnWriteArrayList<>()

    // -------------------------------------------------------------------------
    // Constructor & DFV Listeners
    // -------------------------------------------------------------------------

    DataflowPromise(DataflowVariable<T> variable) {
        this.variable = variable
    }

    // -------------------------------------------------------------------------
    // Helper: the promise's state, read from the variable
    // -------------------------------------------------------------------------

    private PromiseState deriveStateFromVariable() {
        if (!variable.isBound()) {
            return PromiseState.PENDING
        }
        if (!variable.hasError()) {
            return PromiseState.COMPLETED
        }
        return (variable.getError() instanceof CancellationException) ? PromiseState.CANCELLED : PromiseState.FAILED
    }

    private boolean isDoneInternal() {
        return variable.isBound()
    }

    private void registerDependent(Promise<?> other) {
        if (other == null || other.is(this)) return

        dependents.add(other)

        // GCU 1.2.2 (DF9): forget the dependent once it has completed. The list used only to grow, so a
        // long-lived promise kept every promise ever derived from it. A completed dependent has nothing
        // left to be told.
        if (other instanceof DataflowPromise) {
            ((DataflowPromise<?>) other).variable.whenBound { dependents.remove(other) }
        }

        // If already cancelled/failed, propagate immediately
        PromiseState s = deriveStateFromVariable()
        if (s == PromiseState.CANCELLED) {
            other.cancel(false)
        } else if (s == PromiseState.FAILED) {
            if (other instanceof DataflowPromise) {
                ((DataflowPromise<?>) other).fail(variable.getError())
            }
        }
    }

    // -------------------------------------------------------------------------
    // Accept (Completion)
    // -------------------------------------------------------------------------

    @Override
    Promise<T> accept(T value) {
        // GCU 1.2.2 (DF7): the variable decides. This used to move a state of its own to COMPLETED and
        // then bind, so isDone() could be true while the variable (and get()) was still unbound.
        if (variable.isBound()) return this
        try {
            variable.bind(value)
        } catch (IllegalStateException e) {
            // bound by another thread between the check and the bind: first writer wins
        }
        return this
    }

    @Override
    Promise<T> accept(Supplier<T> supplier) {
        if (supplier == null) return this

        taskHandle = CompletableFuture.supplyAsync(
                { supplier.get() } as Supplier<T>,
                variable.executor
        )

        taskHandle.whenComplete { T value, Throwable error ->
            if (error != null) {
                Throwable actual = (error instanceof CompletionException && error.cause != null)
                        ? error.cause : error
                if (actual instanceof CancellationException) {
                    cancel(false)
                } else {
                    fail(actual)
                }
            } else {
                accept(value)
            }
        }

        return this
    }

    @Override
    Promise<T> accept(CompletableFuture<T> future) {
        if (future == null) return this

        future.whenComplete { T value, Throwable error ->
            if (error != null) {
                Throwable actual = (error instanceof CompletionException && error.cause != null)
                        ? error.cause : error
                if (actual instanceof CancellationException) {
                    cancel(false)
                } else {
                    fail(actual)
                }
            } else {
                accept(value)
            }
        }

        return this
    }

    @Override
    Promise<T> accept(Promise<T> other) {
        if (other == null) return this

        if (other instanceof DataflowPromise) {
            ((DataflowPromise<T>) other).registerDependent(this)
        }

        other.onSuccess { T v -> accept(v) }
        other.onError { Throwable e ->
            if (e instanceof CancellationException) cancel(false)
            else fail(e)
        }

        if (other.isCancelled()) cancel(false)

        return this
    }

    @Override
    Promise<T> fail(Throwable error) {
        if (error instanceof CancellationException) {
            cancel(false)
            return this
        }

        // Single-assignment semantics: first writer wins. If already in a terminal state, ignore
        // silently. GCU 1.2.2 (DF7): the variable decides; there is no separate state to move first.
        if (variable.isBound()) {
            log.debug("fail() called but promise already in terminal state: ${deriveStateFromVariable()} - ignoring (error was: ${error?.class?.simpleName})")
            return this
        }
        try {
            variable.bindError(error)
        } catch (IllegalStateException e) {
            // Variable was bound by another thread between the check and the bind - that outcome stands
            log.debug("fail(): variable already bound, keeping the first outcome")
        }

        return this
    }

    // -------------------------------------------------------------------------
    // Blocking Retrieval
    // -------------------------------------------------------------------------

    @Override
    T get() throws Exception {
        // 1.2.0 (DF2): read the variable's own future. toFuture() built a second future completed by
        // listeners, and a listener registered while the variable was being bound could be missed,
        // leaving this get() blocked for ever.
        try {
            return variable.get()
        } catch (ExecutionException e) {
            Throwable c = e.cause ?: e
            if (c instanceof Exception) throw (Exception)c
            throw new RuntimeException(c)
        } catch (InterruptedException e) {
            throw interruptedWhileReading(e)
        }
    }

    @Override
    T get(long timeout, TimeUnit unit) throws TimeoutException, Exception {
        // 1.2.0 (DF2): as get() above.
        try {
            return variable.get(timeout, unit)
        } catch (TimeoutException e) {
            throw e
        } catch (ExecutionException e) {
            Throwable c = e.cause ?: e
            if (c instanceof Exception) throw (Exception)c
            throw new RuntimeException(c)
        } catch (InterruptedException e) {
            throw interruptedWhileReading(e)
        }
    }

    /**
     * GCU 1.2.2 (DF13): an InterruptedException out of the variable is one of two things. If it is the
     * error the task itself ended with, the reading thread was never interrupted and its interrupt flag
     * must not be set (it used to be, which made the reader's next blocking call fail). Otherwise the
     * reader really was interrupted while waiting and the flag is restored. Either way the caller gets
     * the same RuntimeException wrapper as before.
     */
    private RuntimeException interruptedWhileReading(InterruptedException e) {
        boolean taskOwnError = variable.isBound() && e.is(variable.getError())
        if (!taskOwnError) {
            Thread.currentThread().interrupt()
        }
        return new RuntimeException(e)
    }

    // -------------------------------------------------------------------------
    // Cancellation
    // -------------------------------------------------------------------------

    @Override
    boolean cancel(boolean mayInterruptIfRunning) {
        PromiseState s = deriveStateFromVariable()

        // If already cancelled, just return true
        if (s == PromiseState.CANCELLED) {
            return true
        }

        // CRITICAL FIX: If already completed or failed, we can't change this promise's state,
        // but we MUST still propagate cancellation to dependents
        if (s == PromiseState.COMPLETED || s == PromiseState.FAILED) {
            // Propagate cancellation to all dependents even though this promise is done
            dependents.each { Promise<?> d -> d.cancel(mayInterruptIfRunning) }
            return false  // Return false because THIS promise wasn't cancelled (it was already done)
        }

        // GCU 1.2.2 (DF7): binding the variable IS the transition. Only a pending variable can be
        // cancelled; if another thread completed it first, the bind is refused and that outcome stands.
        try {
            variable.bindCancelled(new CancellationException("Promise was explicitly cancelled"))
        } catch (IllegalStateException lostRace) {
            s = deriveStateFromVariable()
            if (s == PromiseState.COMPLETED || s == PromiseState.FAILED) {
                dependents.each { Promise<?> d -> d.cancel(mayInterruptIfRunning) }
            }
            return s == PromiseState.CANCELLED
        }

        // Successfully transitioned to CANCELLED

        // Cancel the underlying task if it exists
        if (taskHandle != null) {
            taskHandle.cancel(mayInterruptIfRunning)
        }

        // 1.2.0 (DF3): a promise from executeAsync has no taskHandle; its work runs as a pool Future held
        // by the variable. Cancel that too, so mayInterruptIfRunning=true really interrupts the work.
        variable.cancelTask(mayInterruptIfRunning)

        // Propagate cancellation to all dependents
        dependents.each { Promise<?> d -> d.cancel(mayInterruptIfRunning) }

        return true
    }


    // -------------------------------------------------------------------------
    // State Queries
    // -------------------------------------------------------------------------

    @Override boolean isDone()       { isDoneInternal() }
    @Override boolean isCompleted()  { deriveStateFromVariable() == PromiseState.COMPLETED }
    @Override boolean isCancelled()  { deriveStateFromVariable() == PromiseState.CANCELLED }
    @Override boolean isFailed()     { deriveStateFromVariable() == PromiseState.FAILED }

    // -------------------------------------------------------------------------
    // Callbacks
    // -------------------------------------------------------------------------

    //internal helper now
    Promise<T> onSuccess(Consumer<T> c) {
        if (c == null) return this
        // whenAvailable already handles the already-bound case by invoking immediately
        // and returning early - no explicit re-invocation needed here (would double-fire).
        variable.whenAvailable { v -> c.accept(v) }
        return this
    }

    @Override
    Promise<T> onError(Consumer<Throwable> c) {
        if (c == null) return this
        // whenError already handles the already-failed case by invoking immediately
        // and returning early - no explicit re-invocation needed here (would double-fire).
        variable.whenError { e -> c.accept(e) }
        return this
    }

    @Override
    Promise<T> onComplete(Consumer<T> callback) {
        if (callback == null) return this
        // Success path only - matches Promise<T>.onComplete contract.
        // whenAvailable already handles the already-bound case by invoking immediately
        // and returning early - no explicit re-invocation needed here (would double-fire).
        variable.whenAvailable { T v ->
            try {
                callback.accept(v)
            } catch (Throwable t) {
                log.error("Error in onComplete callback", t)
            }
        }
        return this
    }

    // -------------------------------------------------------------------------
    // Functional Transformations
    // -------------------------------------------------------------------------

    @Override
    <R> Promise<R> map(Function<? super T, ? extends R> mapper) {
        return then { T v -> mapper.apply(v) }
    }

    @Override
    <R> Promise<R> then(Function<T, R> fn) {
        DataflowVariable<R> nextVar = new DataflowVariable<>(variable.pool)
        DataflowPromise<R> next = new DataflowPromise<>(nextVar)

        registerDependent(next)

        this.onSuccess { T v ->
            try {
                next.accept(fn.apply(v))
            } catch (Throwable t) {
                next.fail(t)
            }
        }

        this.onError { Throwable e -> next.fail(e) }

        // Removed redundant state checks - callbacks handle all cases
        // DataflowVariable.whenBound() fires immediately if already bound

        return next
    }

    @Override
    <R> Promise<R> recover(Function<Throwable, R> fn) {
        DataflowVariable<R> nextVar = new DataflowVariable<>(variable.pool)
        DataflowPromise<R> next = new DataflowPromise<>(nextVar)

        // NOTE: Do NOT call registerDependent(next) for recover!
        // registerDependent would immediately fail the recovered promise if THIS is already failed,
        // which prevents the recovery function from running.

        this.onSuccess { T v -> next.accept((R)v) }
        this.onError { Throwable e ->
            try {
                next.accept(fn.apply(e))
            } catch (java.lang.reflect.UndeclaredThrowableException ute) {
                // Unwrap UndeclaredThrowableException that occurs when Groovy closures
                // converted to Java functional interfaces throw checked exceptions
                Throwable cause = ute.cause ?: ute
                next.fail(cause)
            } catch (Throwable t) {
                next.fail(t)
            }
        }

        // Removed redundant state checks - callbacks handle all cases
        // DataflowVariable.whenBound() fires immediately if already bound

        return next
    }

    @Override
    <R> Promise<R> flatMap(Function<? super T, Promise<R>> mapper) {
        Objects.requireNonNull(mapper, "mapper must not be null")

        DataflowVariable<R> nextVar = new DataflowVariable<>(variable.pool)
        DataflowPromise<R> nextPromise = new DataflowPromise<>(nextVar)

        // Track the inner promise for cancellation
        final AtomicReference<Promise<R>> innerRef = new AtomicReference<>(null)

        // 1) If OUTER is already cancelled → cancel nextPromise immediately
        if (this.isCancelled()) {
            nextPromise.cancel(false)
            return nextPromise
        }

        // 2) CRITICAL: Register as dependent FIRST, before any async processing
        //    This ensures that if cancel() is called on outer, it propagates to nextPromise
        registerDependent(nextPromise)

        // 3) If OUTER is already completed, handle synchronously but check for cancellation
        if (this.isCompleted()) {
            try {
                // Double-check cancellation before processing
                if (this.isCancelled()) {
                    nextPromise.cancel(false)
                    return nextPromise
                }

                T value = variable.get()
                Promise<R> inner = mapper.apply(value)

                if (inner == null) {
                    nextPromise.fail(new NullPointerException("flatMap mapper returned null promise"))
                    return nextPromise
                }

                innerRef.set(inner)

                // Register inner as dependent of outer for cancellation propagation
                if (inner instanceof DataflowPromise) {
                    registerDependent(inner)
                }

                // Check if outer was cancelled during mapper execution
                if (this.isCancelled()) {
                    inner.cancel(false)
                    nextPromise.cancel(false)
                    return nextPromise
                }

                // Check inner's state synchronously
                if (inner.isCancelled()) {
                    nextPromise.cancel(false)
                    return nextPromise
                }

                // If inner is already done, propagate immediately
                if (inner.isDone()) {
                    if (inner instanceof DataflowPromise) {
                        DataflowPromise<R> dfInner = (DataflowPromise<R>) inner
                        if (dfInner.variable.hasError()) {
                            Throwable err = dfInner.variable.getError()
                            if (err instanceof CancellationException) {
                                nextPromise.cancel(false)
                            } else {
                                nextPromise.fail(err)
                            }
                        } else {
                            // Final cancellation check before accepting
                            if (this.isCancelled() || nextPromise.isCancelled()) {
                                nextPromise.cancel(false)
                            } else {
                                nextPromise.accept(dfInner.variable.get())
                            }
                        }
                        return nextPromise
                    }
                }

                // Inner is still pending, set up callbacks
                setupInnerCallbacks(inner, nextPromise)

            } catch (Throwable ex) {
                nextPromise.fail(ex)
            }

            return nextPromise
        }

        // 4) If OUTER is already failed/cancelled, handle synchronously
        if (this.isDone()) {
            if (this.isCancelled()) {
                nextPromise.cancel(false)
            } else {
                nextPromise.fail(variable.getError())
            }
            return nextPromise
        }

        // 5) Set up bidirectional cancellation for async case
        nextPromise.onError { Throwable e ->
            if (e instanceof CancellationException) {
                Promise<R> inner = innerRef.get()
                if (inner != null && !inner.isCancelled()) {
                    inner.cancel(false)
                }
            }
        }

        // 6) When OUTER fails (including cancellation) → propagate to nextPromise
        this.onError { Throwable e ->
            if (e instanceof CancellationException) {
                nextPromise.cancel(false)
                Promise<R> inner = innerRef.get()
                if (inner != null && !inner.isCancelled()) {
                    inner.cancel(false)
                }
            } else {
                nextPromise.fail(e)
            }
        }

        // 7) When OUTER succeeds → create and wire up inner promise
        this.onSuccess { T value ->
            // Check if outer was cancelled after success callback fired
            if (this.isCancelled()) {
                nextPromise.cancel(false)
                return
            }

            Promise<R> inner
            try {
                inner = mapper.apply(value)
            } catch (Throwable ex) {
                nextPromise.fail(ex)
                return
            }

            if (inner == null) {
                nextPromise.fail(new NullPointerException("flatMap mapper returned null promise"))
                return
            }

            innerRef.set(inner)

            // Register inner as dependent for cancellation propagation
            if (inner instanceof DataflowPromise) {
                registerDependent(inner)
            }

            // Check if inner is already cancelled SYNCHRONOUSLY
            if (inner.isCancelled()) {
                nextPromise.cancel(false)
                return
            }

            // Check if outer was cancelled while we were setting up
            if (this.isCancelled()) {
                inner.cancel(false)
                nextPromise.cancel(false)
                return
            }

            // Check if nextPromise was cancelled while we were setting up
            if (nextPromise.isCancelled()) {
                inner.cancel(false)
                return
            }

            // Set up callbacks for async completion
            setupInnerCallbacks(inner, nextPromise)
        }

        return nextPromise
    }

    //internal helper for flat map to ge the logic to work
    private <R> void setupInnerCallbacks(Promise<R> inner, DataflowPromise<R> nextPromise) {
        inner.onSuccess { R r ->
            // CRITICAL: Check both nextPromise and outer promise cancellation
            if (nextPromise.isCancelled() || this.isCancelled()) {
                // Don't accept the value if either promise was cancelled
                if (!nextPromise.isCancelled()) {
                    nextPromise.cancel(false)
                }
                return
            }
            nextPromise.accept(r)
        }

        inner.onError { Throwable e ->
            if (e instanceof CancellationException) {
                nextPromise.cancel(false)
            } else {
                nextPromise.fail(e)
            }
        }
    }

    @Override
    Promise<T> filter(Predicate<? super T> predicate) {
        Objects.requireNonNull(predicate)

        DataflowVariable<T> nextVar = new DataflowVariable<>(variable.pool)
        DataflowPromise<T> next = new DataflowPromise<>(nextVar)

        registerDependent(next)

        this.onSuccess { T v ->
            try {
                if (predicate.test(v)) next.accept(v)
                else next.fail(new NoSuchElementException("Predicate not satisfied"))
            } catch (Throwable t) {
                next.fail(t)
            }
        }

        this.onError { Throwable e -> next.fail(e) }

        return next
    }

    // -------------------------------------------------------------------------
    // Type Coercion
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // whenComplete - Unified Callback
    // -------------------------------------------------------------------------

    @Override
    Promise<T> whenComplete(java.util.function.BiConsumer<T, Throwable> action) {
        if (action == null) return this

        // Register for both success and error
        this.onSuccess { T v ->
            try {
                action.accept(v, null)
            } catch (Throwable t) {
                log.error("Error in whenComplete callback (success path)", t)
            }
        }

        this.onError { Throwable e ->
            try {
                action.accept(null, e)
            } catch (Throwable t) {
                log.error("Error in whenComplete callback (error path)", t)
            }
        }

        return this
    }

    // -------------------------------------------------------------------------
    // Tap - Side Effects
    // -------------------------------------------------------------------------

    @Override
    Promise<T> tap(Consumer<T> action) {
        if (action == null) return this

        return this.then { T v ->
            try {
                action.accept(v)
            } catch (Throwable t) {
                log.warn("Error in tap action (continuing)", t)
            }
            return v  // Return original value unchanged
        } as Promise<T>
    }

    // -------------------------------------------------------------------------
    // Timeout Operations
    // -------------------------------------------------------------------------

    @Override
    Promise<T> timeout(long timeout, TimeUnit unit) {
        DataflowVariable<T> nextVar = new DataflowVariable<>(variable.pool)
        DataflowPromise<T> next = new DataflowPromise<>(nextVar)

        // Use the shared scheduled executor for a one-shot timer - no thread parked.
        def scheduledFuture = ConcurrentPool.sharedScheduledExecutor.schedule({
            if (!next.isDone()) {
                next.fail(new TimeoutException("Promise timed out after ${timeout} ${unit}"))
            }
        } as Runnable, timeout, unit)

        this.onSuccess { T v ->
            scheduledFuture.cancel(false)
            if (!next.isDone()) next.accept(v)
        }
        this.onError { Throwable e ->
            scheduledFuture.cancel(false)
            if (!next.isDone()) next.fail(e)
        }

        return next
    }

    @Override
    Promise<T> timeout(long timeout, TimeUnit unit, T fallbackValue) {
        DataflowVariable<T> nextVar = new DataflowVariable<>(variable.pool)
        DataflowPromise<T> next = new DataflowPromise<>(nextVar)

        // Use the shared scheduled executor for a one-shot timer - no thread parked.
        def scheduledFuture = ConcurrentPool.sharedScheduledExecutor.schedule({
            if (!next.isDone()) {
                next.accept(fallbackValue)
            }
        } as Runnable, timeout, unit)

        this.onSuccess { T v ->
            scheduledFuture.cancel(false)
            if (!next.isDone()) next.accept(v)
        }
        this.onError { Throwable e ->
            scheduledFuture.cancel(false)
            if (!next.isDone()) next.fail(e)
        }

        return next
    }

    @Override
    Promise<T> orTimeout(long timeout, TimeUnit unit) {
        // One-shot scheduled timer - no thread parked sleeping.
        final ScheduledFuture<?> timer = ConcurrentPool.sharedScheduledExecutor.schedule({
            if (!this.isDone()) {
                this.fail(new TimeoutException("Promise timed out after ${timeout} ${unit}"))
            }
        } as Runnable, timeout, unit)
        // GCU 1.2.1 (DF8): release the timer as soon as the promise completes. It used to stay scheduled,
        // holding this promise, until the timeout elapsed.
        this.whenComplete({ T v, Throwable e -> timer.cancel(false) } as BiConsumer<T, Throwable>)
        return this
    }

    // -------------------------------------------------------------------------
    // Combining Operations
    // -------------------------------------------------------------------------

    @Override
    <U, R> Promise<R> zip(Promise<U> other, java.util.function.BiFunction<T, U, R> combiner) {
        Objects.requireNonNull(other, "other promise must not be null")
        Objects.requireNonNull(combiner, "combiner must not be null")

        DataflowVariable<R> nextVar = new DataflowVariable<>(variable.pool)
        DataflowPromise<R> next = new DataflowPromise<>(nextVar)

        // Use atomic reference to store values as they arrive
        def thisValue = new AtomicReference<T>()
        def otherValue = new AtomicReference<U>()
        def thisCompleted = new AtomicBoolean(false)
        def otherCompleted = new AtomicBoolean(false)

        // When THIS completes
        this.onSuccess { T v ->
            thisValue.set(v)
            if (thisCompleted.compareAndSet(false, true)) {
                // Check if other is also complete
                if (otherCompleted.get()) {
                    try {
                        def result = combiner.apply(v, otherValue.get())
                        next.accept(result)
                    } catch (Throwable t) {
                        next.fail(t)
                    }
                }
            }
        }

        this.onError { Throwable e -> next.fail(e) }

        // When OTHER completes
        other.onSuccess { U u ->
            otherValue.set(u)
            if (otherCompleted.compareAndSet(false, true)) {
                // Check if this is also complete
                if (thisCompleted.get()) {
                    try {
                        def result = combiner.apply(thisValue.get(), u)
                        next.accept(result)
                    } catch (Throwable t) {
                        next.fail(t)
                    }
                }
            }
        }

        other.onError { Throwable e -> next.fail(e) }

        return next
    }

    // -------------------------------------------------------------------------
    // Type Coercion
    // -------------------------------------------------------------------------

    @Override
    CompletableFuture<T> asType(Class clazz) {
        if (clazz == CompletableFuture) {
            return variable.toFuture()
        }
        throw new RuntimeException("Conversion to type $clazz is not supported")
    }
}