package org.softwood.dag

import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import org.awaitility.Awaitility

import java.util.concurrent.TimeUnit

import org.softwood.dag.TaskGraph
import org.softwood.dag.task.TaskState
import org.softwood.promise.Promise
import org.softwood.promise.Promises

class TaskGraphRoutingTest {

    // Utility to await a promise
    private static <T> T awaitPromise(Promise<T> p) {
        // First wait for the promise to complete
        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .until({ p.isDone() })

        // Then get the result (which may block briefly if not quite ready)
        return p.get()
    }

    // ------------------------------------------------------------
    // TEST 4 – Conditional Routing
    // ------------------------------------------------------------
    @Test
    void testConditionalFork() {

        def graph = TaskGraph.build {

            serviceTask("start") {
                action { ctx, _ ->
                    ctx.promiseFactory.executeAsync { "A" }
                }
            }

            fork("conditional") {
                from "start"
                to "a"
                conditionalOn(["b"]) { prev -> prev == "B" }
            }

            serviceTask("a") {
                action { ctx, prevValue ->
                    ctx.promiseFactory.executeAsync { "A-ran" }
                }
            }

            serviceTask("b") {
                action { ctx, prevValue ->
                    ctx.promiseFactory.executeAsync { "B-ran" }
                }
            }
        }

        def value = awaitPromise(graph.run())

        assertEquals(TaskState.COMPLETED, graph.tasks["a"].state, "Task 'a' should be COMPLETED")
        assertEquals(TaskState.SKIPPED, graph.tasks["b"].state, "Task 'b' should be SKIPPED")
    }

    // ------------------------------------------------------------
    // TEST 5 – Dynamic Routing
    // ------------------------------------------------------------
    @Test
    void testDynamicRouting() {

        def graph = TaskGraph.build {

            serviceTask("classify") {
                action { ctx, _ ->
                    ctx.promiseFactory.executeAsync { [type: "fast", payload: 99] }
                }
            }

            fork("router") {
                from "classify"
                to "fast", "standard"  // Declare possible targets
                route { prev ->
                    prev.type == "fast" ? ["fast"] : ["standard"]
                }
            }

            serviceTask("fast") {
                action { ctx, prevValue ->
                    ctx.promiseFactory.executeAsync { "FAST-RAN" }
                }
            }

            serviceTask("standard") {
                action { ctx, prevValue ->
                    ctx.promiseFactory.executeAsync { "STANDARD-RAN" }
                }
            }
        }

        def value = awaitPromise(graph.run())

        assertEquals(TaskState.COMPLETED, graph.tasks["fast"].state, "Task 'fast' should be COMPLETED")
        assertEquals(TaskState.SKIPPED, graph.tasks["standard"].state, "Task 'standard' should be SKIPPED")
    }

    // UPDATED TEST - Use prevOpt instead of ctx.globals

