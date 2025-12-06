package org.softwood.dag

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.softwood.dag.task.TaskContext
import org.softwood.pool.ExecutorPool
import org.softwood.pool.WorkerPool
import org.softwood.promise.Promise
import org.softwood.promise.PromiseFactory
import org.softwood.promise.core.PromiseState

import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Delayed
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Predicate
import java.util.function.Supplier

import static java.util.concurrent.TimeUnit.SECONDS
import static org.awaitility.Awaitility.await


class TaskGraphExtraTest {

    static <T> T awaitValue(Promise<T> promise) {
        if (promise == null)
            return null

        def valRef = new AtomicReference<T>()
        def errRef = new AtomicReference<Throwable>()
        def done = new AtomicReference<Boolean>(false)

        promise.onComplete { v ->
            valRef.set((T) v)
            done.set(true)
        }.onError { e ->
            errRef.set(e as Throwable)
            done.set(true)
        }

        await().atMost(5, SECONDS).until { done.get() }

        if (errRef.get() != null)
            throw new AssertionError("Promise failed", errRef.get())

        return valRef.get()
    }

    // ---------------------------------------------------------------
    // Slow task should not block graph execution
    // ---------------------------------------------------------------
    @Test
    @DisplayName("Slow task does not block parallel tasks")
    void testSlowTaskNonBlocking() {
        def graph = TaskGraph.build {
            serviceTask("slow") {
                action { ctx, _ ->
                    Thread.sleep(500)   // simulate slow worker
                    return "done"
                }
            }

            serviceTask("fast") {
                action { ctx, _ -> "fast" }
            }
        }

        def p = graph.run()
        def result = awaitValue(p)

        def results = graph.ctx.globals.__taskResults
        assert results["fast"].get() == "fast"
        assert results["slow"].get() == "done"
    }

    // ---------------------------------------------------------------
    // Retry logic test
    // ---------------------------------------------------------------
    @Test
    @DisplayName("Task retries until success")
    void testTaskRetries() {
        int attempts = 0

        def graph = TaskGraph.build {
            serviceTask("retrying") {
                maxRetries = 3
                action { ctx, _ ->
                    attempts++
                    if (attempts < 3) {
                        throw new RuntimeException("fail")
                    }
                    return "success"
                }
            }
        }

        def p = graph.run()
        def res = awaitValue(p)

        assert res == "success"
        assert attempts == 3
    }

    // ---------------------------------------------------------------
    // Timeout handling test
    // ---------------------------------------------------------------
    @Test
    @DisplayName("Task times out when execution exceeds timeout setting")
    void testTimeout() {
        /*
         * you cannot call awaitValue(p) and expect the task to fail and the test to continue.
         * The promise failure propagates to the test's main thread and causes the test to crash.
         * By wrapping it in a try-catch, you assert the graph failed,
         * then proceed to verify the side effects on the TaskGraph object (graph.tasks["t"].state and graph.tasks["t"].error).
         */
        def graph = TaskGraph.build {
            serviceTask("t") {
                timeoutMillis = 200
                action { ctx, _ ->
                    Thread.sleep(500) // This takes 500ms
                    return "too slow"
                }
            }
        }

        def p = graph.run()

        // ------------------------------------------------------------------
        // FIX: Expect and catch the failure of the graph's final promise (p)
        // ------------------------------------------------------------------
        def failed = false
        try {
            awaitValue(p) // This is expected to throw the "Promise failed" AssertionError
            // If we reach here, the graph unexpectedly completed successfully, which is wrong
            assert false : "The graph promise 'p' should have failed."
        } catch (AssertionError e) {
            // We caught the expected failure from awaitValue
            assert e.message.contains("Promise failed")
            failed = true
        }

        // Ensure the catch block was executed
        assert failed : "Expected graph failure not caught."

        // Optional: Add a short sleep if the Task state update is not synchronous
        // with the promise recovery. This gives the underlying async system a moment
        // to finalize the task's state after the timeout exception.
        // Thread.sleep(100)

        // ------------------------------------------------------------------
        // Assert the task state after the expected failure
        // ------------------------------------------------------------------
        assert graph.tasks["t"].state.toString() == "FAILED"

        // FIX THE NULLPOINTEREXCEPTION HERE:
        // Check the exception's class name, as the message property is null.
        def taskError = graph.tasks["t"].error
        assert taskError != null

        // Check if the error is a TimeoutException
        assert taskError instanceof java.util.concurrent.TimeoutException

        // Optional: Check the full string representation for the word "timeout"
        assert taskError.toString().contains("TimeoutException")
    }

