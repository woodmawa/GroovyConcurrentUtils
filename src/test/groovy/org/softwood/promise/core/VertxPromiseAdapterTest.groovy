package org.softwood.promise.core

import io.vertx.core.Vertx
import org.junit.jupiter.api.*
import org.softwood.promise.Promise
import org.softwood.promise.core.vertx.VertxPromiseAdapter
import org.softwood.promise.core.vertx.VertxPromiseFactory

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

import static org.junit.jupiter.api.Assertions.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VertxPromiseFactoryTest {

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

    // ---------------------------------------------------------
    // executeAsync()
    // ---------------------------------------------------------

    @Test
    void "executeAsync runs closure on worker thread"() {
        def p = factory.executeAsync({ ->
            Thread.sleep(50)
            return 42
        })

        assertEquals(42, p.get(1, TimeUnit.SECONDS))
    }

    // ---------------------------------------------------------
    // all()
    // ---------------------------------------------------------

    @Test
    void "all returns list of results when all succeed"() {
        def p1 = factory.createPromise("A")
        def p2 = factory.createPromise("B")
        def p3 = factory.createPromise("C")

        def all = factory.all([p1, p2, p3])

        assertEquals(["A", "B", "C"], all.get(1, TimeUnit.SECONDS))
    }

    @Test
    void "all fails when any promise fails"() {
        def ok = factory.createPromise("OK")
        def bad = factory.createFailedPromise(new RuntimeException("nope"))

        def all = factory.all([ok, bad])

        RuntimeException ex = assertThrows(RuntimeException) {
            all.get(1, TimeUnit.SECONDS)
        }
        assertEquals("nope", ex.message)
    }

    // ---------------------------------------------------------
    // any()
    // ---------------------------------------------------------

    @Test
    void "any returns first successful result"() {
        def p1 = factory.executeAsync({ ->
            Thread.sleep(100)
            return "slow"
        })

        def p2 = factory.executeAsync({ ->
            Thread.sleep(10)
            return "fast"
        })

        def any = factory.any([p1, p2])

        assertEquals("fast", any.get(1, TimeUnit.SECONDS))
    }

    @Test
    void "any fails when all fail"() {
        def p1 = factory.createFailedPromise(new RuntimeException("x"))
        def p2 = factory.createFailedPromise(new RuntimeException("y"))

        def any = factory.any([p1, p2])

        RuntimeException ex = assertThrows(RuntimeException) {
            any.get(1, TimeUnit.SECONDS)
        }
        assertTrue(ex.message.contains("x") || ex.message.contains("y"))
    }

    // ---------------------------------------------------------
    // sequence()
    // ---------------------------------------------------------

    @Test
    void "sequence runs tasks in order"() {
        List<String> calls = []

        def out = factory.sequence("start", [
                { v -> calls << "step1"; return v + "-1" },
                { v -> calls << "step2"; return v + "-2" },
                { v -> calls << "step3"; return v + "-3" }
        ])

        assertEquals("start-1-2-3", out.get(1, TimeUnit.SECONDS))
        assertEquals(["step1", "step2", "step3"], calls)
    }

    // ---------------------------------------------------------
    // shutdown()
    // ---------------------------------------------------------

    @Test
    void "shutdown closes Vertx and returns completed promise"() {
        def v = Vertx.vertx()
        def f = new VertxPromiseFactory(v)

        def p = f.shutdown()
        assertDoesNotThrow((org.junit.jupiter.api.function.Executable) {
            p.get(1, TimeUnit.SECONDS)
        })
    }
}
