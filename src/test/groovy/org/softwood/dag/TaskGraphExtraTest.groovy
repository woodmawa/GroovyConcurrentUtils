package org.softwood.dag

import org.awaitility.Awaitility
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.softwood.dag.task.TaskContext

import java.util.concurrent.TimeUnit

class TaskGraphExtraTest {

    static <T> T await(promise) {
        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .until({ promise.isCompleted() }, { promise.get() })
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
        await(p)

        def results = graph.ctx.globals.__taskResults
        assert results["fast"].get().get() == "fast"
        assert results["slow"].get().get() == "done"
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
        def res = await(p)

        assert res.get() == "success"
        assert attempts == 3
    }

    // ---------------------------------------------------------------
    // Timeout handling test
    // ---------------------------------------------------------------
    @Test
    @DisplayName("Task times out when execution exceeds timeout setting")
    void testTimeout() {
        def graph = TaskGraph.build {
            serviceTask("t") {
                timeoutMillis = 200
                action { ctx, _ ->
                    Thread.sleep(500)
                    return "too slow"
                }
            }
        }

        def p = graph.run()
        await(p)

        assert graph.tasks["t"].state.toString() == "FAILED"
        assert graph.tasks["t"].error.message.contains("timeout")
    }

    @Test
    @DisplayName("Graph uses injected worker pool and submits tasks correctly")
    void testInjectedWorkerPool() {
        def fakePool = new FakePool()

        def graph = new TaskGraph("g1", new TaskContext(pool: fakePool))

        def dsl = new TaskGraphDsl(graph)
        dsl.serviceTask("a") {
            action { ctx, _ -> "A" }
        }

        graph.addTask(graph.tasks["a"])

        def p = graph.run()
        def result = await(p)

        assert fakePool.submitted.size() == 1
        assert result.get() == "A"
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
        def result = await(p)

        assert result.get().orders == "ORDERS"
        assert result.get().invoices == "INVOICES"
        assert graph.tasks["loadOrders"].state.toString() == "COMPLETED"
    }

}

class FakePool {
    List<Runnable> submitted = []

    void submit(Runnable r) {
        submitted << r
        r.run()      // synchronous execution for testing
    }
}