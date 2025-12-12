package org.softwood.agent

import groovy.test.GroovyTestCase
import org.junit.jupiter.api.Test

import java.util.concurrent.TimeUnit

import static org.junit.jupiter.api.Assertions.*

/**
 * Tests for AgentPool functionality.
 */
class AgentPoolTest extends GroovyTestCase {

    @Test
    void testCreatePool() {
        def pool = AgentPoolFactory.create(4) { [count: 0] }

        assertNotNull(pool)
        assertEquals(4, pool.size())

        pool.shutdown()
    }

    @Test
    void testRoundRobinDispatch() {
        def pool = AgentPoolFactory.create(3) { [count: 0, id: UUID.randomUUID().toString()] }

        // Send 9 tasks - each agent should get 3
        9.times { i ->
            pool.send { count++ }
        }

        Thread.sleep(200)

        // Check each agent got approximately equal tasks
        def values = pool.getAllValues()
        values.each { state ->
            assertEquals(3, state.count)
        }

        pool.shutdown()
    }

    @Test
    void testBroadcast() {
        def pool = AgentPoolFactory.create(4) { [count: 0] }

        // Broadcast to all agents
        pool.broadcast { count = 10 }

        Thread.sleep(200)

        // All agents should have count=10
        def values = pool.getAllValues()
        values.each { state ->
            assertEquals(10, state.count)
        }

        pool.shutdown()
    }

    @Test
    void testBroadcastAndGet() {
        def pool = AgentPoolFactory.create(3) { [count: 5] }

        def results = pool.broadcastAndGet({ count * 2 }, 5)

        assertEquals(3, results.size())
        results.each { result ->
            assertEquals(10, result)
        }

        pool.shutdown()
    }

    @Test
    void testGetValueByIndex() {
        def pool = AgentPoolFactory.create(3) { [count: 0] }

        // Send tasks to specific agents via direct access
        pool.getAgent(0).send { count = 100 }
        pool.getAgent(1).send { count = 200 }
        pool.getAgent(2).send { count = 300 }

        Thread.sleep(200)

        assertEquals(100, pool.getValue(0).count)
        assertEquals(200, pool.getValue(1).count)
        assertEquals(300, pool.getValue(2).count)

        pool.shutdown()
    }

    @Test
    void testPoolOperators() {
        def pool = AgentPoolFactory.create(2) { [count: 0] }

        pool >> { count++ }
        pool >> { count++ }

        def result = pool << { count }

        assertTrue(result in [1, 2])  // Depends on which agent was selected

        pool.shutdown()
    }

    @Test
    void testPoolShutdownWithTimeout() {
        def pool = AgentPoolFactory.create(3) { [count: 0] }

        3.times {
            pool.send { count++ }
        }

        boolean completed = pool.shutdown(5, TimeUnit.SECONDS)

        assertTrue(completed)
        assertTrue(pool.isShutdown())
        assertTrue(pool.isTerminated())
    }

    @Test
    void testPoolShutdownNow() {
        def pool = AgentPoolFactory.create(3) { [count: 0] }

        // Queue many tasks
        30.times {
            pool.send { count++; Thread.sleep(50) }
        }

        pool.shutdownNow()

        assertTrue(pool.isShutdown())
        // Give a moment for agents to realize they're shutdown
        Thread.sleep(100)
        assertTrue(pool.isTerminated())
    }

    @Test
    void testPoolHealth() {
        def pool = AgentPoolFactory.create(4) { [count: 0] }

        def health = pool.health()

        assertNotNull(health)
        assertEquals("HEALTHY", health.status)
        assertEquals(4, health.poolSize)
        assertEquals(0, health.agentsShuttingDown)
        assertTrue(health.containsKey("totalQueueSize"))
        assertTrue(health.containsKey("agentHealths"))

        pool.shutdown()
    }

