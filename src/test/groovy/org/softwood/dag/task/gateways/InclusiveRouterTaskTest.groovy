package org.softwood.dag.task.gateways

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.softwood.dag.TaskGraph

import static org.junit.jupiter.api.Assertions.*

import org.awaitility.Awaitility
import java.util.concurrent.TimeUnit
import org.softwood.promise.Promise
import org.softwood.dag.task.*

/**
 * Tests for InclusiveRouterTask - OR Gateway Multi-Path Routing
 */
class InclusiveRouterTaskTest {

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
    void testAllConditionsMatch() {
        def graph = TaskGraph.build {
            serviceTask("start") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        [amount: 15000, riskScore: 60, country: "US"]
                    }
                }
            }
            
            task("checks", TaskType.INCLUSIVE_GATEWAY) {
                route "credit-check" when { r -> r.prev.amount > 10000 }
                route "fraud-check" when { r -> r.prev.riskScore > 50 }
                route "compliance-check" when { r -> r.prev.country in ["US", "UK"] }
            }
            
            serviceTask("credit-check") {
                action { ctx, prev -> ctx.promiseFactory.executeAsync { "credit-ok" } }
            }
            serviceTask("fraud-check") {
                action { ctx, prev -> ctx.promiseFactory.executeAsync { "fraud-ok" } }
            }
            serviceTask("compliance-check") {
                action { ctx, prev -> ctx.promiseFactory.executeAsync { "compliance-ok" } }
            }
            
            fork("flow") {
                from "start"
                to "checks"
            }
        }
        
        def result = awaitPromise(graph.run())
        
        def creditTask = graph.tasks["credit-check"]
        def fraudTask = graph.tasks["fraud-check"]
        def complianceTask = graph.tasks["compliance-check"]
        
        assertTrue(creditTask.completionPromise.isDone())
        assertTrue(fraudTask.completionPromise.isDone())
        assertTrue(complianceTask.completionPromise.isDone())
    }

    @Test
    void testSomeConditionsMatch() {
        def graph = TaskGraph.build {
            serviceTask("start") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        [amount: 5000, riskScore: 60, country: "CA"]
                    }
                }
            }
            
            task("checks", TaskType.INCLUSIVE_GATEWAY) {
                route "credit-check" when { r -> r.prev.amount > 10000 }
                route "fraud-check" when { r -> r.prev.riskScore > 50 }
                route "compliance-check" when { r -> r.prev.country in ["US", "UK"] }
            }
            
            serviceTask("credit-check") {
                action { ctx, prev -> ctx.promiseFactory.executeAsync { "credit-ok" } }
            }
            serviceTask("fraud-check") {
                action { ctx, prev -> ctx.promiseFactory.executeAsync { "fraud-ok" } }
            }
            serviceTask("compliance-check") {
                action { ctx, prev -> ctx.promiseFactory.executeAsync { "compliance-ok" } }
            }
            
            fork("flow") {
                from "start"
                to "checks"
            }
        }
        
        def result = awaitPromise(graph.run())
        
        def creditTask = graph.tasks["credit-check"]
        def fraudTask = graph.tasks["fraud-check"]
        def complianceTask = graph.tasks["compliance-check"]
        
        assertFalse(creditTask.completionPromise.isDone())
        assertTrue(fraudTask.completionPromise.isDone())
        assertFalse(complianceTask.completionPromise.isDone())
    }

    @Test
    void testNoConditionsMatch() {
        def graph = TaskGraph.build {
            serviceTask("start") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        [amount: 100, riskScore: 10, country: "CA"]
                    }
                }
            }
            
            task("checks", TaskType.INCLUSIVE_GATEWAY) {
                route "credit-check" when { r -> r.prev.amount > 10000 }
                route "fraud-check" when { r -> r.prev.riskScore > 50 }
                route "compliance-check" when { r -> r.prev.country in ["US", "UK"] }
            }
            
            serviceTask("credit-check") {
                action { ctx, prev -> ctx.promiseFactory.executeAsync { "credit-ok" } }
            }
            serviceTask("fraud-check") {
                action { ctx, prev -> ctx.promiseFactory.executeAsync { "fraud-ok" } }
            }
            serviceTask("compliance-check") {
                action { ctx, prev -> ctx.promiseFactory.executeAsync { "compliance-ok" } }
            }
            
            fork("flow") {
                from "start"
                to "checks"
            }
        }
        
        def result = awaitPromise(graph.run())
        
        def creditTask = graph.tasks["credit-check"]
        def fraudTask = graph.tasks["fraud-check"]
        def complianceTask = graph.tasks["compliance-check"]
        
        assertFalse(creditTask.completionPromise.isDone())
        assertFalse(fraudTask.completionPromise.isDone())
        assertFalse(complianceTask.completionPromise.isDone())
    }

    @Test
    void testMultiRegionDeployment() {
        def graph = TaskGraph.build {
            serviceTask("prepare") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        [regions: ["us-east", "eu-west"]]
                    }
                }
            }
            
            task("deploy", TaskType.INCLUSIVE_GATEWAY) {
                route "deploy-us-east" when { r -> "us-east" in r.prev.regions }
                route "deploy-us-west" when { r -> "us-west" in r.prev.regions }
                route "deploy-eu-west" when { r -> "eu-west" in r.prev.regions }
                route "deploy-ap-south" when { r -> "ap-south" in r.prev.regions }
            }
            
            serviceTask("deploy-us-east") {
                action { ctx, prev -> ctx.promiseFactory.executeAsync { "deployed-us-east" } }
            }
            serviceTask("deploy-us-west") {
                action { ctx, prev -> ctx.promiseFactory.executeAsync { "deployed-us-west" } }
            }
            serviceTask("deploy-eu-west") {
                action { ctx, prev -> ctx.promiseFactory.executeAsync { "deployed-eu-west" } }
            }
            serviceTask("deploy-ap-south") {
                action { ctx, prev -> ctx.promiseFactory.executeAsync { "deployed-ap-south" } }
            }
            
            fork("flow") {
                from "prepare"
                to "deploy"
            }
        }
        
        def result = awaitPromise(graph.run())
        
        assertTrue(graph.tasks["deploy-us-east"].completionPromise.isDone())
        assertFalse(graph.tasks["deploy-us-west"].completionPromise.isDone())
        assertTrue(graph.tasks["deploy-eu-west"].completionPromise.isDone())
        assertFalse(graph.tasks["deploy-ap-south"].completionPromise.isDone())
    }

    @Test
    void testRouterInputDataInjection() {
        def graph = TaskGraph.build {
            serviceTask("start") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        [orderId: "ORD-123", amount: 1000]
                    }
                }
            }
            
            task("route", TaskType.INCLUSIVE_GATEWAY) {
                route "process" when { r -> r.prev.amount > 0 }
            }
            
            serviceTask("process") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        "Processed: ${prev.orderId}"
                    }
                }
            }
            
            fork("flow") {
                from "start"
                to "route"
            }
        }
        
        def result = awaitPromise(graph.run())
        
        assertTrue(result.toString().contains("ORD-123"))
    }

    @Test
    void testAccessToGlobals() {
        ctx.globals.set("threshold", 100)
        
        def graph = TaskGraph.build {
            serviceTask("start") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync { [value: 150] }
                }
            }
            
            task("route", TaskType.INCLUSIVE_GATEWAY) {
                route "high-value" when { r -> 
                    r.prev.value > r.globals.get("threshold")
                }
                route "low-value" when { r ->
                    r.prev.value <= r.globals.get("threshold")
                }
            }
            
            serviceTask("high-value") {
                action { ctx, prev -> ctx.promiseFactory.executeAsync { "high" } }
            }
            serviceTask("low-value") {
                action { ctx, prev -> ctx.promiseFactory.executeAsync { "low" } }
            }
            
            fork("flow") {
                from "start"
                to "route"
            }
        }
        
        graph.ctx = ctx
        def result = awaitPromise(graph.run())
        
        assertTrue(graph.tasks["high-value"].completionPromise.isDone())
        assertFalse(graph.tasks["low-value"].completionPromise.isDone())
    }

    @Test
    void testComplexConditions() {
        def graph = TaskGraph.build {
            serviceTask("start") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        [
                            user: [age: 25, premium: true],
                            cart: [items: 5, total: 200]
                        ]
                    }
                }
            }
            
            task("offers", TaskType.INCLUSIVE_GATEWAY) {
                route "student-discount" when { r ->
                    r.prev.user.age < 26
                }
                route "bulk-discount" when { r ->
                    r.prev.cart.items > 3
                }
                route "premium-bonus" when { r ->
                    r.prev.user.premium && r.prev.cart.total > 100
                }
            }
            
            serviceTask("student-discount") {
                action { ctx, prev -> ctx.promiseFactory.executeAsync { "student-10%" } }
            }
            serviceTask("bulk-discount") {
                action { ctx, prev -> ctx.promiseFactory.executeAsync { "bulk-15%" } }
            }
            serviceTask("premium-bonus") {
                action { ctx, prev -> ctx.promiseFactory.executeAsync { "premium-20%" } }
            }
            
            fork("flow") {
                from "start"
                to "offers"
            }
        }
        
        def result = awaitPromise(graph.run())
        
        assertTrue(graph.tasks["student-discount"].completionPromise.isDone())
        assertTrue(graph.tasks["bulk-discount"].completionPromise.isDone())
        assertTrue(graph.tasks["premium-bonus"].completionPromise.isDone())
    }

    @Test
    void testEmptyRoutes() {
        def router = new InclusiveRouterTask("router", "Router", ctx)
        
        assertThrows(IllegalStateException) {
            router.execute(ctx.promiseFactory.createPromise([data: "test"]))
        }
    }

    @Test
    void testMissingWhenCondition() {
        def router = new InclusiveRouterTask("router", "Router", ctx)
        
        assertThrows(IllegalArgumentException) {
            router.route("target", [:])
        }
    }

    @Test
    void testConditionEvaluationError() {
        def graph = TaskGraph.build {
            serviceTask("start") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync { [value: "not-a-number"] }
                }
            }
            
            task("route", TaskType.INCLUSIVE_GATEWAY) {
                route "target1" when { r -> 
                    r.prev.value > 100
                }
                route "target2" when { r ->
                    r.prev.value != null
                }
            }
            
            serviceTask("target1") {
                action { ctx, prev -> ctx.promiseFactory.executeAsync { "result1" } }
            }
            serviceTask("target2") {
                action { ctx, prev -> ctx.promiseFactory.executeAsync { "result2" } }
            }
            
            fork("flow") {
                from "start"
                to "route"
            }
        }
        
        def result = awaitPromise(graph.run())
        
        assertFalse(graph.tasks["target1"].completionPromise.isDone())
        assertTrue(graph.tasks["target2"].completionPromise.isDone())
    }

    @Test
    void testDifferenceFromExclusiveGateway() {
        def inclusiveGraph = TaskGraph.build {
            serviceTask("start") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync { [score: 75] }
                }
            }
            
            task("route", TaskType.INCLUSIVE_GATEWAY) {
                route "good" when { r -> r.prev.score > 50 }
                route "great" when { r -> r.prev.score > 70 }
                route "excellent" when { r -> r.prev.score > 90 }
            }
            
            serviceTask("good") {
                action { ctx, prev -> ctx.promiseFactory.executeAsync { "good" } }
            }
            serviceTask("great") {
                action { ctx, prev -> ctx.promiseFactory.executeAsync { "great" } }
            }
            serviceTask("excellent") {
                action { ctx, prev -> ctx.promiseFactory.executeAsync { "excellent" } }
            }
            
            fork("flow") {
                from "start"
                to "route"
            }
        }
        
        def result = awaitPromise(inclusiveGraph.run())
        
        assertTrue(inclusiveGraph.tasks["good"].completionPromise.isDone())
        assertTrue(inclusiveGraph.tasks["great"].completionPromise.isDone())
        assertFalse(inclusiveGraph.tasks["excellent"].completionPromise.isDone())
    }
}
