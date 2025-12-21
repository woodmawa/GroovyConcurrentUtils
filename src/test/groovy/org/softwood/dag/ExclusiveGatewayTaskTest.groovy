package org.softwood.dag

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import static org.junit.jupiter.api.Assertions.*

import org.awaitility.Awaitility
import java.util.concurrent.TimeUnit
import org.softwood.promise.Promise
import org.softwood.dag.task.*

/**
 * Tests for ExclusiveGatewayTask (XOR Gateway)
 */
class ExclusiveGatewayTaskTest {

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
    void testFirstMatchingConditionWins() {
        // Create task with multiple conditions
        def gateway = new ExclusiveGatewayTask("xor1", "Test XOR", ctx)
        
        gateway.when { val -> val > 100 } route "high"
        gateway.when { val -> val > 50 } route "medium"
        gateway.when { val -> val > 0 } route "low"
        gateway.otherwise "none"

        // Test: value 150 should match first condition
        def result1 = awaitPromise(gateway.execute(ctx.promiseFactory.createPromise(150)))
        assertEquals(["high"], result1)
        assertEquals(TaskState.COMPLETED, gateway.state)

        // Reset for next test
        gateway.state = TaskState.SCHEDULED
        gateway.alreadyRouted = false
        gateway.lastSelectedTargets = null

        // Test: value 75 should match second condition (not first)
        def result2 = awaitPromise(gateway.execute(ctx.promiseFactory.createPromise(75)))
        assertEquals(["medium"], result2)
    }

    @Test
    void testOtherwiseWhenNoMatch() {
        def gateway = new ExclusiveGatewayTask("xor2", "Test Default", ctx)
        
        gateway.when { val -> val == "A" } route "path-a"
        gateway.when { val -> val == "B" } route "path-b"
        gateway.otherwise "default-path"

        // Test: value not matching any condition uses default
        def result = awaitPromise(gateway.execute(ctx.promiseFactory.createPromise("C")))
        assertEquals(["default-path"], result)
    }

    @Test
    void testFailOnNoMatchWithoutDefault() {
        def gateway = new ExclusiveGatewayTask("xor3", "Test No Default", ctx)
        
        gateway.when { val -> val == "MATCH" } route "matched"
        // No otherwise() call - should fail

        // Test: unmatched value with no default should throw
        assertThrows(IllegalStateException) {
            awaitPromise(gateway.execute(ctx.promiseFactory.createPromise("NOMATCH")))
        }
    }

    @Test
    void testExactlyOnePathSelected() {
        def gateway = new ExclusiveGatewayTask("xor4", "Test Single Path", ctx)
        
        // Multiple conditions that could match
        gateway.when { val -> val > 10 } route "path1"
        gateway.when { val -> val > 5 } route "path2"
        gateway.when { val -> val > 0 } route "path3"

        // Test: only first matching path is selected
        def result = awaitPromise(gateway.execute(ctx.promiseFactory.createPromise(20)))
        assertEquals(1, result.size())
        assertEquals(["path1"], result)
    }

    @Test
    void testComplexConditions() {
        def gateway = new ExclusiveGatewayTask("xor5", "Complex Conditions", ctx)
        
        gateway.when { order -> 
            order.value > 10000 && order.customer.tier == "gold" 
        } route "vip-processing"
        
        gateway.when { order -> 
            order.type == "bulk" 
        } route "bulk-processing"
        
        gateway.otherwise "standard-processing"

        // Test: VIP order
        def vipOrder = [value: 15000, customer: [tier: "gold"], type: "normal"]
        def result1 = awaitPromise(gateway.execute(ctx.promiseFactory.createPromise(vipOrder)))
        assertEquals(["vip-processing"], result1)

        // Reset
        gateway.state = TaskState.SCHEDULED
        gateway.alreadyRouted = false
        gateway.lastSelectedTargets = null

        // Test: Bulk order (not VIP)
        def bulkOrder = [value: 5000, customer: [tier: "silver"], type: "bulk"]
        def result2 = awaitPromise(gateway.execute(ctx.promiseFactory.createPromise(bulkOrder)))
        assertEquals(["bulk-processing"], result2)

        // Reset
        gateway.state = TaskState.SCHEDULED
        gateway.alreadyRouted = false
        gateway.lastSelectedTargets = null

        // Test: Standard order
        def standardOrder = [value: 100, customer: [tier: "bronze"], type: "normal"]
        def result3 = awaitPromise(gateway.execute(ctx.promiseFactory.createPromise(standardOrder)))
        assertEquals(["standard-processing"], result3)
    }

    @Test
    void testTargetIdsPopulated() {
        def gateway = new ExclusiveGatewayTask("xor6", "Check Targets", ctx)
        
        gateway.when { val -> val > 50 } route "high"
        gateway.when { val -> val > 25 } route "medium"
        gateway.otherwise "low"

        // Verify all possible targets are registered
        assertTrue(gateway.targetIds.contains("high"))
        assertTrue(gateway.targetIds.contains("medium"))
        assertTrue(gateway.targetIds.contains("low"))
        assertEquals(3, gateway.targetIds.size())
    }

    @Test
    void testFailOnNoMatchCanBeDisabled() {
        def gateway = new ExclusiveGatewayTask("xor7", "No Fail Test", ctx)
        
        gateway.when { val -> val == "SPECIFIC" } route "matched"
        // Set otherwise - this disables failOnNoMatch
        gateway.otherwise "default"

        // Should not throw - uses default
        def result = awaitPromise(gateway.execute(ctx.promiseFactory.createPromise("OTHER")))
        assertEquals(["default"], result)
    }

    @Test
    void testConditionEvaluationOrder() {
        def gateway = new ExclusiveGatewayTask("xor8", "Order Test", ctx)
        
        def evaluationOrder = []
        
        gateway.when { val -> 
            evaluationOrder << "first"
            false 
        } route "path1"
        
        gateway.when { val -> 
            evaluationOrder << "second"
            true 
        } route "path2"
        
        gateway.when { val -> 
            evaluationOrder << "third"
            true 
        } route "path3"

        def result = awaitPromise(gateway.execute(ctx.promiseFactory.createPromise(null)))
        
        // First and second should be evaluated, third should not
        assertEquals(["first", "second"], evaluationOrder)
        assertEquals(["path2"], result)
    }
}
