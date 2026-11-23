package org.softwood.dag.task.gateways

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.softwood.dag.TaskGraph

import static org.junit.jupiter.api.Assertions.*

import org.awaitility.Awaitility
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.softwood.promise.Promise
import org.softwood.dag.task.*

/**
 * Tests for EventGatewayTask - First-Event-Wins Pattern
 */
class EventGatewayTaskTest {

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
    void testTimeoutEvent() {
        def graph = TaskGraph.build {
            task("await", TaskType.EVENT_GATEWAY) {
                on("quick-timeout").after(Duration.ofMillis(100L)) {
                    [status: "timeout"]
                }
            }
        }
        
        def start = System.currentTimeMillis()
        def result = awaitPromise(graph.run())
        def elapsed = System.currentTimeMillis() - start
        
        assertNotNull(result)
        assertEquals("quick-timeout", result.event)
        assertEquals("timeout", result.data.status)
        assertTrue(elapsed >= 100)
        assertTrue(elapsed < 500)
    }

    @Test
    void testExternalEventTrigger() {
        def graph = TaskGraph.build {
            task("await", TaskType.EVENT_GATEWAY) {
                on("payment-received").trigger { data ->
                    [status: "paid", amount: data.amount]
                }
                on("timeout").after(Duration.ofSeconds(5L)) {
                    [status: "timeout"]
                }
            }
        }
        
        def promise = graph.run()
        
        Thread.startVirtualThread {
            Thread.sleep(200)
            EventGatewayTask.triggerEvent("payment-received", [amount: 100])
        }
        
        def result = awaitPromise(promise)
        
        assertNotNull(result)
        assertEquals("payment-received", result.event)
        assertEquals("paid", result.data.status)
        assertEquals(100, result.data.amount)
    }

    @Test
    void testFirstEventWins() {
        def graph = TaskGraph.build {
            task("await", TaskType.EVENT_GATEWAY) {
                on("event1").trigger { data ->
                    [from: "event1", data: data]
                }
                on("event2").trigger { data ->
                    [from: "event2", data: data]
                }
                on("timeout").after(Duration.ofSeconds(5L)) {
                    [from: "timeout"]
                }
            }
        }
        
        def promise = graph.run()
        
        Thread.startVirtualThread {
            Thread.sleep(100)
            EventGatewayTask.triggerEvent("event2", "second")
            Thread.sleep(50)
            EventGatewayTask.triggerEvent("event1", "first")
        }
        
        def result = awaitPromise(promise)
        
        assertNotNull(result)
        assertEquals("event2", result.event)
        assertEquals("event2", result.data.from)
        assertEquals("second", result.data.data)
    }

    @Test
    void testMultipleTimeouts() {
        def graph = TaskGraph.build {
            task("await", TaskType.EVENT_GATEWAY) {
                on("short-timeout").after(Duration.ofMillis(100L)) {
                    [order: 1]
                }
                on("medium-timeout").after(Duration.ofMillis(500L)) {
                    [order: 2]
                }
                on("long-timeout").after(Duration.ofSeconds(2L)) {
                    [order: 3]
                }
            }
        }
        
        def start = System.currentTimeMillis()
        def result = awaitPromise(graph.run())
        def elapsed = System.currentTimeMillis() - start
        
        assertNotNull(result)
        assertEquals("short-timeout", result.event)
        assertEquals(1, result.data.order)
        assertTrue(elapsed >= 100 && elapsed < 400)
    }

    @Test
    void testEventVsTimeout() {
        def graph = TaskGraph.build {
            task("await", TaskType.EVENT_GATEWAY) {
                on("quick-event").trigger { data ->
                    [status: "event-received", data: data]
                }
                on("timeout").after(Duration.ofSeconds(1L)) {
                    [status: "timeout"]
                }
            }
        }
        
        def promise = graph.run()
        
        Thread.startVirtualThread {
            Thread.sleep(100)
            EventGatewayTask.triggerEvent("quick-event", "data")
        }
        
        def result = awaitPromise(promise)
        
        assertEquals("quick-event", result.event)
        assertEquals("event-received", result.data.status)
    }

    @Test
    void testTimeoutVsEvent() {
        def graph = TaskGraph.build {
            task("await", TaskType.EVENT_GATEWAY) {
                on("slow-event").trigger { data ->
                    [status: "event-received"]
                }
                on("timeout").after(Duration.ofMillis(100L)) {
                    [status: "timeout"]
                }
            }
        }
        
        def promise = graph.run()
        
        Thread.startVirtualThread {
            Thread.sleep(500)
            EventGatewayTask.triggerEvent("slow-event", "data")
        }
        
        def result = awaitPromise(promise)
        
        assertEquals("timeout", result.event)
        assertEquals("timeout", result.data.status)
    }

