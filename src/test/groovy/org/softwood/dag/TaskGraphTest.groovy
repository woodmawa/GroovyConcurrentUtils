package org.softwood.dag

import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*
import org.awaitility.Awaitility

import java.util.concurrent.TimeUnit

import org.softwood.dag.task.TaskState
import org.softwood.promise.Promise

/**
 * Basic TaskGraph tests - core graph building and execution
 */
class TaskGraphTest {

    // Utility to await a promise
    private static <T> T awaitPromise(Promise<T> p) {
        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .until({ p.isDone() })
        return p.get()
    }

    @Test
    void testLinearChain() {
        def graph = TaskGraph.build {
            serviceTask("task1") {
                action { ctx, _ ->
                    ctx.promiseFactory.executeAsync { "A" }
                }
            }

            serviceTask("task2") {
                action { ctx, prevOpt ->
                    def prev = prevOpt.get()
                    ctx.promiseFactory.executeAsync { prev + "B" }
                }
            }

            fork("chain") {
                from "task1"
                to "task2"
            }
        }

        def result = awaitPromise(graph.run())
        
        assertEquals(TaskState.COMPLETED, graph.tasks["task1"].state)
        assertEquals(TaskState.COMPLETED, graph.tasks["task2"].state)
    }

    @Test
    void testParallelTasks() {
        def graph = TaskGraph.build {
            serviceTask("start") {
                action { ctx, _ ->
                    ctx.promiseFactory.executeAsync { "START" }
                }
            }

            fork("parallel") {
                from "start"
                to "task1", "task2", "task3"
            }

            serviceTask("task1") {
                action { ctx, prevOpt ->
                    ctx.promiseFactory.executeAsync { "T1" }
                }
            }

            serviceTask("task2") {
                action { ctx, prevOpt ->
                    ctx.promiseFactory.executeAsync { "T2" }
                }
            }

            serviceTask("task3") {
                action { ctx, prevOpt ->
                    ctx.promiseFactory.executeAsync { "T3" }
                }
            }

            join("combiner") {
                from "task1", "task2", "task3"
                action { ctx, promises ->
                    def results = promises.collect { it.get() }
                    ctx.promiseFactory.executeAsync {
                        results.join("-")
                    }
                }
            }
        }

        def result = awaitPromise(graph.run())

        assertEquals(TaskState.COMPLETED, graph.tasks["task1"].state)
        assertEquals(TaskState.COMPLETED, graph.tasks["task2"].state)
        assertEquals(TaskState.COMPLETED, graph.tasks["task3"].state)
        assertTrue(result.contains("T1"))
        assertTrue(result.contains("T2"))
        assertTrue(result.contains("T3"))
    }

    @Test
    void testStaticFork() {
        def graph = TaskGraph.build {
            serviceTask("start") {
                action { ctx, _ ->
                    ctx.promiseFactory.executeAsync { 10 }
                }
            }

            fork("staticFork") {
                from "start"
                to "pathA", "pathB"
            }

            serviceTask("pathA") {
                action { ctx, prevOpt ->
                    ctx.promiseFactory.executeAsync { "A" }
                }
            }

            serviceTask("pathB") {
                action { ctx, prevOpt ->
                    ctx.promiseFactory.executeAsync { "B" }
                }
            }

            join("merge") {
                from "pathA", "pathB"
                action { ctx, promises ->
                    def results = promises.collect { it.get() }
                    ctx.promiseFactory.executeAsync {
                        "Merged: ${results.join(' + ')}"
                    }
                }
            }
        }

        def result = awaitPromise(graph.run())

        assertEquals(TaskState.COMPLETED, graph.tasks["pathA"].state)
        assertEquals(TaskState.COMPLETED, graph.tasks["pathB"].state)
        assertTrue(result.contains("Merged"))
    }

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
                action { ctx, prevOpt ->
                    ctx.promiseFactory.executeAsync { "A-ran" }
                }
            }

            serviceTask("b") {
                action { ctx, prevOpt ->
                    ctx.promiseFactory.executeAsync { "B-ran" }
                }
            }
        }

        def value = awaitPromise(graph.run())

        assertEquals(TaskState.COMPLETED, graph.tasks["a"].state, "Task 'a' should be COMPLETED")
        assertEquals(TaskState.SKIPPED, graph.tasks["b"].state, "Task 'b' should be SKIPPED")
    }

    @Test
    void testDynamicRoutingFork() {
        def graph = TaskGraph.build {
            serviceTask("classify") {
                action { ctx, _ ->
                    ctx.promiseFactory.executeAsync { [type: "fast", payload: 99] }
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
                    ctx.promiseFactory.executeAsync { "FAST-RAN" }
                }
            }

            serviceTask("standard") {
                action { ctx, prevOpt ->
                    ctx.promiseFactory.executeAsync { "STANDARD-RAN" }
                }
            }
        }

        def value = awaitPromise(graph.run())

        assertEquals(TaskState.COMPLETED, graph.tasks["fast"].state, "Task 'fast' should be COMPLETED")
        assertEquals(TaskState.SKIPPED, graph.tasks["standard"].state, "Task 'standard' should be SKIPPED")
    }

    @Test
    void testJoinDsl() {
        def graph = TaskGraph.build {
            serviceTask("a") {
                action { ctx, _ ->
                    ctx.promiseFactory.executeAsync { 1 }
                }
            }

            serviceTask("b") {
                action { ctx, _ ->
                    ctx.promiseFactory.executeAsync { 2 }
                }
            }

            join("sum") {
                from "a", "b"
                action { ctx, promises ->
                    def values = promises.collect { it.get() as Integer }
                    ctx.promiseFactory.executeAsync {
                        values.sum()
                    }
                }
            }
        }

        def result = awaitPromise(graph.run())

        assertEquals(3, result)
    }

    @Test
    void testFailurePropagation() {
        def graph = TaskGraph.build {
            serviceTask("failing") {
                action { ctx, _ ->
                    ctx.promiseFactory.executeAsync {
                        throw new RuntimeException("Intentional failure")
                    }
                }
            }

            serviceTask("dependent") {
                action { ctx, prevOpt ->
                    ctx.promiseFactory.executeAsync { "Should not run" }
                }
            }

            fork("chain") {
                from "failing"
                to "dependent"
            }
        }

        try {
            awaitPromise(graph.run())
            fail("Should have thrown an exception")
        } catch (Exception e) {
            assertEquals(TaskState.FAILED, graph.tasks["failing"].state)
        }
    }

    @Test
    void testGlobalsPropagation() {
        def graph = TaskGraph.build {
            globals {
                configValue = "shared"
            }

            serviceTask("reader") {
                action { ctx, _ ->
                    ctx.promiseFactory.executeAsync {
                        ctx.globals.configValue
                    }
                }
            }
        }

        def result = awaitPromise(graph.run())
        assertEquals("shared", result)
    }
}