    @Test
    void testShardingRouter() {

        def graph = TaskGraph.build {

            serviceTask("loadData") {
                action { ctx, _ ->
                    ctx.promiseFactory.executeAsync { (1..10).toList() }
                }
            }

            fork("shardFork") {
                from "loadData"
                shard("process", 3) { data -> data }
            }

            (0..2).each { idx ->
                serviceTask("process_shard_${idx}") {
                    action { ctx, prevValue ->
                        // prevValue is already unwrapped (not Optional)
                        def myShard = prevValue ?: []

                        // Process the shard
                        ctx.promiseFactory.executeAsync {
                            "SHARD-${idx}-DONE (processed ${myShard.size()} items: $myShard)"
                        }
                    }
                }
            }

            join("joinShards") {
                from "process_shard_0", "process_shard_1", "process_shard_2"
                action { ctx, prevValue ->
                    // prevValue is already the list of predecessor results
                    def results = prevValue ?: []
                    ctx.promiseFactory.executeAsync {
                        "JOINED: ${results.join(', ')}"
                    }
                }
            }
        }

        def result = awaitPromise(graph.run())

        println "Final result: $result"

        (0..2).each { idx ->
            assertEquals(TaskState.COMPLETED, graph.tasks["process_shard_${idx}"].state,
                    "Shard ${idx} should be COMPLETED")
        }

        assertTrue(result.contains("JOINED"), "Result should contain JOINED")
    }
    // ------------------------------------------------------------
    // Test B – Nested Routers
    // ------------------------------------------------------------
    @Test
    void testNestedRouters() {

        def graph = TaskGraph.build {

            serviceTask("start") {
                action { ctx, _ ->
                    ctx.promiseFactory.executeAsync { 42 }
                }
            }

            fork("router1") {
                from "start"
                conditionalOn(["x"]) { n -> n > 10 }
                conditionalOn(["y"]) { n -> n <= 10 }
            }

            serviceTask("x") {
                action { ctx, prevValue ->
                    ctx.promiseFactory.executeAsync { "X" }
                }
            }

            serviceTask("y") {
                action { ctx, prevValue ->
                    ctx.promiseFactory.executeAsync { "Y" }
                }
            }

            fork("router2") {
                from "x"
                conditionalOn(["xa"]) { v -> true }
                conditionalOn(["xb"]) { v -> false }
            }

            serviceTask("xa") {
                action { ctx, _ ->
                    ctx.promiseFactory.executeAsync { "XA" }
                }
            }

            serviceTask("xb") {
                action { ctx, _ ->
                    ctx.promiseFactory.executeAsync { "XB" }
                }
            }
        }

        awaitPromise(graph.run())

        assertEquals(TaskState.COMPLETED, graph.tasks["x"].state, "Task 'x' should be COMPLETED")
        assertEquals(TaskState.SKIPPED, graph.tasks["y"].state, "Task 'y' should be SKIPPED")

        assertEquals(TaskState.COMPLETED, graph.tasks["xa"].state, "Task 'xa' should be COMPLETED")
        assertEquals(TaskState.SKIPPED, graph.tasks["xb"].state, "Task 'xb' should be SKIPPED")
    }

    // ------------------------------------------------------------
    // Test C – Router returns empty list
    // ------------------------------------------------------------
    @Test
    void testRouterReturnsEmptyList() {

        def graph = TaskGraph.build {

            serviceTask("start") {
                action { ctx, _ ->
                    ctx.promiseFactory.executeAsync { 5 }
                }
            }

            fork("router") {
                from "start"
                route { prev -> [] }
                to "t1", "t2"
            }

            serviceTask("t1") {
                action { ctx, _ ->
                    ctx.promiseFactory.executeAsync { "T1" }
                }
            }

            serviceTask("t2") {
                action { ctx, _ ->
                    ctx.promiseFactory.executeAsync { "T2" }
                }
            }
        }

        awaitPromise(graph.run())

        assertEquals(TaskState.SKIPPED, graph.tasks["t1"].state, "Task 't1' should be SKIPPED")
        assertEquals(TaskState.SKIPPED, graph.tasks["t2"].state, "Task 't2' should be SKIPPED")
    }

    // ------------------------------------------------------------
    // Test D – Mixed static + conditional routing
    // ------------------------------------------------------------
    //@org.junit.jupiter.api.Disabled("Hangs - investigating cause")
    @Test
    void testMixedRouting() {

        def graph = TaskGraph.build {

            serviceTask("start") {
                action { ctx, _ ->
                    ctx.promiseFactory.executeAsync { 20 }
                }
            }

            fork("mixed") {
                from "start"
                to "always"
                conditionalOn(["maybe"]) { n -> n > 10 }
            }

            serviceTask("always") {
                action { ctx, _ ->
                    ctx.promiseFactory.executeAsync { "ALWAYS" }
                }
            }

            serviceTask("maybe") {
                action { ctx, _ ->
                    ctx.promiseFactory.executeAsync { "MAYBE" }
                }
            }
        }

        def value = awaitPromise(graph.run())

        // Give a tiny bit of time for final state updates to propagate
        Thread.sleep(50)

        assertEquals(TaskState.COMPLETED, graph.tasks["always"].state, "Task 'always' should be COMPLETED")
        assertEquals(TaskState.COMPLETED, graph.tasks["maybe"].state, "Task 'maybe' should be COMPLETED")
    }
}
