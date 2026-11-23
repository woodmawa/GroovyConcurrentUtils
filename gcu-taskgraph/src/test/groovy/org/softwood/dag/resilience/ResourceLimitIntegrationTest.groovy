package org.softwood.dag.resilience

import org.junit.jupiter.api.Test
import org.softwood.dag.TaskGraph
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import static org.junit.jupiter.api.Assertions.*

/**
 * Integration tests for resource limits.
 * 
 * Tests verify that:
 * - Concurrency limits are enforced
 * - Memory limits are checked
 * - Queue limits work correctly
 * - Resource monitoring is accurate
 * - Callbacks are invoked properly
 */
class ResourceLimitIntegrationTest {

    @Test
    void 'test basic concurrency limit'() {
        // Limit to 2 concurrent tasks
        // 5 tasks should execute in waves: 2, 2, 1
        def maxConcurrent = new AtomicInteger(0)
        def currentConcurrent = new AtomicInteger(0)
        
        def graph = TaskGraph.build {
            resourceLimits {
                maxConcurrentTasks 2
            }
            
            (1..5).each { i ->
                serviceTask("task$i") {
                    action { ctx, prev ->
                        def concurrent = currentConcurrent.incrementAndGet()
                        maxConcurrent.set(Math.max(maxConcurrent.get(), concurrent))
                        
                        Thread.sleep(100)  // Simulate work
                        
                        currentConcurrent.decrementAndGet()
                        ctx.promiseFactory.createPromise("task$i")
                    }
                }
            }
        }

        def result = graph.start().get()
        
        // Verify max concurrent never exceeded limit
        assertTrue(maxConcurrent.get() <= 2, 
            "Max concurrent was ${maxConcurrent.get()}, limit was 2")
    }

    @Test
    void 'test concurrency limit with sequential dependencies'() {
        // Even with concurrency limit, dependencies should be respected
        def executionOrder = []
        
        def graph = TaskGraph.build {
            resourceLimits {
                maxConcurrentTasks 10  // High limit
            }
            
            serviceTask("task1") {
                action { ctx, prev ->
                    executionOrder << "task1"
                    ctx.promiseFactory.createPromise("task1")
                }
            }
            
            serviceTask("task2") {
                dependsOn "task1"
                action { ctx, prev ->
                    executionOrder << "task2"
                    ctx.promiseFactory.createPromise("task2")
                }
            }
            
            serviceTask("task3") {
                dependsOn "task2"
                action { ctx, prev ->
                    executionOrder << "task3"
                    ctx.promiseFactory.createPromise("task3")
                }
            }
        }

        graph.start().get()
        
        assertEquals(["task1", "task2", "task3"], executionOrder)
    }

    @Test
    void 'test fail fast on concurrency limit'() {
        def graph = TaskGraph.build {
            resourceLimits {
                maxConcurrentTasks 1
                failFastOnConcurrency true  // Fail immediately if no slots
            }
            
            // Launch 3 tasks simultaneously
            serviceTask("task1") {
                action { ctx, prev ->
                    Thread.sleep(500)  // Hold slot
                    ctx.promiseFactory.createPromise("task1")
                }
            }
            
            serviceTask("task2") {
                action { ctx, prev ->
                    Thread.sleep(500)
                    ctx.promiseFactory.createPromise("task2")
                }
            }
            
            serviceTask("task3") {
                action { ctx, prev ->
                    Thread.sleep(500)
                    ctx.promiseFactory.createPromise("task3")
                }
            }
        }

        // Should fail because tasks try to execute concurrently
        // but only 1 slot is available and failFast is enabled
        assertThrows(ResourceLimitExceededException) {
            graph.start().get()
        }
    }

    @Test
    void 'test queue limits'() {
        def graph = TaskGraph.build {
            resourceLimits {
                maxConcurrentTasks 1
                maxQueuedTasks 2
                failFastOnConcurrency false  // Queue tasks
            }
            
            // Try to queue 5 tasks with limit of 2 in queue
            (1..5).each { i ->
                serviceTask("task$i") {
                    action { ctx, prev ->
                        Thread.sleep(100)
                        ctx.promiseFactory.createPromise("task$i")
                    }
                }
            }
        }

        // Should fail because queue limit exceeded
        assertThrows(ResourceLimitExceededException) {
            graph.start().get()
        }
    }