    @Test
    @DisplayName("Graph uses injected worker pool and submits tasks correctly")
    void testInjectedWorkerPool() {

        def fakePool = new FakePool()
        def fakeFactory = new FakePromiseFactory(fakePool)

        def gctx = TaskContext.builder().pool(fakePool).promiseFactory(fakeFactory).build()

        def graph = new TaskGraph("g", gctx)



        def dsl = new TaskGraphDsl(graph)
        dsl.serviceTask("a") {
            action { ctx, _ -> "A" }
        }

        graph.addTask(graph.tasks["a"])

        def p = graph.run()
        def result = awaitValue(p)

        assert fakePool.submitted.size() == 1
        assert result == "A"
    }

    //-------
    //Integration test
    //-------

    @Test
    @DisplayName("Full integration: conditional fork → static to → join merge")
    void testFullIntegrationWorkflow() {

        def graph = TaskGraph.build {

            globals {
                threshold = 50
            }

            serviceTask("loadUser") {
                action { ctx, _ ->
                    return [id: 1, score: 72]
                }
            }

            serviceTask("loadOrders") {
                action { ctx, prev -> "ORDERS" }
            }

            serviceTask("loadInvoices") {
                action { ctx, prev -> "INVOICES" }
            }

            // conditional + static fan-out combined
            fork("router") {
                from "loadUser"
                to "loadInvoices"

                conditionalOn(["loadOrders"]) { u ->
                    u.score > ctx.globals.threshold
                }
            }

            join("combine") {
                from "loadOrders", "loadInvoices"
                action { ctx, promises ->
                    return [
                            orders  : promises[0].get(),
                            invoices: promises[1].get()
                    ]
                }
            }
        }

        def p = graph.run()
        def result = awaitValue(p)

        assert result.orders == "ORDERS"
        assert result.invoices == "INVOICES"
        assert graph.tasks["loadOrders"].state.toString() == "COMPLETED"
    }

}

/**
 * Fully synchronous FakePool.
 *
 * Nothing is executed asynchronously.
 * All tasks run immediately on the calling thread.
 * CompletableFuture is only used as a return wrapper (already completed).
 */
class FakePool implements ExecutorPool {

    boolean closed = false
    String name = "fake"
    boolean useVT = false

    // TRACK all submitted work (for tests)
    final List<String> submitted = Collections.synchronizedList([])

    ExecutorService executor = Executors.newSingleThreadExecutor()
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor()

    // -----------------------------------------------
    // Core synchronous execution + submit tracking
    // -----------------------------------------------

    @Override
    CompletableFuture execute(Closure task) {
        submitted << "closure"   // track submission
        return runSync { task.call() }
    }

    @Override
    CompletableFuture execute(Closure task, Object[] args) {
        submitted << "closure-args"
        return runSync { task.call(*args) }
    }

    @Override
    boolean tryExecute(Closure task) {
        submitted << "try"
        task.call()
        return true
    }

    @Override
    CompletableFuture execute(Callable task) {
        submitted << "callable"
        return runSync { task.call() }
    }

    @Override
    CompletableFuture execute(Runnable task) {
        submitted << "runnable"
        return runSync {
            task.run()
            return null
        }
    }

    // -----------------------------------------------
    // Synchronous schedule methods
    // -----------------------------------------------

    @Override
    ScheduledFuture scheduleExecution(int delay, TimeUnit unit, Closure task) {
        submitted << "schedule"
        task.call()
        return new CompletedScheduledFuture()
    }

    @Override
    ScheduledFuture scheduleWithFixedDelay(int initialDelay, int delay, TimeUnit unit,
                                           Closure task) {
        submitted << "schedule-delay"
        task.call()
        return new CompletedScheduledFuture()
    }

    @Override
    ScheduledFuture scheduleAtFixedRate(int initialDelay, int period, TimeUnit unit,
                                        Closure task) {
        submitted << "schedule-rate"
        task.call()
        return new CompletedScheduledFuture()
    }

    // -----------------------------------------------
    // Utility — synchronous exec
    // -----------------------------------------------
    private static CompletableFuture runSync(Closure action) {
        try {
            def r = action.call()
            return CompletableFuture.completedFuture(r)
        } catch (Throwable t) {
            def cf = new CompletableFuture()
            cf.completeExceptionally(t)
            return cf
        }
    }

    static class CompletedScheduledFuture implements ScheduledFuture<Object> {
        @Override Object get() { null }
        @Override Object get(long t, TimeUnit u) { null }
        @Override long getDelay(TimeUnit unit) { 0 }
        @Override int compareTo(Delayed o) { 0 }
        @Override boolean cancel(boolean b) { false }
        @Override boolean isCancelled() { false }
        @Override boolean isDone() { true }
    }

