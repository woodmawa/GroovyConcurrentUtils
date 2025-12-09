package org.softwood.promise

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.softwood.promise.core.PromiseConfiguration

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

import static org.junit.jupiter.api.Assertions.*

/**
 * Comprehensive test suite for the Promises facade class.
 * Tests all static methods and various usage patterns.
 */
class PromisesTest {

    @BeforeEach
    void setUp() {
        // Ensure we're using DATAFLOW for deterministic testing
        PromiseConfiguration.setDefaultImplementation(PromiseImplementation.DATAFLOW)
    }

    // =========================================================================
    // Basic Creation Tests
    // =========================================================================

    @Test
    void testNewPromise_Empty() {
        def promise = Promises.newPromise()
        
        assertNotNull(promise)
        assertFalse(promise.isDone())
    }

    @Test
    void testNewPromise_WithValue() {
        def promise = Promises.newPromise(42)
        
        assertTrue(promise.isDone())
        assertEquals(42, promise.get())
    }

    @Test
    void testNewPromise_SpecificImplementation() {
        def dfPromise = Promises.newPromise(PromiseImplementation.DATAFLOW)
        def cfPromise = Promises.newPromise(PromiseImplementation.COMPLETABLE_FUTURE)
        
        assertNotNull(dfPromise)
        assertNotNull(cfPromise)
        assertFalse(dfPromise.isDone())
        assertFalse(cfPromise.isDone())
    }

    @Test
    void testNewPromise_WithImplementationAndValue() {
        def promise = Promises.newPromise(PromiseImplementation.DATAFLOW, "test")
        
        assertTrue(promise.isDone())
        assertEquals("test", promise.get())
    }

    // =========================================================================
    // Failed Promise Tests
    // =========================================================================

    @Test
    void testFailed_CreatesFailedPromise() {
        def error = new RuntimeException("Test error")
        def promise = Promises.failed(error)
        
        assertTrue(promise.isDone())
        
        def exception = assertThrows(Exception.class, () -> promise.get())
        assertTrue(exception.message.contains("Test error"))
    }

    @Test
    void testFailed_WithImplementation() {
        def error = new IllegalStateException("State error")
        def promise = Promises.failed(PromiseImplementation.COMPLETABLE_FUTURE, error)
        
        assertTrue(promise.isDone())
        assertThrows(Exception.class, () -> promise.get())
    }

    @Test
    void testFromError_Alias() {
        def error = new RuntimeException("Error")
        def promise = Promises.fromError(error)
        
        assertTrue(promise.isDone())
        assertThrows(Exception.class, () -> promise.get())
    }

    // =========================================================================
    // Async Execution Tests
    // =========================================================================

    @Test
    void testAsync_Closure() {
        def promise = Promises.async {
            Thread.sleep(50)
            "async result"
        }
        
        def result = promise.get(1, TimeUnit.SECONDS)
        assertEquals("async result", result)
    }

    @Test
    void testAsync_ClosureWithException() {
        def promise = Promises.async {
            throw new RuntimeException("Async error")
        }
        
        // Wait for execution
        Thread.sleep(100)
        
        assertTrue(promise.isDone())
        assertThrows(Exception.class, () -> promise.get())
    }

    @Test
    void testAsync_WithImplementation() {
        def promise = Promises.async(PromiseImplementation.COMPLETABLE_FUTURE) {
            42 * 2
        }
        
        assertEquals(84, promise.get())
    }

    @Test
    void testAsync_Supplier() {
        def promise = Promises.async({ -> "supplier result" })
        
        def result = promise.get()
        assertEquals("supplier result", result)
    }

    // =========================================================================
    // Adapter Tests
    // =========================================================================

    @Test
    void testFrom_CompletableFuture() {
        def future = CompletableFuture.supplyAsync { 100 }
        def promise = Promises.from(future)
        
        assertEquals(100, promise.get())
    }

    @Test
    void testFrom_AnotherPromise() {
        def sourcePromise = Promises.newPromise(99)
        def adaptedPromise = Promises.from(sourcePromise)
        
        assertEquals(99, adaptedPromise.get())
    }

    // =========================================================================
    // EnsurePromise Tests
    // =========================================================================

    @Test
    void testEnsurePromise_AlreadyPromise() {
        def original = Promises.newPromise(42)
        def ensured = Promises.ensurePromise(original)
        
        assertSame(original, ensured)
    }