    @Test
    void 'test memory limit warning'() {
        def warningIssued = false
        
        def graph = TaskGraph.build {
            resourceLimits {
                maxMemoryMB 1  // Very low limit - will trigger
                failFastOnMemory false  // Just warn
                memoryCheckInterval Duration.ofMillis(10)
                
                onLimitExceeded { type, current, limit ->
                    if (type == "memory_mb") {
                        warningIssued = true
                    }
                }
            }
            
            serviceTask("task1") {
                action { ctx, prev ->
                    // Allocate some memory
                    def data = new byte[1024 * 1024]  // 1 MB
                    Thread.sleep(100)
                    ctx.promiseFactory.createPromise("done")
                }
            }
        }

        def result = graph.start().get()
        
        // Task should complete despite memory warning
        assertEquals("done", result)
        assertTrue(warningIssued, "Memory warning should have been issued")
    }

    @Test
    void 'test onLimitExceeded callback'() {
        def callbackInvoked = false
        def limitType = null
        
        def graph = TaskGraph.build {
            resourceLimits {
                maxConcurrentTasks 1
                failFastOnConcurrency true
                
                onLimitExceeded { type, current, limit ->
                    callbackInvoked = true
                    limitType = type
                }
            }
            
            // Two parallel tasks
            serviceTask("task1") {
                action { ctx, prev ->
                    Thread.sleep(200)
                    ctx.promiseFactory.createPromise("task1")
                }
            }
            
            serviceTask("task2") {
                action { ctx, prev ->
                    Thread.sleep(200)
                    ctx.promiseFactory.createPromise("task2")
                }
            }
        }

        try {
            graph.start().get()
        } catch (ResourceLimitExceededException e) {
            // Expected
        }
        
        assertTrue(callbackInvoked, "Callback should have been invoked")
        assertEquals("concurrent_tasks", limitType)
    }

    @Test
    void 'test resource statistics'() {
        def graph = TaskGraph.build {
            resourceLimits {
                maxConcurrentTasks 3
            }
            
            (1..5).each { i ->
                serviceTask("task$i") {
                    action { ctx, prev ->
                        // Check stats during execution
                        def monitor = ctx.resourceMonitor
                        if (monitor) {
                            def stats = monitor.getStats()
                            assertTrue(stats.runningTasks <= 3)
                        }
                        
                        Thread.sleep(50)
                        ctx.promiseFactory.createPromise("task$i")
                    }
                }
            }
        }

        graph.start().get()
    }

    @Test
    void 'test preset configurations'() {
        // Test "moderate" preset
        def graph = TaskGraph.build {
            resourceLimits {
                preset "moderate"
            }
            
            serviceTask("task1") {
                action { ctx, prev ->
                    ctx.promiseFactory.createPromise("done")
                }
            }
        }

        def result = graph.start().get()
        assertEquals("done", result)
        
        // Verify policy was configured
        assertNotNull(graph.resourceLimitPolicy)
        assertEquals(5, graph.resourceLimitPolicy.maxConcurrentTasks)
        assertEquals(512L, graph.resourceLimitPolicy.maxMemoryMB)
    }

    @Test
    void 'test disabled resource limits'() {
        // No limits = no monitoring
        def graph = TaskGraph.build {
            // No resourceLimits block
            
            (1..10).each { i ->
                serviceTask("task$i") {
                    action { ctx, prev ->
                        ctx.promiseFactory.createPromise("task$i")
                    }
                }
            }
        }

        def result = graph.start().get()
        
        // All tasks should complete without limits
        assertNotNull(result)
    }

    @Test
    void 'test complex graph with resource limits'() {
        // Diamond pattern with concurrency limit
        def graph = TaskGraph.build {
            resourceLimits {
                maxConcurrentTasks 2
            }
            
            serviceTask("A") {
                action { ctx, prev ->
                    Thread.sleep(100)
                    ctx.promiseFactory.createPromise("A")
                }
            }
            
            serviceTask("B") {
                dependsOn "A"
                action { ctx, prev ->
                    Thread.sleep(100)
                    ctx.promiseFactory.createPromise("B")
                }
            }
            
            serviceTask("C") {
                dependsOn "A"
                action { ctx, prev ->
                    Thread.sleep(100)
                    ctx.promiseFactory.createPromise("C")
                }
            }
            
            serviceTask("D") {
                dependsOn "B"
                dependsOn "C"
                action { ctx, prev ->
                    Thread.sleep(100)
                    ctx.promiseFactory.createPromise("D")
                }
            }
        }

        def result = graph.start().get()
        assertEquals("D", result)
    }

