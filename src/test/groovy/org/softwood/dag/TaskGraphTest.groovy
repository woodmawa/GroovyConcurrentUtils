package org.softwood.dag

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.softwood.dag.task.TaskState
import org.softwood.promise.Promise
import org.softwood.promise.Promises

import java.util.concurrent.atomic.AtomicReference

import static org.awaitility.Awaitility.await
import static java.util.concurrent.TimeUnit.SECONDS
import static org.junit.jupiter.api.Assertions.*

class TaskGraphTest {

    // =====================================================================
    // Helpers – tests use *values*, not Promise objects
    // =====================================================================

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

    static <T> T awaitOptionalPromise(Optional<Promise<T>> opt) {
        if (opt == null || !opt.isPresent())
            return null
        return awaitValue((Promise<T>) opt.get())
    }

    // =====================================================================
    // Test 1 – Linear chain
    // =====================================================================

    @Test
    @DisplayName("TaskGraph executes simple linear dependency chain")
    void testLinearChain() {

        def graph = TaskGraph.build {
            serviceTask("A") {
                action { ctx, prevOpt -> "A" }
            }

            serviceTask("B") {
                dependsOn "A"
                action { ctx, prevOpt ->
                    Promise prev = prevOpt.orElse(null)
                    return Promises.async {
                        def v = prev?.get()
                        return "B:$v".toString()  // force Java String, not GString
                    }
                }
            }
        }

        Promise<String> graphPromise = (Promise<String>) graph.run()
        def result = awaitValue(graphPromise)

        assertEquals("B:A", result)
    }

    // =====================================================================
    // Test 2 – Parallel tasks with join
    // =====================================================================

    @Test
    @DisplayName("Parallel tasks execute concurrently and results are correct")
    void testParallelTasks() {

        def graph = TaskGraph.build {

            serviceTask("start") {
                action { ctx, prevOpt -> 1 }
            }

            serviceTask("inc1") {
                action { ctx, prevOpt ->
                    Promise prev = prevOpt.orElse(null)
                    Promises.async { (prev.get() as Integer) + 1 }
                }
            }

            serviceTask("inc2") {
                action { ctx, prevOpt ->
                    Promise prev = prevOpt.orElse(null)
                    Promises.async { (prev.get() as Integer) + 2 }
                }
            }

            serviceTask("inc3") {
                action { ctx, prevOpt ->
                    Promise prev = prevOpt.orElse(null)
                    Promises.async { (prev.get() as Integer) + 3 }
                }
            }

            fork("parallelFork") {
                from "start"
                to "inc1", "inc2", "inc3"
            }

            join("sum") {
                from "inc1", "inc2", "inc3"
                action { ctx, promises ->
                    Promises.async {
                        def vals = promises.collect { it.get() as Integer }
                        vals.sum()
                    }
                }
            }
        }

        Promise<Integer> graphPromise = (Promise<Integer>) graph.run()
        def total = awaitValue(graphPromise)

        assertEquals(9, total)  // 2 + 3 + 4
    }

    // =====================================================================
    // Test 3 – Static fork
    // =====================================================================

    @Test
    @DisplayName("Static fork executes all target tasks")
    void testStaticFork() {

        def graph = TaskGraph.build {

            serviceTask("start") {
                action { ctx, prevOpt -> 5 }
            }

            serviceTask("double") {
                action { ctx, prevOpt ->
                    Promise prev = prevOpt.orElse(null)
                    Promises.async {
                        (prev.get() as Integer) * 2
                    }
                }
            }

            serviceTask("triple") {
                action { ctx, prevOpt ->
                    Promise prev = prevOpt.orElse(null)
                    Promises.async {
                        (prev.get() as Integer) * 3
                    }
                }
            }

            fork("staticFork") {
                from "start"
                to "double", "triple"
            }

            join("sum") {
                from "double", "triple"
                action { ctx, promises ->
                    Promises.async {
                        def vals = promises.collect { it.get() as Integer }
                        vals.sum()
                    }
                }
            }
        }

        Promise<Integer> graphPromise = (Promise<Integer>) graph.run()
        def sum = awaitValue(graphPromise)

        assertEquals(25, sum)
    }

    // =====================================================================
    // Test 4 – Conditional fork
    // =====================================================================

