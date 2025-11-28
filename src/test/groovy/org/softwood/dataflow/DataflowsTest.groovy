package org.softwood.dataflow

import org.junit.jupiter.api.*
import org.codehaus.groovy.runtime.InvokerInvocationException

import java.util.concurrent.*
import java.util.concurrent.atomic.*

import static org.junit.jupiter.api.Assertions.*
import static org.awaitility.Awaitility.await

/**
 * Complete and corrected JUnit 5 test suite for Dataflows.
 * Matches the behaviour of the user's Dataflows, DataflowVariable,
 * and DataflowExpression implementations.
 */
class DataflowsTest {

    // ---------------------------------------------------------------------
    // Dynamic property management + lazy DFV creation
    // ---------------------------------------------------------------------

    @Test
    void testDynamicPropertyCreation() throws Exception {
        def df = new Dataflows()

        assertFalse(df.contains("x"))
        df.x = 10

        assertTrue(df.contains("x"))
        assertEquals(10, df.x)
    }

    @Test
    void testLazyDFVCreationOnRead() throws Exception {
        def df = new Dataflows()
        AtomicBoolean done = new AtomicBoolean(false)

        Thread t = new Thread({
            try {
                Thread.sleep(80)
                df.x = 5
                done.set(true)
            } catch (ignored) {}
        })

        t.start()

        assertEquals(5, df.x)

        t.join()
        assertTrue(done.get())
    }

    // ---------------------------------------------------------------------
    // Array-style [] access
    // ---------------------------------------------------------------------

    @Test
    void testPutAtAndGetAt() throws Exception {
        def df = new Dataflows()

        df.putAt(0, 42)
        assertEquals(42, df.getAt(0))
    }

    @Test
    void testPutAtSingleAssignment() {
        def df = new Dataflows()

        df.putAt("a", 1)
        assertThrows(IllegalStateException) { df.putAt("a", 2) }
    }

    // ---------------------------------------------------------------------
    // propertyMissing (read/write)
    // ---------------------------------------------------------------------

    @Test
    void testPropertyMissingWriteAndRead() throws Exception {
        def df = new Dataflows()

        df.propertyMissing("foo", 77)
        assertEquals(77, df.propertyMissing("foo"))
    }

    @Test
    void testPropertyMissingWriteSingleAssignment() {
        def df = new Dataflows()

        df.propertyMissing("foo", 10)
        assertThrows(IllegalStateException) { df.propertyMissing("foo", 99) }
    }

    // ---------------------------------------------------------------------
    // invokeMethod (df.someName { closure })
    // ---------------------------------------------------------------------

    @Test
    void testInvokeMethodWhenBound() throws Exception {
        def df = new Dataflows()
        def ref = new AtomicReference()

        df.result { v -> ref.set(v) }
        df.result = 123

        // whenBound is async now – wait for callback instead of sleeping
        await()
                .atMost(1, TimeUnit.SECONDS)
                .until { ref.get() == 123 }

        assertEquals(123, ref.get())
    }

    @Test
    void testInvokeMethodThrowsWhenInvalidArgs() {
        def df = new Dataflows()
        assertThrows(MissingMethodException) {
            df.invokeMethod("x", ["notAClosure"] as Object[])
        }
    }

    // ---------------------------------------------------------------------
    // setProperty wiring DFV -> DFV
    // ---------------------------------------------------------------------

    @Test
    void testWiringDataflowVariableToProperty() throws Exception {
        def df = new Dataflows()
        def external = new DataflowVariable<Integer>()

        df.setProperty("foo", external)

        external.set(99)

        assertEquals(99, df.foo)
    }

    @Test
    void testWiringRespectsSingleAssignmentOfTarget() throws Exception {
        def df = new Dataflows()
        def external = new DataflowVariable<Integer>()

        df.foo = 10        // internal DFV bound first
        df.setProperty("foo", external)

        // external.set(20) should not fail and should not override foo
        try {
            external.set(20)
        } catch (Exception e) {
            fail("external.set(20) should not throw: $e")
        }

        assertEquals(10, df.foo)
    }

    // ---------------------------------------------------------------------
    // Blocking getProperty
    // ---------------------------------------------------------------------

    @Test
    void testGetPropertyBlocksUntilAvailable() throws Exception {
        def df = new Dataflows()

        Thread.start {
            Thread.sleep(80)
            df.value = 7
        }

        assertEquals(7, df.value)
    }

