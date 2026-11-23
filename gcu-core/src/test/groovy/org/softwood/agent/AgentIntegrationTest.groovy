package org.softwood.agent

import groovy.test.GroovyTestCase
import org.junit.jupiter.api.Test
import org.softwood.pool.ExecutorPoolFactory

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import static org.junit.jupiter.api.Assertions.*

class AgentIntegrationTest extends GroovyTestCase {

    @Test
    void testAsyncSendExecutesOnWrappedObjectSequentially() {
        Agent agent = AgentFactory.create([count: 0])

        10.times {
            agent.send { count++ }
        }

        def result = agent.sendAndGet({ count }, 0)

        assertEquals(10, result)
        assertEquals(10, agent.value.count)

        agent.shutdown()
    }

    @Test
    void testSendAndGetReturnsResult() {
        Agent agent = AgentFactory.create([value: 40])

        def result = agent.sendAndGet({
            value += 2
            value
        }, 0)

        assertEquals(42, result)
        assertEquals(42, agent.value.value)

        agent.shutdown()
    }

    @Test
    void testRightShiftAndLeftShiftOperators() {
        Agent agent = AgentFactory.create([count: 0])

        agent >> { count++ }
        agent >> { count += 2 }

        def result = agent << { count }

        assertEquals(3, result)
        assertEquals(3, agent.value.count)

        agent.shutdown()
    }

    @Test
    void testAsyncAliasAndSyncAlias() {
        Agent agent = AgentFactory.create([count: 0])

        agent.async { count++ }
        agent.async { count++ }

        def value = agent.sync({ count }, 0)

        assertEquals(2, value)
        assertEquals(2, agent.value.count)

        agent.shutdown()
    }

    @Test
    void testGetValueReturnsDefensiveCopyForSimpleMap() {
        def underlying = [count: 0, items: [1, 2, 3]]
        Agent agent = AgentFactory.create(underlying)

        agent.sendAndGet({
            count = 5
            items << 4
        }, 0)

        def snapshot = agent.value
        assertEquals(5, snapshot.count)
        assertEquals([1, 2, 3, 4], snapshot.items)

        snapshot.count = 999
        snapshot.items << 999

        def secondSnapshot = agent.value
        assertEquals(5, secondSnapshot.count)
        assertEquals([1, 2, 3, 4], secondSnapshot.items)

        agent.shutdown()
    }

    @Test
    void testSendAndGetTimeoutThrows() {
        Agent agent = AgentFactory.create([flag: false])

        // No-timeout version must succeed
        try {
            agent.sendAndGet({
                Thread.sleep(500)
                flag = true
                flag
            }, 0L)
        } catch (Exception e) {
            fail("First sendAndGet without timeout should not fail: $e")
        }

        // Timeout expected
        def ex = assertThrows(RuntimeException) {
            agent.sendAndGet({
                Thread.sleep(1500)
                flag = true
                flag
            }, 1L)
        }

        assertTrue(ex.message.contains("sendAndGet timed out"))

        agent.shutdown()
    }

    @Test
    void testExternalExecutorUsageDoesNotShutdownExecutor() {
        def externalPool = ExecutorPoolFactory.builder()
                .name("external-test-pool")
                .build()
        
        Agent agent = AgentFactory.createWithSharedPool([count: 0], externalPool)

        agent.send { count++ }
        agent.sendAndGet({ count++ }, 0)

        agent.shutdown()

        // Pool should still be usable
        def latch = new CountDownLatch(1)
        externalPool.execute({ latch.countDown() } as Runnable)

        assertTrue(latch.await(2, TimeUnit.SECONDS),
                "External pool should still be running")

        externalPool.shutdown()
    }

    @Test
    void testSequentialOrderingUnderConcurrentCalls() {
        Agent agent = AgentFactory.create([list: []])
        def threads = []
        def latch = new CountDownLatch(20)

        20.times { i ->
            threads << Thread.start {
                agent.send { list << i }
                latch.countDown()
            }
        }

        latch.await(5, TimeUnit.SECONDS)
        threads*.join()

        def snapshot = agent.value
        assertEquals(20, snapshot.list.size())
        assertEquals((0..19).toSet(), snapshot.list.toSet())

        agent.shutdown()
    }

    @Test
    void testSendAndGetPropagatesExceptionAsRuntimeException() {
        Agent agent = AgentFactory.create([value: 1])

        def ex = assertThrows(IllegalArgumentException) {
            agent.sendAndGet({
                throw new IllegalArgumentException("boom")
            }, 0)
        }

        assertEquals("boom", ex.message)

        agent.shutdown()
    }

