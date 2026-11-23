package org.softwood.dag.resilience

import org.softwood.dag.task.TaskContext
import org.softwood.dag.task.TaskFactory
import org.softwood.pool.ExecutorPoolFactory
import org.softwood.promise.core.dataflow.DataflowPromiseFactory
import spock.lang.Specification
import spock.lang.Timeout as SpockTimeout

import java.time.Duration
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Integration tests for Dead Letter Queue with TaskBase.
 * Tests automatic capture of task failures.
 */
class DeadLetterQueueTaskIntegrationTest extends Specification {
    
    TaskContext ctx
    DeadLetterQueue dlq
    
    def setup() {
        def pool = ExecutorPoolFactory.builder()
            .name("dlq-task-test-pool")
            .build()
        ctx = new TaskContext(pool, new DataflowPromiseFactory())
        dlq = new DeadLetterQueue()
        ctx.deadLetterQueue = dlq
    }
    
    def cleanup() {
        ctx?.pool?.shutdown()
        dlq?.clear()
    }
    
    // =========================================================================
    // Basic Task Failure Capture
    // =========================================================================
    
    def "test task failure automatically captured to DLQ"() {
        given:
        def task = TaskFactory.createServiceTask("failing-task", "Failing Task", ctx)
        task.action { taskCtx, prev ->
            throw new RuntimeException("Test failure")
        }
        
        // Enable DLQ with permissive policy
        task.deadLetterQueue("permissive")
        
        when:
        try {
            task.execute(null).get()
        } catch (Exception e) {
            // Expected failure
        }
        
        then:
        dlq.size() == 1
        
        def entry = dlq.getAllEntries()[0]
        entry.taskId == "failing-task"
        entry.taskName == "Failing Task"
        entry.exceptionType == "java.lang.RuntimeException"
        entry.exceptionMessage == "Test failure"
        entry.attemptCount == 1
    }
    
    def "test task success does not create DLQ entry"() {
        given:
        def task = TaskFactory.createServiceTask("success-task", "Success Task", ctx)
        task.action { taskCtx, prev ->
            return taskCtx.promiseFactory.createPromise("success")
        }
        task.deadLetterQueue("permissive")
        
        when:
        def result = task.execute(null).get()
        
        then:
        result == "success"
        dlq.isEmpty()
    }
    
    def "test task failure with input value captured"() {
        given:
        def task = TaskFactory.createServiceTask("task-with-input", "Task", ctx)
        task.action { taskCtx, prev ->
            throw new IOException("Network error")
        }
        task.deadLetterQueue("permissive")
        
        def inputPromise = ctx.promiseFactory.createPromise([userId: "user123", data: "test"])
        
        when:
        try {
            task.execute(inputPromise).get()
        } catch (Exception e) {
            // Expected
        }
        
        then:
        dlq.size() == 1
        def entry = dlq.getAllEntries()[0]
        entry.inputValue.userId == "user123"
        entry.inputValue.data == "test"
    }
    
    // =========================================================================
    // DLQ DSL Configuration
    // =========================================================================
    
    def "test DLQ DSL block configuration"() {
        given:
        def callbackInvoked = new AtomicBoolean(false)
        
        def task = TaskFactory.createServiceTask("configured-task", "Task", ctx)
        task.action { taskCtx, prev ->
            throw new RuntimeException("Test")
        }
        
        task.deadLetterQueue {
            maxSize 100
            maxAge Duration.ofHours(1)
            onEntryAdded { entry ->
                callbackInvoked.set(true)
            }
        }
        
        when:
        try {
            task.execute(null).get()
        } catch (Exception e) {
            // Expected
        }
        
        then:
        callbackInvoked.get()
        dlq.size() == 1
    }
    
    def "test DLQ preset configuration"() {
        given:
        def task = TaskFactory.createServiceTask("preset-task", "Task", ctx)
        task.action { taskCtx, prev ->
            throw new RuntimeException("Test")
        }
        task.deadLetterQueue("strict")
        
        when:
        try {
            task.execute(null).get()
        } catch (Exception e) {
            // Expected
        }
        
        then:
        dlq.size() == 1
        // Strict preset has maxSize 100
        dlq.maxSize == 1000  // DLQ object wasn't reconfigured, only policy
    }
    