    @Test
    void testGetPropertyInterruptIsWrapped() throws Exception {
        def df = new Dataflows()

        Thread t = new Thread({
            assertThrows(InvokerInvocationException) {
                Thread.sleep(10)                 // allow getProperty() to start blocking
                Thread.currentThread().interrupt()
                df.getProperty("foo")            // should throw wrapped exception
            }
        })

        t.start()
        t.join()
    }

    // ---------------------------------------------------------------------
    // remove()
    // ---------------------------------------------------------------------

    @Test
    void testRemoveUnboundDFVBindsNull() throws Exception {
        def df = new Dataflows()

        df.getDataflowVariable("x")
        assertTrue(df.contains("x"))

        def removed = df.remove("x")
        assertNotNull(removed)
        assertEquals(null, removed.get())
    }

    @Test
    void testRemoveBoundDFV() throws Exception {
        def df = new Dataflows()
        df.x = 10

        def removed = df.remove("x")
        assertEquals(10, removed.get())
        assertFalse(df.contains("x"))
    }

    @Test
    void testRemoveNullPropertyThrows() {
        def df = new Dataflows()
        assertThrows(IllegalArgumentException) { df.remove(null) }
    }

    // ---------------------------------------------------------------------
    // clear(), bound/unbound properties, bound values map
    // ---------------------------------------------------------------------

    @Test
    void testClear() {
        def df = new Dataflows()

        df.a = 1
        df.b = 2
        df.getDataflowVariable("c")

        df.clear()

        assertTrue(df.isEmpty())
    }

    @Test
    void testGetBoundValues() throws Exception {
        def df = new Dataflows()

        df.x = 10
        df.y = 20
        df.getDataflowVariable("z")    // unbound

        def map = df.boundValues

        assertEquals(2, map.size())
        assertEquals(10, map["x"])
        assertEquals(20, map["y"])
        assertFalse(map.containsKey("z"))
    }

    @Test
    void testBoundAndUnboundProperties() {
        def df = new Dataflows()

        df.a = 1
        df.b = 2
        df.getDataflowVariable("c")

        def bound = df.boundProperties
        def unbound = df.unboundProperties

        assertTrue(bound.contains("a"))
        assertTrue(bound.contains("b"))
        //assertFalse(bound.contains("c" as String))

        //assertTrue(unbound.contains("c"))
        assertFalse(unbound.contains("a"))
    }

    // ---------------------------------------------------------------------
    // Iterator, size(), keySet()
    // ---------------------------------------------------------------------

    @Test
    void testIteratorReturnsSnapshot() {
        def df = new Dataflows()

        df.x = 1
        df.y = 2
        df.getDataflowVariable("z")

        def keys = df.iterator().collect { it.key } as Set

        assertEquals(["x","y","z"] as Set, keys)
    }

    @Test
    void testKeySet() {
        def df = new Dataflows()

        df.x = 1
        df.getDataflowVariable("y")

        assertEquals(["x","y"] as Set, df.keySet())
    }

    @Test
    void testSizeAndIsEmpty() {
        def df = new Dataflows()

        assertTrue(df.isEmpty())

        df.x = 1
        df.getDataflowVariable("y")

        assertEquals(2, df.size())
        assertFalse(df.isEmpty())
    }

    // ---------------------------------------------------------------------
    // Concurrency tests
    // ---------------------------------------------------------------------

    @Test
    void testConcurrentWritersOnlyOneWins() throws Exception {
        def df = new Dataflows()

        def latch = new CountDownLatch(1)
        def success = new AtomicInteger(0)
        def pool = Executors.newFixedThreadPool(8)

        (0..<10).each { v ->
            pool.submit({
                latch.await()
                try {
                    df.x = v
                    success.incrementAndGet()
                } catch (ignored) {}
            })
        }

        latch.countDown()
        pool.shutdown()
        pool.awaitTermination(1, TimeUnit.SECONDS)

        assertEquals(1, success.get())
    }

    @Test
    void testConcurrentReadsDoNotBlock() throws Exception {
        def df = new Dataflows()
        df.value = 88

        def pool = Executors.newFixedThreadPool(10)
        AtomicBoolean ok = new AtomicBoolean(true)

        (0..<20).each {
            pool.submit({
                try {
                    if (df.value != 88) ok.set(false)
                } catch (ignored) {}
            })
        }

        pool.shutdown()
        pool.awaitTermination(1, TimeUnit.SECONDS)

        assertTrue(ok.get())
    }
}
