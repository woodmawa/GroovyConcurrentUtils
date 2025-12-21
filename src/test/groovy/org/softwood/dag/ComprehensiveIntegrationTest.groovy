package org.softwood.dag

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import static org.junit.jupiter.api.Assertions.*

import org.awaitility.Awaitility
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.softwood.promise.Promise
import org.softwood.dag.task.*

/**
 * Comprehensive integration test demonstrating all TaskGraph features.
 */
class ComprehensiveIntegrationTest {

    private TaskContext ctx

    @BeforeEach
    void setup() {
        ctx = new TaskContext()
        SignalTask.clearAllSignals()
    }

    private static <T> T awaitPromise(Promise<T> p) {
        Awaitility.await()
                .atMost(10, TimeUnit.SECONDS)
                .until({ p.isDone() })
        return p.get()
    }

    @Test
    void testCompleteOrderProcessingWorkflow() {
        def results = [:]

        def graph = TaskGraph.build {

            serviceTask("receive-order") {
                action { ctx, _ ->
                    ctx.promiseFactory.executeAsync {
                        [orderId: "ORD-123", customerId: "CUST-456", amount: 15000,
                         items: ["laptop", "mouse"], customerTier: "gold", urgency: "high"]
                    }
                }
            }

            serviceTask("validate-order") {
                action { ctx, prev ->
                    results.orderReceived = prev.orderId
                    ctx.promiseFactory.executeAsync { prev + [validated: true] }
                }
            }

            fork("tier-routing") {
                from "validate-order"
                exclusiveGateway {
                    when { order -> order.customerTier == "gold" && order.amount > 10000 } route "vip-processing"
                    when { order -> order.amount > 5000 } route "priority-processing"
                    otherwise "standard-processing"
                }
            }

            serviceTask("vip-processing") {
                action { ctx, prev ->
                    results.processedAsVIP = true
                    ctx.promiseFactory.executeAsync { prev + [processingTier: "VIP"] }
                }
            }

            serviceTask("priority-processing") {
                action { ctx, prev ->
                    results.processedAsPriority = true
                    ctx.promiseFactory.executeAsync { prev + [processingTier: "PRIORITY"] }
                }
            }

            serviceTask("standard-processing") {
                action { ctx, prev ->
                    results.processedAsStandard = true
                    ctx.promiseFactory.executeAsync { prev + [processingTier: "STANDARD"] }
                }
            }

            join("after-tier-routing") {
                from "vip-processing", "priority-processing", "standard-processing"
                action { ctx, prev -> ctx.promiseFactory.executeAsync { prev } }
            }

            fork("parallel-processing") {
                from "after-tier-routing"
                to "check-inventory", "payment-processing"
            }

            serviceTask("check-inventory") {
                action { ctx, prev ->
                    Thread.sleep(50)
                    ctx.promiseFactory.executeAsync { [available: true, warehouse: "WH-01"] }
                }
            }

            task("payment-processing", TaskType.SUBGRAPH) {
                subGraph {
                    serviceTask("charge") {
                        action { ctx, prev ->
                            ctx.promiseFactory.executeAsync {
                                [transactionId: "TXN-123", status: "SUCCESS"]
                            }
                        }
                    }
                }
            }

            join("combine-results") {
                from "check-inventory", "payment-processing"
                action { ctx, joinResults ->
                    ctx.promiseFactory.executeAsync {
                        [inventoryAvailable: true, paymentConfirmed: true, transactionId: "TXN-123"]
                    }
                }
            }

            task("approval-required", TaskType.MANUAL) {
                title "Approve High-Value Order"
                description "Please review and approve"
                assignee "manager@company.com"
                priority Priority.HIGH

                form {
                    field "approved", [type: FormField.FieldType.BOOLEAN, required: true]
                    field "comments", [type: FormField.FieldType.TEXTAREA]
                }

                onSuccess { taskCtx ->
                    results.approvalReceived = taskCtx.formData.approved
                }
            }

            fork("to-approval") {
                from "combine-results"
                to "approval-required"
            }

            fork("urgency-routing") {
                from "approval-required"
                switchRouter {
                    switchOn { prev -> "high" }
                    case_("high") route "express-shipping"
                    case_("normal") route "standard-shipping"
                    default_ "standard-shipping"
                }
            }

            serviceTask("express-shipping") {
                action { ctx, prev ->
                    results.shippingMethod = "EXPRESS"
                    ctx.promiseFactory.executeAsync {
                        [shipped: true, method: "EXPRESS", trackingId: "TRACK-EXP-123"]
                    }
                }
            }

            serviceTask("standard-shipping") {
                action { ctx, prev ->
                    results.shippingMethod = "STANDARD"
                    ctx.promiseFactory.executeAsync {
                        [shipped: true, method: "STANDARD", trackingId: "TRACK-STD-456"]
                    }
                }
            }

            join("after-shipping") {
                from "express-shipping", "standard-shipping"
                action { ctx, prev -> ctx.promiseFactory.executeAsync { prev } }
            }

            task("send-completion-signal", TaskType.SIGNAL) {
                mode SignalMode.SEND
                signalName "order-shipped"
                payload { ctx, prev -> [orderId: "ORD-123", shipped: true] }
            }

            fork("signal-flow") {
                from "after-shipping"
                to "send-completion-signal"
            }

            serviceTask("finalize-order") {
                action { ctx, prev ->
                    results.orderCompleted = true
                    results.signalSent = true
                    ctx.promiseFactory.executeAsync { "Order ORD-123 completed successfully" }
                }
            }

            fork("to-finalize") {
                from "send-completion-signal"
                to "finalize-order"
            }

            fork("init") {
                from "receive-order"
                to "validate-order"
            }
        }

        def promise = graph.run()
        Thread.sleep(500)  // Give workflow time to start

        println "About to complete manual task..."
        def approvalTask = graph.tasks["approval-required"] as ManualTask
        println "Manual task state: ${approvalTask.state}"
        println "Manual task promise: ${approvalTask.completionPromise}"
        
        approvalTask.complete(
                outcome: CompletionOutcome.SUCCESS,
                formData: [approved: true, comments: "Approved"],
                completedBy: "manager@company.com"
        )
        
        println "Manual task completed"

        def result = awaitPromise(promise)

        assertEquals("ORD-123", results.orderReceived)
        assertTrue(results.processedAsVIP)
        assertTrue(results.approvalReceived)
        assertNotNull(results.shippingMethod)
        assertTrue(results.orderCompleted)
        assertTrue(results.signalSent)

        assertEquals(TaskState.COMPLETED, graph.tasks["receive-order"].state)
        assertEquals(TaskState.COMPLETED, graph.tasks["vip-processing"].state)
        assertEquals(TaskState.COMPLETED, graph.tasks["approval-required"].state)

        println "✅ Complete order processing workflow executed successfully!"
    }
}