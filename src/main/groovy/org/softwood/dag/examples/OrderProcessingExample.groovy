package org.softwood.dag.examples

import org.softwood.dag.TaskGraph
import org.softwood.dag.task.*
import java.time.Duration

/**
 * Example using ENHANCED ForkDsl with multi-source support.
 */
class OrderProcessingExample {

    static void main(String[] args) {
        println "=" * 80
        println "TaskGraph - Order Processing (Enhanced DSL)"
        println "=" * 80

        SignalTask.clearAllSignals()
        runWorkflow()
    }

    static void runWorkflow() {

        def graph = TaskGraph.build {

            // ============================================================
            // STEP 1: Order Reception
            // ============================================================
            serviceTask("receive-order") {
                action { ctx, _ ->
                    println "\n📦 STEP 1: Receiving Order"
                    ctx.promiseFactory.executeAsync {
                        [orderId: "ORD-123", customerName: "Alice Johnson",
                         customerTier: "gold", amount: 15000,
                         items: ["Laptop Pro", "Wireless Mouse"]]
                    }
                }
            }

            serviceTask("validate-order") {
                action { ctx, prev ->
                    println "  ✓ Order validated: ${prev.orderId}"
                    println "    Customer: ${prev.customerName} (${prev.customerTier})"
                    println "    Amount: \$${prev.amount}"
                    ctx.promiseFactory.executeAsync { prev + [validated: true] }
                }
            }

            // ============================================================
            // STEP 2: Customer Tier Routing
            // ============================================================
            serviceTask("vip-processing") {
                action { ctx, prev ->
                    println "\n👑 STEP 2: VIP Processing"
                    println "  • Dedicated account manager"
                    println "  • Express handling"
                    println "  • Bonus: +500 points"
                    ctx.promiseFactory.executeAsync { prev + [processingTier: "VIP", bonusPoints: 500] }
                }
            }

            serviceTask("standard-processing") {
                action { ctx, prev ->
                    println "\n📋 STEP 2: Standard Processing"
                    println "  • Standard queue"
                    ctx.promiseFactory.executeAsync { prev + [processingTier: "STANDARD", bonusPoints: 0] }
                }
            }

            // ============================================================
            // STEP 3: Parallel Processing (Inventory + Payment)
            // ============================================================
            serviceTask("check-inventory") {
                action { ctx, prev ->
                    println "\n📊 STEP 3a: Checking Inventory"
                    Thread.sleep(100)
                    println "  ✓ All items available in WH-01"
                    ctx.promiseFactory.executeAsync {
                        [available: true, warehouse: "WH-01"]
                    }
                }
            }

            task("payment-processing", TaskType.SUBGRAPH) {
                subGraph {
                    serviceTask("validate-card") {
                        action { ctx, prev ->
                            println "\n💳 STEP 3b: Payment Processing"
                            println "  • Validating payment card..."
                            ctx.promiseFactory.executeAsync { prev + [cardValid: true] }
                        }
                    }

                    serviceTask("charge-card") {
                        action { ctx, prev ->
                            println "  • Charging card for \$${prev.amount}..."
                            ctx.promiseFactory.executeAsync {
                                prev + [transactionId: "TXN-${System.currentTimeMillis()}",
                                        paymentStatus: "SUCCESS"]
                            }
                        }
                    }

                    fork("payment-flow") {
                        from "validate-card"
                        to "charge-card"
                    }
                }
            }

            // ============================================================
            // STEP 4: Manual Approval
            // ============================================================
            task("manager-approval", TaskType.MANUAL) {
                title "Approve High-Value Order"
                description "Review order ORD-123 for \$15,000"
                assignee "manager@company.com"
                priority Priority.HIGH

                form {
                    field "approved", [type: FormField.FieldType.BOOLEAN, required: true]
                    field "discount", [type: FormField.FieldType.NUMBER, min: 0, max: 20]
                    field "comments", [type: FormField.FieldType.TEXTAREA]
                }

                onSuccess { taskCtx ->
                    println "\n✅ STEP 4: Manager Approval"
                    println "  • Approved by: ${taskCtx.completedBy}"
                    if (taskCtx.formData.discount) {
                        println "  • Discount: ${taskCtx.formData.discount}%"
                    }
                }
            }

            // ============================================================
            // STEP 5: Shipping
            // ============================================================
            serviceTask("express-shipping") {
                action { ctx, prev ->
                    println "\n🚀 STEP 5: Express Shipping"
                    println "  • Method: Express (1-2 days)"
                    println "  • Tracking: TRACK-EXP-${System.currentTimeMillis()}"
                    ctx.promiseFactory.executeAsync {
                        [shipped: true, shippingMethod: "EXPRESS"]
                    }
                }
            }

            serviceTask("standard-shipping") {
                action { ctx, prev ->
                    println "\n📦 STEP 5: Standard Shipping"
                    println "  • Method: Standard (3-5 days)"
                    println "  • Tracking: TRACK-STD-${System.currentTimeMillis()}"
                    ctx.promiseFactory.executeAsync {
                        [shipped: true, shippingMethod: "STANDARD"]
                    }
                }
            }

            // ============================================================
            // STEP 6: Completion Signal
            // ============================================================
            task("send-signal", TaskType.SIGNAL) {
                mode SignalMode.SEND
                signalName "order-completed"
                payload { ctx, prev ->
                    [orderId: "ORD-123", status: "SHIPPED",
                     shippingMethod: prev.shippingMethod]
                }
            }

            serviceTask("finalize") {
                action { ctx, prev ->
                    println "\n🎉 STEP 6: Order Complete!"
                    println "  • Signal sent"
                    println "  • Order finalized"
                    ctx.promiseFactory.executeAsync { "Order ORD-123 completed!" }
                }
            }

            // ============================================================
            // WORKFLOW WIRING (Using Enhanced DSL)
            // ============================================================

            // Initial flow
            fork("init") {
                from "receive-order"
                to "validate-order"
            }

            // Tier routing with ExclusiveGateway
            fork("tier-routing") {
                from "validate-order"
                exclusiveGateway {
                    when { o -> o.customerTier == "gold" && o.amount > 10000 } route "vip-processing"
                    otherwise "standard-processing"
                }
            }

            // ENHANCED: Multiple sources with auto-join!
            fork("after-tier") {
                from "vip-processing", "standard-processing"  // Auto-creates join
                to "check-inventory", "payment-processing"    // Then fan-out
            }

            // ENHANCED: Merge parallel results with custom strategy
            fork("merge-parallel") {
                from "check-inventory", "payment-processing"  // Auto-join
                mergeWith { ctx, results ->
                    println "\n✓ Parallel processing complete"
                    println "  • Inventory: ${results[0]?.available ? 'Available' : 'N/A'}"
                    println "  • Payment: ${results[1]?.paymentStatus ?: 'N/A'}"
                    ctx.promiseFactory.executeAsync {
                        [inventoryOK: true, paymentOK: true]
                    }
                }
                to "manager-approval"
            }

            // Shipping routing
            fork("shipping-route") {
                from "manager-approval"
                switchRouter {
                    switchOn { prev -> "express" }
                    case_("express") route "express-shipping"
                    case_("standard") route "standard-shipping"
                    default_ "express-shipping"
                }
            }

            // ENHANCED: Merge shipping paths
            fork("after-shipping") {
                from "express-shipping", "standard-shipping"  // Auto-join
                to "send-signal"
            }

            // Final flow
            fork("complete") {
                from "send-signal"
                to "finalize"
            }
        }

        println "\n🚀 Starting workflow..."
        def promise = graph.run()

        Thread.sleep(500)
        println "\n⏳ Waiting for manager approval..."

        def approvalTask = graph.tasks["manager-approval"] as ManualTask
        approvalTask.complete(
                outcome: CompletionOutcome.SUCCESS,
                formData: [approved: true, discount: 5, comments: "Approved!"],
                completedBy: "manager@company.com"
        )

        def result = promise.get()

        println "\n" + "=" * 80
        println "WORKFLOW COMPLETE"
        println "=" * 80

        def stats = graph.tasks.values().groupBy { it.state }
        println "Statistics:"
        println "  • Completed: ${stats[TaskState.COMPLETED]?.size() ?: 0}"
        println "  • Skipped: ${stats[TaskState.SKIPPED]?.size() ?: 0}"
        println "  • Total tasks: ${graph.tasks.size()}"

        println "\nResult: $result"
        println "\n✅ Enhanced DSL demonstration complete!"
        println "=" * 80
    }
}