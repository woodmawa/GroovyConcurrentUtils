package org.softwood.promise.core

import org.junit.jupiter.api.*
import org.softwood.dataflow.DataflowVariable
import org.softwood.promise.Promise
import org.softwood.promise.core.dataflow.DataflowPromise

import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Supplier

import static org.junit.jupiter.api.Assertions.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DataflowPromiseTest {

    // -----------------------------------------------------------
    //  Basic Completion Tests
    // -----------------------------------------------------------

    @Test
    void "accept(T) completes successfully"() {
        def dfv = new DataflowVariable<String>()
        Promise<String> promise = new DataflowPromise<>(dfv)

        promise.accept("hello")

        assertEquals("hello", promise.get())
        assertTrue(promise.isDone())
    }

    @Test
    void "accept(Supplier) completes with supplied value"() {
        def dfv = new DataflowVariable<Integer>()
        Promise<Integer> promise = new DataflowPromise<>(dfv)

        promise.accept({ -> 42 } as Supplier<Integer>)

        assertEquals(42, promise.get())
        assertTrue(promise.isDone())
    }

    // -----------------------------------------------------------
    //  then() Transformation Tests
    // -----------------------------------------------------------

    @Test
    void "then transforms successful result"() {
        def dfv = new DataflowVariable<String>()
        Promise<String> p = new DataflowPromise<>(dfv)

        def chained = p
                .accept("abc")
                .then({ String v -> v.toUpperCase() } as Function<String, String>)

        assertEquals("ABC", chained.get())
    }

    // -----------------------------------------------------------
    //  onComplete() Tests
    // -----------------------------------------------------------

    @Test
    void "onComplete is called when value becomes available"() {
        def dfv = new DataflowVariable<String>()
        Promise<String> p = new DataflowPromise<>(dfv)

        def ref = new AtomicReference<String>()
        def latch = new java.util.concurrent.CountDownLatch(1)

        p.onComplete({ String v ->
            ref.set(v)
            latch.countDown()
        } as Consumer<String>)

        p.accept("done")

        assertTrue(latch.await(1, TimeUnit.SECONDS))
        assertEquals("done", ref.get())
    }

    @Test
    void "onComplete called immediately if value already bound"() {
        def dfv = new DataflowVariable<Integer>()
        Promise<Integer> p = new DataflowPromise<>(dfv)

        p.accept(99)

        def ref = new AtomicReference<Integer>()
        def latch = new java.util.concurrent.CountDownLatch(1)

        p.onComplete({ Integer v ->
            ref.set(v)
            latch.countDown()
        } as Consumer<Integer>)

        assertTrue(latch.await(1, TimeUnit.SECONDS))
        assertEquals(99, ref.get())
    }

    // -----------------------------------------------------------
    //  Blocking get() and timeout
    // -----------------------------------------------------------

    @Test
    void "get(long,TimeUnit) times out when not completed"() {
        def dfv = new DataflowVariable<String>()
        Promise<String> p = new DataflowPromise<>(dfv)

        assertThrows(TimeoutException) {
            p.get(100, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    void "isDone false before completion and true after"() {
        def dfv = new DataflowVariable<String>()
        Promise<String> p = new DataflowPromise<>(dfv)

        assertFalse(p.isDone())

        p.accept("done")

        assertTrue(p.isDone())
    }
}