    @Test
    void testDeepCopyNestedCollectionsAreIsolated() {
        def underlying = [
                count: 0,
                items: [1, 2, 3],
                meta : [tags: ['a', 'b']]
        ]
        Agent agent = AgentFactory.create(underlying)

        agent.sendAndGet({
            count = 5
            items << 4
            meta.tags << 'c'
        }, 0)

        def snapshot1 = agent.value
        assertEquals(5, snapshot1.count)
        assertEquals([1, 2, 3, 4], snapshot1.items)
        assertEquals(['a', 'b', 'c'], snapshot1.meta.tags)

        // Mutate snapshot but not the real state
        snapshot1.count = 999
        snapshot1.items << 999
        snapshot1.meta.tags << 'MUTATED'

        def snapshot2 = agent.value
        assertEquals(5, snapshot2.count)
        assertEquals([1, 2, 3, 4], snapshot2.items)
        assertEquals(['a', 'b', 'c'], snapshot2.meta.tags)

        agent.shutdown()
    }

    @Test
    void testDeepCopyNestedMapsAreIsolated() {
        def underlying = [
                user: [
                        name : 'Alice',
                        roles: ['admin', 'user']
                ]
        ]
        Agent agent = AgentFactory.create(underlying)

        def snap1 = agent.value
        assertEquals('Alice', snap1.user.name)
        assertEquals(['admin', 'user'], snap1.user.roles)

        snap1.user.name = 'Bob'
        snap1.user.roles << 'hacker'

        def snap2 = agent.value
        assertEquals('Alice', snap2.user.name)
        assertEquals(['admin', 'user'], snap2.user.roles)

        agent.shutdown()
    }

    static class SamplePojo {
        int x
        List<Integer> nums = []
        Map<String, Object> meta = [:]
    }

    @Test
    void testDeepCopyPojoWithFields() {
        def pojo = new SamplePojo(
                x: 10,
                nums: [1, 2, 3],
                meta: [label: 'orig', flags: [true, false]]
        )
        Agent<SamplePojo> agent = AgentFactory.create(pojo)

        agent.sendAndGet({
            x = 20
            nums << 4
            meta.flags << true
        }, 0)

        def snap1 = agent.value
        assertEquals(20, snap1.x)
        assertEquals([1, 2, 3, 4], snap1.nums)
        assertEquals([true, false, true], snap1.meta.flags)

        snap1.x = 999
        snap1.nums << 999
        snap1.meta.flags.clear()

        def snap2 = agent.value
        assertEquals(20, snap2.x)
        assertEquals([1, 2, 3, 4], snap2.nums)
        assertEquals([true, false, true], snap2.meta.flags)

        agent.shutdown()
    }

    @Test
    void testHealthEndpointReturnsStatus() {
        Agent agent = AgentFactory.create([count: 0])

        def health = agent.health()
        
        assertNotNull(health)
        assertEquals("HEALTHY", health.status)
        assertFalse(health.shuttingDown as Boolean)
        assertTrue(health.containsKey("queueSize"))
        assertTrue(health.containsKey("timestamp"))

        agent.shutdown()

        def healthAfterShutdown = agent.health()
        assertEquals("SHUTTING_DOWN", healthAfterShutdown.status)
        assertTrue(healthAfterShutdown.shuttingDown as Boolean)
    }

    @Test
    void testMetricsEndpointReturnsOperationalData() {
        Agent agent = AgentFactory.create([count: 0])

        // Submit some tasks
        5.times {
            agent.send { count++ }
        }
        agent.sendAndGet({ count }, 0)

        def metrics = agent.metrics()

        assertNotNull(metrics)
        assertEquals(6L, metrics.tasksSubmitted)
        assertTrue(metrics.tasksCompleted as Long >= 0)
        assertEquals(0L, metrics.tasksErrored)
        assertTrue(metrics.containsKey("queueDepth"))
        assertTrue(metrics.containsKey("uptimeMs"))
        assertTrue(metrics.containsKey("createdAt"))
        assertTrue(metrics.containsKey("timestamp"))

        agent.shutdown()
    }

    @Test
    void testAgentBuilderWithCustomPool() {
        def customPool = ExecutorPoolFactory.builder()
                .name("custom-agent-pool")
                .build()

        Agent agent = AgentFactory.builder([count: 0])
                .pool(customPool)
                .build()

        agent.send { count++ }
        def result = agent.sendAndGet({ count }, 0)

        assertEquals(1, result)

        agent.shutdown()
        customPool.shutdown()
    }

    @Test
    void testAgentBuilderWithCustomCopyStrategy() {
        def customCopy = { -> [count: 999] } // Always returns a fixed copy
        
        Agent agent = AgentFactory.builder([count: 0])
                .copyStrategy(customCopy)
                .build()

        agent.send { count = 42 }
        Thread.sleep(100) // Let task complete

        def snapshot = agent.value
        assertEquals(999, snapshot.count) // Custom copy strategy returns 999

        agent.shutdown()
    }

    @Test
    void testFactoryCreateWithPool() {
        Agent agent = AgentFactory.createWithPool([count: 0], "named-pool")

        agent.send { count++ }
        def result = agent.sendAndGet({ count }, 0)

        assertEquals(1, result)

        agent.shutdown()
    }
}
