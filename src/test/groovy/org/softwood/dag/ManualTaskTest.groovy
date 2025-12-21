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
 * Tests for ManualTask (human interaction task)
 */
class ManualTaskTest {

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
    void testBasicManualTaskSuccess() {
        // Create a simple manual task
        def task = new ManualTask("approval", "Approval Task", ctx)
        task.title = "Approve Document"
        task.description = "Please review and approve this document"
        
        // Start the task (it will wait for completion)
        def promise = task.execute(ctx.promiseFactory.createPromise(null))
        
        // Verify task is waiting
        assertFalse(promise.isDone())
        
        // Complete the task
        task.complete(
            outcome: CompletionOutcome.SUCCESS,
            completedBy: "john.doe",
            comments: "Looks good!"
        )
        
        // Wait for completion
        def result = awaitPromise(promise)
        
        // Verify result
        assertEquals("SUCCESS", result.outcome)
        assertEquals("john.doe", result.completedBy)
        assertEquals("Looks good!", result.comments)
        assertNotNull(result.completedAt)
    }

    @Test
    void testManualTaskWithTimeout() {
        def task = new ManualTask("timeout-task", "Timeout Task", ctx)
        task.title = "Time Sensitive Approval"
        task.timeout(Duration.ofMillis(500), [autoAction: CompletionOutcome.SKIP])
        
        def promise = task.execute(ctx.promiseFactory.createPromise(null))
        
        // Wait for timeout to trigger
        def result = awaitPromise(promise)
        
        // Should auto-complete with SKIP
        assertEquals("SKIP", result.outcome)
        assertEquals("SYSTEM_TIMEOUT", result.completedBy)
        assertTrue(result.comments.contains("timed out"))
    }

    @Test
    void testManualTaskInWorkflow() {
        def results = [:]
        
        def graph = TaskGraph.build {
            serviceTask("prepare") {
                action { ctx, _ ->
                    ctx.promiseFactory.executeAsync {
                        [documentId: 123, content: "Draft document"]
                    }
                }
            }
            
            task("manual-review", TaskType.MANUAL) {
                title "Review Document"
                description "Please review the document"
                assignee "reviewer@company.com"
                priority Priority.HIGH
                
                onSuccess { ctx ->
                    results.completed = true
                }
            }
            
            fork("workflow") {
                from "prepare"
                to "manual-review"
            }
        }
        
        // Start workflow
        def promise = graph.run()
        
        // Wait for prepare task
        Thread.sleep(100)
        
        // Complete the manual task
        def manualTask = graph.tasks["manual-review"] as ManualTask
        manualTask.complete(
            outcome: CompletionOutcome.SUCCESS,
            completedBy: "reviewer@company.com"
        )
        
        // Wait for workflow
        awaitPromise(promise)
        
        // Verify
        assertEquals(TaskState.COMPLETED, graph.tasks["prepare"].state)
        assertEquals(TaskState.COMPLETED, graph.tasks["manual-review"].state)
        assertTrue(results.completed)
    }
}
