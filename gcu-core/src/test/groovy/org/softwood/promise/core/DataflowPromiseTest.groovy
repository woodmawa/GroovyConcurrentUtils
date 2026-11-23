package org.softwood.promise.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.softwood.dataflow.DataflowFactory
import org.softwood.dataflow.DataflowVariable
import org.softwood.promise.Promise
import org.softwood.promise.core.dataflow.DataflowPromise
import org.softwood.promise.core.dataflow.DataflowPromiseFactory

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Supplier

import static org.junit.jupiter.api.Assertions.*
import static org.awaitility.Awaitility.await
import static java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.CancellationException

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DataflowPromiseTest {

    // -----------------------------------------------------------
    //  Basic Completion Tests
    // -----------------------------------------------------------

    @Test
    void "accept(T) completes successfully"() {
        def dfv = new DataflowVariable<String>()
        Promise<String> promise = new DataflowPromise<>(dfv)

        promise.accept("hello")

        assertEquals("hello", promise.get())
        assertTrue(promise.isDone())
    }

    @Test
    void "accept(Supplier) completes with supplied value"() {
        def dfv = new DataflowVariable<Integer>()
        Promise<Integer> promise = new DataflowPromise<>(dfv)

        // accept(Supplier) now runs the supplier asynchronously on the DFV's executor,
        // but promise.get() will block until the supplier completes.
        promise.accept({ -> 42 } as Supplier<Integer>)

        assertEquals(42, promise.get())
        assertTrue(promise.isDone())
    }

    // -----------------------------------------------------------
    //  then() Transformation Tests
    // -----------------------------------------------------------

    @Test
    void "then transforms successful result"() {
        def dfv = new DataflowVariable<String>()
        Promise<String> p = new DataflowPromise<>(dfv)

        def chained = p
                .accept("abc")
                .then({ String v -> v.toUpperCase() } as Function<String, String>)

        // then() is asynchronous, but chained.get() will block until the transform finishes
        assertEquals("ABC", chained.get())
    }

    // -----------------------------------------------------------
    //  onComplete() Tests
    // -----------------------------------------------------------

    @Test
    void "onComplete is called when value becomes available"() {
        def dfv = new DataflowVariable<String>()
        Promise<String> p = new DataflowPromise<>(dfv)

        def ref = new AtomicReference<String>()

        p.onComplete({ String v ->
            ref.set(v)
        } as Consumer<String>)

        p.accept("done")

        // onComplete is always asynchronous now; wait for the callback
        await().atMost(1, SECONDS).until { ref.get() != null }
        assertEquals("done", ref.get())
    }

    @Test
    void "onComplete called immediately if value already bound"() {
        def dfv = new DataflowVariable<Integer>()
        Promise<Integer> p = new DataflowPromise<>(dfv)

        p.accept(99)

        def ref = new AtomicReference<Integer>()

        p.onComplete({ Integer v ->
            ref.set(v)
        } as Consumer<Integer>)

        // Even when the value was already bound, the callback is scheduled asynchronously
        await().atMost(1, SECONDS).until { ref.get() != null }
        assertEquals(99, ref.get())
    }

    // -----------------------------------------------------------
    //  Blocking get() and timeout
    // -----------------------------------------------------------

    @Test
    void "get(long,TimeUnit) times out when not completed"() {
        def dfv = new DataflowVariable<String>()
        Promise<String> p = new DataflowPromise<>(dfv)

        assertThrows(TimeoutException) {
            p.get(100, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    void "isDone false before completion and true after"() {
        def dfv = new DataflowVariable<String>()
        Promise<String> p = new DataflowPromise<>(dfv)

        assertFalse(p.isDone())

        p.accept("done")

        // accept(T) completes synchronously, so isDone should immediately become true
        assertTrue(p.isDone())
    }

    @Test
    void "accept(CompletableFuture) completes when future completes"() {
        def dfv = new DataflowVariable<String>()
        Promise<String> p = new DataflowPromise<>(dfv)

        def future = new CompletableFuture<String>()

        p.accept(future)

        future.complete("futureValue")

        assertEquals("futureValue", p.get())
        assertTrue(p.isDone())
    }

    @Test
    void "accept(CompletableFuture) propagates future exception"() {
        def dfv = new DataflowVariable<String>()
        Promise<String> p = new DataflowPromise<>(dfv)

        def future = new CompletableFuture<String>()

        p.accept(future)

        def ex = new RuntimeException("failure")
        future.completeExceptionally(ex)

        def caught = assertThrows(Exception) {
            p.get()
        }

        assertTrue(caught.message.contains("failure"))
    }

    @Test
    void "accept(Promise) completes when other promise completes"() {
        def sourceVar = new DataflowVariable<String>()
        Promise<String> source = new DataflowPromise<>(sourceVar)

        def targetVar = new DataflowVariable<String>()
        Promise<String> target = new DataflowPromise<>(targetVar)

        target.accept(source)

        source.accept("fromSource")

        assertEquals("fromSource", target.get())
        assertTrue(target.isDone())
    }

    @Test
    void "accept(Promise) propagates error from other promise"() {
        def sourceVar = new DataflowVariable<String>()
        Promise<String> source = new DataflowPromise<>(sourceVar)

        def targetVar = new DataflowVariable<String>()
        Promise<String> target = new DataflowPromise<>(targetVar)

        // Wire source → target
        target.accept(source)

        // Now fail the source using the official API
        def ex = new RuntimeException("boom")
        source.fail(ex)

        // target should now receive the propagated error
        def thrown = assertThrows(Exception) {
            target.get()
        }

        assertTrue(thrown.message.contains("boom"))
    }

    @Test
    void "recover maps error into a value"() {
        def dv = new DataflowVariable<String>()
        Promise<String> p = new DataflowPromise<>(dv)

        def recovered = p.recover { ex -> "fallback" }

        p.fail(new RuntimeException("boom"))

        assertEquals("fallback", recovered.get())
    }

    @Test
    void "recover does not run on success"() {
        def dv = new DataflowVariable<String>()
        Promise<String> p = new DataflowPromise<>(dv)

        def recovered = p.recover { ex -> "shouldNotRun" }

        p.accept("good")

        assertEquals("good", recovered.get())
    }

    @Test
    void "recover rethrows if recovery function throws"() {
        def dv = new DataflowVariable<String>()
        Promise<String> p = new DataflowPromise<>(dv)

        def recovered = p.recover { ex -> throw new RuntimeException("fail in recover") }

        p.fail(new RuntimeException("source"))

        def thrown = assertThrows(Exception) { recovered.get() }
        assertTrue(thrown.message.contains("fail in recover"))
    }

    // =============================================================================================
    // Promise API Enhancement Tests (map, flatMap, filter)
    // =============================================================================================

    @Test
    void "flatMap maps value into another promise and flattens the result"() {
        def factory = new DataflowPromiseFactory(new DataflowFactory())

        DataflowPromise<Integer> p = factory.createPromise(10)

        def chained = p.flatMap { v ->
            factory.createPromise(v + 5)
        }

        assertEquals(15, chained.get())
    }

    @Test
    void "flatMap propagates errors from the outer promise"() {
        def factory = new DataflowPromiseFactory(new DataflowFactory())

        DataflowPromise<Integer> p = factory.createPromise()
        def chained = p.flatMap { v ->
            factory.createPromise(v + 1)
        }

        p.fail(new RuntimeException("outer-fail"))

        def ex = assertThrows(Exception) { chained.get() }
        assertTrue(ex.message.contains("outer-fail"))
    }

    @Test
    void "flatMap propagates errors from the inner promise"() {
        def factory = new DataflowPromiseFactory(new DataflowFactory())

        DataflowPromise<Integer> p = factory.createPromise(10)

        def chained = p.flatMap { v ->
            def inner = factory.createPromise()
            inner.fail(new RuntimeException("inner-fail"))
            return inner
        }

        def ex = assertThrows(Exception) { chained.get() }
        assertTrue(ex.message.contains("inner-fail"))
    }

    @Test
    void "flatMap handles null inner promise as error"() {
        def factory = new DataflowPromiseFactory(new DataflowFactory())
        DataflowPromise<Integer> p = factory.createPromise(10)

        def chained = p.flatMap { v -> null }

        def ex = assertThrows(Exception) { chained.get() }

        assertTrue(ex instanceof NullPointerException)
        assertTrue(ex.message.contains("null promise"))
    }

    @Test
    void "cancel() cancels the promise"() {
        def dfv = new DataflowVariable<Integer>()
        Promise<Integer> p = new DataflowPromise<>(dfv)

        p.cancel(true)

        assertTrue(p.isCancelled())
        assertTrue(p.isDone())

        assertThrows(CancellationException) {
            p.get()
        }
    }

    @Test
    void "cancellation of underlying DataflowVariable propagates to promise"() {
        def dfv = new DataflowVariable<Integer>()
        Promise<Integer> p = new DataflowPromise<>(dfv)

        dfv.bindCancelled()

        assertTrue(p.isCancelled())
        assertTrue(p.isDone())

        assertThrows(CancellationException) { p.get() }
    }

    @Test
    void "accept(Promise) propagates cancellation"() {
        def srcVar = new DataflowVariable<Integer>()
        Promise<Integer> source = new DataflowPromise<>(srcVar)

        def tgtVar = new DataflowVariable<Integer>()
        Promise<Integer> target = new DataflowPromise<>(tgtVar)

        target.accept(source)

        source.cancel(true)

        assertTrue(target.isCancelled())
        assertTrue(target.isDone())

        assertThrows(CancellationException) { target.get() }
    }

    @Test
    void "cancellation propagates through then()"() {
        def dfv = new DataflowVariable<Integer>()
        Promise<Integer> p = new DataflowPromise<>(dfv)

        def chained = p.then { v -> v * 2 }

        p.cancel(true)

        assertTrue(chained.isCancelled())
        assertTrue(chained.isDone())

        assertThrows(CancellationException) { chained.get() }
    }

    @Test
    void "then() transform does not run after cancellation"() {
        def dfv = new DataflowVariable<Integer>()
        Promise<Integer> p = new DataflowPromise<>(dfv)

        def flag = new AtomicReference(false)

        def chained = p.then { v ->
            flag.set(true)
            return v * 2
        }

        p.cancel(true)

        assertTrue(chained.isCancelled())
        assertFalse(flag.get())  // transform must NOT run

        assertThrows(CancellationException) { chained.get() }
    }

    @Test
    void "cancellation propagates through flatMap()"() {
        def factory = new DataflowPromiseFactory(new DataflowFactory())
        DataflowPromise<Integer> p = factory.createPromise()  // Empty promise

        def chained = p.flatMap { v ->
            factory.createPromise(v + 5)
        }

        p.cancel(true)  // Cancel before any value is set

        assertTrue(chained.isCancelled())
        assertThrows(CancellationException) { chained.get() }
    }

    @Test
    void "flatMap inner cancellation propagates"() {
        def factory = new DataflowPromiseFactory(new DataflowFactory())

        DataflowPromise<Integer> p = factory.createPromise()  // Empty promise

        def chained = p.flatMap { v ->
            def inner = factory.createPromise()  // Empty promise
            inner.cancel(true)
            return inner
        }

        p.accept(10)  // Trigger flatMap after setup

        // Give async callbacks time to propagate
        await().atMost(1, SECONDS).until { chained.isDone() }

        assertTrue(chained.isCancelled())
        assertThrows(CancellationException) { chained.get() }
    }

}
