package org.softwood.dag

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import static org.junit.jupiter.api.Assertions.*

import org.awaitility.Awaitility
import java.util.concurrent.TimeUnit
import org.softwood.promise.Promise
import org.softwood.dag.task.*

/**
 * Tests for SendTask - external message/event sending.
 */
class SendTaskTest {

    private TaskContext ctx

    @BeforeEach
    void setup() {
        ctx = new TaskContext()
    }

    private static <T> T awaitPromise(Promise<T> p) {
        Awaitility.await()
                .atMost(10, TimeUnit.SECONDS)
                .until({ p.isDone() })
        return p.get()
    }

    @Test
    void testBasicHttpSend() {
        def task = new SendTask("send-test", "Send Test", ctx)
        
        task.url = "https://httpbin.org/post"
        task.method = "POST"
        task.payload { taskCtx, prev ->
            [message: "Hello World", timestamp: System.currentTimeMillis()]
        }
        
        def prevPromise = ctx.promiseFactory.createPromise([value: 42])
        def promise = task.execute(prevPromise)
        def result = awaitPromise(promise)
        
        assertTrue(result.success)
        assertEquals(200, result.statusCode)
        assertNotNull(result.responseBody)
        assertEquals(TaskState.COMPLETED, task.state)
        
        println "✅ Basic HTTP send test passed"
    }

    @Test
    void testHttpSendWithHeaders() {
        def task = new SendTask("headers-test", "Headers Test", ctx)
        
        task.url = "https://httpbin.org/post"
        task.headers {
            delegate["X-Custom-Header"] = "TestValue"
            delegate["X-Request-ID"] = "12345"
        }
        task.payload { taskCtx, prev ->
            [test: "data"]
        }
        
        def prevPromise = ctx.promiseFactory.createPromise(null)
        def promise = task.execute(prevPromise)
        def result = awaitPromise(promise)
        
        assertTrue(result.success)
        assertNotNull(result.responseBody)
        
        println "✅ HTTP send with headers test passed"
    }

    @Test
    void testHttpGet() {
        def task = new SendTask("get-test", "GET Test", ctx)
        
        task.url = "https://httpbin.org/get"
        task.method = "GET"
        
        def prevPromise = ctx.promiseFactory.createPromise(null)
        def promise = task.execute(prevPromise)
        def result = awaitPromise(promise)
        
        assertTrue(result.success)
        assertEquals(200, result.statusCode)
        
        println "✅ HTTP GET test passed"
    }

    @Test
    void testMissingUrl() {
        def task = new SendTask("missing-url-test", "Missing URL Test", ctx)
        // Don't set URL
        
        def prevPromise = ctx.promiseFactory.createPromise(null)
        def promise = task.execute(prevPromise)
        
        assertThrows(Exception.class) {
            awaitPromise(promise)
        }
        
        println "✅ Missing URL validation test passed"
    }

    @Test
    void testPayloadProvider() {
        def task = new SendTask("payload-test", "Payload Test", ctx)
        
        task.url = "https://httpbin.org/post"
        task.payload { taskCtx, prev ->
            [
                orderId: prev.orderId,
                total: prev.items.sum { it.price },
                itemCount: prev.items.size()
            ]
        }
        
        def inputData = [
            orderId: "ORD-123",
            items: [
                [name: "Item1", price: 10],
                [name: "Item2", price: 20]
            ]
        ]
        
        def prevPromise = ctx.promiseFactory.createPromise(inputData)
        def promise = task.execute(prevPromise)
        def result = awaitPromise(promise)
        
        assertTrue(result.success)
        
        println "✅ Payload provider test passed"
    }

    @Test
    void testSuccessCallback() {
        def task = new SendTask("success-callback-test", "Success Callback Test", ctx)
        
        def callbackCalled = false
        def callbackResult = null
        
        task.url = "https://httpbin.org/get"
        task.method = "GET"
        task.onSuccess { response ->
            callbackCalled = true
            callbackResult = response
        }
        
        def prevPromise = ctx.promiseFactory.createPromise(null)
        def promise = task.execute(prevPromise)
        awaitPromise(promise)
        
        Thread.sleep(200)  // Give callback time to execute
        
        assertTrue(callbackCalled)
        assertNotNull(callbackResult)
        assertTrue(callbackResult.success)
        
        println "✅ Success callback test passed"
    }

    @Test
    void testInvalidUrl() {
        def task = new SendTask("invalid-url-test", "Invalid URL Test", ctx)
        
        task.url = "http://this-domain-definitely-does-not-exist-12345.com"
        task.payload { taskCtx, prev -> [:] }
        
        def prevPromise = ctx.promiseFactory.createPromise(null)
        def promise = task.execute(prevPromise)
        def result = awaitPromise(promise)
        
        assertFalse(result.success)
        assertNotNull(result.error)
        assertEquals(TaskState.COMPLETED, task.state)  // Task completes even if send fails
        
        println "✅ Invalid URL handling test passed"
    }

    @Test
    void testProtocolValidation() {
        def task = new SendTask("protocol-test", "Protocol Test", ctx)
        
        task.protocol = "kafka"  // Unsupported protocol
        task.url = "kafka://localhost:9092/topic"
        task.payload { taskCtx, prev -> [:] }
        
        def prevPromise = ctx.promiseFactory.createPromise(null)
        def promise = task.execute(prevPromise)
        
        assertThrows(IllegalStateException.class) {
            awaitPromise(promise)
        }
        
        println "✅ Protocol validation test passed"
    }
}
