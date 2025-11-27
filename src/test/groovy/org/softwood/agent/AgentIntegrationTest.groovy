package org.softwood.agent

import groovy.test.GroovyTestCase
import org.junit.jupiter.api.Test

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import static org.junit.jupiter.api.Assertions.*

class AgentIntegrationTest extends GroovyTestCase {

    @Test
    void testAsyncSendExecutesOnWrappedObjectSequentially() {
        def agent = Agent.agent([count: 0]).build()

        10.times {
            agent.send { count++ }
        }

        def result = agent.sendAndGet { count }

        assertEquals(10, result)
        assertEquals(10, agent.val.count)

        agent.shutdown()
    }

    @Test
    void testSendAndGetReturnsResult() {
        def agent = Agent.agent([value: 40]).build()

        def result = agent.sendAndGet {
            value += 2
            value
        }

        assertEquals(42, result)
        assertEquals(42, agent.val.value)

        agent.shutdown()
    }

    @Test
    void testRightShiftAndLeftShiftOperators() {
        def agent = Agent.agent([count: 0]).build()

        agent >> { count++ }
        agent >> { count += 2 }

        def result = agent << { count }

        assertEquals(3, result)
        assertEquals(3, agent.val.count)

        agent.shutdown()
    }

    @Test
    void testAsyncAliasAndSyncAlias() {
        def agent = Agent.agent([count: 0]).build()

        agent.async { count++ }
        agent.async { count++ }

        def value = agent.sync { count }

        assertEquals(2, value)
        assertEquals(2, agent.val.count)

        agent.shutdown()
    }

    @Test
    void testGetValReturnsDefensiveCopyForSimpleMap() {
        def underlying = [count: 0, items: [1, 2, 3]]
        def agent = Agent.agent(underlying).build()

        agent.sendAndGet {
            count = 5
            items << 4
        }

        def snapshot = agent.val
        assertEquals(5, snapshot.count)
        assertEquals([1, 2, 3, 4], snapshot.items)

        snapshot.count = 999
        snapshot.items << 999

        def secondSnapshot = agent.val
        assertEquals(5, secondSnapshot.count)
        assertEquals([1, 2, 3, 4], secondSnapshot.items)

        agent.shutdown()
    }

    @Test
    void testSendAndGetTimeoutThrows() {
        def agent = Agent.agent([flag: false]).build()

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
        def externalExecutor = Executors.newSingleThreadExecutor()
        def agent = new Agent([count: 0], null, externalExecutor)

        agent.send { count++ }
        agent.sendAndGet { count++ }

        agent.shutdown()

        // Executor should still be usable
        def latch = new CountDownLatch(1)
        externalExecutor.execute { latch.countDown() }

        assertTrue(latch.await(2, TimeUnit.SECONDS),
                "External executor should still be running")

        externalExecutor.shutdown()
    }

    @Test
    void testSequentialOrderingUnderConcurrentCalls() {
        def agent = Agent.agent([list: []]).build()
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

        def snapshot = agent.val
        assertEquals(20, snapshot.list.size())
        assertEquals((0..19).toSet(), snapshot.list.toSet())

        agent.shutdown()
    }

    @Test
    void testSendAndGetPropagatesExceptionAsRuntimeException() {
        def agent = Agent.agent([value: 1]).build()

        def ex = assertThrows(IllegalArgumentException) {
            agent.sendAndGet {
                throw new IllegalArgumentException("boom")
            }
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
        def agent = Agent.agent(underlying).build()

        agent.sendAndGet {
            count = 5
            items << 4
            meta.tags << 'c'
        }

        def snapshot1 = agent.val
        assertEquals(5, snapshot1.count)
        assertEquals([1, 2, 3, 4], snapshot1.items)
        assertEquals(['a', 'b', 'c'], snapshot1.meta.tags)

        // Mutate snapshot but not the real state
        snapshot1.count = 999
        snapshot1.items << 999
        snapshot1.meta.tags << 'MUTATED'

        def snapshot2 = agent.val
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
        def agent = Agent.agent(underlying).build()

        def snap1 = agent.val
        assertEquals('Alice', snap1.user.name)
        assertEquals(['admin', 'user'], snap1.user.roles)

        snap1.user.name = 'Bob'
        snap1.user.roles << 'hacker'

        def snap2 = agent.val
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
        def agent = new Agent<SamplePojo>(pojo)

        agent.sendAndGet {
            x = 20
            nums << 4
            meta.flags << true
        }

        def snap1 = agent.val
        assertEquals(20, snap1.x)
        assertEquals([1, 2, 3, 4], snap1.nums)
        assertEquals([true, false, true], snap1.meta.flags)

        snap1.x = 999
        snap1.nums << 999
        snap1.meta.flags.clear()

        def snap2 = agent.val
        assertEquals(20, snap2.x)
        assertEquals([1, 2, 3, 4], snap2.nums)
        assertEquals([true, false, true], snap2.meta.flags)

        agent.shutdown()
    }
}