    @Test
    void testPoolMetrics() {
        def pool = AgentPoolFactory.create(3) { [count: 0] }

        9.times {
            pool.send { count++ }
        }

        Thread.sleep(200)

        def metrics = pool.metrics()

        assertNotNull(metrics)
        assertEquals(3, metrics.poolSize)
        assertEquals(9L, metrics.totalDispatches)
        assertEquals(9L, metrics.totalTasksSubmitted)
        assertTrue(metrics.poolThroughputPerSec >= 0)
        assertTrue(metrics.containsKey("agentMetrics"))

        pool.shutdown()
    }

    @Test
    void testPoolSetMaxQueueSize() {
        def pool = AgentPoolFactory.create(2) { [count: 0] }

        pool.setMaxQueueSize(5)

        // Each agent should have maxQueueSize=5
        pool.agents.each { agent ->
            assertEquals(5, agent.getMaxQueueSize())
        }

        pool.shutdown()
    }

    @Test
    void testPoolErrorHandling() {
        def errorCount = new java.util.concurrent.atomic.AtomicInteger(0)
        
        def pool = AgentPoolFactory.builder(2) { [count: 0] }
                .onError({ e -> errorCount.incrementAndGet() })
                .build()

        pool.broadcast { throw new RuntimeException("Test error") }

        Thread.sleep(500)  // Give more time for errors to be processed

        assertTrue(errorCount.get() >= 2, "Expected at least 2 errors, got ${errorCount.get()}")

        def allErrors = pool.getAllErrors(10)
        assertTrue(!allErrors.isEmpty(), "Should have errors")

        pool.clearAllErrors()
        allErrors = pool.getAllErrors(10)
        assertTrue(allErrors.isEmpty(), "Errors should be cleared")

        pool.shutdown()
    }

    @Test
    void testPoolBuilderWithSharedPool() {
        def execPool = org.softwood.pool.ExecutorPoolFactory.builder()
                .name("shared-test-pool")
                .build()

        def pool = AgentPoolFactory.builder(3) { [count: 0] }
                .sharedPool(execPool)
                .build()

        pool.send { count++ }
        
        Thread.sleep(200)

        pool.shutdown()
        execPool.shutdown()
    }

    @Test
    void testPoolBuilderWithQueueLimit() {
        def pool = AgentPoolFactory.builder(2) { [count: 0] }
                .maxQueueSize(5)
                .build()

        // Fill one agent's queue
        6.times {
            try {
                pool.getAgent(0).send { Thread.sleep(100); count++ }
            } catch (Exception ignored) {
                // Expected when queue is full
            }
        }

        pool.shutdownNow()
    }

    @Test
    void testGetAllValues() {
        def pool = AgentPoolFactory.create(3) { [count: 0, id: it] }

        pool.broadcast { count = 42 }
        Thread.sleep(100)

        def values = pool.getAllValues()

        assertEquals(3, values.size())
        values.each { state ->
            assertEquals(42, state.count)
        }

        pool.shutdown()
    }

    @Test
    void testPoolAsyncAndSync() {
        def pool = AgentPoolFactory.create(2) { [count: 0] }

        pool.async { count++ }
        pool.async { count++ }

        def result = pool.sync({ count }, 5)

        assertTrue(result in [1, 2])

        pool.shutdown()
    }

    @Test
    void testInvalidPoolSize() {
        assertThrows(IllegalArgumentException) {
            AgentPoolFactory.create(0) { [count: 0] }
        }

        assertThrows(IllegalArgumentException) {
            AgentPoolFactory.create(-1) { [count: 0] }
        }
    }

    @Test
    void testIndexOutOfBounds() {
        def pool = AgentPoolFactory.create(3) { [count: 0] }

        assertThrows(IndexOutOfBoundsException) {
            pool.getValue(-1)
        }

        assertThrows(IndexOutOfBoundsException) {
            pool.getValue(3)
        }

        assertThrows(IndexOutOfBoundsException) {
            pool.getAgent(5)
        }

        pool.shutdown()
    }
}
