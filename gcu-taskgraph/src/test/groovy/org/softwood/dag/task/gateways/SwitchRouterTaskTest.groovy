package org.softwood.dag.task.gateways

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import static org.junit.jupiter.api.Assertions.*

import org.awaitility.Awaitility
import java.util.concurrent.TimeUnit
import org.softwood.promise.Promise
import org.softwood.dag.task.*

/**
 * Tests for SwitchRouterTask (Switch/Case Router)
 */
class SwitchRouterTaskTest {

    private TaskContext ctx

    @BeforeEach
    void setup() {
        ctx = new TaskContext()
    }

    private static <T> T awaitPromise(Promise<T> p) {
        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .until({ p.isDone() })
        return p.get()
    }

    @Test
    void testBasicSwitchRouting() {
        def switcher = new SwitchRouterTask("switch1", "Test Switch", ctx)
        
        switcher.switchOn { it.status }
        switcher.case_("NEW") route "new-handler"
        switcher.case_("PENDING") route "pending-handler"
        switcher.case_("APPROVED") route "approved-handler"
        switcher.default_ "unknown-handler"

        // Test: NEW status
        def ticket1 = [status: "NEW", id: 123]
        def result1 = awaitPromise(switcher.execute(ctx.promiseFactory.createPromise(ticket1)))
        assertEquals(["new-handler"], result1)

        // Reset
        switcher.state = TaskState.SCHEDULED
        switcher.alreadyRouted = false
        switcher.lastSelectedTargets = null

        // Test: APPROVED status
        def ticket2 = [status: "APPROVED", id: 456]
        def result2 = awaitPromise(switcher.execute(ctx.promiseFactory.createPromise(ticket2)))
        assertEquals(["approved-handler"], result2)
    }

    @Test
    void testMultipleValuesPerCase() {
        def switcher = new SwitchRouterTask("switch2", "Multi-value Switch", ctx)
        
        switcher.switchOn { it.status }
        switcher.case_(["RESOLVED", "CLOSED", "ARCHIVED"]) route "archive-handler"
        switcher.case_(["NEW", "OPEN"]) route "active-handler"
        switcher.default_ "unknown-handler"

        // Test: RESOLVED matches first case
        def ticket1 = [status: "RESOLVED"]
        def result1 = awaitPromise(switcher.execute(ctx.promiseFactory.createPromise(ticket1)))
        assertEquals(["archive-handler"], result1)

        // Reset
        switcher.state = TaskState.SCHEDULED
        switcher.alreadyRouted = false
        switcher.lastSelectedTargets = null

        // Test: OPEN matches second case
        def ticket2 = [status: "OPEN"]
        def result2 = awaitPromise(switcher.execute(ctx.promiseFactory.createPromise(ticket2)))
        assertEquals(["active-handler"], result2)
    }

    @Test
    void testDefaultCase() {
        def switcher = new SwitchRouterTask("switch3", "Default Test", ctx)
        
        switcher.switchOn { it.type }
        switcher.case_("ORDER") route "order-handler"
        switcher.case_("REFUND") route "refund-handler"
        switcher.default_ "generic-handler"

        // Test: unknown type uses default
        def data = [type: "INQUIRY"]
        def result = awaitPromise(switcher.execute(ctx.promiseFactory.createPromise(data)))
        assertEquals(["generic-handler"], result)
    }

    @Test
    void testNoMatchWithoutDefaultThrows() {
        def switcher = new SwitchRouterTask("switch4", "No Default Test", ctx)
        
        switcher.switchOn { it.value }
        switcher.case_("A") route "path-a"
        switcher.case_("B") route "path-b"
        // No default

        // Test: unmatched value should throw
        assertThrows(IllegalStateException) {
            awaitPromise(switcher.execute(ctx.promiseFactory.createPromise([value: "C"])))
        }
    }

    @Test
    void testMissingValueExtractorThrows() {
        def switcher = new SwitchRouterTask("switch5", "No Extractor", ctx)
        
        switcher.case_("VALUE") route "path"
        switcher.default_ "default"
        // No switchOn() call

        // Test: should throw because valueExtractor not set
        assertThrows(IllegalStateException) {
            awaitPromise(switcher.execute(ctx.promiseFactory.createPromise(null)))
        }
    }

