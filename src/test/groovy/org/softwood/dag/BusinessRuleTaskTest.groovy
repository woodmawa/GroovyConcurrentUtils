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
 * Tests for BusinessRuleTask - Condition-Based Reactive Execution
 */
class BusinessRuleTaskTest {

    private TaskContext ctx

    @BeforeEach
    void setup() {
        ctx = new TaskContext()
        SignalTask.clearAllSignals()
    }

    @AfterEach
    void cleanup() {
        SignalTask.clearAllSignals()
    }

    private static <T> T awaitPromise(Promise<T> p) {
        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .until({ p.isDone() })
        return p.get()
    }

    @Test
    void testSignalTrigger() {
        def rule = new BusinessRuleTask("rule", "Rule", ctx)
        rule.triggerSignal = "test-signal"
        rule.evaluationRule = { c, data -> data.value > 10 }
        rule.trueAction = { c, data -> "approved: ${data.value}" }
        
        def promise = rule.execute(ctx.promiseFactory.createPromise(null))
        
        // Send signal
        SignalTask.sendSignalGlobal("test-signal", [value: 20])
        
        def result = awaitPromise(promise)
        
        assertEquals("completed", result.status)
        assertTrue(result.ruleResult)
        assertEquals("approved: 20", result.actionResult.toString())
    }

    @Test
    void testPollingTrigger() {
        ctx.globals.counter = 0
        
        def rule = new BusinessRuleTask("rule", "Rule", ctx)
        rule.pollingInterval = Duration.ofMillis(50)
        rule.triggerCondition = { c ->
            c.globals.counter++
            c.globals.counter >= 3
        }
        rule.trueAction = { c, data ->
            "triggered after ${c.globals.counter} polls"
        }
        
        def promise = rule.execute(ctx.promiseFactory.createPromise(null))
        def result = awaitPromise(promise)
        
        assertEquals("completed", result.status)
        assertTrue(ctx.globals.counter >= 3)
    }

    @Test
    void testRuleEvaluationTrue() {
        def rule = new BusinessRuleTask("rule", "Rule", ctx)
        rule.triggerSignal = "eval-test"
        rule.evaluationRule = { c, data ->
            data.amount < 1000
        }
        rule.trueAction = { c, data ->
            "approved: ${data.amount}"
        }
        rule.falseAction = { c, data ->
            "rejected: ${data.amount}"
        }
        
        def promise = rule.execute(ctx.promiseFactory.createPromise(null))
        
        SignalTask.sendSignalGlobal("eval-test", [amount: 500])
        
        def result = awaitPromise(promise)
        
        assertTrue(result.ruleResult)
        assertEquals("approved: 500", result.actionResult.toString())
    }

    @Test
    @org.junit.jupiter.api.Disabled("TODO: Promise not completing when falseAction runs - needs investigation")
    void testRuleEvaluationFalse() {
        def falseActionCalled = false
        
        def rule = new BusinessRuleTask("rule", "Rule", ctx)
        rule.triggerSignal = "eval-test"
        rule.evaluationRule = { c, data ->
            data.amount < 1000
        }
        rule.trueAction = { c, data ->
            "approved"
        }
        rule.falseAction = { c, data ->
            falseActionCalled = true
            null  // False action doesn't need return value
        }
        
        def promise = rule.execute(ctx.promiseFactory.createPromise(null))
        
        SignalTask.sendSignalGlobal("eval-test", [amount: 2000])
        
        def result = awaitPromise(promise)
        
        assertFalse(result.ruleResult)
        assertTrue(falseActionCalled)
    }

    @Test
    void testSignalBeforeWait() {
        // Send signal before rule starts waiting
        SignalTask.sendSignalGlobal("early-signal", [data: "early"])
        
        def rule = new BusinessRuleTask("rule", "Rule", ctx)
        rule.triggerSignal = "early-signal"
        rule.trueAction = { c, data -> "received: ${data.data}" }
        
        def promise = rule.execute(ctx.promiseFactory.createPromise(null))
        def result = awaitPromise(promise)
        
        assertEquals("completed", result.status)
        assertEquals("received: early", result.actionResult.toString())
    }

