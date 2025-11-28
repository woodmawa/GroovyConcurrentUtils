package org.softwood.dataflow

import org.junit.jupiter.api.Test

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import static org.junit.jupiter.api.Assertions.*
import static org.awaitility.Awaitility.await

class DataflowExpressionTest {

    @Test
    void testSetValueAndGetValue() throws Exception {
        DataflowVariable<Integer> df = new DataflowVariable<>()

        df.setValue(42)

        Integer result = df.getValue()

        assertEquals(42, result)
        assertTrue(df.isBound())
    }

    @Test
    void testWhenBoundCallbackTriggered() throws Exception {
        DataflowVariable<String> df = new DataflowVariable<>()

        AtomicBoolean callbackCalled = new AtomicBoolean(false)

        df.whenBound("test", { value, msg ->
            callbackCalled.set(true)
            assertEquals("hello", value)
            assertEquals("test", msg)
        })

        df.setValue("hello")

        // callback is scheduled asynchronously now, so wait for it
        await()
                .atMost(1, TimeUnit.SECONDS)
                .until { callbackCalled.get() }

        assertTrue(callbackCalled.get(), "whenBound callback was not invoked")
    }

    @Test
    void testThenTransformation() throws Exception {
        DataflowVariable<Integer> df = new DataflowVariable<>()

        def doubled = df.then { v -> v * 2 }

        df.setValue(21)

        assertEquals(42, doubled.getValue())
    }

    @Test
    void testSetErrorPropagates() {
        DataflowVariable<Integer> df = new DataflowVariable<>()

        df.setError(new DataflowException("boom"))

        DataflowException ex = assertThrows(DataflowException.class, df::getValue)
        assertEquals("boom", ex.getMessage())
        assertTrue(df.hasError())
    }

    @Test
    void testTimeoutGet() throws Exception {
        DataflowVariable<String> df = new DataflowVariable<>()

        assertThrows(Exception.class) {
            df.getValue(100, TimeUnit.MILLISECONDS)
        }
    }
}
