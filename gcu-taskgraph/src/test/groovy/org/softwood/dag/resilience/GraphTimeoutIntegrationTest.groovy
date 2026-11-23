package org.softwood.dag.resilience

import org.junit.jupiter.api.Test
import org.softwood.dag.TaskGraph
import java.time.Duration
import static org.junit.jupiter.api.Assertions.*

/**
 * Integration tests for graph-level timeouts.
 * 
 * Tests verify that:
 * - Graphs timeout correctly when execution exceeds limit
 * - Fast graphs complete successfully within timeout
 * - Remaining tasks are cancelled on timeout
 * - Timeout exceptions contain correct information
 */
class GraphTimeoutIntegrationTest {

    @Test
    void 'test graph timeout with slow tasks'() {
        // Graph with 3 slow tasks (500ms each) → 1.5s total
        // Timeout: 1 second → should fail
        def graph = TaskGraph.build {
            graphTimeout Duration.ofSeconds(1)
            
            serviceTask("task1") {
                action { ctx, prev ->
                    Thread.sleep(500)
                    ctx.promiseFactory.createPromise("task1")
                }
            }
            
            serviceTask("task2") {
                dependsOn "task1"
                action { ctx, prev ->
                    Thread.sleep(500)
                    ctx.promiseFactory.createPromise("task2")
                }
            }
            
            serviceTask("task3") {
                dependsOn "task2"
                action { ctx, prev ->
                    Thread.sleep(500)
                    ctx.promiseFactory.createPromise("task3")
                }
            }
        }

        def exception = assertThrows(GraphTimeoutException) {
            graph.start().get()
        }

        // Verify timeout exception details
        assertTrue(exception.message.contains("timed out"))
        assertNotNull(exception.timeout)
        assertNotNull(exception.elapsed)
        assertTrue(exception.elapsed >= Duration.ofSeconds(1))
        assertTrue(exception.completedTasks < exception.totalTasks)
    }

    @Test
    void 'test graph completes within timeout'() {
        // Graph with 3 fast tasks (50ms each) → 150ms total
        // Timeout: 2 seconds → should succeed
        def graph = TaskGraph.build {
            graphTimeout Duration.ofSeconds(2)
            
            serviceTask("task1") {
                action { ctx, prev ->
                    Thread.sleep(50)
                    ctx.promiseFactory.createPromise("task1")
                }
            }
            
            serviceTask("task2") {
                dependsOn "task1"
                action { ctx, prev ->
                    Thread.sleep(50)
                    ctx.promiseFactory.createPromise("task2")
                }
            }
            
            serviceTask("task3") {
                dependsOn "task2"
                action { ctx, prev ->
                    Thread.sleep(50)
                    ctx.promiseFactory.createPromise("task3")
                }
            }
        }

        def result = graph.start().get()
        
        assertEquals("task3", result)
    }

    @Test
    void 'test graph timeout cancels remaining tasks'() {
        // First task takes 2 seconds, graph times out at 1 second
        // Second task should be cancelled
        def task2Started = false
        
        def graph = TaskGraph.build {
            graphTimeout Duration.ofSeconds(1)
            
            serviceTask("task1") {
                action { ctx, prev ->
                    Thread.sleep(2000)
                    ctx.promiseFactory.createPromise("task1")
                }
            }
            
            serviceTask("task2") {
                dependsOn "task1"
                action { ctx, prev ->
                    task2Started = true
                    ctx.promiseFactory.createPromise("task2")
                }
            }
        }

        assertThrows(GraphTimeoutException) {
            graph.start().get()
        }

        // Task 2 should never have started
        assertFalse(task2Started, "Task 2 should have been cancelled before starting")
    }

    @Test
    void 'test graph timeout with parallel tasks'() {
        // Two parallel tasks, each taking 800ms
        // Total time = ~800ms (parallel)
        // Timeout: 500ms → should fail
        def graph = TaskGraph.build {
            graphTimeout Duration.ofMillis(500)
            
            serviceTask("parallel1") {
                action { ctx, prev ->
                    Thread.sleep(800)
                    ctx.promiseFactory.createPromise("parallel1")
                }
            }
            
            serviceTask("parallel2") {
                action { ctx, prev ->
                    Thread.sleep(800)
                    ctx.promiseFactory.createPromise("parallel2")
                }
            }
            
            serviceTask("join") {
                dependsOn "parallel1"
                dependsOn "parallel2"
                action { ctx, prev ->
                    ctx.promiseFactory.createPromise("joined")
                }
            }
        }

        def exception = assertThrows(GraphTimeoutException) {
            graph.start().get()
        }

        assertTrue(exception.elapsed >= Duration.ofMillis(500))
        assertTrue(exception.completedTasks < exception.totalTasks)
    }

    @Test
    void 'test graph timeout with human readable duration'() {
        // Test the "5 seconds" string format
        def graph = TaskGraph.build {
            graphTimeout "1 second"
            
            serviceTask("slow") {
                action { ctx, prev ->
                    Thread.sleep(2000)
                    ctx.promiseFactory.createPromise("done")
                }
            }
        }

        assertThrows(GraphTimeoutException) {
            graph.start().get()
        }
    }