    @Test
    void testNumericSwitching() {
        def switcher = new SwitchRouterTask("switch6", "Numeric Switch", ctx)
        
        switcher.switchOn { it.priority }
        switcher.case_(1) route "p1-handler"
        switcher.case_(2) route "p2-handler"
        switcher.case_(3) route "p3-handler"
        switcher.default_ "default-priority"

        // Test: priority 2
        def task = [priority: 2, name: "Fix bug"]
        def result = awaitPromise(switcher.execute(ctx.promiseFactory.createPromise(task)))
        assertEquals(["p2-handler"], result)
    }

    @Test
    void testTargetIdsPopulated() {
        def switcher = new SwitchRouterTask("switch7", "Target IDs Test", ctx)
        
        switcher.switchOn { it }
        switcher.case_("A") route "handler-a"
        switcher.case_("B") route "handler-b"
        switcher.case_(["C", "D"]) route "handler-cd"
        switcher.default_ "default-handler"

        // Verify all targets are registered
        assertTrue(switcher.targetIds.contains("handler-a"))
        assertTrue(switcher.targetIds.contains("handler-b"))
        assertTrue(switcher.targetIds.contains("handler-cd"))
        assertTrue(switcher.targetIds.contains("default-handler"))
    }

    @Test
    void testComplexValueExtraction() {
        def switcher = new SwitchRouterTask("switch8", "Complex Extraction", ctx)
        
        // Extract nested value
        switcher.switchOn { order -> order.customer.tier.toUpperCase() }
        switcher.case_("GOLD") route "vip-processing"
        switcher.case_("SILVER") route "standard-processing"
        switcher.default_ "basic-processing"

        // Test
        def order = [
            customer: [tier: "gold"],
            value: 500
        ]
        def result = awaitPromise(switcher.execute(ctx.promiseFactory.createPromise(order)))
        assertEquals(["vip-processing"], result)
    }

    @Test
    void testCaseInsensitiveStringMatching() {
        def switcher = new SwitchRouterTask("switch9", "Case Insensitive", ctx)
        
        // Value extractor normalizes to uppercase
        switcher.switchOn { it.status.toUpperCase() }
        switcher.case_("NEW") route "new-handler"
        switcher.case_("PENDING") route "pending-handler"
        switcher.default_ "unknown-handler"

        // Test: lowercase input matches uppercase case
        def ticket = [status: "new"]
        def result = awaitPromise(switcher.execute(ctx.promiseFactory.createPromise(ticket)))
        assertEquals(["new-handler"], result)
    }

    @Test
    void testNullValueHandling() {
        def switcher = new SwitchRouterTask("switch10", "Null Test", ctx)
        
        switcher.switchOn { it }
        switcher.case_(null) route "null-handler"
        switcher.case_("value") route "value-handler"
        switcher.default_ "default-handler"

        // Test: null value
        def result = awaitPromise(switcher.execute(ctx.promiseFactory.createPromise(null)))
        assertEquals(["null-handler"], result)
    }

    @Test
    void testBooleanSwitching() {
        def switcher = new SwitchRouterTask("switch11", "Boolean Switch", ctx)
        
        switcher.switchOn { it.approved }
        switcher.case_(true) route "approved-path"
        switcher.case_(false) route "rejected-path"

        // Test: approved = true
        def request1 = [approved: true]
        def result1 = awaitPromise(switcher.execute(ctx.promiseFactory.createPromise(request1)))
        assertEquals(["approved-path"], result1)

        // Reset
        switcher.state = TaskState.SCHEDULED
        switcher.alreadyRouted = false
        switcher.lastSelectedTargets = null

        // Test: approved = false
        def request2 = [approved: false]
        def result2 = awaitPromise(switcher.execute(ctx.promiseFactory.createPromise(request2)))
        assertEquals(["rejected-path"], result2)
    }
}
