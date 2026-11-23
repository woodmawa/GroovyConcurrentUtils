package org.softwood.dag.examples

import org.softwood.dag.*
import org.softwood.dag.task.*
import java.time.Duration

/**
 * Example Scenarios for New Task Types
 * 
 * Demonstrates practical usage patterns for:
 * - TimerTask (periodic/scheduled execution)
 * - BusinessRuleTask (condition-based reactive execution)
 * - CallActivityTask (subprocess invocation)
 * - TasksCollection (loose task coordination)
 */
class NewTaskTypeExamples {

    // =========================================================================
    // Example 1: Polling with TimerTask
    // =========================================================================
    
    static void example1_PollingStatusCheck() {
        println "\n=== Example 1: Polling Status Check ==="
        
        def graph = TaskGraph.build {
            globals {
                statusCheckCount = 0
                systemReady = false
            }
            
            timer("pollStatus") {
                interval Duration.ofSeconds(2)
                maxExecutions 10  // Safety limit
                
                action { ctx ->
                    ctx.globals.statusCheckCount++
                    println "Checking status... (attempt ${ctx.globals.statusCheckCount})"
                    
                    // Simulate status check
                    def status = [
                        ready: ctx.globals.statusCheckCount >= 3,
                        services: ctx.globals.statusCheckCount,
                        uptime: ctx.globals.statusCheckCount * 2
                    ]
                    
                    if (status.ready) {
                        ctx.globals.systemReady = true
                        return TimerControl.stop(status)
                    }
                    
                    return TimerControl.continueWith(status)
                }
            }
            
            serviceTask("processWhenReady") {
                action { ctx, timerResult ->
                    ctx.promiseFactory.executeAsync {
                        println "System ready after ${timerResult.executionCount} checks!"
                        println "Final status: ${timerResult.lastResult}"
                        "Processing started"
                    }
                }
            }
            
            fork("flow") {
                from "pollStatus"
                to "processWhenReady"
            }
        }
        
        def result = graph.run().get()
        println "Result: $result"
    }

    // =========================================================================
    // Example 2: Data Collection with TimerTask Accumulator
    // =========================================================================
    
    static void example2_SensorDataCollection() {
        println "\n=== Example 2: Sensor Data Collection ==="
        
        def graph = TaskGraph.build {
            timer("collectSamples") {
                interval Duration.ofMillis(100)
                maxExecutions 10
                
                accumulate { ctx, acc, sample ->
                    acc.samples = acc.samples ?: []
                    acc.samples << sample
                    acc.min = acc.samples.min()
                    acc.max = acc.samples.max()
                    acc.avg = acc.samples.sum() / acc.samples.size()
                    return acc
                }
                
                action { ctx ->
                    // Simulate sensor reading
                    def reading = 20 + (Math.random() * 10).round(2)
                    println "Sample: ${reading}°C"
                    return reading
                }
            }
            
            serviceTask("analyzeSamples") {
                action { ctx, timerResult ->
                    ctx.promiseFactory.executeAsync {
                        def acc = timerResult.accumulator
                        println "\nAnalysis Results:"
                        println "  Samples collected: ${acc.samples.size()}"
                        println "  Min: ${acc.min}°C"
                        println "  Max: ${acc.max}°C"
                        println "  Average: ${acc.avg.round(2)}°C"
                        "Analysis complete"
                    }
                }
            }
            
            fork("flow") {
                from "collectSamples"
                to "analyzeSamples"
            }
        }
        
        graph.run().get()
    }

    // =========================================================================
    // Example 3: BusinessRuleTask for Approval Workflow
    // =========================================================================
    
    static void example3_ApprovalWorkflow() {
        println "\n=== Example 3: Approval Workflow ==="
        
        def graph = TaskGraph.build {
            globals {
                requests = []
            }
            
            serviceTask("submitRequest") {
                action { ctx, prev ->
                    def request = [
                        id: "REQ-001",
                        amount: 750,
                        submittedBy: "user123",
                        timestamp: System.currentTimeMillis()
                    ]
                    println "Submitting request: ${request.id} for \$${request.amount}"
                    
                    // Send signal to trigger rule
                    SignalTask.sendSignalGlobal("approval-request", request)
                    
                    ctx.promiseFactory.executeAsync { request }
                }
            }
            
            businessRule("autoApproval") {
                when {
                    signal "approval-request"
                }
                
                evaluate { ctx, request ->
                    println "Evaluating request ${request.id}..."
                    request.amount < 1000
                }
                
                action { ctx, request ->
                    println "✓ AUTO-APPROVED: Request ${request.id} for \$${request.amount}"
                    [
                        requestId: request.id,
                        status: "approved",
                        approvedBy: "system",
                        autoApproved: true
                    ]
                }
                
                onFalse { ctx, request ->
                    println "✗ REQUIRES MANUAL REVIEW: Request ${request.id} for \$${request.amount}"
                }
            }
            
            serviceTask("processApproval") {
                action { ctx, ruleResult ->
                    ctx.promiseFactory.executeAsync {
                        if (ruleResult.ruleResult) {
                            "Request ${ruleResult.actionResult.requestId} approved and processed"
                        } else {
                            "Request sent for manual review"
                        }
                    }
                }
            }
            
            fork("flow") {
                from "submitRequest", "autoApproval"
                to "processApproval"
            }
        }
        
        def result = graph.run().get()
        println "Result: $result"
    }