    @Test
    void 'test no timeout when not configured'() {
        // Graph without timeout should complete regardless of duration
        def graph = TaskGraph.build {
            // No graphTimeout configured
            
            serviceTask("task1") {
                action { ctx, prev ->
                    Thread.sleep(100)
                    ctx.promiseFactory.createPromise("done")
                }
            }
        }

        def result = graph.start().get()
        assertEquals("done", result)
    }

    @Test
    void 'test timeout exception contains graph metadata'() {
        def graph = TaskGraph.build {
            graphTimeout Duration.ofMillis(100)
            
            globals {
                graphId = "test-graph-123"
            }
            
            serviceTask("task1") {
                action { ctx, prev ->
                    Thread.sleep(500)
                    ctx.promiseFactory.createPromise("done")
                }
            }
        }

        def exception = assertThrows(GraphTimeoutException) {
            graph.start().get()
        }

        // Verify exception contains graph ID
        assertNotNull(exception.graphId)
        assertEquals(1, exception.totalTasks)
        assertTrue(exception.completedTasks < exception.totalTasks)
    }

    @Test
    void 'test very short timeout'() {
        // Timeout: 10ms, Task takes 100ms → guaranteed timeout
        def graph = TaskGraph.build {
            graphTimeout Duration.ofMillis(10)
            
            serviceTask("task1") {
                action { ctx, prev ->
                    Thread.sleep(100)  // Ensure we exceed timeout
                    ctx.promiseFactory.createPromise("done")
                }
            }
        }

        // Should timeout
        assertThrows(GraphTimeoutException) {
            graph.start().get()
        }
    }

    @Test
    void 'test long timeout with fast execution'() {
        // Very generous timeout with fast tasks
        def graph = TaskGraph.build {
            graphTimeout Duration.ofHours(1)
            
            serviceTask("task1") {
                action { ctx, prev ->
                    ctx.promiseFactory.createPromise("fast")
                }
            }
        }

        def result = graph.start().get()
        assertEquals("fast", result)
    }

    @Test
    void 'test timeout with failing task'() {
        // Task fails before timeout occurs
        def graph = TaskGraph.build {
            graphTimeout Duration.ofSeconds(5)
            
            serviceTask("failing") {
                action { ctx, prev ->
                    throw new RuntimeException("Task failed")
                }
            }
        }

        // Should get task failure exception, not timeout
        def exception = assertThrows(RuntimeException) {
            graph.start().get()
        }
        
        assertEquals("Task failed", exception.message)
    }

    @Test
    void 'test timeout with complex graph structure'() {
        // Diamond pattern: A → B,C → D
        // Each task: 300ms → Total: ~900ms
        // Timeout: 500ms → should fail
        def graph = TaskGraph.build {
            graphTimeout Duration.ofMillis(500)
            
            serviceTask("A") {
                action { ctx, prev ->
                    Thread.sleep(300)
                    ctx.promiseFactory.createPromise("A")
                }
            }
            
            serviceTask("B") {
                dependsOn "A"
                action { ctx, prev ->
                    Thread.sleep(300)
                    ctx.promiseFactory.createPromise("B")
                }
            }
            
            serviceTask("C") {
                dependsOn "A"
                action { ctx, prev ->
                    Thread.sleep(300)
                    ctx.promiseFactory.createPromise("C")
                }
            }
            
            serviceTask("D") {
                dependsOn "B"
                dependsOn "C"
                action { ctx, prev ->
                    Thread.sleep(300)
                    ctx.promiseFactory.createPromise("D")
                }
            }
        }

        def exception = assertThrows(GraphTimeoutException) {
            graph.start().get()
        }

        assertTrue(exception.completedTasks > 0, "Some tasks should have completed")
        assertTrue(exception.completedTasks < exception.totalTasks, "Not all tasks should complete")
    }

    @Test
    void 'test timeout with single fast task'() {
        // Single task that completes quickly
        def graph = TaskGraph.build {
            graphTimeout Duration.ofSeconds(1)
            
            serviceTask("fast") {
                action { ctx, prev ->
                    ctx.promiseFactory.createPromise("result")
                }
            }
        }

        def result = graph.start().get()
        assertEquals("result", result)
    }

    @Test
    void 'test timeout duration formats'() {
        // Test various duration string formats
        def testCases = [
            ["1 second", Duration.ofSeconds(1)],
            ["30 seconds", Duration.ofSeconds(30)],
            ["2 minutes", Duration.ofMinutes(2)],
            ["1 hour", Duration.ofHours(1)],
            ["3 days", Duration.ofDays(3)]
        ]

        testCases.each { testCase ->
            def (stringFormat, expectedDuration) = testCase
            
            def graph = TaskGraph.build {
                graphTimeout stringFormat
                
                serviceTask("dummy") {
                    action { ctx, prev ->
                        ctx.promiseFactory.createPromise("done")
                    }
                }
            }

            assertEquals(expectedDuration, graph.graphTimeout,
                "Failed to parse '$stringFormat'")
        }
    }

    @Test
    void 'test invalid timeout format throws exception'() {
        assertThrows(IllegalArgumentException) {
            TaskGraph.build {
                graphTimeout "invalid format"
            }
        }
    }
}