    @Test
    void testEnsurePromise_CompletableFuture() {
        def future = CompletableFuture.completedFuture(50)
        def promise = Promises.ensurePromise(future)
        
        assertEquals(50, promise.get())
    }

    @Test
    void testEnsurePromise_PlainValue() {
        def promise = Promises.ensurePromise("plain value")
        
        assertTrue(promise.isDone())
        assertEquals("plain value", promise.get())
    }

    @Test
    void testEnsurePromise_Null() {
        def promise = Promises.ensurePromise(null)
        
        assertTrue(promise.isDone())
        assertThrows(NullPointerException.class, () -> promise.get())
    }

    // =========================================================================
    // All / AllOf Tests
    // =========================================================================

    @Test
    void testAll_MultiplePromises() {
        def p1 = Promises.newPromise(1)
        def p2 = Promises.newPromise(2)
        def p3 = Promises.newPromise(3)
        
        def allPromise = Promises.all([p1, p2, p3])
        
        def results = allPromise.get()
        assertEquals([1, 2, 3], results)
    }

    @Test
    void testAll_EmptyList() {
        def allPromise = Promises.all([])
        
        def results = allPromise.get()
        assertTrue(results.isEmpty())
    }

    @Test
    void testAll_OneFailure() {
        def p1 = Promises.newPromise(1)
        def p2 = Promises.failed(new RuntimeException("Failed"))
        def p3 = Promises.newPromise(3)
        
        def allPromise = Promises.all([p1, p2, p3])
        
        // Give promises time to complete
        Thread.sleep(100)
        
        assertTrue(allPromise.isDone())
        assertThrows(Exception.class, () -> allPromise.get())
    }

    @Test
    void testAll_WithNulls() {
        def p1 = Promises.newPromise(1)
        def p2 = Promises.newPromise(2)
        
        def allPromise = Promises.all([p1, null, p2, null])
        
        def results = allPromise.get()
        assertEquals([1, 2], results)  // Nulls filtered out
    }

    @Test
    void testAll_AsyncPromises() {
        def p1 = Promises.async { Thread.sleep(50); "one" }
        def p2 = Promises.async { Thread.sleep(30); "two" }
        def p3 = Promises.async { Thread.sleep(20); "three" }
        
        def allPromise = Promises.all([p1, p2, p3])
        
        def results = allPromise.get(2, TimeUnit.SECONDS)
        assertEquals(["one", "two", "three"], results)
    }

    @Test
    void testAllOf_Alias() {
        def p1 = Promises.newPromise("a")
        def p2 = Promises.newPromise("b")
        
        def allPromise = Promises.allOf([p1, p2])
        
        def results = allPromise.get()
        assertEquals(["a", "b"], results)
    }

    // =========================================================================
    // Any Tests
    // =========================================================================

    @Test
    void testAny_FirstSuccess() {
        def p1 = Promises.async { Thread.sleep(100); "slow" }
        def p2 = Promises.async { Thread.sleep(10); "fast" }
        def p3 = Promises.async { Thread.sleep(200); "slowest" }
        
        def anyPromise = Promises.any([p1, p2, p3])
        
        def result = anyPromise.get(1, TimeUnit.SECONDS)
        assertEquals("fast", result)  // Should get the fastest one
    }

    @Test
    void testAny_EmptyList() {
        def anyPromise = Promises.any([])
        
        def result = anyPromise.get()
        assertNull(result)
    }

    @Test
    void testAny_AllFail() {
        def p1 = Promises.failed(new RuntimeException("Error 1"))
        def p2 = Promises.failed(new RuntimeException("Error 2"))
        def p3 = Promises.failed(new RuntimeException("Error 3"))
        
        def anyPromise = Promises.any([p1, p2, p3])
        
        // Give time for all to fail
        Thread.sleep(100)
        
        assertTrue(anyPromise.isDone())
        def exception = assertThrows(RuntimeException.class, () -> anyPromise.get())
        assertTrue(exception.message.contains("All promises failed"))
    }

    @Test
    void testAny_WithNulls() {
        def p1 = Promises.async { Thread.sleep(50); "result" }
        
        def anyPromise = Promises.any([null, p1, null])
        
        def result = anyPromise.get()
        assertEquals("result", result)
    }

