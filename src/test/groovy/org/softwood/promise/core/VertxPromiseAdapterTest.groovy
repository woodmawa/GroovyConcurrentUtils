package org.softwood.promise.core.vertx

import io.vertx.core.Vertx
import org.junit.jupiter.api.*
import org.softwood.promise.Promise

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

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VertxPromiseAdapterTest {

    Vertx vertx

    @BeforeEach
    void setup() {
        vertx = Vertx.vertx()
    }

    @AfterEach
    void teardown() {
        vertx.close()
    }

    // -----------------------------------------------------------
    //  Basic Completion Tests
    // -----------------------------------------------------------

    @Test
    void "accept(T) completes successfully"() {
        Promise<String> p = VertxPromiseAdapter.create(vertx)

        p.accept("hello")

        assertEquals("hello", p.get())
        assertTrue(p.isDone())
    }

    @Test
    void "accept(Supplier) completes with supplied value"() {
        Promise<Integer> p = VertxPromiseAdapter.create(vertx)

        // VertxPromiseAdapter should run the Supplier asynchronously on Vert.x,
        // but get() will block until the supplier result is available.
        p.accept({ -> 42 } as Supplier<Integer>)

        assertEquals(42, p.get())
        assertTrue(p.isDone())
    }

    // -----------------------------------------------------------
    //  then() Transformation Tests
    // -----------------------------------------------------------

    @Test
    void "then transforms successful result"() {
        Promise<String> p = VertxPromiseAdapter.create(vertx)

        def chained = p
                .accept("abc")
                .then({ String v -> v.toUpperCase() } as Function<String, String>)

        // then() is asynchronous but chained.get() blocks until transform finishes
        assertEquals("ABC", chained.get())
    }

    // -----------------------------------------------------------
    //  onComplete() Tests
    // -----------------------------------------------------------

    @Test
    void "onComplete is called when value becomes available"() {
        Promise<String> p = VertxPromiseAdapter.create(vertx)

        def ref = new AtomicReference<String>()

        p.onComplete({ String v ->
            ref.set(v)
        } as Consumer<String>)

        p.accept("done")

        // onComplete is always async; wait for the callback
        await().atMost(1, SECONDS).until { ref.get() != null }
        assertEquals("done", ref.get())
    }

    @Test
    void "onComplete called immediately if value already bound"() {
        Promise<Integer> p = VertxPromiseAdapter.create(vertx)

        p.accept(99)

        def ref = new AtomicReference<Integer>()

        p.onComplete({ Integer v ->
            ref.set(v)
        } as Consumer<Integer>)

        // even if already bound, callback is scheduled asynchronously
        await().atMost(1, SECONDS).until { ref.get() != null }
        assertEquals(99, ref.get())
    }

    // -----------------------------------------------------------
    //  Blocking get() and timeout
    // -----------------------------------------------------------

    @Test
    void "get(long,TimeUnit) times out when not completed"() {
        Promise<String> p = VertxPromiseAdapter.create(vertx)

        assertThrows(TimeoutException) {
            p.get(100, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    void "isDone false before completion and true after"() {
        Promise<String> p = VertxPromiseAdapter.create(vertx)

        assertFalse(p.isDone())

        p.accept("done")

        // there can be delay before Vert.x schedules the accept handler
        await().atMost(1, SECONDS).until { p.isDone() }

        assertTrue(p.isDone())
    }

    // -----------------------------------------------------------
    //  accept(CompletableFuture)
    // -----------------------------------------------------------

    @Test
    void "accept(CompletableFuture) completes when future completes"() {
        Promise<String> p = VertxPromiseAdapter.create(vertx)

        def future = new CompletableFuture<String>()
        p.accept(future)

        future.complete("futureValue")

        assertEquals("futureValue", p.get())
        assertTrue(p.isDone())
    }

    @Test
    void "accept(CompletableFuture) propagates future exception"() {
        Promise<String> p = VertxPromiseAdapter.create(vertx)

        def future = new CompletableFuture<String>()
        p.accept(future)

        def ex = new RuntimeException("failure")
        future.completeExceptionally(ex)

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
        Promise<String> source = VertxPromiseAdapter.create(vertx)
        Promise<String> target = VertxPromiseAdapter.create(vertx)

        target.accept(source)
        source.accept("fromSource")

        assertEquals("fromSource", target.get())
        assertTrue(target.isDone())
    }

    @Test
    void "accept(Promise) propagates error from other promise"() {
        Promise<String> source = VertxPromiseAdapter.create(vertx)
        Promise<String> target = VertxPromiseAdapter.create(vertx)

        target.accept(source)

        def ex = new RuntimeException("boom")
        source.fail(ex)

        def thrown = assertThrows(Exception) {
            target.get()
        }

        assertTrue(thrown.message.contains("boom"))
    }
}