    @Test
    void 'test resource limits with task failures'() {
        def graph = TaskGraph.build {
            resourceLimits {
                maxConcurrentTasks 2
            }
            
            serviceTask("task1") {
                action { ctx, prev ->
                    throw new RuntimeException("Task 1 failed")
                }
            }
            
            serviceTask("task2") {
                action { ctx, prev ->
                    ctx.promiseFactory.createPromise("task2")
                }
            }
        }

        // Should fail but not leak resources
        assertThrows(RuntimeException) {
            graph.start().get()
        }
    }

    @Test
    void 'test zero concurrent tasks throws exception'() {
        assertThrows(IllegalArgumentException) {
            TaskGraph.build {
                resourceLimits {
                    maxConcurrentTasks 0
                }
            }
        }
    }

    @Test
    void 'test negative memory limit throws exception'() {
        assertThrows(IllegalArgumentException) {
            TaskGraph.build {
                resourceLimits {
                    maxMemoryMB(-1)  // Use parentheses for negative numbers
                }
            }
        }
    }

    @Test
    void 'test high concurrency with limits'() {
        // 20 tasks with limit of 5
        def maxConcurrent = new AtomicInteger(0)
        def currentConcurrent = new AtomicInteger(0)
        
        def graph = TaskGraph.build {
            resourceLimits {
                maxConcurrentTasks 5
            }
            
            (1..20).each { i ->
                serviceTask("task$i") {
                    action { ctx, prev ->
                        def concurrent = currentConcurrent.incrementAndGet()
                        maxConcurrent.set(Math.max(maxConcurrent.get(), concurrent))
                        
                        Thread.sleep(50)
                        
                        currentConcurrent.decrementAndGet()
                        ctx.promiseFactory.createPromise("task$i")
                    }
                }
            }
        }

        graph.start().get()
        
        assertTrue(maxConcurrent.get() <= 5,
            "Max concurrent was ${maxConcurrent.get()}, limit was 5")
    }

    @Test
    void 'test resource limit exception contains details'() {
        def graph = TaskGraph.build {
            resourceLimits {
                maxConcurrentTasks 1
                failFastOnConcurrency true
            }
            
            serviceTask("task1") {
                action { ctx, prev ->
                    Thread.sleep(200)
                    ctx.promiseFactory.createPromise("task1")
                }
            }
            
            serviceTask("task2") {
                action { ctx, prev ->
                    ctx.promiseFactory.createPromise("task2")
                }
            }
        }

        try {
            graph.start().get()
            fail("Should have thrown ResourceLimitExceededException")
        } catch (ResourceLimitExceededException e) {
            assertNotNull(e.resourceType)
            assertEquals("concurrent_tasks", e.resourceType)
            assertTrue(e.currentValue > 0)
            assertTrue(e.limitValue > 0)
        }
    }

    @Test
    void 'test unlimited preset'() {
        def graph = TaskGraph.build {
            resourceLimits {
                preset "unlimited"
            }
            
            (1..10).each { i ->
                serviceTask("task$i") {
                    action { ctx, prev ->
                        ctx.promiseFactory.createPromise("task$i")
                    }
                }
            }
        }

        def result = graph.start().get()
        
        // All tasks should execute without limits
        assertNotNull(result)
        assertFalse(graph.resourceLimitPolicy.enabled)
    }

    @Test
    void 'test resource limits with graph timeout'() {
        // Both resource limits AND graph timeout
        def graph = TaskGraph.build {
            resourceLimits {
                maxConcurrentTasks 2
            }
            
            graphTimeout Duration.ofSeconds(2)
            
            (1..5).each { i ->
                serviceTask("task$i") {
                    action { ctx, prev ->
                        Thread.sleep(100)
                        ctx.promiseFactory.createPromise("task$i")
                    }
                }
            }
        }

        def result = graph.start().get()
        
        // Should complete successfully within both limits
        assertNotNull(result)
    }

    @Test
    void 'test resource limits persisted correctly'() {
        def policy = new ResourceLimitPolicy()
        policy.maxConcurrentTasks = 5
        policy.maxMemoryMB = 256
        policy.enabled = true
        
        assertTrue(policy.hasConcurrencyLimit())
        assertTrue(policy.hasMemoryLimit())
        assertFalse(policy.hasQueueLimit())
        
        assertEquals("ResourceLimitPolicy(enabled=true, maxConcurrent=5, maxMemory=256MB)", 
            policy.toString())
    }
}
