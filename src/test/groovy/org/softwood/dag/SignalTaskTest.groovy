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
 * Tests for SignalTask (event coordination task)
 */
class SignalTaskTest {

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
    void testBasicSendSignal() {
        def task = new SignalTask("sender", "Sender", ctx)
        task.mode = SignalMode.SEND
        task.signalName = "test-signal"
        
        def promise = task.execute(ctx.promiseFactory.createPromise(null))
        def result = awaitPromise(promise)
        
        assertEquals("test-signal", result.signalName)
        assertEquals("SEND", result.mode)
    }

    @Test
    void testWaitForSignal() {
        def task = new SignalTask("waiter", "Waiter", ctx)
        task.mode = SignalMode.WAIT
        task.signalName = "approval"
        
        def promise = task.execute(ctx.promiseFactory.createPromise(null))
        assertFalse(promise.isDone())
        
        SignalTask.sendSignalGlobal("approval", [approved: true])
        
        def result = awaitPromise(promise)
        assertEquals(true, result.signalData.approved)
    }

    @Test
    void testSignalSentBeforeWait() {
        // Send signal first
        SignalTask.sendSignalGlobal("early-signal", [data: "early"])
        
        // Then wait for it
        def task = new SignalTask("waiter", "Waiter", ctx)
        task.mode = SignalMode.WAIT
        task.signalName = "early-signal"
        
        def promise = task.execute(ctx.promiseFactory.createPromise(null))
        
        // Should complete immediately
        def result = awaitPromise(promise)
        assertEquals("early", result.signalData.data)
    }

    @Test
    void testWaitAll() {
        def task = new SignalTask("wait-all", "Wait All", ctx)
        task.mode = SignalMode.WAIT_ALL
        task.signalNames("signal1", "signal2", "signal3")
        
        def promise = task.execute(ctx.promiseFactory.createPromise(null))
        
        // Send signals one by one
        SignalTask.sendSignalGlobal("signal1", [data: "first"])
        Thread.sleep(50)
        assertFalse(promise.isDone())
        
        SignalTask.sendSignalGlobal("signal2", [data: "second"])
        Thread.sleep(50)
        assertFalse(promise.isDone())
        
        SignalTask.sendSignalGlobal("signal3", [data: "third"])
        
        // Now should complete
        def result = awaitPromise(promise)
        assertEquals("WAIT_ALL", result.mode)
        assertTrue(result.receivedSignals.containsAll(["signal1", "signal2", "signal3"]))
    }

    @Test
    void testWaitAny() {
        def task = new SignalTask("wait-any", "Wait Any", ctx)
        task.mode = SignalMode.WAIT_ANY
        task.signalNames("option1", "option2", "option3")
        
        def promise = task.execute(ctx.promiseFactory.createPromise(null))
        
        // Give it a moment to register in waiting lists
        Thread.sleep(100)
        
        // Send just one signal
        SignalTask.sendSignalGlobal("option2", [chosen: "option2"])
        
        // Should complete immediately
        def result = awaitPromise(promise)
        assertEquals("option2", result.signalData.matchedSignal)
        assertEquals("option2", result.signalData.chosen)
    }

    @Test
    void testSignalInWorkflow() {
        def results = [:]
        
        def graph = TaskGraph.build {
            // Task that waits for approval
            task("wait-approval", TaskType.SIGNAL) {
                mode SignalMode.WAIT
                signalName "approval-signal"
            }
            
            // Task that processes after approval
            serviceTask("process") {
                action { ctx, prevValue ->
                    results.signalData = prevValue.signalData
                    results.processed = true
                    ctx.promiseFactory.executeAsync { "Processed" }
                }
            }
            
            fork("workflow") {
                from "wait-approval"
                to "process"
            }
        }
        
        // Start workflow
        def promise = graph.run()
        
        // Wait a bit
        Thread.sleep(100)
        
        // Send approval signal
        SignalTask.sendSignalGlobal("approval-signal", [approved: true, by: "admin"])
        
        // Workflow should complete
        awaitPromise(promise)
        
        assertTrue(results.processed)
        assertEquals(true, results.signalData.approved)
    }
}
