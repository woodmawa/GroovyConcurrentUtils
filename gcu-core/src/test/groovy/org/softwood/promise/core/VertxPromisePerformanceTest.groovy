package org.softwood.promise.core

import io.vertx.core.Vertx
import org.junit.jupiter.api.*
import org.softwood.promise.Promise
import org.softwood.promise.core.vertx.VertxPromiseFactory

import java.util.concurrent.TimeUnit

import static org.junit.jupiter.api.Assertions.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VertxPromisePerformanceTest {

    Vertx vertx
    VertxPromiseFactory factory

    @BeforeAll
    void setup() {
        vertx = Vertx.vertx()
        factory = new VertxPromiseFactory(vertx)
        warmup()
    }

    @AfterAll
    void teardown() {
        vertx.close()
    }

    // ----------------------------------------------------------------------------
    // Warm-up
    // ----------------------------------------------------------------------------

    void warmup(int n = 2000) {
        def tmp = (1..n).collect { i ->
            factory.executeAsync({ -> i })
        }
        factory.all(tmp).get(5, TimeUnit.SECONDS)
    }

    // ----------------------------------------------------------------------------
    // 10k parallel promises
    // ----------------------------------------------------------------------------

    @Test
    void "10k parallel promises complete under time threshold"() {
        int N = 10_000
        long t0 = System.nanoTime()

        def promises = (1..N).collect {
            factory.executeAsync({ -> it })
        }

        def all = factory.all(promises)
        def result = all.get(10, TimeUnit.SECONDS)

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000
        println "Vert.x parallel 10k: ${elapsedMs} ms  (${(N / (elapsedMs / 1000)).intValue()} ops/sec)"

        assertEquals(N, result.size())
        assertTrue(elapsedMs < 10_000)
    }

    // ----------------------------------------------------------------------------
    // 500-step sequential chain
    // ----------------------------------------------------------------------------

    @Test
    void "500-step sequential chain runs in reasonable time"() {
        int N = 500

        def tasks = (1..N).collect { { int v -> v + 1 } }

        long t0 = System.nanoTime()
        def p = factory.sequence(0, tasks)
        def value = p.get(10, TimeUnit.SECONDS)
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000

        println "Vert.x 500-step chain: ${elapsedMs} ms"

        assertEquals(N, value)
        assertTrue(elapsedMs < 10_000)
    }

    // ----------------------------------------------------------------------------
    // 100×50 parallel chains
    // ----------------------------------------------------------------------------

    @RepeatedTest(3)
    void "load test: 100 parallel chains of 50 steps each"() {
        def chains = (1..100).collect {
            factory.sequence(0, (1..50).collect { { int v -> v + 1 } })
        }

        long t0 = System.nanoTime()
        def vals = factory.all(chains).get(10, TimeUnit.SECONDS)
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000

        println "Vert.x 100×50 chains: ${elapsedMs} ms"

        vals.each { assertEquals(50, it) }
    }

    // ----------------------------------------------------------------------------
    // NEW: 10k failed promises
    // ----------------------------------------------------------------------------

    @Test
    void "10k failed promises complete quickly"() {
        int N = 10_000
        long t0 = System.nanoTime()

        def promises = (1..N).collect {
            def p = factory.createPromise()
            p.fail(new RuntimeException("boom-$it"))
            return p
        }

        def all = factory.all(promises)

        assertThrows(Exception) { all.get(10, TimeUnit.SECONDS) }

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000
        println "Vert.x 10k error-path: ${elapsedMs} ms"

        assertTrue(elapsedMs < 10_000)
    }

    // ----------------------------------------------------------------------------
    // NEW: map + flatMap pipelines under Vert.x
    // ----------------------------------------------------------------------------

    @Test
    @DisplayName("1000 map + flatMap operations under Vert.x")
    void testMapFlatMapPipeline1000() {
        int N = 1000
        def p = factory.createPromise(1)

        (1..N).each {
            p = p.map { v -> v + 1 }
                    .flatMap { v -> factory.createPromise(v) }
        }

        long t0 = System.nanoTime()
        def result = p.get(10, TimeUnit.SECONDS)
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000

        println "Vert.x 1000×(map+flatMap) pipeline: ${elapsedMs} ms"

        assertEquals(N + 1, result)
        assertTrue(elapsedMs < 10_000)
    }

    // ----------------------------------------------------------------------------
    // NEW: mixed Vert.x load (parallel + chained)
    // ----------------------------------------------------------------------------

    @Test
    void "mixed load: 2000 single tasks + 100 chains"() {
        long t0 = System.nanoTime()

        def singles = (1..2000).collect {
            factory.executeAsync({ -> it })
        }

        def chains = (1..100).collect {
            factory.sequence(0, (1..20).collect { { int v -> v + 1 } })
        }

        def all = factory.all(singles + chains)
        def vals = all.get(10, TimeUnit.SECONDS)

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000

        println "Vert.x mixed load (2000 singles + 100 chains): ${elapsedMs} ms"

        assertEquals(2100, vals.size())
        assertTrue(elapsedMs < 15_000)
    }
}
