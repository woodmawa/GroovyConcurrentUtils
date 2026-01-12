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
 * Tests for TimerTask - Periodic/Scheduled Execution
 */
class TimerTaskTest {

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
    void testBasicInterval() {
        def executions = []
        
        def timer = new TimerTask("timer", "Timer", ctx)
        timer.interval = Duration.ofMillis(100)
        timer.maxExecutions = 3
        timer.action = { c ->
            executions << System.currentTimeMillis()
            return executions.size()
        }
        
        def promise = timer.execute(ctx.promiseFactory.createPromise(null))
        def result = awaitPromise(promise)
        
        assertEquals("completed", result.status)
        assertEquals(3, result.executionCount)
        assertEquals(3, executions.size())
        assertEquals("maxExecutions", result.stoppedBy)
        assertEquals(3, result.lastResult)
    }

    @Test
    void testTimeout() {
        def executions = []
        
        def timer = new TimerTask("timer", "Timer", ctx)
        timer.interval = Duration.ofMillis(50)
        timer.timeout = Duration.ofMillis(200)
        timer.action = { c ->
            executions << System.currentTimeMillis()
        }
        
        def promise = timer.execute(ctx.promiseFactory.createPromise(null))
        def result = awaitPromise(promise)
        
        assertEquals("completed", result.status)
        assertTrue(result.executionCount >= 3)
        assertEquals("timeout", result.stoppedBy)
    }

    @Test
    void testStopWhen() {
        def executions = []
        
        def timer = new TimerTask("timer", "Timer", ctx)
        timer.interval = Duration.ofMillis(50)
        timer.maxExecutions = 100  // Safety limit
        timer.stopWhenCondition = { c, executionCount -> executionCount >= 5 }
        timer.action = { c ->
            executions << executions.size() + 1
            return executions.size()
        }
        
        def promise = timer.execute(ctx.promiseFactory.createPromise(null))
        def result = awaitPromise(promise)
        
        assertEquals("completed", result.status)
        assertEquals(5, result.executionCount)
        assertEquals("condition", result.stoppedBy)
    }

    @Test
    void testTimerControlStop() {
        def executions = []
        
        def timer = new TimerTask("timer", "Timer", ctx)
        timer.interval = Duration.ofMillis(50)
        timer.maxExecutions = 100  // Safety limit
        timer.action = { c ->
            executions << executions.size() + 1
            if (executions.size() == 3) {
                return TimerControl.stop("early exit")
            }
            return TimerControl.continueWith("tick")
        }
        
        def promise = timer.execute(ctx.promiseFactory.createPromise(null))
        def result = awaitPromise(promise)
        
        assertEquals("completed", result.status)
        assertEquals(3, result.executionCount)
        assertEquals("action", result.stoppedBy)
        assertEquals("early exit", result.lastResult)
    }

    @Test
    void testAccumulator() {
        def timer = new TimerTask("timer", "Timer", ctx)
        timer.interval = Duration.ofMillis(50)
        timer.maxExecutions = 5
        timer.action = { c ->
            return Math.random() * 100
        }
        timer.accumulatorFn = { c, acc, result ->
            acc.values = acc.values ?: []
            acc.values << result
            acc.sum = (acc.sum ?: 0) + result
            acc.avg = acc.sum / acc.values.size()
            return acc
        }
        
        def promise = timer.execute(ctx.promiseFactory.createPromise(null))
        def result = awaitPromise(promise)
        
        assertEquals("completed", result.status)
        assertEquals(5, result.executionCount)
        assertEquals(5, result.accumulator.values.size())
        assertNotNull(result.accumulator.avg)
        assertTrue(result.accumulator.sum > 0)
    }

    @Test
    void testTimerInGraph() {
        def ticks = []
        
        def graph = TaskGraph.build {
            task("timer", TaskType.TIMER) {
                interval Duration.ofMillis(50)
                maxExecutions 3
                action { ctx ->
                    ticks << System.currentTimeMillis()
                    return ticks.size()
                }
            }
            
            serviceTask("process") {
                action { ctx, timerResult ->
                    ctx.promiseFactory.executeAsync {
                        "Processed ${timerResult.executionCount} ticks".toString()
                    }
                }
            }
            
            fork("flow") {
                from "timer"
                to "process"
            }
        }
        
        def result = awaitPromise(graph.run())
        
        assertEquals("Processed 3 ticks", result.toString())
        assertEquals(3, ticks.size())
    }

    @Test
    void testTimerWithoutStopConditionInGraphFails() {
        def graph = TaskGraph.build {
            task("timer", TaskType.TIMER) {
                interval Duration.ofMillis(50)
                action { ctx -> "tick" }
            }
            
            serviceTask("next") {
                action { ctx, prev -> "done" }
            }
            
            fork("flow") {
                from "timer"
                to "next"
            }
        }
        
        // Should fail during execution when timer validates config
        def promise = graph.run()
        
        // Wait for the promise to complete (either success or failure)
        Awaitility.await()
            .atMost(2, TimeUnit.SECONDS)
            .pollInterval(50, TimeUnit.MILLISECONDS)
            .until({ promise.isDone() })
        
        // The promise should have failed
        assertTrue(promise.isFailed(), "Promise should be in failed state")
        
        // Getting from the promise should throw
        def exception = assertThrows(Exception) {
            promise.get()
        }
        
        // Verify it's the right type of error
        def message = exception.message ?: ""
        def cause = exception.cause
        def causeMessage = cause?.message ?: ""
        
        assertTrue(
            message.contains("requires at least one stop condition") ||
            causeMessage.contains("requires at least one stop condition"),
            "Expected error about stop condition, but got: ${exception.class.name} - ${message}"
        )
    }

    @Test
    void testManualStop() {
        def timer = new TimerTask("timer", "Timer", ctx)
        timer.interval = Duration.ofMillis(50)
        timer.maxExecutions = 1000  // Would run a long time
        timer.action = { c -> "tick" }
        
        def promise = timer.execute(ctx.promiseFactory.createPromise(null))
        
        // Let it run a bit
        Thread.sleep(200)
        
        // Stop it manually
        timer.stop()
        
        def result = awaitPromise(promise)
        
        assertEquals("completed", result.status)
        assertEquals("manual", result.stoppedBy)
        assertTrue(result.executionCount > 0)
    }

    @Test
    void testInitialDelay() {
        def executions = []
        def startTime = System.currentTimeMillis()
        
        def timer = new TimerTask("timer", "Timer", ctx)
        timer.interval = Duration.ofMillis(50)
        timer.initialDelay = Duration.ofMillis(200)
        timer.maxExecutions = 2
        timer.action = { c ->
            executions << System.currentTimeMillis() - startTime
        }
        
        def promise = timer.execute(ctx.promiseFactory.createPromise(null))
        def result = awaitPromise(promise)
        
        assertEquals(2, result.executionCount)
        // First execution should be after initial delay
        assertTrue(executions[0] >= 200)
    }

    @Test
    void testStopOnError() {
        def executions = []
        
        def timer = new TimerTask("timer", "Timer", ctx)
        timer.interval = Duration.ofMillis(50)
        timer.maxExecutions = 10
        timer.stopOnError = true
        timer.action = { c ->
            executions << executions.size() + 1
            if (executions.size() == 3) {
                throw new RuntimeException("Intentional error")
            }
            return "ok"
        }
        
        def promise = timer.execute(ctx.promiseFactory.createPromise(null))
        
        assertThrows(RuntimeException) {
            awaitPromise(promise)
        }
        
        // Should have stopped at 3
        assertTrue(executions.size() <= 3)
    }
}
