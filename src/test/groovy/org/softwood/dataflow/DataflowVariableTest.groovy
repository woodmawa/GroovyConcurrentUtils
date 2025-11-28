package org.softwood.dataflow

import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import static org.junit.jupiter.api.Assertions.*
import static org.awaitility.Awaitility.await

class DataflowVariableTest {

    @Test
    void testBindAndGetValue() {
        def df = new DataflowVariable<Integer>()
        df.bind(42)

        // df.get() is synchronous and blocking-read only
        assertEquals(42, df.get())
        assertTrue(df.isDone())
        assertTrue(df.isSuccess())
        assertFalse(df.hasError())
    }

    @Test
    void testRightShiftCallback() {
        def df = new DataflowVariable<Integer>()
        def called = new AtomicBoolean(false)

        df >> { v ->
            called.set(true)
            assertEquals(7, v)
        }

        df.bind(7)

        await().atMost(1, TimeUnit.SECONDS).until { called.get() }
    }

    @Test
    void testWhenErrorReceivesError() {
        def df = new DataflowVariable<Integer>()
        def called = new AtomicBoolean(false)

        df.whenError { err ->
            called.set(true)
            assertTrue(err instanceof IllegalStateException)
            assertEquals("boom", err.message)
        }

        df.bindError(new IllegalStateException("boom"))

        await().atMost(1, TimeUnit.SECONDS).until { called.get() }
    }

    @Test
    void testThenTransformationAsync() {
        def df = new DataflowVariable<Integer>()
        def transformed = df.then { it * 2 }

        df.bind(10)

        await().atMost(1, TimeUnit.SECONDS).until {
            transformed.isDone()
        }
        assertEquals(20, transformed.get())
    }

    @Test
    void testThenPropagatesError() {
        def df = new DataflowVariable<Integer>()
        def chained = df.then { it * 2 }

        df.bindError(new RuntimeException("fail"))

        await().atMost(1, TimeUnit.SECONDS).until { chained.isDone() }
        assertTrue(chained.hasError())
        assertEquals("fail", chained.getError().message)
    }

    @Test
    void testRecoverTransformsError() {
        def df = new DataflowVariable<Integer>()
        def recovered = df.recover { 99 }

        df.bindError(new IllegalArgumentException("bad"))

        await().atMost(1, TimeUnit.SECONDS).until { recovered.isDone() }
        assertEquals(99, recovered.get())
    }

    @Test
    void testToFutureSuccess() {
        def df = new DataflowVariable<Integer>()
        def future = df.toFuture()

        df.bind(55)

        assertEquals(55, future.get(1, TimeUnit.SECONDS))
    }

    @Test
    void testToFutureError() {
        def df = new DataflowVariable<Integer>()
        def future = df.toFuture()

        df.bindError(new RuntimeException("oops"))

        def ex = assertThrows(Exception) {
            future.get(1, TimeUnit.SECONDS)
        }
        assertTrue(ex.cause instanceof RuntimeException)
        assertEquals("oops", ex.cause.message)
    }

    // =============================================================================================
    // Operator overload tests (+ - * /)
    // =============================================================================================

    @Test
    void testPlusOperatorDFVtoDFV() {
        def a = new DataflowVariable<Integer>()
        def b = new DataflowVariable<Integer>()

        def sum = a + b

        a.bind(10)
        b.bind(5)

        await().atMost(1, TimeUnit.SECONDS).until { sum.isDone() }
        assertEquals(15, sum.get())
    }

    @Test
    void testPlusOperatorWithConstant() {
        def a = new DataflowVariable<Integer>()
        def result = a + 3

        a.bind(7)

        await().atMost(1, TimeUnit.SECONDS).until { result.isDone() }
        assertEquals(10, result.get())
    }

    @Test
    void testMinusOperator() {
        def a = new DataflowVariable<Integer>()
        def b = new DataflowVariable<Integer>()

        def diff = a - b

        a.bind(20)
        b.bind(4)

        await().atMost(1, TimeUnit.SECONDS).until { diff.isDone() }
        assertEquals(16, diff.get())
    }

    @Test
    void testMultiplyOperator() {
        def a = new DataflowVariable<Integer>()
        def b = new DataflowVariable<Integer>()

        def prod = a * b

        a.bind(6)
        b.bind(7)

        await().atMost(1, TimeUnit.SECONDS).until { prod.isDone() }
        assertEquals(42, prod.get())
    }

    @Test
    void testDivOperator() {
        def a = new DataflowVariable<Integer>()
        def b = new DataflowVariable<Integer>()

        def div = a / b

        a.bind(20)
        b.bind(4)

        await().atMost(1, TimeUnit.SECONDS).until { div.isDone() }
        assertEquals(5, div.get())
    }

    @Test
    void testChainedOperatorExpression() {
        def a = new DataflowVariable<Integer>()
        def b = new DataflowVariable<Integer>()
        def c = new DataflowVariable<Integer>()

        // (a + b) * c
        def expr = (a + b) * c

        a.bind(2)
        b.bind(3)
        c.bind(4)

        await().atMost(1, TimeUnit.SECONDS).until { expr.isDone() }
        assertEquals(20, expr.get())
    }

    @Test
    void testOperatorResultPropagatesLeftError() {
        def a = new DataflowVariable<Integer>()
        def b = new DataflowVariable<Integer>()

        def sum = a + b

        a.bindError(new RuntimeException("left-failed"))
        b.bind(10)

        await().atMost(1, TimeUnit.SECONDS).until { sum.isDone() }
        assertTrue(sum.hasError())
        assertEquals("left-failed", sum.getError().message)
    }

    @Test
    void testOperatorResultPropagatesRightError() {
        def a = new DataflowVariable<Integer>()
        def b = new DataflowVariable<Integer>()

        def sum = a + b

        a.bind(10)
        b.bindError(new RuntimeException("right-failed"))

        await().atMost(1, TimeUnit.SECONDS).until { sum.isDone() }
        assertTrue(sum.hasError())
        assertEquals("right-failed", sum.getError().message)
    }

    @Test
    void testOperatorEvaluationExceptionsProduceError() {
        def a = new DataflowVariable<Integer>()
        def b = new DataflowVariable<Integer>()

        // Division by zero inside operator closure
        def div = a / b

    }
}