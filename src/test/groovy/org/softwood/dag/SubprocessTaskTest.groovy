package org.softwood.dag

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import static org.junit.jupiter.api.Assertions.*

import org.awaitility.Awaitility
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.softwood.promise.Promise
import org.softwood.dag.task.*

/**
 * Tests for SubprocessTask - Subprocess Invocation
 */
class SubprocessTaskTest {

    private TaskContext ctx

    @BeforeEach
    void setup() {
        ctx = new TaskContext()
        SubprocessTask.clearRegistry()
    }

    @AfterEach
    void cleanup() {
        SubprocessTask.clearRegistry()
    }

    private static <T> T awaitPromise(Promise<T> p) {
        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .until({ p.isDone() })
        return p.get()
    }

    @Test
    void testBasicSubProcess() {
        def task = new SubprocessTask("call", "Subprocess", ctx)
        task.subProcessProvider = { c, input ->
            c.promiseFactory.executeAsync {
                "processed: ${input}".toString()
            }
        }
        
        def promise = task.execute(ctx.promiseFactory.createPromise("test-input"))
        def result = awaitPromise(promise)
        
        assertEquals("processed: test-input", result.toString())
    }

    @Test
    void testInputMapping() {
        ctx.globals.set("orderId", "ORDER-123")
        
        def task = new SubprocessTask("call", "Subprocess", ctx)
        task.inputMapper = { c ->
            [id: c.globals.get("orderId"), priority: "high"]
        }
        task.subProcessProvider = { c, input ->
            c.promiseFactory.executeAsync {
                "Order ${input.id} with priority ${input.priority}".toString()
            }
        }
        
        def promise = task.execute(ctx.promiseFactory.createPromise(null))
        def result = awaitPromise(promise)
        
        assertEquals("Order ORDER-123 with priority high", result.toString())
    }

    @Test
    void testOutputMapping() {
        def task = new SubprocessTask("call", "Subprocess", ctx)
        task.subProcessProvider = { c, input ->
            c.promiseFactory.executeAsync {
                [status: "success", data: "result"]
            }
        }
        task.outputMapper = { result ->
            result.status
        }
        
        def promise = task.execute(ctx.promiseFactory.createPromise(null))
        def result = awaitPromise(promise)
        
        assertEquals("success", result)
    }

    @Test
    void testSubProcessGraph() {
        def task = new SubprocessTask("call", "Subprocess", ctx)
        task.subProcessProvider = { c, input ->
            def graph = TaskGraph.build {
                serviceTask("step1") {
                    action { ctx, prev ->
                        ctx.promiseFactory.executeAsync {
                            "step1: ${input}".toString()
                        }
                    }
                }
                
                serviceTask("step2") {
                    action { ctx, prev ->
                        ctx.promiseFactory.executeAsync {
                            "${prev} -> step2".toString()
                        }
                    }
                }
                
                fork("flow") {
                    from "step1"
                    to "step2"
                }
            }
            
            return graph.run()
        }
        
        def promise = task.execute(ctx.promiseFactory.createPromise("input-data"))
        def result = awaitPromise(promise)
        
        assertEquals("step1: input-data -> step2", result.toString())
    }

    @Test
    void testErrorHandler() {
        def errorHandled = false
        
        def task = new SubprocessTask("call", "Subprocess", ctx)
        task.subProcessProvider = { c, input ->
            throw new RuntimeException("Subprocess failed")
        }
        task.errorHandler = { error ->
            errorHandled = true
        }
        
        def promise = task.execute(ctx.promiseFactory.createPromise(null))
        
        assertThrows(RuntimeException) {
            awaitPromise(promise)
        }
        
        assertTrue(errorHandled)
    }

    @Test
    void testTimeout() {
        def task = new SubprocessTask("call", "Subprocess", ctx)
        task.timeout = Duration.ofMillis(100)
        task.subProcessProvider = { c, input ->
            c.promiseFactory.executeAsync {
                Thread.sleep(500)
                "done"
            }
        }
        
        def promise = task.execute(ctx.promiseFactory.createPromise(null))
        
        def exception = assertThrows(Exception) {
            awaitPromise(promise)
        }
        
        def isTimeout = exception instanceof java.util.concurrent.TimeoutException ||
                        exception.cause instanceof java.util.concurrent.TimeoutException
        
        assertTrue(isTimeout, "Expected TimeoutException but got: ${exception.class.name}")
    }

    @Test
    void testSubProcessRegistry() {
        SubprocessTask.registerSubProcess("test-subprocess", { c, input ->
            c.promiseFactory.executeAsync {
                "subprocess result: ${input}".toString()
            }
        })
        
        def task = new SubprocessTask("call", "Subprocess", ctx)
        task.subProcessRef = "test-subprocess"
        
        def promise = task.execute(ctx.promiseFactory.createPromise("test"))
        def result = awaitPromise(promise)
        
        assertEquals("subprocess result: test", result.toString())
        
        SubprocessTask.unregisterSubProcess("test-subprocess")
    }

