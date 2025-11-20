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

    @Test
    void "10k parallel dataflow promises complete under time threshold"() {
        int N = 10_000

        def promises = (1..N).collect { int i ->
            factory.executeAsync({ -> i } as Closure<Integer>)
        }

        long start = System.currentTimeMillis()
        def results = promises.collect { Promise<Integer> p ->
            p.get(10, TimeUnit.SECONDS)
        }
        long elapsed = System.currentTimeMillis() - start

        assertEquals(N, results.size())
        assertEquals((1..N).toList(), results)
        assertTrue(elapsed < 10_000)    // 10 seconds soft upper bound
    }

    @Test
    void "500-step then chain runs in reasonable time"() {
        int N = 500

        Promise<Integer> p = factory.createPromise(0)

        (1..N).each {
            p = p.then({ int v -> v + 1 } as java.util.function.Function<Integer, Integer>)
        }

        long start = System.currentTimeMillis()
        int value = p.get(10, TimeUnit.SECONDS)
        long elapsed = System.currentTimeMillis() - start

        assertEquals(N, value)
        assertTrue(elapsed < 10_000)
    }

    @RepeatedTest(3)
    void "load test: 100 parallel chains of 50 steps each"() {
        def promises = (1..100).collect {
            Promise<Integer> p = factory.createPromise(0)
            (1..50).each {
                p = p.then({ int v -> v + 1 } as java.util.function.Function<Integer, Integer>)
            }
            p
        }

        def results = promises.collect { it.get(10, TimeUnit.SECONDS) }

        results.each { assertEquals(50, it) }
    }
}
