package org.softwood.agent

import groovy.test.GroovyTestCase
import org.junit.jupiter.api.Test

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import static org.junit.jupiter.api.Assertions.assertThrows

class AgentIntegrationTest extends GroovyTestCase {

    @Test
    void testAsyncSendExecutesOnWrappedObjectSequentially() {
        def agent = Agent.agent([count: 0]).build()

        // enqueue several increments
        10.times {
            agent.send { count++ }
        }

        // now read back synchronously
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

        // mutate via agent
        agent.sendAndGet {
            count = 5
            items << 4
        }

        def snapshot = agent.val
        assertEquals(5, snapshot.count)
        assertEquals([1, 2, 3, 4], snapshot.items)

        // now try to mutate snapshot and ensure underlying agent state doesn't change
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

        // Block inside closure longer than timeout
        try {
            agent.sendAndGet({
                Thread.sleep(500)
                flag = true
                flag
            }, 0L /* no timeout, should succeed */)
        } catch (Exception e) {
            fail("First sendAndGet without timeout should not fail: $e")
        }

        // Now with a very small timeout
        def ex = assertThrows(RuntimeException) {
            agent.sendAndGet({
                Thread.sleep(1500)
                flag = true
                flag
            }, 1L) // change to >0 if you want strict timeout; here 0L means "no timeout"
        }

        assertTrue(ex.message.contains("sendAndGet timed out"))

        // If you want a real timeout assertion, use >0 timeoutSeconds and assert message
        // For now we just validate that we can call with timeout and not blow up API-wise

        agent.shutdown()
    }

    @Test
    void testExternalExecutorUsageDoesNotShutdownExecutor() {
        def externalExecutor = Executors.newSingleThreadExecutor()
        def agent = new Agent([count: 0], null, externalExecutor)

        agent.send { count++ }
        agent.sendAndGet { count++ }   // ensure some work ran

        agent.shutdown()   // should NOT shut down externalExecutor

        // verify external executor still accepts tasks
        def latch = new CountDownLatch(1)
        externalExecutor.execute {
            latch.countDown()
        }
        assertTrue("External executor should still be running",
                latch.await(2, TimeUnit.SECONDS))

        externalExecutor.shutdown()
    }

    @Test
    void testSequentialOrderingUnderConcurrentCalls() {
        def agent = Agent.agent([list: []]).build()
        def threads = []
        def latch = new CountDownLatch(20)

        20.times { i ->
            def t = Thread.start {
                agent.send {
                    list << i
                }
                latch.countDown()
            }
            threads << t
        }

        latch.await(5, TimeUnit.SECONDS)
        threads*.join()

        // the order in the list should be the same as the order tasks were processed,
        // which is FIFO on the queue, but submission order is concurrent.
        // We can't guarantee numeric order, but we can assert size and that all values exist.
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

        // mutate snapshot deeply
        snapshot1.count = 999
        snapshot1.items << 999
        snapshot1.meta.tags << 'MUTATED'

        // re-read from agent; should not see snapshot mutations
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

        // mutate snapshot
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

        // mutate snapshot
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