    @Test
    void testInvalidSubProcessRef() {
        def task = new SubprocessTask("call", "Subprocess", ctx)
        task.subProcessRef = "non-existent"
        
        def promise = task.execute(ctx.promiseFactory.createPromise(null))
        
        assertThrows(IllegalStateException) {
            awaitPromise(promise)
        }
    }

    @Test
    void testInGraph() {
        def graph = TaskGraph.build {
            serviceTask("prepare") {
                action { ctx, prev ->
                    ctx.globals.set("data", [value: 100])
                    ctx.promiseFactory.executeAsync { "prepared" }
                }
            }
            
            task("subprocess", TaskType.SUBPROCESS) {
                input { ctx ->
                    ctx.globals.get("data")
                }
                
                subProcess { ctx, input ->
                    def subgraph = TaskGraph.build {
                        serviceTask("validate") {
                            action { c, p ->
                                c.promiseFactory.executeAsync {
                                    input.value > 50 ? "valid" : "invalid"
                                }
                            }
                        }
                    }
                    subgraph.run()
                }
                
                output { result ->
                    "subprocess returned: ${result}".toString()
                }
            }
            
            serviceTask("finalize") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        "finalized: ${prev}".toString()
                    }
                }
            }
            
            fork("flow") {
                from "prepare"
                to "subprocess"
            }
            
            fork("flow2") {
                from "subprocess"
                to "finalize"
            }
        }
        
        def result = awaitPromise(graph.run())
        
        assertEquals("finalized: subprocess returned: valid", result.toString())
    }

    @Test
    void testNonPromiseSubProcess() {
        def task = new SubprocessTask("call", "Subprocess", ctx)
        task.subProcessProvider = { c, input ->
            "direct result: ${input}".toString()
        }
        
        def promise = task.execute(ctx.promiseFactory.createPromise("test"))
        def result = awaitPromise(promise)
        
        assertEquals("direct result: test", result.toString())
    }

    @Test
    void testComplexInputOutputMapping() {
        ctx.globals.set("order", [
            id: "ORD-001",
            items: [[name: "Item1", qty: 2], [name: "Item2", qty: 1]],
            customer: [id: "CUST-123", tier: "gold"]
        ])
        
        def task = new SubprocessTask("call", "Subprocess", ctx)
        
        task.inputMapper = { c ->
            def order = c.globals.get("order")
            [
                orderId: order.id,
                itemCount: order.items.size(),
                customerTier: order.customer.tier
            ]
        }
        
        task.subProcessProvider = { c, input ->
            c.promiseFactory.executeAsync {
                [
                    processed: true,
                    orderId: input.orderId,
                    discount: input.customerTier == "gold" ? 0.1 : 0.0,
                    items: input.itemCount
                ]
            }
        }
        
        task.outputMapper = { result ->
            ctx.globals.set("processedOrder", result)
            "Order ${result.orderId}: ${result.items} items, discount: ${result.discount}".toString()
        }
        
        def promise = task.execute(ctx.promiseFactory.createPromise(null))
        def result = awaitPromise(promise)
        
        assertEquals("Order ORD-001: 2 items, discount: 0.1", result.toString())
        assertNotNull(ctx.globals.get("processedOrder"))
        assertTrue(ctx.globals.get("processedOrder").processed)
    }

    @Test
    void testNestedSubProcesses() {
        def task = new SubprocessTask("call", "Subprocess", ctx)
        
        task.subProcessProvider = { c, input ->
            def outerGraph = TaskGraph.build {
                serviceTask("inner") {
                    action { ctx, prev ->
                        ctx.promiseFactory.executeAsync {
                            "Inner processed: ${input}".toString()
                        }
                    }
                }
                
                serviceTask("outer-process") {
                    action { ctx, prev ->
                        ctx.promiseFactory.executeAsync {
                            "Outer: ${prev}".toString()
                        }
                    }
                }
                
                fork("flow") {
                    from "inner"
                    to "outer-process"
                }
            }
            
            return outerGraph.run()
        }
        
        def promise = task.execute(ctx.promiseFactory.createPromise("original"))
        def result = awaitPromise(promise)
        
        assertEquals("Outer: Inner processed: original", result.toString())
    }

    @Test
    void testSubProcessWithSharedContext() {
        ctx.globals.set("sharedData", "parent-data")
        
        def task = new SubprocessTask("call", "Subprocess", ctx)
        task.subProcessProvider = { c, input ->
            c.promiseFactory.executeAsync {
                "subprocess sees: ${c.globals.get('sharedData')}".toString()
            }
        }
        
        def promise = task.execute(ctx.promiseFactory.createPromise(null))
        def result = awaitPromise(promise)
        
        assertEquals("subprocess sees: parent-data", result.toString())
    }
}