    @Override boolean isClosed() { closed }
    @Override String getName() { name }
    @Override boolean isUsingVirtualThreads() { useVT }
    @Override ExecutorService getExecutor() { executor }
    @Override ScheduledExecutorService getScheduledExecutor() { scheduler }

    void shutdown() {
        //do nothing
    }
}

/**
 * A deterministic synchronous Promise implementation for tests.
 *
 * - No async
 * - No threads
 * - flatMap / map / recover all executed inline
 * - Fail/complete happens immediately
 * - includes onSuccess() internal method for compatibility with DataflowPromise.flatMap()
 *
 */
class FakePromise<T> implements Promise<T> {

    private T value
    private Throwable error
    private boolean done = false
    private boolean cancelled = false

    // Store callbacks for immediate or deferred execution
    private final List<Consumer<T>> successCallbacks = []
    private final List<Consumer<Throwable>> errorCallbacks = []

    FakePromise() {}

    // Utility: finish value
    private void completeSuccess(T v) {
        value = v
        done = true
        // Immediately invoke any registered success callbacks
        successCallbacks.each { callback ->
            try {
                callback.accept(v)
            } catch (Throwable t) {
                // Log but don't propagate callback errors
                System.err.println("Error in success callback: $t")
            }
        }
    }

    // Utility: finish error
    private void completeError(Throwable t) {
        error = t
        done = true
        // Immediately invoke any registered error callbacks
        errorCallbacks.each { callback ->
            try {
                callback.accept(t)
            } catch (Throwable ex) {
                System.err.println("Error in error callback: $ex")
            }
        }
    }

    @Override
    Promise<T> accept(T v) {
        if (!done) {
            completeSuccess(v)
        }
        return this
    }

    @Override
    Promise<T> accept(Supplier<T> supplier) {
        if (!done) {
            try {
                completeSuccess(supplier.get())
            } catch (Throwable t) {
                completeError(t)
            }
        }
        return this
    }

    @Override
    Promise<T> accept(CompletableFuture<T> future) {
        if (!done) {
            // synchronous block
            try {
                completeSuccess(future.get())
            } catch (Exception ex) {
                completeError(ex)
            }
        }
        return this
    }

    @Override
    Promise<T> accept(Promise<T> other) {
        if (!done) {
            try {
                completeSuccess(other.get())
            } catch (Throwable t) {
                completeError(t)
            }
        }
        return this
    }

    @Override
    T get() throws Exception {
        if (error) throw error
        return value
    }

    @Override
    T get(long timeout, TimeUnit unit) throws TimeoutException {
        if (!done) throw new TimeoutException("FakePromise never async-waits")
        if (error) throw new RuntimeException(error)
        return value
    }

    @Override boolean isDone() { done }
    @Override boolean isCompleted() { done && !error }

    @Override
    Promise<T> onComplete(Consumer<T> callback) {
        if (done && error == null) {
            // Already complete - invoke immediately
            callback.accept(value)
        } else if (!done) {
            // Not done yet - store for later
            successCallbacks.add(callback)
        }
        return this
    }

    /**
     * CRITICAL: Internal method used by DataflowPromise.flatMap() and other internal operations.
     * This is NOT part of the public Promise interface but is called via duck typing.
     *
     * DataflowPromise calls inner.onSuccess() when wiring up flatMap chains.
     */
    Promise<T> onSuccess(Consumer<T> callback) {
        if (done && error == null) {
            // Already complete - invoke immediately
            callback.accept(value)
        } else if (!done) {
            // Not done yet - store for later
            successCallbacks.add(callback)
        }
        return this
    }

    @Override
    Promise<T> onError(Consumer<Throwable> errorHandler) {
        if (done && error != null) {
            // Already failed - invoke immediately
            errorHandler.accept(error)
        } else if (!done) {
            // Not done yet - store for later
            errorCallbacks.add(errorHandler)
        }
        return this
    }

    @Override
    Promise<T> fail(Throwable t) {
        if (!done) {
            completeError(t)
        }
        return this
    }

