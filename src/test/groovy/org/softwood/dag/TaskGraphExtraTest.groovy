package org.softwood.dag


import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.softwood.dag.task.TaskContext
import org.softwood.pool.WorkerPool
import org.softwood.promise.Promise

import java.util.concurrent.atomic.AtomicReference

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

        def gctx = new TaskContext(fakePool)

        // Create the graph instance first
        def graph = new TaskGraph("g1", gctx)


        // Ensure the graph uses the context with the right pool
        //graph.ctx = gctx

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

class FakePool implements WorkerPool {

    List<Runnable> submitted = []

    @Override
    void execute(Runnable r) {
        submitted << r
        r.run()
    }

    @Override
    void submit(Runnable r) {
        submitted << r
        r.run()
    }
}