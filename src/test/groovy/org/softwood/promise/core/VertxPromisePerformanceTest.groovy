package org.softwood.promise.core

import io.vertx.core.Vertx
import org.junit.jupiter.api.*
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
    }

    @AfterAll
    void teardown() {
        vertx.close()
    }

    @Test
    void "10k parallel promises complete under time threshold"() {
        int N = 10_000

        def promises = (1..N).collect {
            factory.executeAsync({ -> it })
        }

        long start = System.currentTimeMillis()
        def all = factory.all(promises)

        def result = all.get(5, TimeUnit.SECONDS)
        long elapsed = System.currentTimeMillis() - start

        assertEquals(N, result.size())
        assertTrue(elapsed < 5_000)   // 5 seconds max
    }

    @Test
    void "500-step sequential chain runs in reasonable time"() {
        int N = 500

        def tasks = (1..N).collect {
            { int v -> v + 1 }
        }

        long start = System.currentTimeMillis()
        def p = factory.sequence(0, tasks)
        def value = p.get(5, TimeUnit.SECONDS)
        long elapsed = System.currentTimeMillis() - start

        assertEquals(N, value)
        assertTrue(elapsed < 5_000)
    }

    @RepeatedTest(5)
    void "load test: 100 parallel chains of 50 steps"() {
        def chains = (1..100).collect {
            factory.sequence(0, (1..50).collect { { int v -> v + 1 } })
        }

        def all = factory.all(chains)
        def vals = all.get(5, TimeUnit.SECONDS)

        vals.each { assertEquals(50, it) }
    }
}