    // =========================================================================
    // Example 4: CallActivityTask for Subprocess Composition
    // =========================================================================
    
    static void example4_OrderProcessing() {
        println "\n=== Example 4: Order Processing with Subprocess ==="
        
        def graph = TaskGraph.build {
            globals {
                orderData = [
                    id: "ORDER-123",
                    items: [
                        [name: "Widget", qty: 2, price: 19.99],
                        [name: "Gadget", qty: 1, price: 49.99]
                    ],
                    customer: [id: "CUST-456", tier: "gold"]
                ]
            }
            
            serviceTask("validateOrder") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        println "Validating order ${ctx.globals.orderData.id}..."
                        ctx.globals.orderData.validated = true
                        "validated"
                    }
                }
            }
            
            callActivity("processPayment") {
                input { ctx ->
                    def order = ctx.globals.orderData
                    def total = order.items.sum { it.qty * it.price }
                    [
                        orderId: order.id,
                        customerId: order.customer.id,
                        amount: total
                    ]
                }
                
                subProcess { ctx, input ->
                    println "Processing payment subprocess..."
                    
                    // Subprocess: Payment processing workflow
                    def paymentGraph = TaskGraph.build {
                        serviceTask("checkFunds") {
                            action { c, p ->
                                c.promiseFactory.executeAsync {
                                    println "  Checking funds for \$${input.amount}..."
                                    [sufficient: true]
                                }
                            }
                        }
                        
                        serviceTask("chargeCard") {
                            action { c, p ->
                                c.promiseFactory.executeAsync {
                                    println "  Charging card..."
                                    [
                                        transactionId: "TXN-${UUID.randomUUID().toString().take(8)}",
                                        amount: input.amount,
                                        status: "success"
                                    ]
                                }
                            }
                        }
                        
                        fork("payment-flow") {
                            from "checkFunds"
                            to "chargeCard"
                        }
                    }
                    
                    return paymentGraph.run()
                }
                
                output { result ->
                    println "Payment completed: ${result.transactionId}"
                    result
                }
                
                timeout Duration.ofSeconds(30)
            }
            
            serviceTask("fulfillOrder") {
                action { ctx, paymentResult ->
                    ctx.promiseFactory.executeAsync {
                        println "Fulfilling order..."
                        [
                            orderId: ctx.globals.orderData.id,
                            payment: paymentResult,
                            status: "fulfilled"
                        ]
                    }
                }
            }
            
            fork("flow") {
                from "validateOrder"
                to "processPayment"
            }
            
            fork("flow2") {
                from "processPayment"
                to "fulfillOrder"
            }
        }
        
        def result = graph.run().get()
        println "Final Result: ${result.status} - Order ${result.orderId}"
    }

    // =========================================================================
    // Example 5: Event-Driven System with TasksCollection
    // =========================================================================
    
    static void example5_EventDrivenMonitoring() {
        println "\n=== Example 5: Event-Driven Monitoring System ==="
        
        def tasks = new TasksCollection()
        
        // Heartbeat timer
        tasks.timer("heartbeat") {
            interval Duration.ofSeconds(2)
            maxExecutions 5
            
            action { ctx ->
                def beat = [
                    timestamp: System.currentTimeMillis(),
                    sequence: (ctx.globals.sequence ?: 0) + 1
                ]
                ctx.globals.sequence = beat.sequence
                
                println "💓 Heartbeat #${beat.sequence}"
                SignalTask.sendSignalGlobal("heartbeat", beat)
                
                return beat
            }
        }
        
        // Monitor that watches heartbeats
        tasks.businessRule("healthMonitor") {
            when {
                signal "heartbeat"
            }
            
            evaluate { ctx, beat ->
                def now = System.currentTimeMillis()
                def age = now - beat.timestamp
                age < 5000  // Healthy if less than 5 seconds old
            }
            
            action { ctx, beat ->
                println "✓ System healthy (heartbeat #${beat.sequence})"
            }
            
            onFalse { ctx, beat ->
                println "⚠ WARNING: Heartbeat too old!"
                SignalTask.sendSignalGlobal("alert", [type: "stale-heartbeat"])
            }
        }
        
        // Alert handler
        tasks.businessRule("alertHandler") {
            when {
                signal "alert"
            }
            
            action { ctx, alert ->
                println "🚨 ALERT: ${alert.type}"
                ctx.globals.alerts = (ctx.globals.alerts ?: 0) + 1
            }
        }
        
        // Start the system
        println "Starting monitoring system..."
        tasks.start()
        
        // Let it run
        Thread.sleep(12000)
        
        // Stop
        println "\nStopping monitoring system..."
        tasks.stop()
        
        println "\nSystem Statistics:"
        println "  Total tasks: ${tasks.taskCount}"
        println "  Active: ${tasks.activeCount}"
        println "  Completed: ${tasks.completedCount}"
        println "  Alerts raised: ${tasks.ctx.globals.alerts ?: 0}"
    }

    // =========================================================================
    // Example 6: Complex Workflow Composition
    // =========================================================================
    
    static void example6_ComplexWorkflow() {
        println "\n=== Example 6: Complex Workflow Composition ==="
        
        def graph = TaskGraph.build {
            globals {
                workflowData = [
                    batchId: "BATCH-001",
                    items: 100,
                    processed: 0
                ]
            }
            
            // Timer to process items in batches
            timer("batchProcessor") {
                interval Duration.ofMillis(500)
                maxExecutions 10
                
                accumulate { ctx, acc, result ->
                    acc.totalProcessed = (acc.totalProcessed ?: 0) + (result?.processed ?: 0)
                    acc.batches = (acc.batches ?: 0) + 1
                    return acc
                }
                
                action { ctx ->
                    def batchSize = 10
                    def remaining = ctx.globals.workflowData.items - ctx.globals.workflowData.processed
                    def toProcess = Math.min(batchSize, remaining)
                    
                    if (toProcess > 0) {
                        ctx.globals.workflowData.processed += toProcess
                        println "Processed batch of ${toProcess} items (total: ${ctx.globals.workflowData.processed})"
                        return [processed: toProcess]
                    } else {
                        println "All items processed!"
                        return TimerControl.stop([processed: 0, complete: true])
                    }
                }
            }
            
            // Validation subprocess
            callActivity("validate") {
                input { ctx ->
                    ctx.globals.workflowData
                }
                
                subProcess { ctx, input ->
                    ctx.promiseFactory.executeAsync {
                        println "Validating batch ${input.batchId}..."
                        [valid: input.processed == input.items]
                    }
                }
                
                timeout Duration.ofSeconds(5)
            }
            
            // Business rule to check completion
            businessRule("completionCheck") {
                when {
                    poll Duration.ofMillis(100)
                    condition { ctx ->
                        ctx.globals.workflowData.processed >= ctx.globals.workflowData.items
                    }
                }
                
                action { ctx, data ->
                    println "Batch processing complete!"
                    "complete"
                }
            }
            
            serviceTask("finalize") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        println "\nWorkflow Summary:"
                        println "  Batch ID: ${ctx.globals.workflowData.batchId}"
                        println "  Items: ${ctx.globals.workflowData.items}"
                        println "  Processed: ${ctx.globals.workflowData.processed}"
                        "workflow-complete"
                    }
                }
            }
            
            fork("flow") {
                from "batchProcessor", "validate", "completionCheck"
                to "finalize"
            }
        }
        
        def result = graph.run().get()
        println "Final Result: $result"
    }

    // =========================================================================
    // Main - Run All Examples
    // =========================================================================
    
    static void main(String[] args) {
        println "╔════════════════════════════════════════════════════════════════╗"
        println "║         New Task Types - Example Scenarios                    ║"
        println "╚════════════════════════════════════════════════════════════════╝"
        
        try {
            example1_PollingStatusCheck()
            Thread.sleep(1000)
            
            example2_SensorDataCollection()
            Thread.sleep(1000)
            
            example3_ApprovalWorkflow()
            Thread.sleep(1000)
            
            example4_OrderProcessing()
            Thread.sleep(1000)
            
            example5_EventDrivenMonitoring()
            Thread.sleep(1000)
            
            example6_ComplexWorkflow()
            
            println "\n╔════════════════════════════════════════════════════════════════╗"
            println "║         All examples completed successfully!                  ║"
            println "╚════════════════════════════════════════════════════════════════╝"
            
        } catch (Exception e) {
            println "\n✗ Error running examples:"
            e.printStackTrace()
        }
    }
}
