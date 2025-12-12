package org.softwood.agent

import groovy.test.GroovyTestCase
import org.junit.jupiter.api.Test

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import static org.junit.jupiter.api.Assertions.*

/**
 * Performance and stress tests for Agent and AgentPool.
 */
class AgentPerformanceTest extends GroovyTestCase {

    @Test
    void testHighVolumeSingleAgent() {
        Agent agent = AgentFactory.create([count: 0])

        int taskCount = 10_000
        def start = System.currentTimeMillis()

        taskCount.times {
            agent.send { count++ }
        }

        def result = agent.sendAndGet({ count }, 30)
        def elapsed = System.currentTimeMillis() - start

        assertEquals(taskCount, result)
        println "Single agent processed ${taskCount} tasks in ${elapsed}ms (${taskCount * 1000 / elapsed} tasks/sec)"

        def metrics = agent.metrics()
        assertEquals(taskCount + 1L, metrics.tasksSubmitted)  // +1 for sendAndGet
        assertEquals(0L, metrics.tasksErrored)

        agent.shutdown()
    }

    @Test
    void testHighVolumeAgentPool() {
        int poolSize = 4
        int taskCount = 10_000
        
        def pool = AgentPoolFactory.create(poolSize) { [count: 0] }

        def start = System.currentTimeMillis()

        taskCount.times {
            pool.send { count++ }
        }

        Thread.sleep(2000)  // Let tasks complete

        def elapsed = System.currentTimeMillis() - start
        def metrics = pool.metrics()

        assertEquals(taskCount.toLong(), metrics.totalDispatches)
        assertTrue(metrics.totalTasksCompleted >= taskCount - poolSize)  // Allow for some in-flight
        
        println "Pool of ${poolSize} agents processed ${taskCount} tasks in ${elapsed}ms (${taskCount * 1000 / elapsed} tasks/sec)"
        println "Pool throughput: ${metrics.poolThroughputPerSec} tasks/sec"

        pool.shutdown()
    }

