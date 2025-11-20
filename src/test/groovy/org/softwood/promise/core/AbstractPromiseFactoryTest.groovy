package org.softwood.promise.core

import org.junit.jupiter.api.*
import org.softwood.promise.Promise
import org.softwood.promise.PromiseFactory

import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Supplier

import static org.junit.jupiter.api.Assertions.*

/**
 * Abstract test suite verifying the full functional contract of PromiseFactory
 * and Promise implementations.
 *
 * Extend this test for each concrete backend implementation:
 *
 *   class VertxPromiseFactoryTest extends AbstractPromiseFactoryTest {
 *       PromiseFactory createFactory() { new VertxPromiseFactory(Vertx.vertx()) }
 *   }
 *
 *   class DataflowPromiseFactoryTest extends AbstractPromiseFactoryTest {
 *       PromiseFactory createFactory() { new DataflowPromiseFactory(new DataflowFactory()) }
 *   }
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractPromiseFactoryTest {

    /**
     * Concrete factories implement this.
     */
    abstract PromiseFactory createFactory()

    PromiseFactory factory

    @BeforeAll
    void setupFactory() {
        factory = createFactory()
    }

    // ------------------------------------------------------------
    // Basic Promise Creation Tests
    // ------------------------------------------------------------

    @Test
    void "createPromise returns incomplete promise that later completes"() {
        Promise<String> p = factory.createPromise()

        assertFalse(p.isDone())
        p.accept("hello")
        assertTrue(p.isDone())
        assertEquals("hello", p.get())
    }

    @Test
    void "createPromise(value) returns completed promise"() {
        Promise<Integer> p = factory.createPromise(123)

        assertTrue(p.isDone())
        assertEquals(123, p.get())
    }

    // ------------------------------------------------------------
    // executeAsync Tests
    // ------------------------------------------------------------

    @Test
    void "executeAsync runs closure asynchronously"() {
        Promise<Integer> p = factory.executeAsync({ ->
            Thread.sleep(30)
            return 42
        })

        assertEquals(42, p.get(2, TimeUnit.SECONDS))
    }

    @Test
    void "multiple executeAsync calls do not interfere"() {
        def results = (1..20).collect { n ->
            factory.executeAsync({ -> n })
        }.collect { it.get(2, TimeUnit.SECONDS) }

        assertEquals((1..20).toList(), results)
    }

    // ------------------------------------------------------------
    // then() Tests
    // ------------------------------------------------------------

    @Test
    void "then transforms successful result"() {
        def p = factory.createPromise(10)
        def chained = p.then({ v -> v * 2 } as Function<Integer, Integer>)

        assertEquals(20, chained.get())
    }

    @Test
    void "then does not run transformer if original fails"() {
        def p = factory.createFailedPromise(new RuntimeException("boom"))

        def chained = p.then({ v ->
            fail("then() must not run on failure")
        } as Function<Object, Object>)

        assertThrows(RuntimeException) {
            chained.get()
        }
    }

    // ------------------------------------------------------------
    // recover() Tests
    // ------------------------------------------------------------

    @Test
    void "recover handles failure and yields fallback"() {
        def p = factory.createFailedPromise(new RuntimeException("boom"))

        def recovered = p.recover({ err -> 99 } as Function<Throwable, Integer>)

        assertEquals(99, recovered.get())
    }

    @Test
    void "recover does not run if original succeeded"() {
        def p = factory.createPromise("ok")

        def recovered = p.recover({ err ->
            fail("recover() must not run on success")
        } as Function<Throwable, String>)

        assertEquals("ok", recovered.get())
    }

    // ------------------------------------------------------------
    // onComplete & onError
    // ------------------------------------------------------------

    @Test
    void "onComplete is invoked when promise resolves"() {
        def ref = new AtomicReference<String>()
        def latch = new java.util.concurrent.CountDownLatch(1)

        def p = factory.createPromise()

        p.onComplete({ v ->
            ref.set(v)
            latch.countDown()
        } as Consumer<String>)

        p.accept("done")

        assertTrue(latch.await(1, TimeUnit.SECONDS))
        assertEquals("done", ref.get())
    }

    @Test
    void "onError receives failure"() {
        def ref = new AtomicReference<Throwable>()
        def latch = new java.util.concurrent.CountDownLatch(1)

        def p = factory.createFailedPromise(new RuntimeException("bad"))

        p.onError({ Throwable err ->
            ref.set(err)
            latch.countDown()
        } as Consumer<Throwable>)

        assertTrue(latch.await(1, TimeUnit.SECONDS))
        assertEquals("bad", ref.get().message)
    }

    // ------------------------------------------------------------
    // Blocking get()
    // ------------------------------------------------------------

    @Test
    void "get with timeout throws TimeoutException if unresolved"() {
        def p = factory.createPromise()

        assertThrows(TimeoutException) {
            p.get(100, TimeUnit.MILLISECONDS)
        }
    }

    // ------------------------------------------------------------
    // Parallel Composition: all()
    // ------------------------------------------------------------

    @Test
    void "all returns list of results when all succeed"() {
        def p1 = factory.createPromise("A")
        def p2 = factory.createPromise("B")
        def p3 = factory.createPromise("C")

        def all = factory.all([p1, p2, p3])

        assertEquals(["A", "B", "C"], all.get(2, TimeUnit.SECONDS))
    }

    @Test
    void "all fails when any fails"() {
        def ok = factory.createPromise("ok")
        def bad = factory.createFailedPromise(new RuntimeException("nope"))

        def all = factory.all([ok, bad])

        RuntimeException ex = assertThrows(RuntimeException) {
            all.get(2, TimeUnit.SECONDS)
        }
        assertEquals("nope", ex.message)
    }

    // ------------------------------------------------------------
    // Parallel Composition: any()
    // ------------------------------------------------------------

    @Test
    void "any returns first success"() {
        def slow = factory.executeAsync({ ->
            Thread.sleep(100)
            return "slow"
        })

        def fast = factory.executeAsync({ ->
            Thread.sleep(10)
            return "fast"
        })

        def any = factory.any([slow, fast])

        assertEquals("fast", any.get(2, TimeUnit.SECONDS))
    }

    @Test
    void "any fails when all fail"() {
        def p1 = factory.createFailedPromise(new RuntimeException("x"))
        def p2 = factory.createFailedPromise(new RuntimeException("y"))

        def any = factory.any([p1, p2])

        assertThrows(RuntimeException) {
            any.get()
        }
    }

    // ------------------------------------------------------------
    // Sequence Tests
    // ------------------------------------------------------------

    @Test
    void "sequence runs closures in order"() {
        def seq = factory.sequence(1, [
                { v -> v + 1 },
                { v -> v * 3 },
                { v -> v - 2 }
        ])

        assertEquals(((1 + 1) * 3) - 2, seq.get())
    }
}
