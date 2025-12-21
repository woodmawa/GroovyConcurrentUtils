package org.softwood.dag

import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

import org.awaitility.Awaitility
import java.util.concurrent.TimeUnit
import org.softwood.promise.Promise
import org.softwood.dag.task.*

/**
 * Integration tests for ExclusiveGateway and SwitchRouter
 * using the enhanced ForkDSL syntax.
 */
class NewRouterIntegrationTest {

    private static <T> T awaitPromise(Promise<T> p) {
        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .until({ p.isDone() })
        return p.get()
    }

    @Test
    void testExclusiveGatewayInWorkflow() {
        // Test: Order processing workflow with XOR gateway
        def results = [:]

        def graph = TaskGraph.build {
            serviceTask("validate-order") {
                action { ctx, _ ->
                    // Simulate order validation
                    ctx.promiseFactory.executeAsync {
                        [orderId: 123, value: 15000, customer: [tier: "gold"]]
                    }
                }
            }

            serviceTask("vip-processing") {
                action { ctx, order ->
                    results.vip = true
                    ctx.promiseFactory.executeAsync { "VIP order processed: ${order.orderId}" }
                }
            }

            serviceTask("bulk-processing") {
                action { ctx, order ->
                    results.bulk = true
                    ctx.promiseFactory.executeAsync { "Bulk order processed: ${order.orderId}" }
                }
            }

            serviceTask("standard-processing") {
                action { ctx, order ->
                    results.standard = true
                    ctx.promiseFactory.executeAsync { "Standard order processed: ${order.orderId}" }
                }
            }

            // ✨ NEW ENHANCED DSL: exclusiveGateway
            fork("order-routing") {
                from "validate-order"
                exclusiveGateway {
                    when { order -> order.value > 10000 && order.customer.tier == "gold" } route "vip-processing"
                    when { order -> order.value > 5000 } route "bulk-processing"
                    otherwise "standard-processing"
                }
            }
        }

        awaitPromise(graph.run())

        // Verify: Only VIP path executed
        assertTrue(results.vip)
        assertFalse(results.containsKey("bulk"))
        assertFalse(results.containsKey("standard"))

        // Verify: Non-selected paths are skipped
        assertEquals(TaskState.COMPLETED, graph.tasks["vip-processing"].state)
        assertEquals(TaskState.SKIPPED, graph.tasks["bulk-processing"].state)
        assertEquals(TaskState.SKIPPED, graph.tasks["standard-processing"].state)
    }

    @Test
    void testSwitchRouterInWorkflow() {
        // Test: Ticket routing workflow with switch/case
        def results = [:]

        def graph = TaskGraph.build {
            serviceTask("receive-ticket") {
                action { ctx, _ ->
                    ctx.promiseFactory.executeAsync {
                        [id: 456, status: "ESCALATED", priority: 1]
                    }
                }
            }

            serviceTask("triage") {
                action { ctx, ticket ->
                    results.triage = true
                    ctx.promiseFactory.executeAsync { "Triaged: ${ticket.id}" }
                }
            }

            serviceTask("senior-support") {
                action { ctx, ticket ->
                    results.senior = true
                    ctx.promiseFactory.executeAsync { "Escalated to senior support: ${ticket.id}" }
                }
            }

            serviceTask("wait-for-customer") {
                action { ctx, ticket ->
                    results.waiting = true
                    ctx.promiseFactory.executeAsync { "Waiting: ${ticket.id}" }
                }
            }

            serviceTask("archive") {
                action { ctx, ticket ->
                    results.archive = true
                    ctx.promiseFactory.executeAsync { "Archived: ${ticket.id}" }
                }
            }

            serviceTask("error-handler") {
                action { ctx, ticket ->
                    results.error = true
                    ctx.promiseFactory.executeAsync { "Unknown status: ${ticket.id}" }
                }
            }

            // ✨ NEW ENHANCED DSL: switchRouter
            fork("ticket-routing") {
                from "receive-ticket"
                switchRouter {
                    switchOn { ticket -> ticket.status }
                    case_("NEW") route "triage"
                    case_("ESCALATED") route "senior-support"
                    case_("PENDING") route "wait-for-customer"
                    case_(["RESOLVED", "CLOSED"]) route "archive"
                    default_ "error-handler"
                }
            }
        }

        awaitPromise(graph.run())

        // Verify: Only senior-support path executed
        assertTrue(results.senior)
        assertFalse(results.containsKey("triage"))
        assertFalse(results.containsKey("waiting"))
        assertFalse(results.containsKey("archive"))
        assertFalse(results.containsKey("error"))

        // Verify states
        assertEquals(TaskState.COMPLETED, graph.tasks["senior-support"].state)
        assertEquals(TaskState.SKIPPED, graph.tasks["triage"].state)
        assertEquals(TaskState.SKIPPED, graph.tasks["error-handler"].state)
    }