    @Test
    void testConcurrentClientsOnAgent() {
        Agent agent = AgentFactory.create([count: 0])

        int numClients = 10
        int tasksPerClient = 100
        def latch = new CountDownLatch(numClients)

        def start = System.currentTimeMillis()

        numClients.times { clientId ->
            Thread.start {
                try {
                    tasksPerClient.times {
                        agent.send { count++ }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "All clients should complete")

        def result = agent.sendAndGet({ count }, 5)
        def elapsed = System.currentTimeMillis() - start

        assertEquals(numClients * tasksPerClient, result)
        println "${numClients} concurrent clients sent ${numClients * tasksPerClient} tasks in ${elapsed}ms"

        agent.shutdown()
    }

    @Test
    void testConcurrentClientsOnPool() {
        int poolSize = 4
        def pool = AgentPoolFactory.create(poolSize) { [count: 0] }

        int numClients = 20
        int tasksPerClient = 50
        def latch = new CountDownLatch(numClients)

        def start = System.currentTimeMillis()

        numClients.times { clientId ->
            Thread.start {
                try {
                    tasksPerClient.times {
                        pool.send { count++ }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "All clients should complete")
        Thread.sleep(500)  // Let remaining tasks complete

        def elapsed = System.currentTimeMillis() - start
        def metrics = pool.metrics()

        println "${numClients} concurrent clients sent ${numClients * tasksPerClient} tasks to pool in ${elapsed}ms"
        println "Pool metrics: ${metrics.totalTasksCompleted} completed, ${metrics.poolThroughputPerSec} tasks/sec"

        pool.shutdown()
    }

    @Test
    void testMemoryStabilityUnderLoad() {
        Agent agent = AgentFactory.create([count: 0, data: []])

        // Run for a while and ensure no memory leaks
        5.times { iteration ->
            1000.times {
                agent.send {
                    count++
                    // Don't let data grow unbounded
                    if (data.size() > 100) {
                        data.clear()
                    }
                    data << count
                }
            }

            Thread.sleep(200)
        }

        def result = agent.sendAndGet({ count }, 5)
        assertEquals(5000, result)

        def metrics = agent.metrics()
        assertEquals(5001L, metrics.tasksSubmitted)  // +1 for sendAndGet
        assertEquals(0L, metrics.tasksErrored)

        agent.shutdown()
    }

    @Test
    void testQueueBackpressure() {
        int maxQueue = 100
        Agent agent = AgentFactory.createWithQueueLimit([count: 0], maxQueue)

        def rejectedCount = new AtomicInteger(0)
        def acceptedCount = new AtomicInteger(0)

        // Try to flood the queue
        1000.times {
            try {
                agent.send {
                    Thread.sleep(1)  // Slow processing
                    count++
                }
                acceptedCount.incrementAndGet()
            } catch (java.util.concurrent.RejectedExecutionException e) {
                rejectedCount.incrementAndGet()
            }
        }

        println "Queue backpressure: ${acceptedCount.get()} accepted, ${rejectedCount.get()} rejected (max=${maxQueue})"

        assertTrue(rejectedCount.get() > 0, "Should have some rejections")
        assertTrue(acceptedCount.get() > 0, "Should have some accepted")

        def metrics = agent.metrics()
        assertTrue(metrics.queueRejections > 0)

        agent.shutdownNow()
    }

    @Test
    void testThroughputComparison() {
        println "\n=== Throughput Comparison ==="

        // Single agent
        def singleAgent = AgentFactory.create([count: 0])
        int taskCount = 5000

        def start = System.currentTimeMillis()
        taskCount.times { singleAgent.send { count++ } }
        singleAgent.sendAndGet({ count }, 10)
        def singleElapsed = System.currentTimeMillis() - start
        def singleThroughput = taskCount * 1000 / singleElapsed

        println "Single agent: ${singleThroughput} tasks/sec"
        singleAgent.shutdown()

        // Pool of 4
        def pool4 = AgentPoolFactory.create(4) { [count: 0] }
        start = System.currentTimeMillis()
        taskCount.times { pool4.send { count++ } }
        Thread.sleep(1000)
        def pool4Elapsed = System.currentTimeMillis() - start
        def pool4Throughput = taskCount * 1000 / pool4Elapsed

        println "Pool of 4: ${pool4Throughput} tasks/sec"
        pool4.shutdown()

        // Pool of 8
        def pool8 = AgentPoolFactory.create(8) { [count: 0] }
        start = System.currentTimeMillis()
        taskCount.times { pool8.send { count++ } }
        Thread.sleep(1000)
        def pool8Elapsed = System.currentTimeMillis() - start
        def pool8Throughput = taskCount * 1000 / pool8Elapsed

        println "Pool of 8: ${pool8Throughput} tasks/sec"
        pool8.shutdown()

        println "Speedup (4 vs 1): ${pool4Throughput / singleThroughput}x"
        println "Speedup (8 vs 1): ${pool8Throughput / singleThroughput}x"
    }

    @Test
    void testErrorImpactOnThroughput() {
        Agent agent = AgentFactory.create([count: 0])

        int taskCount = 1000
        def start = System.currentTimeMillis()

        taskCount.times { i ->
            agent.send {
                if (i % 10 == 0) {
                    throw new RuntimeException("Simulated error")
                }
                count++
            }
        }

        agent.sendAndGet({ count }, 10)
        def elapsed = System.currentTimeMillis() - start

        def metrics = agent.metrics()
        def successCount = metrics.tasksCompleted - metrics.tasksErrored

        println "With 10% error rate: ${successCount} succeeded, ${metrics.tasksErrored} failed in ${elapsed}ms"
        println "Effective throughput: ${successCount * 1000 / elapsed} tasks/sec"
        println "Error rate: ${metrics.errorRatePercent}%"

        assertEquals(100L, metrics.tasksErrored)
        assertEquals(900, agent.getValue().count)

        agent.shutdown()
    }

    @Test
    void testDeepCopyPerformance() {
        // Test with simple map
        def start = System.currentTimeMillis()
        def agent1 = AgentFactory.create([count: 0, items: (1..100).toList()])
        100.times {
            agent1.getValue()  // Force deep copy
        }
        def simpleElapsed = System.currentTimeMillis() - start

        // Test with nested structure
        start = System.currentTimeMillis()
        def complex = [
                users: (1..50).collect { [id: it, name: "User$it", tags: ['a', 'b', 'c']] },
                settings: [theme: 'dark', features: (1..20).toList()]
        ]
        def agent2 = AgentFactory.create(complex)
        100.times {
            agent2.getValue()  // Force deep copy
        }
        def complexElapsed = System.currentTimeMillis() - start

        println "Deep copy performance:"
        println "  Simple (100 items): ${simpleElapsed}ms for 100 copies"
        println "  Complex (nested): ${complexElapsed}ms for 100 copies"

        agent1.shutdown()
        agent2.shutdown()
    }

    @Test
    void testShutdownPerformance() {
        int agentCount = 10
        def agents = []

        agentCount.times {
            agents << AgentFactory.create([count: 0])
        }

        // Queue tasks
        agents.each { agent ->
            100.times { agent.send { count++ } }
        }

        // Measure graceful shutdown time
        def start = System.currentTimeMillis()
        agents.each { it.shutdown(10, TimeUnit.SECONDS) }
        def elapsed = System.currentTimeMillis() - start

        println "Shutdown ${agentCount} agents in ${elapsed}ms"
        assertTrue(elapsed < 5000, "Should shutdown reasonably quickly")

        agents.each { agent ->
            assertTrue(agent.isTerminated())
        }
    }
}
