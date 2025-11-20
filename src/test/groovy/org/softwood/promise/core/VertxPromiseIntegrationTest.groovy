package org.softwood.promise.core

import io.vertx.core.Vertx
import org.junit.jupiter.api.*
import org.softwood.promise.core.vertx.VertxPromiseFactory

import java.util.concurrent.TimeUnit

import static org.junit.jupiter.api.Assertions.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VertxPromiseIntegrationTest {

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
    @DisplayName("full pipeline: async → then → recover → all → any → sequence")
    void fullPipelineAsyncThenRecoverAllAnySequence(){

        // async step
        def p1 = factory.executeAsync({ -> 10 })
        def p2 = factory.executeAsync({ -> 20 })

        // transform
        def p3 = p1.then({ x -> x * 2 })

        // recover
        def p4 = p2.recover({ err -> 99 })

        // parallel all
        def all = factory.all([p3, p4])

        // any (both succeed → fastest wins)
        def any = factory.any([p3, p4])

        // sequential pipeline
        def seq = factory.sequence(5, [
                { v -> v + 1 },
                { v -> v * 2 },
                { v -> v - 3 }
        ])

        assertEquals([20, 20], all.get(1, TimeUnit.SECONDS))
        assertTrue(any.get(1, TimeUnit.SECONDS) in [20, 40])
        assertEquals( ((5 + 1) * 2) - 3, seq.get(1, TimeUnit.SECONDS))
    }
}