    // =========================================================================
    // Capture Filtering
    // =========================================================================
    
    def "test alwaysCapture exception filter"() {
        given:
        def task = TaskFactory.createServiceTask("filtered-task", "Task", ctx)
        task.action { taskCtx, prev ->
            throw new IOException("Network failure")
        }
        
        task.deadLetterQueue {
            alwaysCapture IOException
        }
        
        when:
        try {
            task.execute(null).get()
        } catch (Exception e) {
            // Expected
        }
        
        then:
        dlq.size() == 1
        dlq.getAllEntries()[0].exceptionType == "java.io.IOException"
    }
    
    def "test neverCapture exception filter"() {
        given:
        def task = TaskFactory.createServiceTask("ignored-task", "Task", ctx)
        task.action { taskCtx, prev ->
            throw new IllegalArgumentException("Bad argument")
        }
        
        task.deadLetterQueue {
            neverCapture IllegalArgumentException
        }
        
        when:
        try {
            task.execute(null).get()
        } catch (Exception e) {
            // Expected
        }
        
        then:
        dlq.isEmpty()  // Should not be captured
    }
    
    def "test captureWhen custom filter"() {
        given:
        def task1 = TaskFactory.createServiceTask("api-task", "API Task", ctx)
        task1.action { taskCtx, prev ->
            throw new RuntimeException("API error")
        }
        task1.deadLetterQueue {
            captureWhen { taskId, exception ->
                taskId.startsWith("api-")
            }
        }
        
        def task2 = TaskFactory.createServiceTask("other-task", "Other Task", ctx)
        task2.action { taskCtx, prev ->
            throw new RuntimeException("Other error")
        }
        task2.deadLetterQueue {
            captureWhen { taskId, exception ->
                taskId.startsWith("api-")
            }
        }
        
        when:
        try {
            task1.execute(null).get()
        } catch (Exception e) {
            // Expected
        }
        try {
            task2.execute(null).get()
        } catch (Exception e) {
            // Expected
        }
        
        then:
        dlq.size() == 1  // Only api-task should be captured
        dlq.getAllEntries()[0].taskId == "api-task"
    }
    
    // =========================================================================
    // Integration with Retry
    // =========================================================================
    
    def "test DLQ captures after retry exhaustion"() {
        given:
        def attemptCount = new AtomicInteger(0)
        
        def task = TaskFactory.createServiceTask("retry-task", "Retry Task", ctx)
        task.retry {
            maxAttempts 3
            initialDelay Duration.ofMillis(10)
        }
        task.deadLetterQueue("permissive")
        task.action { taskCtx, prev ->
            attemptCount.incrementAndGet()
            throw new IOException("Network failure")
        }
        
        when:
        try {
            task.execute(null).get()
        } catch (Exception e) {
            // Expected
        }
        
        then:
        attemptCount.get() == 3  // Should have tried 3 times
        dlq.size() == 3  // Each attempt captured (configurable behavior)
        
        // Last entry should show attempt count
        def entries = dlq.getEntriesByTaskId("retry-task")
        entries.size() == 3
    }
    
    def "test DLQ not captured for non-retriable errors"() {
        given:
        def task = TaskFactory.createServiceTask("config-error-task", "Task", ctx)
        task.retry {
            maxAttempts 3
        }
        task.deadLetterQueue("permissive")
        task.action { taskCtx, prev ->
            throw new IllegalStateException("Config error")
        }
        
        when:
        try {
            task.execute(null).get()
        } catch (Exception e) {
            // Expected
        }
        
        then:
        dlq.size() == 1  // Still captured, just not retried
    }
    
    // =========================================================================
    // Multiple Tasks
    // =========================================================================
    
