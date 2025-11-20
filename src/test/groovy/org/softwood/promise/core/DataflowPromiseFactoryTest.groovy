package org.softwood.promise.core

import org.junit.jupiter.api.*
import org.softwood.dataflow.DataflowFactory
import org.softwood.promise.Promise
import org.softwood.promise.core.dataflow.DataflowPromiseFactory

import java.util.concurrent.TimeUnit

import static org.junit.jupiter.api.Assertions.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DataflowPromiseFactoryTest {

    DataflowPromiseFactory factory

    @BeforeAll
    void setup() {
        // DataflowFactory is your underlying dataflow implementation factory
        factory = new DataflowPromiseFactory(new DataflowFactory())
    }

    // ---------------------------------------------------------
    // createPromise()
    // ---------------------------------------------------------

    @Test
    void "createPromise returns incomplete promise that can be completed"() {
        Promise<String> p = factory.createPromise()

        assertFalse(p.isDone())
        p.accept("hello")
        assertTrue(p.isDone())
        assertEquals("hello", p.get())
    }

    @Test
    void "createPromise(value) returns an already completed promise"() {
        Promise<Integer> p = factory.createPromise(123)

        assertTrue(p.isDone())
        assertEquals(123, p.get())
    }

    // ---------------------------------------------------------
    // executeAsync()
    // ---------------------------------------------------------

    @Test
    void "executeAsync runs closure and completes promise"() {
        Promise<Integer> p = factory.executeAsync({ ->
            Thread.sleep(50)
            return 42
        } as Closure<Integer>)

        assertEquals(42, p.get(2, TimeUnit.SECONDS))
    }

    @Test
    void "factory remains usable after multiple async executions"() {
        (1..50).each { int i ->
            Promise<Integer> p = factory.executeAsync({ -> i } as Closure<Integer>)
            assertEquals(i, p.get(2, TimeUnit.SECONDS))
        }
    }
}
