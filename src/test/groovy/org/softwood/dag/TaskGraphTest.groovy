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
        return Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .until({ p.isDone() }, { p.get() })
    }

    // ------------------------------------------------------------
    // Corrected TEST 4 — Conditional Routing
    // ------------------------------------------------------------
    @Test
    void testConditionalFork() {

        def graph = TaskGraph.build {

            serviceTask("start") {
                action { ctx, _ -> Promises.async { "A" } }
            }

            fork("conditional") {
                from "start"
                to "a"
                conditionalOn(["b"]) { prev -> prev == "B" }
            }

            serviceTask("a") {
                action { ctx, prevOpt ->
                    Promises.async { "A-ran" }
                }
            }

            serviceTask("b") {
                action { ctx, prevOpt ->
                    Promises.async { "B-ran" }
                }
            }
        }

        def value = awaitPromise(graph.run())

        assertEquals(TaskState.COMPLETED, graph.tasks["a"].state)
        assertEquals(TaskState.SKIPPED, graph.tasks["b"].state)
    }

    // ------------------------------------------------------------
    // Corrected TEST 5 — Dynamic Routing
    // ------------------------------------------------------------
    @Test
    void testDynamicRouting() {

        def graph = TaskGraph.build {

            serviceTask("classify") {
                action { ctx, _ ->
                    Promises.async { [type: "fast", payload: 99] }
                }
            }

            fork("router") {
                from "classify"
                route { prev ->
                    prev.type == "fast" ? ["fast"] : ["standard"]
                }
            }

            serviceTask("fast") {
                action { ctx, prevOpt ->
                    Promises.async { "FAST-RAN" }
                }
            }

            serviceTask("standard") {
                action { ctx, prevOpt ->
                    Promises.async { "STANDARD-RAN" }
                }
            }
        }

        def value = awaitPromise(graph.run())

        assertEquals(TaskState.COMPLETED, graph.tasks["fast"].state)
        assertEquals(TaskState.SKIPPED, graph.tasks["standard"].state)
    }

    // ------------------------------------------------------------
    // Test A — Sharding Router
    // ------------------------------------------------------------
    @Test
    void testShardingRouter() {

        def graph = TaskGraph.build {

            serviceTask("loadData") {
                action { ctx, _ ->
                    Promises.async { (1..10).toList() }
                }
            }

            fork("shardFork") {
                from "loadData"
                shard("process", 3) { data -> data }
            }

            (0..2).each { idx ->
                serviceTask("process_shard_${idx}") {
                    action { ctx, prevOpt ->
                        Promises.async { "SHARD-${idx}-DONE" }
                    }
                }
            }

            join("joinShards") {
                from "process_shard_0", "process_shard_1", "process_shard_2"
                action { ctx, promises ->
                    Promises.async { "JOINED" }
                }
            }
        }

        def result = awaitPromise(graph.run())

        (0..2).each { idx ->
            assertEquals(TaskState.COMPLETED, graph.tasks["process_shard_${idx}"].state)
        }
        assertEquals("JOINED", result)
    }

    // ------------------------------------------------------------
    // Test B — Nested Routers
    // ------------------------------------------------------------
    @Test
    void testNestedRouters() {

        def graph = TaskGraph.build {

            serviceTask("start") {
                action { ctx, _ -> Promises.async { 42 } }
            }

            fork("router1") {
                from "start"
                conditionalOn(["x"]) { n -> n > 10 }
                conditionalOn(["y"]) { n -> n <= 10 }
            }

            serviceTask("x") {
                action { ctx, prevOpt -> Promises.async { "X" } }
            }

            serviceTask("y") {
                action { ctx, prevOpt -> Promises.async { "Y" } }
            }

            fork("router2") {
                from "x"
                conditionalOn(["xa"]) { v -> true }
                conditionalOn(["xb"]) { v -> false }
            }

            serviceTask("xa") { action { ctx, _ -> Promises.async { "XA" } } }
            serviceTask("xb") { action { ctx, _ -> Promises.async { "XB" } } }
        }

        awaitPromise(graph.run())

        assertEquals(TaskState.COMPLETED, graph.tasks["x"].state)
        assertEquals(TaskState.SKIPPED,   graph.tasks["y"].state)

        assertEquals(TaskState.COMPLETED, graph.tasks["xa"].state)
        assertEquals(TaskState.SKIPPED,   graph.tasks["xb"].state)
    }

    // ------------------------------------------------------------
    // Test C — Router returns empty list
    // ------------------------------------------------------------
    @Test
    void testRouterReturnsEmptyList() {

        def graph = TaskGraph.build {

            serviceTask("start") {
                action { ctx, _ -> Promises.async { 5 } }
            }

            fork("router") {
                from "start"
                route { prev -> [] }
                to "t1", "t2"
            }

            serviceTask("t1") {
                action { ctx, _ -> Promises.async { "T1" } }
            }

            serviceTask("t2") {
                action { ctx, _ -> Promises.async { "T2" } }
            }
        }

        awaitPromise(graph.run())

        assertEquals(TaskState.SKIPPED, graph.tasks["t1"].state)
        assertEquals(TaskState.SKIPPED, graph.tasks["t2"].state)
    }

    // ------------------------------------------------------------
    // Test D — Mixed static + conditional routing
    // ------------------------------------------------------------
    @Test
    void testMixedRouting() {

        def graph = TaskGraph.build {

            serviceTask("start") {
                action { ctx, _ -> Promises.async { 20 } }
            }

            fork("mixed") {
                from "start"
                to "always"
                conditionalOn(["maybe"]) { n -> n > 10 }
            }

            serviceTask("always") {
                action { ctx, _ -> Promises.async { "ALWAYS" } }
            }

            serviceTask("maybe") {
                action { ctx, _ -> Promises.async { "MAYBE" } }
            }
        }

        awaitPromise(graph.run())

        assertEquals(TaskState.COMPLETED, graph.tasks["always"].state)
        assertEquals(TaskState.COMPLETED, graph.tasks["maybe"].state)
    }
}