    @Override
    <R> Promise<R> then(Function<T, R> fn) {
        if (error) {
            def p = new FakePromise<R>()
            p.fail(error)
            return p
        }
        if (!done) {
            // Not yet complete - need to chain
            def nextPromise = new FakePromise<R>()
            onSuccess { v ->
                try {
                    nextPromise.accept(fn.apply(v))
                } catch (Throwable t) {
                    nextPromise.fail(t)
                }
            }
            onError { e -> nextPromise.fail(e) }
            return nextPromise
        }
        // Already complete
        try {
            return new FakePromise<R>().accept(fn.apply(value))
        } catch (Throwable t) {
            return new FakePromise<R>().fail(t)
        }
    }

    @Override
    <R> Promise<R> recover(Function<Throwable, R> fn) {
        if (!error) {
            def p = new FakePromise<R>()
            p.accept((R)value)
            return p
        }
        try {
            return new FakePromise<R>().accept(fn.apply(error))
        } catch (Throwable t) {
            return new FakePromise<R>().fail(t)
        }
    }

    @Override
    CompletableFuture<T> asType(Class clazz) {
        def cf = new CompletableFuture<T>()
        if (error) cf.completeExceptionally(error)
        else cf.complete(value)
        return cf
    }

    @Override
    <R> Promise<R> map(Function<? super T, ? extends R> mapper) {
        if (error) {
            def p = new FakePromise<R>()
            p.fail(error)
            return p
        }
        if (!done) {
            // Not yet complete - need to chain
            def nextPromise = new FakePromise<R>()
            onSuccess { v ->
                try {
                    nextPromise.accept(mapper.apply(v))
                } catch (Throwable t) {
                    nextPromise.fail(t)
                }
            }
            onError { e -> nextPromise.fail(e) }
            return nextPromise
        }
        // Already complete
        try {
            return new FakePromise<R>().accept(mapper.apply(value))
        } catch (Throwable t) {
            return new FakePromise<R>().fail(t)
        }
    }

    @Override
    <R> Promise<R> flatMap(Function<? super T, Promise<R>> mapper) {
        if (error) {
            def p = new FakePromise<R>()
            p.fail(error)
            return p
        }

        if (!done) {
            // Not yet complete - need to chain
            def nextPromise = new FakePromise<R>()

            onSuccess { v ->
                try {
                    Promise<R> innerPromise = mapper.apply(v)

                    // Wire up the inner promise to the next promise
                    innerPromise.onComplete { r -> nextPromise.accept(r) }
                    innerPromise.onError { e -> nextPromise.fail(e) }
                } catch (Throwable t) {
                    nextPromise.fail(t)
                }
            }

            onError { e -> nextPromise.fail(e) }

            return nextPromise
        }

        // Already complete
        try {
            return mapper.apply(value)
        } catch (Throwable t) {
            def p = new FakePromise<R>()
            p.fail(t)
            return p
        }
    }

    @Override
    Promise<T> filter(Predicate<? super T> predicate) {
        if (!error && done && !predicate.test(value)) {
            completeError(new IllegalStateException("Predicate failed"))
        }
        return this
    }

    @Override
    boolean cancel(boolean mayInterruptIfRunning) {
        if (!done) {
            cancelled = true
            done = true
            error = new CancellationException("FakePromise cancelled")
            // Notify error callbacks
            errorCallbacks.each { it.accept(error) }
            return true
        }
        return false
    }

    @Override
    boolean isCancelled() { cancelled }
}


/**
 * fake promise factory
 */
class FakePromiseFactory implements PromiseFactory {

    // Reference to the pool so we can track submissions
    final FakePool pool

    FakePromiseFactory(FakePool pool = null) {
        this.pool = pool
    }

    @Override
    <T> Promise<T> createPromise() {
        return new FakePromise<T>()
    }

    @Override
    <T> Promise<T> createPromise(T value) {
        return new FakePromise<T>().accept(value)
    }

    @Override
    <T> Promise<T> createFailedPromise(Throwable cause) {
        return new FakePromise<T>().fail(cause)
    }

    @Override
    <T> Promise<T> executeAsync(Closure<T> task) {
        // CRITICAL: Track this execution in the pool
        if (pool != null) {
            pool.submitted << "factory-executeAsync"
        }

        try {
            return new FakePromise<T>().accept(task.call())
        } catch (Throwable t) {
            return new FakePromise<T>().fail(t)
        }
    }

    @Override
    <T> Promise<T> from(CompletableFuture<T> future) {
        try {
            return new FakePromise<T>().accept(future.get())
        } catch (Throwable t) {
            return new FakePromise<T>().fail(t)
        }
    }

    @Override
    <T> Promise<T> from(Promise<T> otherPromise) {
        try {
            return new FakePromise<T>().accept(otherPromise.get())
        } catch (Throwable t) {
            return new FakePromise<T>().fail(t)
        }
    }
}