    @Test
    void testCombinedRoutersInComplexWorkflow() {
        // Test: Complex workflow combining both router types
        def executionLog = []

        def graph = TaskGraph.build {
            serviceTask("start") {
                action { ctx, _ ->
                    executionLog << "start"
                    ctx.promiseFactory.executeAsync {
                        [type: "order", value: 8000, status: "NEW"]
                    }
                }
            }

            serviceTask("validate-order") {
                action { ctx, data ->
                    executionLog << "validate-order"
                    ctx.promiseFactory.executeAsync { data }
                }
            }

            serviceTask("validate-refund") {
                action { ctx, data ->
                    executionLog << "validate-refund"
                    ctx.promiseFactory.executeAsync { data }
                }
            }

            serviceTask("reject") {
                action { ctx, data ->
                    executionLog << "reject"
                    ctx.promiseFactory.executeAsync { "rejected" }
                }
            }

            serviceTask("premium") {
                action { ctx, order ->
                    executionLog << "premium"
                    ctx.promiseFactory.executeAsync { "premium-${order.status}" }
                }
            }

            serviceTask("standard") {
                action { ctx, order ->
                    executionLog << "standard"
                    ctx.promiseFactory.executeAsync { "standard-${order.status}" }
                }
            }

            serviceTask("basic") {
                action { ctx, order ->
                    executionLog << "basic"
                    ctx.promiseFactory.executeAsync { "basic-${order.status}" }
                }
            }

            // ✨ First router: switch on type
            fork("type-routing") {
                from "start"
                switchRouter {
                    switchOn { it.type }
                    case_("order") route "validate-order"
                    case_("refund") route "validate-refund"
                    default_ "reject"
                }
            }

            // ✨ Second router: XOR on value (only for orders)
            fork("value-routing") {
                from "validate-order"
                exclusiveGateway {
                    when { order -> order.value > 10000 } route "premium"
                    when { order -> order.value > 1000 } route "standard"
                    otherwise "basic"
                }
            }
        }

        awaitPromise(graph.run())

        // Verify execution path: start -> validate-order -> standard
        assertEquals(["start", "validate-order", "standard"], executionLog)

        // Verify states
        assertEquals(TaskState.COMPLETED, graph.tasks["validate-order"].state)
        assertEquals(TaskState.SKIPPED, graph.tasks["validate-refund"].state)
        assertEquals(TaskState.SKIPPED, graph.tasks["reject"].state)
        assertEquals(TaskState.COMPLETED, graph.tasks["standard"].state)
        assertEquals(TaskState.SKIPPED, graph.tasks["premium"].state)
        assertEquals(TaskState.SKIPPED, graph.tasks["basic"].state)
    }

    @Test
    void testExclusiveGatewayWithDefaultPath() {
        // Test: Ensure 'otherwise' path works correctly
        def results = []

        def graph = TaskGraph.build {
            serviceTask("input") {
                action { ctx, _ ->
                    ctx.promiseFactory.executeAsync { [score: 45] }
                }
            }

            ["a", "b", "c", "d", "f"].each { letter ->
                serviceTask("grade-${letter}") {
                    action { ctx, data ->
                        results << letter
                        ctx.promiseFactory.executeAsync { letter }
                    }
                }
            }

            // ✨ XOR Gateway for grading
            fork("grading") {
                from "input"
                exclusiveGateway {
                    when { it.score >= 90 } route "grade-a"
                    when { it.score >= 80 } route "grade-b"
                    when { it.score >= 70 } route "grade-c"
                    when { it.score >= 60 } route "grade-d"
                    otherwise "grade-f"
                }
            }
        }

        awaitPromise(graph.run())

        // Verify: Only F grade executed (score 45 < 60)
        assertEquals(["f"], results)
        assertEquals(TaskState.COMPLETED, graph.tasks["grade-f"].state)
        assertEquals(TaskState.SKIPPED, graph.tasks["grade-a"].state)
    }
}
