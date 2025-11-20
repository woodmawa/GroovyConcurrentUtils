package org.softwood.promise.core

import org.junit.jupiter.api.*
import org.softwood.promise.Promise
import org.softwood.promise.PromiseFactory

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Function

import static org.junit.jupiter.api.Assertions.*

/**
 * Abstract performance and stress test suite for PromiseFactory + Promise implementations.
 *
 * Extend this class for concrete implementations:
 *
 *   class VertxPromisePerformanceTest extends AbstractPromisePerformanceTest {
 *       @Override PromiseFactory createFactory() { new VertxPromiseFactory(Vertx.vertx()) }
 *   }
 *
 *   class DataflowPromisePerformanceTest extends AbstractPromisePerformanceTest {
 *       @Override PromiseFactory createFactory() { new DataflowPromiseFactory(new DataflowFactory()) }
 *   }
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractPromisePerformanceTest {

    abstract PromiseFactory createFactory()

    PromiseFactory factory

    @BeforeAll
    void setupFactory() {
        factory = createFactory()
    }

    // -------------------------------------------------------------------------
    // 1. MASSIVE PARALLEL EXECUTION TEST
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("10k parallel async promises resolve under reasonable time")
    void parallel10k() {
        int N = 10_000

        long start = System.currentTimeMillis()

        def promises = (1..N).collect { i ->
            factory.executeAsync({ -> i })
        }

        def results = promises.collect { p ->
            p.get(10, TimeUnit.SECONDS)
        }

        long elapsed = System.currentTimeMillis() - start

        assertEquals(N, results.size())
        assertEquals((1..N).toList(), results)
        assertTrue(elapsed < 10_000, "10k async load should resolve within 10 seconds")
    }

    // -------------------------------------------------------------------------
    // 2. DEEP CHAIN TEST
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("500-step then() chain resolves correctly")
    void deepChain500() {
        int N = 500
        Promise<Integer> p = factory.createPromise(0)

        (1..N).each {
            p = p.then({ v -> v + 1 } as Function<Integer, Integer>)
        }

        long start = System.currentTimeMillis()
        int value = p.get(10, TimeUnit.SECONDS)
        long elapsed = System.currentTimeMillis() - start

        assertEquals(N, value)
        assertTrue(elapsed < 10_000)
    }

    // -------------------------------------------------------------------------
    // 3. MULTI-RUN LOAD TEST
    // -------------------------------------------------------------------------

    @RepeatedTest(5)
    @DisplayName("Repeated 100-chain 50-step runs")
    void repeatedChains() {
        int chains = 100
        int steps = 50

        def promises = (1..chains).collect {
            Promise<Integer> p = factory.createPromise(0)
            (1..steps).each {
                p = p.then({ v -> v + 1 } as Function<Integer, Integer>)
            }
            p
        }

        def values = promises.collect { it.get(10, TimeUnit.SECONDS) }

        values.each { assertEquals(steps, it) }
    }

    // -------------------------------------------------------------------------
    // 4. FAN-OUT TEST
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Promise fan-out: 1 input → 1000 listeners")
    void fanOut1000() {
        Promise<Integer> p = factory.createPromise()
        int listeners = 1000
        AtomicInteger counter = new AtomicInteger(0)

        (1..listeners).each {
            p.onComplete({ v ->
                counter.incrementAndGet()
            })
        }

        p.accept(123)

        assertEquals(123, p.get())
        assertEquals(listeners, counter.get(), "All listeners should fire")
    }

    // -------------------------------------------------------------------------
    // 5. FAILURE → RECOVER → THEN chain test
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("failure → recover → then chain resolves correctly under load")
    void failureRecoverThenChain() {
        Promise<Integer> p = factory.createFailedPromise(new RuntimeException("boom"))

        Promise<Integer> r = p
                .recover({ err -> 99 })
                .then({ v -> v + 1 })

        assertEquals(100, r.get())
    }

    // -------------------------------------------------------------------------
    // 6. MIXED PARALLEL + SEQUENTIAL WORK TEST
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Mixed: 100 parallel chains, each 100 steps")
    void mixedParallelSequential() {
        int outer = 100
        int inner = 100

        def promises = (1..outer).collect { idx ->
            Promise<Integer> p = factory.createPromise(idx)
            (1..inner).each {
                p = p.then({ v -> v + 1 })
            }
            p
        }

        def results = promises.collect { it.get(10, TimeUnit.SECONDS) }

        results.eachWithIndex { v, i ->
            assertEquals((i + 1) + inner, v)
        }
    }

    // -------------------------------------------------------------------------
    // 7. THROUGHPUT TEST
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Throughput: 50k light promises")
    void throughput50k() {
        int N = 50_000

        long start = System.currentTimeMillis()

        def promises = (1..N).collect { i ->
            factory.createPromise(i)
        }

        def out = promises.collect { it.get() }

        long elapsed = System.currentTimeMillis() - start

        assertEquals(N, out.size())
        assertEquals((1..N).toList(), out)
        assertTrue(elapsed < 5000, "50k raw resolves should complete in under 5 seconds")
    }
}