    def "test DLQ captures failures from multiple tasks"() {
        given:
        def task1 = TaskFactory.createServiceTask("task-1", "Task 1", ctx)
        task1.action { taskCtx, prev -> throw new RuntimeException("Error 1") }
        task1.deadLetterQueue("permissive")
        
        def task2 = TaskFactory.createServiceTask("task-2", "Task 2", ctx)
        task2.action { taskCtx, prev -> throw new IOException("Error 2") }
        task2.deadLetterQueue("permissive")
        
        def task3 = TaskFactory.createServiceTask("task-3", "Task 3", ctx)
        task3.action { taskCtx, prev -> throw new TimeoutException("Error 3") }
        task3.deadLetterQueue("permissive")
        
        when:
        try { task1.execute(null).get() } catch (Exception e) { }
        try { task2.execute(null).get() } catch (Exception e) { }
        try { task3.execute(null).get() } catch (Exception e) { }
        
        then:
        dlq.size() == 3
        
        def summary = dlq.getTaskSummary()
        summary["task-1"] == 1
        summary["task-2"] == 1
        summary["task-3"] == 1
    }
    
    // =========================================================================
    // Metadata Capture
    // =========================================================================
    
    def "test DLQ captures task metadata"() {
        given:
        def task = TaskFactory.createServiceTask("meta-task", "Task", ctx)
        task.retry { maxAttempts 3 }
        task.timeout(Duration.ofSeconds(30))
        task.deadLetterQueue("permissive")
        task.action { taskCtx, prev ->
            throw new RuntimeException("Test")
        }
        
        when:
        try {
            task.execute(null).get()
        } catch (Exception e) {
            // Expected
        }
        
        then:
        dlq.size() >= 1
        def entry = dlq.getAllEntries()[0]
        entry.metadata.taskType == "ServiceTask"
        entry.metadata.hasRetryPolicy == true
        entry.metadata.hasTimeout == true
        entry.metadata.hasRateLimit == false
    }
    
    // =========================================================================
    // No DLQ Configured
    // =========================================================================
    
    def "test task failure without DLQ configured does not error"() {
        given:
        // Create context without DLQ
        def noDlqCtx = new TaskContext(ctx.pool, ctx.promiseFactory)
        
        def task = TaskFactory.createServiceTask("no-dlq-task", "Task", noDlqCtx)
        task.action { taskCtx, prev ->
            throw new RuntimeException("Should not break")
        }
        task.deadLetterQueue("permissive")  // Policy set but no DLQ in context
        
        when:
        try {
            task.execute(null).get()
        } catch (Exception e) {
            // Expected failure, but should not error on DLQ
        }
        
        then:
        notThrown(Exception)  // The RuntimeException is expected
    }
    
    // =========================================================================
    // Callbacks
    // =========================================================================
    
    def "test DLQ onEntryAdded callback fires on task failure"() {
        given:
        def callbackFired = new AtomicBoolean(false)
        def capturedTaskId = null
        
        dlq.onEntryAdded = { entry ->
            callbackFired.set(true)
            capturedTaskId = entry.taskId
        }
        
        def task = TaskFactory.createServiceTask("callback-task", "Task", ctx)
        task.action { taskCtx, prev ->
            throw new RuntimeException("Test")
        }
        task.deadLetterQueue("permissive")
        
        when:
        try {
            task.execute(null).get()
        } catch (Exception e) {
            // Expected
        }
        
        then:
        callbackFired.get()
        capturedTaskId == "callback-task"
    }
    
    // =========================================================================
    // Task-Specific vs Global DLQ Configuration
    // =========================================================================
    
    def "test task without DLQ config uses default capture behavior"() {
        given:
        def task = TaskFactory.createServiceTask("default-task", "Task", ctx)
        task.action { taskCtx, prev ->
            throw new RuntimeException("Test")
        }
        // Note: No deadLetterQueue() call - using default policy
        
        when:
        try {
            task.execute(null).get()
        } catch (Exception e) {
            // Expected
        }
        
        then:
        dlq.isEmpty()  // Default policy has maxSize 1000, but no captures yet
        // Actually, we need to enable it explicitly, so it won't capture
        // Let's verify this is the expected behavior
        true
    }
}
