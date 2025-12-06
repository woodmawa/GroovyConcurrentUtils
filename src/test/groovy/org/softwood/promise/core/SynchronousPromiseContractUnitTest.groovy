package org.softwood.promise.core


import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.softwood.promise.Promise
import org.softwood.promise.PromiseFactory

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import static org.junit.jupiter.api.Assertions.*
import static org.awaitility.Awaitility.await

class SynchronousPromiseContractUnitTest {

    private PromiseFactory factory

    @BeforeEach
    void setUp() {
        factory = new SynchronousPromiseFactoryMock()
    }

    // -------------------------------------------------------------------------
    // Basic Completion
    // -------------------------------------------------------------------------

    @Test
    void testCreateCompletedPromiseReturnsValueImmediately() throws Exception {
        Promise<Integer> p = factory.createPromise(42)

        // Even though synchronous, use await to mirror async test style.
        await().atMost(1, TimeUnit.SECONDS).until(p::isDone)

        assertEquals(42, p.get())
    }

    @Test
    void testPromiseAcceptsValue() throws Exception {
        Promise<Integer> p = factory.createPromise()
        p.accept(10)

        await().atMost(1, TimeUnit.SECONDS).until(p::isDone);
        assertEquals(10, p.get())
    }

    @Test
    void testPromiseAcceptsSupplier() throws Exception {
        Promise<String> p = factory.createPromise()
        p.accept(() -> "hello")

        await().until(p::isDone)

        assertEquals("hello", p.get())
    }

    @Test
    void testPromiseAcceptsAnotherPromise() throws Exception {
        Promise<Integer> p1 = factory.createPromise(7)
        Promise<Integer> p2 = factory.createPromise()
        p2.accept(p1);

        await().until(p2::isDone)

        assertEquals(7, p2.get())
    }

    // -------------------------------------------------------------------------
    // Async (Sync in mock) execution
    // -------------------------------------------------------------------------

    @Test
    void testExecuteAsyncReturnsImmediateResult() throws Exception {
        Promise<String> p = factory.executeAsync(() -> "abc")

        await().until(p::isDone)

        assertEquals("abc", p.get())
    }

    @Test
    void testExecuteAsyncCapturesExceptionsAsFailures() {
        Promise<Integer> p = factory.executeAsync(() -> { throw new RuntimeException("boom"); })

        await().until(p::isDone)

        assertThrows(RuntimeException.class, p::get)
    }

    // -------------------------------------------------------------------------
    // Transformations
    // -------------------------------------------------------------------------

    @Test
    void testThenTransformsValue() throws Exception {
        Promise<Integer> p = factory.createPromise(5)
                .then(v -> v * 2)

        await().until(p::isDone)

        assertEquals(10, p.get())
    }

    @Test
    void testMapTransformsValue() throws Exception {
        Promise<String> p = factory.createPromise("abc")
                .map(String::toUpperCase)

        await().until(p::isDone)

        assertEquals("ABC", p.get())
    }

    @Test
    void testFlatMapCreatesNewPromise() throws Exception {
        Promise<Integer> p = factory.createPromise(4)
                .flatMap(v -> factory.createPromise(v + 6))

        await().until(p::isDone)

        assertEquals(10, p.get())
    }

    // -------------------------------------------------------------------------
    // Failure / Recovery
    // -------------------------------------------------------------------------

    @Test
    void testFailMarksPromiseAsFailed() {
        Promise<Integer> p = factory.createPromise()
        p.fail(new IllegalStateException("oops"))

        await().until(p::isDone)

        assertThrows(IllegalStateException.class, p::get)
    }

    @Test
    void testRecoverReturnsRecoveredPromise() throws Exception {
        Promise<Integer> p = factory.createFailedPromise(new RuntimeException("err"))
                .recover(e -> 99)

        await().until(p::isDone)

        assertEquals(99, p.get())
    }

    // -------------------------------------------------------------------------
    // Callbacks
    // -------------------------------------------------------------------------

    @Test
    void testOnCompleteIsCalledImmediately() {
        AtomicBoolean called = new AtomicBoolean(false)

        Promise<Integer> p = factory.createPromise(123)
        p.onComplete(v -> called.set(true))

        await().untilTrue(called);
    }

    @Test
    void testOnErrorIsCalledImmediately() {
        AtomicBoolean called = new AtomicBoolean(false)

        Promise<Integer> p = factory.createFailedPromise(new RuntimeException("e"))
        p.onError(err -> called.set(true))

        await().untilTrue(called)
    }

    // -------------------------------------------------------------------------
    // Filtering
    // -------------------------------------------------------------------------

    @Test
    void testFilterAllowsValidValues() throws Exception {
        Promise<Integer> p = factory.createPromise(20)
                .filter(v -> v > 10)

        await().until(p::isDone)

        assertEquals(20, p.get())
    }

    @Test
    void testFilterRejectsInvalidValues() {
        Promise<Integer> p = factory.createPromise(5)
                .filter(v -> v > 10)

        await().until(p::isDone)

        assertThrows(Exception.class, p::get)
    }

    // -------------------------------------------------------------------------
    // Cancellation
    // -------------------------------------------------------------------------

    @Test
    void testCancelMarksPromiseAsCancelled() {
        Promise<Integer> p = factory.createPromise(77);

        assertTrue(p.cancel(true))
        assertTrue(p.isCancelled())
        assertTrue(p.isDone())
    }
}