    // =========================================================================
    // Pool Context Tests
    // =========================================================================

    @Test
    void testWithPool_CustomPool() {
        // This test would require a custom pool implementation
        // For now, just verify the method exists and doesn't throw
        
        def result = Promises.withPool(null) {
            "executed"
        }
        
        assertEquals("executed", result)
    }

    @Test
    void testGetCurrentPool() {
        def pool = Promises.getCurrentPool()
        // Just verify method doesn't throw - actual pool may be null
        // depending on configuration
    }

    // =========================================================================
    // Integration/Chain Tests
    // =========================================================================

    @Test
    void testChaining_ThenAndOnComplete() {
        def completed = false
        def finalResult = null
        
        Promises.newPromise(10)
            .then { it * 2 }
            .then { it + 5 }
            .onComplete { result ->
                completed = true
                finalResult = result
            }
        
        // Give callbacks time
        Thread.sleep(100)
        
        assertTrue(completed)
        assertEquals(25, finalResult)
    }

    @Test
    void testChaining_ErrorPropagation() {
        def errorCaught = false
        def errorMessage = null
        
        Promises.async {
            throw new RuntimeException("Chain error")
        }.then { it * 2 }
         .then { it + 5 }
         .onError { error ->
             errorCaught = true
             errorMessage = error.message
         }
        
        // Give time for async execution and callbacks
        Thread.sleep(200)
        
        assertTrue(errorCaught)
        assertTrue(errorMessage.contains("Chain error"))
    }

    @Test
    void testRecoverFromError() {
        def promise = Promises.async {
            throw new RuntimeException("Original error")
        }.recover { error ->
            "recovered value"
        }
        
        def result = promise.get()
        assertEquals("recovered value", result)
    }

    @Test
    void testAsyncComposition() {
        def result = Promises.async {
            5
        }.then { value ->
            Promises.async { value * 2 }
        }.get()
        
        // Note: Depending on implementation, nested promises may need special handling
        // This tests the general pattern
        assertNotNull(result)
    }

    // =========================================================================
    // Configuration Tests
    // =========================================================================

    @Test
    void testDefaultImplementation() {
        def impl = PromiseConfiguration.getDefaultImplementation()
        assertNotNull(impl)
    }

    @Test
    void testSetDefaultImplementation() {
        def original = PromiseConfiguration.getDefaultImplementation()
        
        try {
            PromiseConfiguration.setDefaultImplementation(PromiseImplementation.COMPLETABLE_FUTURE)
            def promise = Promises.newPromise(99)
            
            assertEquals(99, promise.get())
        } finally {
            // Restore original
            PromiseConfiguration.setDefaultImplementation(original)
        }
    }

    // =========================================================================
    // Edge Cases
    // =========================================================================

    @Test
    void testTimeout() {
        def promise = Promises.async {
            Thread.sleep(5000)  // Very long sleep
            "never"
        }
        
        assertThrows(TimeoutException.class, () ->
            promise.get(100, TimeUnit.MILLISECONDS)
        )
    }

    @Test
    void testMultipleCallbacksOnSamePromise() {
        def promise = Promises.newPromise()
        
        def callback1Called = false
        def callback2Called = false
        
        promise.onComplete { callback1Called = true }
        promise.onComplete { callback2Called = true }
        
        promise.accept("value")
        
        // Give callbacks time
        Thread.sleep(100)
        
        assertTrue(callback1Called)
        assertTrue(callback2Called)
    }

    @Test
    void testFilter_Success() {
        def promise = Promises.newPromise(50)
            .filter { it > 10 }
        
        assertEquals(50, promise.get())
    }

    @Test
    void testFilter_Failure() {
        def promise = Promises.newPromise(5)
            .filter { it > 10 }
        
        assertThrows(Exception.class, () -> promise.get())
    }

    @Test
    void testMap_Transformation() {
        def promise = Promises.newPromise(42)
            .map { it.toString() }
        
        def result = promise.get()
        assertTrue(result instanceof String)
        assertEquals("42", result)
    }

    @Test
    void testFlatMap_PromiseChaining() {
        def promise = Promises.newPromise(10)
            .flatMap { value ->
                Promises.newPromise(value * 2)
            }
        
        assertEquals(20, promise.get())
    }
}
