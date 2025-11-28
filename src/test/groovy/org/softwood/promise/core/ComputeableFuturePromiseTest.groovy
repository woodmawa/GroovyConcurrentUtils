package org.softwood.promise.core.cfuture

import org.junit.jupiter.api.*
import org.softwood.promise.Promise
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Supplier

import static org.junit.jupiter.api.Assertions.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompletableFuturePromiseTest {

    // -----------------------------------------------------------
    //  Basic Completion Tests
    // -----------------------------------------------------------

    @Test
    void "accept(T) completes successfully"() {
        Promise<String> p = new CompletableFuturePromise<>(new CompletableFuture<>())

        p.accept("hello")

        assertEquals("hello", p.get())
        assertTrue(p.isDone())
    }

    @Test
    void "accept(Supplier) completes with supplied value"() {
        Promise<Integer> p = new CompletableFuturePromise<>(new CompletableFuture<>())

        p.accept({ -> 42 } as Supplier<Integer>)

        assertEquals(42, p.get())
        assertTrue(p.isDone())
    }

    // -----------------------------------------------------------
    //  then() Transformation Tests
    // -----------------------------------------------------------

    @Test
    void "then transforms successful result"() {
        Promise<String> p = new CompletableFuturePromise<>(new CompletableFuture<>())

        def chained = p
                .accept("abc")
                .then({ String v -> v.toUpperCase() } as Function<String, String>)

        assertEquals("ABC", chained.get())
    }

    // -----------------------------------------------------------
    //  onComplete() Tests
    // -----------------------------------------------------------

    @Test
    void "onComplete is called when value becomes available"() {
        Promise<String> p = new CompletableFuturePromise<>(new CompletableFuture<>())

        def ref = new AtomicReference<String>()
        def latch = new CountDownLatch(1)

        p.onComplete({ String v ->
            ref.set(v)
            latch.countDown()
        } as Consumer<String>)

        p.accept("done")

        assertTrue(latch.await(1, TimeUnit.SECONDS))
        assertEquals("done", ref.get())
    }

    @Test
    void "onComplete called immediately if already complete"() {
        Promise<Integer> p = new CompletableFuturePromise<>(new CompletableFuture<>())

        p.accept(99)

        def ref = new AtomicReference<Integer>()
        def latch = new CountDownLatch(1)

        p.onComplete({ Integer v ->
            ref.set(v)
            latch.countDown()
        } as Consumer<Integer>)

        assertTrue(latch.await(1, TimeUnit.SECONDS))
        assertEquals(99, ref.get())
    }

    // -----------------------------------------------------------
    //  Blocking get() and timeout
    // -----------------------------------------------------------

    @Test
    void "get(long,TimeUnit) times out when not completed"() {
        Promise<String> p = new CompletableFuturePromise<>(new CompletableFuture<>())

        assertThrows(TimeoutException) {
            p.get(100, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    void "isDone false before completion and true after"() {
        Promise<String> p = new CompletableFuturePromise<>(new CompletableFuture<>())

        assertFalse(p.isDone())

        p.accept("done")

        assertTrue(p.isDone())
    }

    // -----------------------------------------------------------
    //  accept(CompletableFuture)
    // -----------------------------------------------------------

    @Test
    void "accept(CompletableFuture) completes when future completes"() {
        Promise<String> p = new CompletableFuturePromise<>(new CompletableFuture<>())
        def other = new CompletableFuture<String>()

        p.accept(other)

        other.complete("futureValue")

        assertEquals("futureValue", p.get())
        assertTrue(p.isDone())
    }

    @Test
    void "accept(CompletableFuture) propagates future exception"() {
        Promise<String> p = new CompletableFuturePromise<>(new CompletableFuture<>())
        def other = new CompletableFuture<String>()

        p.accept(other)

        def ex = new RuntimeException("failure")
        other.completeExceptionally(ex)

        def caught = assertThrows(Exception) {
            p.get()
        }

        assertTrue(caught.message.contains("failure"))
    }

    // -----------------------------------------------------------
    //  accept(Promise)
    // -----------------------------------------------------------

    @Test
    void "accept(Promise) completes when other promise completes"() {
        Promise<String> source = new CompletableFuturePromise<>(new CompletableFuture<>())
        Promise<String> target = new CompletableFuturePromise<>(new CompletableFuture<>())

        target.accept(source)
        source.accept("fromSource")

        assertEquals("fromSource", target.get())
        assertTrue(target.isDone())
    }

    @Test
    void "accept(Promise) propagates error from other promise"() {
        Promise<String> source = new CompletableFuturePromise<>(new CompletableFuture<>())
        Promise<String> target = new CompletableFuturePromise<>(new CompletableFuture<>())

        target.accept(source)

        def ex = new RuntimeException("boom")
        source.fail(ex)

        def thrown = assertThrows(Exception) {
            target.get()
        }

        assertTrue(thrown.message.contains("boom"))
    }

    // -----------------------------------------------------------
    //  recover()
    // -----------------------------------------------------------

    @Test
    void "recover maps error into a value"() {
        Promise<String> p = new CompletableFuturePromise<>(new CompletableFuture<>())

        def recovered = p.recover { ex -> "fallback" }

        p.fail(new RuntimeException("boom"))

        assertEquals("fallback", recovered.get())
    }

    @Test
    void "recover does not run on success"() {
        Promise<String> p = new CompletableFuturePromise<>(new CompletableFuture<>())

        def recovered = p.recover { ex -> "shouldNotRun" }

        p.accept("good")

        assertEquals("good", recovered.get())
    }

    @Test
    void "recover rethrows if recovery function throws"() {
        Promise<String> p = new CompletableFuturePromise<>(new CompletableFuture<>())

        def recovered = p.recover { ex ->
            throw new RuntimeException("fail in recover")
        }

        p.fail(new RuntimeException("srcErr"))

        def thrown = assertThrows(Exception) { recovered.get() }

        assertTrue(thrown.message.contains("fail in recover"))
    }

    // -----------------------------------------------------------
//  map()
// -----------------------------------------------------------

    @Test
    void "map transforms value"() {
        Promise<Integer> p = new CompletableFuturePromise<>(new CompletableFuture<>())

        def mapped = p.map { v -> v * 2 }

        p.accept(10)

        assertEquals(20, mapped.get())
    }

    @Test
    void "map propagates error"() {
        Promise<Integer> p = new CompletableFuturePromise<>(new CompletableFuture<>())

        def mapped = p.map { v -> v * 2 }

        p.fail(new RuntimeException("mapErr"))

        def ex = assertThrows(Exception) { mapped.get() }
        assertTrue(ex.message.contains("mapErr"))
    }

// -----------------------------------------------------------
//  flatMap()
// -----------------------------------------------------------

    @Test
    void "flatMap maps and flattens inner promise"() {
        Promise<Integer> p = new CompletableFuturePromise<>(new CompletableFuture<>())

        def chained = p.flatMap { v ->
            def inner = new CompletableFuturePromise<>(new CompletableFuture<Integer>())
            inner.accept(v + 5)
            return inner
        }

        p.accept(10)

        assertEquals(15, chained.get())
    }

    @Test
    void "flatMap propagates inner error"() {
        Promise<Integer> p = new CompletableFuturePromise<>(new CompletableFuture<>())

        def chained = p.flatMap { v ->
            def inner = new CompletableFuturePromise<>(new CompletableFuture<Integer>())
            inner.fail(new RuntimeException("innerFail"))
            return inner
        }

        p.accept(10)

        def ex = assertThrows(Exception) { chained.get() }
        assertTrue(ex.message.contains("innerFail"))
    }

    @Test
    void "flatMap propagates outer error"() {
        Promise<Integer> p = new CompletableFuturePromise<>(new CompletableFuture<>())

        def chained = p.flatMap { v ->
            new CompletableFuturePromise<>(new CompletableFuture<Integer>())
        }

        p.fail(new RuntimeException("outerFail"))

        def ex = assertThrows(Exception) { chained.get() }
        assertTrue(ex.message.contains("outerFail"))
    }

    @Test
    void "flatMap null inner promise produces error"() {
        Promise<Integer> p = new CompletableFuturePromise<>(new CompletableFuture<>())

        def chained = p.flatMap { v -> null }

        p.accept(10)

        def ex = assertThrows(Exception) { chained.get() }
        assertTrue(ex.message.contains("null"))
    }

// -----------------------------------------------------------
//  filter()
// -----------------------------------------------------------

    @Test
    void "filter passes value when predicate is true"() {
        Promise<Integer> p = new CompletableFuturePromise<>(new CompletableFuture<>())

        def filtered = p.filter { v -> v > 5 }

        p.accept(10)

        assertEquals(10, filtered.get())
    }

    @Test
    void "filter fails when predicate is false"() {
        Promise<Integer> p = new CompletableFuturePromise<>(new CompletableFuture<>())

        def filtered = p.filter { v -> v > 5 }

        p.accept(3)

        def ex = assertThrows(Exception) { filtered.get() }
        assertTrue(ex.message.contains("Predicate not satisfied"))
    }

    @Test
    void "filter propagates source error"() {
        Promise<Integer> p = new CompletableFuturePromise<>(new CompletableFuture<>())

        def filtered = p.filter { v -> v > 5 }

        p.fail(new RuntimeException("srcError"))

        def ex = assertThrows(Exception) { filtered.get() }
        assertTrue(ex.message.contains("srcError"))
    }

}