    @Test
    void testMixedTriggers() {
        ctx.globals.ready = false
        
        def rule = new BusinessRuleTask("rule", "Rule", ctx)
        rule.triggerSignal = "manual-trigger"
        rule.pollingInterval = Duration.ofMillis(50)
        rule.triggerCondition = { c -> c.globals.ready }
        rule.trueAction = { c, data -> "triggered" }
        
        def promise = rule.execute(ctx.promiseFactory.createPromise(null))
        
        // Let polling happen a few times
        Thread.sleep(150)
        
        // Then set flag to trigger via polling
        ctx.globals.ready = true
        
        def result = awaitPromise(promise)
        
        assertEquals("completed", result.status)
        assertEquals("triggered", result.actionResult)
    }

    @Test
    void testPriority() {
        def rule = new BusinessRuleTask("rule", "High Priority", ctx)
        rule.priority = 10
        rule.triggerSignal = "test"
        rule.trueAction = { c, data -> "done" }
        
        assertEquals(10, rule.priority)
    }

    @Test
    void testTimeout() {
        def rule = new BusinessRuleTask("rule", "Rule", ctx)
        rule.triggerSignal = "never-sent"
        rule.timeout = Duration.ofMillis(200)
        rule.trueAction = { c, data -> "done" }
        
        def promise = rule.execute(ctx.promiseFactory.createPromise(null))
        
        assertThrows(java.util.concurrent.TimeoutException) {
            awaitPromise(promise)
        }
    }

    @Test
    @org.junit.jupiter.api.Disabled("TODO: Race condition - signal sent before BusinessRuleTask starts listening")
    void testInGraph() {
        def graph = TaskGraph.build {
            serviceTask("trigger") {
                action { ctx, prev ->
                    SignalTask.sendSignalGlobal("graph-signal", [value: 42])
                    ctx.promiseFactory.executeAsync { "signal sent" }
                }
            }
            
            task("rule", TaskType.BUSINESS_RULE) {
                when {
                    signal "graph-signal"
                }
                evaluate { ctx, data ->
                    data.value > 10
                }
                action { ctx, data ->
                    "Rule passed: ${data.value}"
                }
            }
            
            serviceTask("next") {
                action { ctx, prevResults ->
                    // prevResults is a list from the fork: [trigger result, rule result]
                    def ruleResult = prevResults[1]  // Get the rule result
                    ctx.promiseFactory.executeAsync {
                        "Processed: ${ruleResult.actionResult}"
                    }
                }
            }
            
            fork("flow") {
                from "trigger", "rule"
                to "next"
            }
        }
        
        def result = awaitPromise(graph.run())
        
        assertEquals("Processed: Rule passed: 42", result.toString())
    }

    @Test
    @org.junit.jupiter.api.Disabled("TODO: Promise not completing after retry - needs investigation")
    void testRetryOnFailure() {
        def attempts = []
        
        def rule = new BusinessRuleTask("rule", "Rule", ctx)
        rule.triggerSignal = "retry-test"
        rule.retryOnFailure = true
        rule.trueAction = { c, data ->
            attempts << System.currentTimeMillis()
            if (attempts.size() < 2) {
                throw new RuntimeException("Fail on first try")
            }
            return "success on attempt ${attempts.size()}"
        }
        
        def promise = rule.execute(ctx.promiseFactory.createPromise(null))
        
        // First signal - will fail
        SignalTask.sendSignalGlobal("retry-test", [test: 1])
        
        // Give it time to fail
        Thread.sleep(100)
        
        // Second signal - should succeed
        SignalTask.sendSignalGlobal("retry-test", [test: 2])
        
        def result = awaitPromise(promise)
        
        assertEquals("completed", result.status)
        assertEquals("success on attempt 2", result.actionResult.toString())
        assertEquals(2, attempts.size())
    }

    @Test
    @org.junit.jupiter.api.Disabled("TODO: Promise not completing - signal handling race condition")
    void testNoEvaluateJustAction() {
        // Rule without evaluation - just execute action
        def rule = new BusinessRuleTask("rule", "Rule", ctx)
        rule.triggerSignal = "simple-test"
        rule.trueAction = { c, data -> "executed: ${data.value}" }
        
        def promise = rule.execute(ctx.promiseFactory.createPromise(null))
        
        SignalTask.sendSignalGlobal("simple-test", [value: 123])
        
        def result = awaitPromise(promise)
        
        assertEquals("completed", result.status)
        assertEquals("executed: 123", result.actionResult.toString())
    }
}