    @Test
    @DisplayName("Conditional fork executes only matching tasks")
    void testConditionalFork() {

        def graph = TaskGraph.build {

            serviceTask("start") {
                action { ctx, prevOpt -> "A" }
            }

            serviceTask("a") {
                action { ctx, prevOpt ->
                    // IMPORTANT: For conditional/dynamic forks, the predecessor of "a"
                    // is the router task, so prevOpt holds the router's result (["a"]),
                    // not the original "start" value.
                    //
                    // To test the value flow from "start", we read its promise
                    // out of the shared results map instead.
                    Promises.async {
                        def results = ctx.globals.__taskResults as Map<String, Optional<Promise<?>>>
                        Optional<Promise<?>> startOpt = results["start"]
                        Promise startPromise = startOpt?.orElse(null)
                        def v = startPromise?.get()
                        v?.toString()?.toUpperCase()
                    }
                }
            }

            serviceTask("b") {
                action { ctx, prevOpt ->
                    // Same pattern as "a", but lowercased. In this test
                    // "b" will be skipped by the router.
                    Promises.async {
                        def results = ctx.globals.__taskResults as Map<String, Optional<Promise<?>>>
                        Optional<Promise<?>> startOpt = results["start"]
                        Promise startPromise = startOpt?.orElse(null)
                        def v = startPromise?.get()
                        v?.toString()?.toLowerCase()
                    }
                }
            }

            fork("conditionalFork") {
                from "start"
                to "a", "b"

                conditionalOn(["a"]) { value ->
                    value == "A"
                }
            }
        }

        Promise<?> p = graph.run()
        awaitValue(p) // Wait for entire graph

        def results = graph.ctx.globals.__taskResults as Map<String, Optional<Promise<?>>>

        // A ran
        def aVal = awaitOptionalPromise(results["a"] as Optional<Promise<String>>)
        assertEquals("A", aVal)

        // B should be skipped
        assertEquals(TaskState.SKIPPED, graph.tasks["b"].state)

        // If promise exists, it must resolve to null
        def bOpt = results["b"]
        if (bOpt != null && bOpt.isPresent()) {
            assertNull(awaitValue((Promise) bOpt.get()))
        }
    }

    // =====================================================================
    // Test 5 – Dynamic routing fork
    // =====================================================================

    @Test
    @DisplayName("Dynamic router selects correct downstream tasks")
    void testDynamicRoutingFork() {

        def graph = TaskGraph.build {

            serviceTask("classify") {
                action { ctx, prevOpt ->
                    [type: "fast"]
                }
            }

            serviceTask("fast") {
                action { ctx, prevOpt ->
                    Promises.async { "FAST" }
                }
            }

            serviceTask("standard") {
                action { ctx, prevOpt ->
                    Promises.async { "STANDARD" }
                }
            }

            fork("dynamicRouter") {
                from "classify"
                to "fast", "standard"

                route { classif ->
                    if (classif.type == "fast") {
                        return ["fast"]
                    } else {
                        return ["standard"]
                    }
                }
            }
        }

        Promise<?> p = graph.run()
        awaitValue(p)

        def results = graph.ctx.globals.__taskResults as Map<String, Optional<Promise<?>>>

        def fastVal = awaitOptionalPromise(results["fast"] as Optional<Promise<String>>)
        def stdVal  = awaitOptionalPromise(results["standard"] as Optional<Promise<String>>)

        assertEquals("FAST", fastVal)
        assertNull(stdVal)

        assertEquals(TaskState.COMPLETED, graph.tasks["fast"].state)
        assertEquals(TaskState.SKIPPED,   graph.tasks["standard"].state)
    }

    // =====================================================================
    // Test 6 – Join logic (merging predecessor results)
    // =====================================================================

    @Test
    @DisplayName("Join DSL merges predecessor results")
    void testJoinDsl() {

        def graph = TaskGraph.build {

            serviceTask("x") {
                action { ctx, prevOpt -> 10 }
            }

            serviceTask("y") {
                action { ctx, prevOpt -> 20 }
            }

            join("sum") {
                from "x", "y"
                action { ctx, promises ->
                    Promises.async {
                        def values = promises.collect { it.get() as Integer }
                        values.sum()
                    }
                }
            }
        }

        Promise<Integer> p = graph.run()
        def result = awaitValue(p)
        assertEquals(30, result)
    }


    // =====================================================================
    // Test 7 – Failure propagation
    // =====================================================================

    @Test
    @DisplayName("Task failures propagate and allow graph to continue")
    void testFailurePropagation() {

        def graph = TaskGraph.build {

            serviceTask("bad") {
                action { ctx, prevOpt ->
                    throw new RuntimeException("boom")
                }
            }

            serviceTask("after") {
                dependsOn "bad"
                action { ctx, prevOpt ->
                    // prevOpt contains the FAILED promise
                    Promises.async { "still ran" }
                }
            }
        }

        // Graph itself must complete (graph.run() waits for all tasks)
        Promise<?> p = graph.run()
        awaitValue(p)

        assertEquals(TaskState.FAILED, graph.tasks["bad"].state)
        assertEquals(TaskState.COMPLETED, graph.tasks["after"].state)

        def results = graph.ctx.globals.__taskResults
        def afterVal = awaitOptionalPromise(results["after"])
        assertEquals("still ran", afterVal)
    }


    // =====================================================================
    // Test 8 – Global context propagation
    // =====================================================================

    @Test
    @DisplayName("Global variables propagate into task context")
    void testGlobalsPropagation() {

        def graph = TaskGraph.build {

            globals {
                factor = 7
            }

            serviceTask("t") {
                action { ctx, prevOpt ->
                    Promises.async { ctx.globals.factor * 3 }
                }
            }
        }

        Promise<Integer> p = graph.run()
        def result = awaitValue(p)
        assertEquals(21, result)
    }


    // =====================================================================
    // Test 9 – Event listeners
    // =====================================================================

    @Test
    @DisplayName("Task event listeners receive proper events during execution")
    void testEventListeners() {

        def events = []

        def graph = TaskGraph.build {

            onTaskEvent { ev ->
                events << ev.type.toString()
            }

            serviceTask("hello") {
                action { ctx, prevOpt -> "Hello" }
            }
        }

        awaitValue(graph.run())

        assertTrue(events.contains("START"))
        assertTrue(events.any { it == "SUCCESS" || it == "COMPLETED" })
    }


}