    @Test
    void testPaymentFlow() {
        def graph = TaskGraph.build {
            serviceTask("init-payment") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        [orderId: "ORD-123", amount: 100]
                    }
                }
            }
            
            task("await-payment", TaskType.EVENT_GATEWAY) {
                on("payment-received").trigger { webhook ->
                    [status: "paid", webhookData: webhook]
                }
                on("payment-cancelled").trigger { notification ->
                    [status: "cancelled", reason: notification.reason]
                }
                on("payment-timeout").after(Duration.ofSeconds(2L)) {
                    [status: "timeout"]
                }
            }
            
            fork("flow") {
                from "init-payment"
                to "await-payment"
            }
        }
        
        def promise = graph.run()
        
        Thread.startVirtualThread {
            Thread.sleep(200)
            EventGatewayTask.triggerEvent("payment-received", [
                transactionId: "TXN-456",
                amount: 100
            ])
        }
        
        def result = awaitPromise(promise)
        
        assertEquals("payment-received", result.event)
        assertEquals("paid", result.data.status)
        assertEquals("TXN-456", result.data.webhookData.transactionId)
    }

    @Test
    void testApprovalProcess() {
        def graph = TaskGraph.build {
            task("await-approval", TaskType.EVENT_GATEWAY) {
                on("manager-approved").trigger { approval ->
                    [
                        approved: true,
                        by: approval.managerId,
                        timestamp: approval.timestamp
                    ]
                }
                on("manager-rejected").trigger { rejection ->
                    [
                        approved: false,
                        reason: rejection.reason
                    ]
                }
                on("approval-timeout").after(Duration.ofSeconds(1L)) {
                    [
                        approved: false,
                        reason: "timeout"
                    ]
                }
            }
        }
        
        def promise = graph.run()
        
        Thread.startVirtualThread {
            Thread.sleep(100)
            EventGatewayTask.triggerEvent("manager-approved", [
                managerId: "MGR-001",
                timestamp: System.currentTimeMillis()
            ])
        }
        
        def result = awaitPromise(promise)
        
        assertEquals("manager-approved", result.event)
        assertTrue(result.data.approved)
        assertEquals("MGR-001", result.data.by)
    }

    @Test
    void testEmptyTriggers() {
        def gateway = new EventGatewayTask("gateway", "Gateway", ctx)
        
        def promise = gateway.execute(ctx.promiseFactory.createPromise(null))
        
        assertThrows(Exception) {
            awaitPromise(promise)
        }
    }

    @Test
    void testMissingTriggerAndAfter() {
        def gateway = new EventGatewayTask("gateway", "Gateway", ctx)
        
        assertThrows(IllegalArgumentException) {
            gateway.on("event", [:])
        }
    }

    @Test
    void testEventHandlerError() {
        def graph = TaskGraph.build {
            task("await", TaskType.EVENT_GATEWAY) {
                on("error-event").trigger { data ->
                    throw new RuntimeException("Handler error")
                }
                on("timeout").after(Duration.ofSeconds(2L)) {
                    [status: "timeout"]
                }
            }
        }
        
        def promise = graph.run()
        
        Thread.startVirtualThread {
            Thread.sleep(100)
            EventGatewayTask.triggerEvent("error-event", "data")
        }
        
        def result = awaitPromise(promise)
        
        assertEquals("error-event", result.event)
        assertNotNull(result.data.error)
    }

    @Test
    void testWithConditionalRouting() {
        def graph = TaskGraph.build {
            task("await-payment", TaskType.EVENT_GATEWAY) {
                on("payment-received").trigger { data ->
                    [status: "paid", amount: data.amount]
                }
                on("payment-cancelled").trigger { data ->
                    [status: "cancelled"]
                }
                on("timeout").after(Duration.ofSeconds(2L)) {
                    [status: "timeout"]
                }
            }
            
            task("route-result", TaskType.EXCLUSIVE_GATEWAY) {
                when { r -> r.data.status == "paid" } route "fulfill-order"
                when { r -> r.data.status == "cancelled" } route "cancel-order"
                when { r -> r.data.status == "timeout" } route "notify-timeout"
                otherwise "notify-timeout"  // Default fallback
            }
            
            serviceTask("fulfill-order") {
                action { ctx, prev -> ctx.promiseFactory.executeAsync { "Order fulfilled" } }
            }
            serviceTask("cancel-order") {
                action { ctx, prev -> ctx.promiseFactory.executeAsync { "Order cancelled" } }
            }
            serviceTask("notify-timeout") {
                action { ctx, prev -> ctx.promiseFactory.executeAsync { "Timeout notification sent" } }
            }
            
            fork("flow1") {
                from "await-payment"
                to "route-result"
            }
        }
        
        def promise = graph.run()
        
        Thread.startVirtualThread {
            Thread.sleep(100)
            EventGatewayTask.triggerEvent("payment-received", [amount: 100])
        }
        
        def result = awaitPromise(promise)
        
        // Result is a list structure: [[routing-decision], [outputs...]]
        // Flatten and check that "Order fulfilled" is in the results
        def resultStr = result.toString()
        assertTrue(resultStr.contains("Order fulfilled"), 
            "Expected result to contain 'Order fulfilled', but got: $resultStr")
    }

    @Test
    void testDefaultHandlerWithNoTrigger() {
        def graph = TaskGraph.build {
            task("await", TaskType.EVENT_GATEWAY) {
                on("quick-timeout").after(Duration.ofMillis(100L)) {
                    [:] // Return empty map as default
                }
            }
        }
        
        def result = awaitPromise(graph.run())
        
        assertNotNull(result)
        assertEquals("quick-timeout", result.event)
        // Default handler returns empty map
        assertNotNull(result.data)
    }
}
