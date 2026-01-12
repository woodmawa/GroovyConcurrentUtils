package org.softwood.dag

import org.junit.jupiter.api.Test
import org.softwood.dag.task.TaskContext
import org.softwood.promise.Promises
import static org.junit.jupiter.api.Assertions.*

class TaskGraphFactoryTest {

    @Test
    void testGraphReuseGuard() {
        // Given: A simple TaskGraph instance
        def graph = TaskGraph.build {
            serviceTask("task1") {
                action { ctx, prevVal ->
                    Promises.newPromise("dummy")
                }
            }
        }

        // When: Try to start the same graph twice
        def exception = assertThrows(IllegalStateException) {
            graph.start()
            graph.start()  // Second start should throw
        }

        // Then: Should throw with helpful message
        assertTrue(exception.message.contains("already been started"))
        assertTrue(exception.message.contains("TaskGraphFactory"))
    }

    @Test
    void testFactoryCreatesIsolatedInstances() {
        // Given: A factory
        def factory = TaskGraphFactory.define {
            serviceTask("counter") {
                action { ctx, prevVal ->
                    Promises.newPromise("dummy")
                }
            }
        }

        // When: Create multiple graphs
        def graph1 = factory.create()
        def graph2 = factory.create()
        def graph3 = factory.create()

        // Then: Each should be a different instance
        assertNotSame(graph1, graph2)
        assertNotSame(graph2, graph3)
        assertNotSame(graph1, graph3)

        // And: Each should have different contexts
        assertNotSame(graph1.ctx, graph2.ctx)
        assertNotSame(graph2.ctx, graph3.ctx)
    }

    @Test
    void testFactoryWithHttpTaskCookieIsolation() {
        // Given: Factory with cookie jar
        def factory = TaskGraphFactory.define {
            httpTask("setCookie") {
                url "https://httpbin.org/cookies/set?session=test123"
                cookieJar true
            }
        }

        // When: Create two graphs
        def graph1 = factory.create()
        def graph2 = factory.create()

        // Then: They should have different TaskContexts
        assertNotSame(graph1.ctx, graph2.ctx)

        // And: Different globals maps
        assertNotSame(graph1.ctx.globals, graph2.ctx.globals)
    }

    @Test
    void testFactoryWithCustomContext() {
        // Given: Custom context with pre-set value
        def customCtx = new TaskContext()
        customCtx.globals["preset"] = "custom-value"

        def factory = TaskGraphFactory.define {
            serviceTask("read") {
                action { ctx, prevVal ->
                    Promises.newPromise("dummy")
                }
            }
        }

        // When: Create graph with custom context
        def graph = factory.create(customCtx)

        // Then: Uses the custom context
        assertSame(customCtx, graph.ctx)
        assertEquals("custom-value", graph.ctx.globals["preset"])
    }

    @Test
    void testFactoryPreservesGraphStructure() {
        // Given: Simple chain structure
        def factory = TaskGraphFactory.define {
            serviceTask("task1") {
                action { ctx, prevVal ->
                    Promises.newPromise("result1")
                }
            }
            serviceTask("task2") {
                dependsOn "task1"
                action { ctx, prevVal ->
                    Promises.newPromise("result2")
                }
            }
            serviceTask("task3") {
                dependsOn "task2"
                action { ctx, prevVal ->
                    Promises.newPromise("result3")
                }
            }
        }

        // When: Create graph
        def graph = factory.create()

        // Then: Graph structure is preserved
        assertTrue(graph.tasks.size() >= 3)
        assertTrue(graph.tasks.containsKey("task1"))
        assertTrue(graph.tasks.containsKey("task2"))
        assertTrue(graph.tasks.containsKey("task3"))

        // Verify dependency chain
        def task1 = graph.tasks["task1"]
        def task2 = graph.tasks["task2"]
        def task3 = graph.tasks["task3"]

        // task1 has no predecessors (it's the root)
        assertTrue(task1.predecessors.isEmpty())

        // task2 depends on task1
        assertTrue(task2.predecessors.contains("task1"))

        // task3 depends on task2
        assertTrue(task3.predecessors.contains("task2"))
    }

    @Test
    void testGraphCannotBeReusedEvenAfterCompletion() {
        // Given: A simple graph
        def graph = TaskGraph.build {
            serviceTask("simple-task") {
                action { ctx, prevVal ->
                    Promises.newPromise("result")
                }
            }
        }

        // When: Try to start twice
        graph.start()  // First time

        def exception = assertThrows(IllegalStateException) {
            graph.start()  // Second time
        }

        // Then: Still throws
        assertTrue(exception.message.contains("already been started"))
    }

    @Test
    void testSlickFactoryDslUsage() {
        // Given: Factory created with slick DSL - TaskGraph.factory { }
        def factory = TaskGraph.factory {
            serviceTask("step1") {
                action { ctx, prevVal ->
                    Promises.newPromise("result1")
                }
            }
            serviceTask("step2") {
                dependsOn "step1"
                action { ctx, prevVal ->
                    Promises.newPromise("result2")
                }
            }
        }

        // When: Create multiple instances
        def graph1 = factory.create()
        def graph2 = factory.create()

        // Then: Both are separate instances
        assertNotSame(graph1, graph2)
        assertNotSame(graph1.ctx, graph2.ctx)
    }

