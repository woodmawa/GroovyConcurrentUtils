package org.softwood.dataflow;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class DataflowVariableTest {

    @Test
    void testSetAndGet() throws Exception {
        DataflowVariable<Integer> df = new DataflowVariable<>();
        df.set(10);

        assertEquals(10, df.get());
    }

    @Test
    void testLeftShiftOperatorEquivalent() throws Exception {
        DataflowVariable<Integer> df = new DataflowVariable<>();
        df.leftShift(5);

        assertEquals(5, df.get());
    }

    @Test
    void testRightShiftCallback() throws Exception {
        DataflowVariable<Integer> df = new DataflowVariable<>();

        AtomicBoolean called = new AtomicBoolean(false);

        df.rightShift((val) -> {
            called.set(true);
            assertEquals(7, val);
        });

        df.set(7);

        assertTrue(called.get(), ">> callback should trigger when value bound");
    }

    @Test
    void testGetIfAvailable() {
        DataflowVariable<Integer> df = new DataflowVariable<>();

        assertEquals(Optional.empty(), df.getIfAvailable());

        df.set(42);

        assertEquals(42, df.getIfAvailable().get());
    }

    @Test
    void testThenChainedTransformation() throws Exception {
        DataflowVariable<Integer> original = new DataflowVariable<>();
        DataflowVariable<String> result =
                original.then(v -> v * 3)
                        .then(v -> "Value = " + v);

        original.set(14);

        assertEquals("Value = 42", result.get());
    }

    @Test
    void testArithmeticPlus() throws Exception {
        DataflowVariable<Integer> a = new DataflowVariable<>();
        DataflowVariable<Integer> b = new DataflowVariable<>();

        var sum = (DataflowVariable<Integer>) a.methodMissing("plus", new Object[]{b});

        a.set(5);
        b.set(3);

        assertEquals(8, sum.get());
    }

    @Test
    void testErrorPropagationThroughOperator() throws Exception {
        DataflowVariable<Integer> x = new DataflowVariable<>();
        var div = (DataflowVariable<Integer>) x.methodMissing("div", new Object[]{0});

        x.set(10);

        assertThrows(Exception.class, div::get);
    }

    @Test
    void testTimeoutGet() {
        DataflowVariable<String> df = new DataflowVariable<>();

        String result = df.get(200, TimeUnit.MILLISECONDS);

        assertNull(result, "Timed get should return null when timeout occurs");
        assertTrue(df.hasError(), "Timeout should set error flag in DataflowVariable");
        assertNotNull(df.getError(), "Error should be stored in the variable");
    }
}
