package org.softwood.promise.core.dataflow

import groovy.util.logging.Slf4j
import org.softwood.dataflow.DataflowVariable
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

    /** Cached lifecycle state for fast checks */
    private final AtomicReference<PromiseState> state =
            new AtomicReference<>(PromiseState.PENDING)

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

        // When DFV resolves successfully
        variable.whenAvailable { T v ->
            log.debug("whenAvailable callback fired, value: ${v}, current state: ${state.get()}")
            boolean casResult = state.compareAndSet(PromiseState.PENDING, PromiseState.COMPLETED)
            log.debug("whenAvailable CAS PENDING->COMPLETED: ${casResult}, final state: ${state.get()}")
        }

        // When DFV resolves with an error
        variable.whenError { Throwable err ->
            log.debug("whenError callback fired, error: ${err.class.simpleName}: ${err.message}, current state: ${state.get()}")
            if (err instanceof CancellationException) {
                boolean casResult = state.compareAndSet(PromiseState.PENDING, PromiseState.CANCELLED)
                log.debug("whenError CAS PENDING->CANCELLED: ${casResult}, final state: ${state.get()}")
            } else {
                boolean casResult = state.compareAndSet(PromiseState.PENDING, PromiseState.FAILED)
                log.debug("whenError CAS PENDING->FAILED: ${casResult}, final state: ${state.get()}")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helper: Synchronize state from DFV
    // -------------------------------------------------------------------------

    private PromiseState deriveStateFromVariable() {
        PromiseState s = state.get()
        log.debug("deriveStateFromVariable() called, current state: ${s}, variable.isBound(): ${variable.isBound()}")

        if (s != PromiseState.PENDING) {
            log.debug("deriveStateFromVariable() returning early, state: ${s}")
            return s
        }

        // State is PENDING - check if variable is actually bound
        if (!variable.isBound()) {
            log.debug("deriveStateFromVariable() variable not bound, returning PENDING")
            return s
        }

        // Variable is bound - update state based on variable's state
        log.debug("deriveStateFromVariable() variable is bound, hasError: ${variable.hasError()}")
        if (variable.hasError()) {
            Throwable err = variable.getError()
            log.debug("deriveStateFromVariable() variable has error: ${err.class.simpleName}: ${err.message}")
            if (err instanceof CancellationException) {
                boolean casResult = state.compareAndSet(PromiseState.PENDING, PromiseState.CANCELLED)
                log.debug("deriveStateFromVariable() CAS PENDING->CANCELLED: ${casResult}")
            } else {
                boolean casResult = state.compareAndSet(PromiseState.PENDING, PromiseState.FAILED)
                log.debug("deriveStateFromVariable() CAS PENDING->FAILED: ${casResult}")
            }
        } else {
            boolean casResult = state.compareAndSet(PromiseState.PENDING, PromiseState.COMPLETED)
            log.debug("deriveStateFromVariable() CAS PENDING->COMPLETED: ${casResult}")
        }

        PromiseState finalState = state.get()
        log.debug("deriveStateFromVariable() returning state: ${finalState}")
        return finalState
    }

    /**
     * Aggressively check if this promise is done by checking both state and variable.
     * This is needed because of potential race conditions between state updates and
     * variable binding.
     */
    private boolean isDoneInternal() {
        // First check cached state
        PromiseState s = state.get()
        log.debug("isDoneInternal() called, state: ${s}, variable.isBound(): ${variable.isBound()}")

        if (s != PromiseState.PENDING) {
            log.debug("isDoneInternal() returning true (state != PENDING)")
            return true
        }

        // State says PENDING - double-check with variable directly
        if (variable.isBound()) {
            log.debug("isDoneInternal() state is PENDING but variable is bound! Forcing sync...")
            // Variable is bound but state hasn't been updated yet
            // Force synchronization
            deriveStateFromVariable()
            PromiseState newState = state.get()
            boolean result = newState != PromiseState.PENDING
            log.debug("isDoneInternal() after sync, state: ${newState}, returning: ${result}")
            return result
        }

        log.debug("isDoneInternal() returning false (state PENDING, variable not bound)")
        return false
    }

    private void registerDependent(Promise<?> other) {
        if (other == null || other.is(this)) return

        dependents.add(other)

        // If already cancelled/failed → propagate immediately
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
        if (state.compareAndSet(PromiseState.PENDING, PromiseState.COMPLETED)) {
            try {
                variable.bind(value)
            } catch (IllegalStateException e) {
                log.debug("DFV already bound in accept(T): ${e.message}")
            }
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
        log.debug("fail() called with error: ${error.class.simpleName}: ${error.message}, current state: ${state.get()}")

        if (error instanceof CancellationException) {
            cancel(false)
            return this
        }

        // Always try to transition to FAILED state
        boolean transitioned = state.compareAndSet(PromiseState.PENDING, PromiseState.FAILED)
        log.debug("fail() CAS result: ${transitioned}, state after CAS: ${state.get()}")

        if (transitioned) {
            // We transitioned from PENDING → FAILED
            try {
                log.debug("fail() calling variable.bindError(), variable.isBound() = ${variable.isBound()}")
                variable.bindError(error)
                log.debug("fail() variable.bindError() completed, variable.isBound() = ${variable.isBound()}, variable.hasError() = ${variable.hasError()}")
            } catch (IllegalStateException e) {
                log.debug("DFV already bound in fail(): ${e.message}")
            }
        } else {
            // State was already non-PENDING (COMPLETED, FAILED, or CANCELLED)
            // Force state to FAILED if not already terminal
            PromiseState currentState = state.get()
            log.debug("fail() CAS failed, current state: ${currentState}")
            if (currentState != PromiseState.FAILED && currentState != PromiseState.CANCELLED) {
                state.set(PromiseState.FAILED)
                log.debug("fail() forced state to FAILED")
            }
            // Try to bind error anyway (will fail silently if already bound)
            try {
                variable.bindError(error)
            } catch (IllegalStateException e) {
                log.debug("DFV already bound in fail() (force): ${e.message}")
            }
        }

        // CRITICAL: Force state synchronization from variable to ensure isDone() works
        // This handles race conditions where callbacks haven't fired yet
        log.debug("fail() calling deriveStateFromVariable() before return, state: ${state.get()}, variable.isBound(): ${variable.isBound()}")
        deriveStateFromVariable()
        log.debug("fail() returning, final state: ${state.get()}, variable.isBound(): ${variable.isBound()}, variable.hasError(): ${variable.hasError()}")

        return this
    }

    // -------------------------------------------------------------------------
    // Blocking Retrieval
    // -------------------------------------------------------------------------

    @Override
    T get() throws Exception {
        CompletableFuture<T> f = variable.toFuture()
        try {
            return f.get()
        } catch (ExecutionException e) {
            Throwable c = e.cause ?: e
            if (c instanceof Exception) throw (Exception)c
            throw new RuntimeException(c)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
            throw new RuntimeException(e)
        }
    }

    @Override
    T get(long timeout, TimeUnit unit) throws TimeoutException {
        CompletableFuture<T> f = variable.toFuture()
        try {
            return f.get(timeout, unit)
        } catch (TimeoutException e) {
            throw e
        } catch (ExecutionException e) {
            Throwable c = e.cause ?: e
            if (c instanceof RuntimeException) throw (RuntimeException)c
            throw new RuntimeException(c)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
            throw new RuntimeException(e)
        }
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

        // Only PENDING promises can transition to CANCELLED
        if (!state.compareAndSet(PromiseState.PENDING, PromiseState.CANCELLED)) {
            // Race condition: state changed between check and CAS
            // Re-derive state and propagate if needed
            s = deriveStateFromVariable()
            if (s == PromiseState.COMPLETED || s == PromiseState.FAILED) {
                dependents.each { Promise<?> d -> d.cancel(mayInterruptIfRunning) }
            }
            return isCancelled()
        }

        // Successfully transitioned to CANCELLED

        // Cancel the underlying task if it exists
        if (taskHandle != null) {
            taskHandle.cancel(mayInterruptIfRunning)
        }

        // Bind the DFV to cancelled state
        try {
            variable.bindCancelled(new CancellationException("Promise was explicitly cancelled"))
        } catch (IllegalStateException e) {
            log.debug("DFV already bound in cancel(): ${e.message}")
        }

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

    // -------------------------------------------------------------------------
    // Callbacks
    // -------------------------------------------------------------------------

    //internal helper now
    Promise<T> onSuccess(Consumer<T> c) {
        if (c == null) return this
        variable.whenAvailable { v -> c.accept(v) }

        // CRITICAL: If already completed, invoke callback immediately
        if (this.isCompleted()) {
            try {
                c.accept(variable.get())
            } catch (Throwable t) {
                log.error("Error in onSuccess callback (immediate invocation)", t)
            }
        }

        return this
    }

    @Override
    Promise<T> onError(Consumer<Throwable> c) {
        if (c == null) return this
        variable.whenError { e -> c.accept(e) }

        // CRITICAL: If already failed, invoke callback immediately
        if (this.isDone() && !this.isCompleted() && !this.isCancelled()) {
            try {
                c.accept(variable.getError())
            } catch (Throwable t) {
                log.error("Error in onError callback (immediate invocation)", t)
            }
        }

        return this
    }

    @Override
    Promise<T> onComplete(Consumer<T> callback) {
        if (callback == null) return this

        // Success path only — matches Promise<T>.onComplete contract
        variable.whenAvailable { T v ->
            try {
                callback.accept(v)
            } catch (Throwable t) {
                log.error("Error in onComplete callback", t)
            }
        }

        // CRITICAL: If already completed, invoke callback immediately
        if (this.isCompleted()) {
            try {
                callback.accept(variable.get())
            } catch (Throwable t) {
                log.error("Error in onComplete callback (immediate invocation)", t)
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

        if (this.isCompleted()) {
            try {
                next.accept(fn.apply(variable.get()))
            } catch (Throwable t) {
                next.fail(t)
            }
        } else if (this.isCancelled()) {
            next.cancel(false)
        } else if (this.isDone()) {
            next.fail(variable.getError())
        }

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
            } catch (Throwable t) {
                next.fail(t)
            }
        }

        // CRITICAL: Handle already-completed promises synchronously
        if (this.isCompleted()) {
            try {
                next.accept((R)variable.get())
            } catch (Throwable t) {
                next.fail(t)
            }
        } else if (this.isCancelled()) {
            next.cancel(false)
        } else if (this.isDone()) {
            // Already failed - apply recovery function synchronously
            try {
                next.accept(fn.apply(variable.getError()))
            } catch (Throwable t) {
                next.fail(t)
            }
        }

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

        // Schedule timeout
        def scheduler = variable.executor
        def timeoutFuture = CompletableFuture.runAsync({
            try {
                unit.sleep(timeout)
                next.fail(new TimeoutException("Promise timed out after ${timeout} ${unit}"))
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt()
            }
        }, scheduler)

        // Wire up this promise completion
        this.onSuccess { T v ->
            timeoutFuture.cancel(true)
            next.accept(v)
        }

        this.onError { Throwable e ->
            timeoutFuture.cancel(true)
            next.fail(e)
        }

        return next
    }

    @Override
    Promise<T> timeout(long timeout, TimeUnit unit, T fallbackValue) {
        DataflowVariable<T> nextVar = new DataflowVariable<>(variable.pool)
        DataflowPromise<T> next = new DataflowPromise<>(nextVar)

        // Schedule timeout with fallback
        def scheduler = variable.executor
        def timeoutFuture = CompletableFuture.runAsync({
            try {
                unit.sleep(timeout)
                next.accept(fallbackValue)
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt()
            }
        }, scheduler)

        // Wire up this promise completion
        this.onSuccess { T v ->
            timeoutFuture.cancel(true)
            next.accept(v)
        }

        this.onError { Throwable e ->
            timeoutFuture.cancel(true)
            next.fail(e)
        }

        return next
    }

    @Override
    Promise<T> orTimeout(long timeout, TimeUnit unit) {
        // Schedule timeout that modifies THIS promise
        def scheduler = variable.executor
        CompletableFuture.runAsync({
            try {
                unit.sleep(timeout)
                this.fail(new TimeoutException("Promise timed out after ${timeout} ${unit}"))
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt()
            }
        }, scheduler)

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