    @Test
    void testSlickFactoryWithHttpTask() {
        // Given: Factory with HTTP task and cookies using slick DSL
        def factory = TaskGraph.factory {
            httpTask("login") {
                url "https://httpbin.org/cookies/set?session=abc"
                cookieJar true
            }
        }

        // When: Create two instances
        def graph1 = factory.create()
        def graph2 = factory.create()

        // Then: Complete isolation
        assertNotSame(graph1.ctx, graph2.ctx)
        assertNotSame(graph1.ctx.globals, graph2.ctx.globals)
    }

    @Test
    void testFactoryConcurrentExecution() {
        // Given: Factory with shared counter in a ServiceTask
        def successCount = new java.util.concurrent.atomic.AtomicInteger(0)

        def factory = TaskGraph.factory {
            serviceTask("increment") {
                action { ctx, prevVal ->
                    // Each thread increments its own context-local counter
                    def currentCount = ctx.globals.get("count", 0) as Integer
                    ctx.globals["count"] = currentCount + 1
                    Promises.newPromise(ctx.globals["count"])
                }
            }
        }

        // When: Execute 10 graphs concurrently on separate threads
        def threads = []
        10.times { i ->
            threads << Thread.start {
                try {
                    def graph = factory.create()
                    def result = graph.start().get()

                    // Verify this graph's context had its own counter
                    assertEquals(1, graph.ctx.globals["count"],
                            "Thread ${i}: Each graph should have isolated counter starting at 0")

                    successCount.incrementAndGet()
                } catch (Exception e) {
                    println "Thread ${i} failed: ${e.message}"
                    e.printStackTrace()
                }
            }
        }

        // Wait for all threads
        threads.each { it.join(5000) } // 5 second timeout per thread

        // Then: All 10 threads should complete successfully
        assertEquals(10, successCount.get(),
                "All concurrent executions should complete successfully")
    }

    @Test
    void testFactoryConcurrentExecutionWithHttpTasks() {
        // Given: Factory with HTTP task and cookie jar
        def factory = TaskGraph.factory {
            httpTask("fetch") {
                url "https://httpbin.org/uuid"
                cookieJar true
            }
        }

        // When: Execute 5 graphs concurrently
        def results = Collections.synchronizedList([])
        def threads = []

        5.times { i ->
            threads << Thread.start {
                try {
                    def graph = factory.create()
                    graph.start().get()

                    // Each graph should have its own cookie jar
                    def cookieJar = graph.ctx.globals["cookieJar"]
                    results << [thread: i, hasCookieJar: cookieJar != null]
                } catch (Exception e) {
                    println "Thread ${i} failed: ${e.message}"
                    results << [thread: i, error: e.message]
                }
            }
        }

        threads.each { it.join(10000) } // 10 second timeout

        // Then: All threads should complete
        assertEquals(5, results.size())

        // And: No errors
        def errors = results.findAll { it.error }
        assertTrue(errors.isEmpty(), "No thread should have errors: ${errors}")
    }

    @Test
    void testFactoryThreadSafetyWithSharedState() {
        // Given: External counter that tracks total executions across all threads
        def globalCounter = new java.util.concurrent.atomic.AtomicInteger(0)

        def factory = TaskGraph.factory {
            serviceTask("worker-task") {
                action { ctx, prevVal ->
                    // Each graph increments the global counter
                    def count = globalCounter.incrementAndGet()

                    // But also tracks its own local execution count
                    def localCount = ctx.globals.get("localCount", 0) as Integer
                    ctx.globals["localCount"] = localCount + 1

                    Promises.newPromise(count)
                }
            }
        }

        // When: Execute 20 graphs concurrently (stress test)
        // PRE-CREATE all graphs BEFORE starting threads to avoid concurrent TaskContext initialization
        def graphs = []
        20.times { i ->
            graphs << factory.create()
        }

        def threads = []
        def successes = new java.util.concurrent.atomic.AtomicInteger(0)

        20.times { i ->
            def graph = graphs[i]  // Use pre-created graph
            threads << Thread.start {
                try {
                    graph.start().get()

                    // Verify isolation: each graph's local count should be 1
                    assertEquals(1, graph.ctx.globals["localCount"],
                            "Graph ${i} should have local count of 1 (isolated context)")

                    successes.incrementAndGet()
                } catch (Exception e) {
                    println "Thread ${i} failed: ${e.message}"
                    e.printStackTrace()
                }
            }
        }

        threads.each { it.join(5000) }

        // Then: All executions successful
        assertEquals(20, successes.get(), "All concurrent executions should succeed")

        // And: Global counter should equal total executions
        assertEquals(20, globalCounter.get(), "Global counter should track all executions")
    }
}