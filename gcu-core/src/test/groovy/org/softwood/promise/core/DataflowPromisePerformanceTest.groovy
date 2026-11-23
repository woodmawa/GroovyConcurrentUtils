package org.softwood.promise.core

import org.junit.jupiter.api.*
import org.softwood.dataflow.DataflowFactory
import org.softwood.promise.Promise
import org.softwood.promise.core.dataflow.DataflowPromiseFactory

import java.util.concurrent.TimeUnit

import static org.junit.jupiter.api.Assertions.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DataflowPromisePerformanceTest {

    DataflowPromiseFactory factory

    @BeforeAll
    void setup() {
        factory = new DataflowPromiseFactory(new DataflowFactory())
    }

    // ---------------------------------------------------------------------------------------------
    // Warm-up utilities
    // ---------------------------------------------------------------------------------------------
    void warmup(int n = 2000) {
        def tmp = (1..n).collect {
            factory.executeAsync({ -> it } as Closure<Integer>)
        }
        tmp.each { it.get(2, TimeUnit.SECONDS) }
    }

    // ---------------------------------------------------------------------------------------------
    // 10k parallel promises
    // ---------------------------------------------------------------------------------------------

    @Test
    void "10k parallel dataflow promises complete under time threshold"() {
        warmup()

        int N = 10_000
        long t0 = System.nanoTime()

        def promises = (1..N).collect { int i ->
            factory.executeAsync({ -> i } as Closure<Integer>)
        }

        def results = promises.collect { Promise<Integer> p ->
            p.get(10, TimeUnit.SECONDS)
        }

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000

        println "Parallel 10k: ${elapsedMs} ms  (${(N / (elapsedMs / 1000)).intValue()} ops/sec)"

        assertEquals(N, results.size())
        assertEquals((1..N).toList(), results)
        assertTrue(elapsedMs < 10_000)
    }

    // ---------------------------------------------------------------------------------------------
    // 500-step chain benchmark
    // ---------------------------------------------------------------------------------------------

    @Test
    void "500-step then-chain runs in reasonable time"() {
        warmup()

        int N = 500

        Promise<Integer> p = factory.createPromise(0)

        (1..N).each {
            p = p.then({ int v -> v + 1 } as java.util.function.Function<Integer, Integer>)
        }

        long t0 = System.nanoTime()
        int value = p.get(10, TimeUnit.SECONDS)
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000

        println "500-step chain: ${elapsedMs} ms"

        assertEquals(N, value)
        assertTrue(elapsedMs < 10_000)
    }

    // ---------------------------------------------------------------------------------------------
    // Load test: 100 × 50-step chains
    // ---------------------------------------------------------------------------------------------

    @RepeatedTest(3)
    void "load test: 100 parallel chains of 50 steps each"() {
        warmup(1000)

        long t0 = System.nanoTime()

        def promises = (1..100).collect {
            Promise<Integer> p = factory.createPromise(0)
            (1..50).each {
                p = p.then({ int v -> v + 1 } as java.util.function.Function<Integer, Integer>)
            }
            p
        }

        def results = promises.collect { it.get(10, TimeUnit.SECONDS) }

        long elapsed = (System.nanoTime() - t0) / 1_000_000
        println "100×50 chains: ${elapsed} ms"

        results.each { assertEquals(50, it) }
    }

    // ---------------------------------------------------------------------------------------------
    // New: Error-path performance
    // ---------------------------------------------------------------------------------------------

    @Test
    void "10k failed promises complete quickly"() {
        warmup()

        int N = 10_000

        long t0 = System.nanoTime()

        def promises = (1..N).collect { i ->
            def p = factory.createPromise()
            p.fail(new RuntimeException("boom-$i"))
            return p
        }

        def results = promises.collect {
            assertThrows(Exception) { it.get(1, TimeUnit.SECONDS) }
        }

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000
        println "10k error-path: ${elapsedMs} ms"

        assertTrue(elapsedMs < 5000)
    }

    // ---------------------------------------------------------------------------------------------
    // New: map / flatMap performance
    // ---------------------------------------------------------------------------------------------

    @Test
    void "1000 map+flatMap pipeline"() {
        warmup()

        int N = 1000
        def p = factory.createPromise(1)

        (1..N).each {
            p = p.map { v -> v + 1 }
                    .flatMap { v -> factory.createPromise(v) }
        }

        long t0 = System.nanoTime()
        def result = p.get(10, TimeUnit.SECONDS)
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000

        println "1000×(map+flatMap) pipeline: ${elapsedMs} ms"

        assertEquals(N + 1, result)
        assertTrue(elapsedMs < 10_000)
    }
}
