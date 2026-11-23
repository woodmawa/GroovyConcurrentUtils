package org.softwood.dag.resilience

import org.softwood.dag.task.ITask
import org.softwood.dag.task.TaskContext
import org.softwood.dag.task.TaskFactory
import org.softwood.pool.ExecutorPoolFactory
import org.softwood.promise.core.dataflow.DataflowPromiseFactory
import spock.lang.Specification
import spock.lang.Timeout as SpockTimeout

import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Integration tests for Dead Letter Queue functionality.
 */
class DeadLetterQueueIntegrationTest extends Specification {
    
    TaskContext ctx
    DeadLetterQueue dlq
    
    def setup() {
        def pool = ExecutorPoolFactory.builder()
            .name("dlq-test-pool")
            .build()
        ctx = new TaskContext(pool, new DataflowPromiseFactory())
        dlq = new DeadLetterQueue()
    }
    
    def cleanup() {
        ctx?.pool?.shutdown()
        dlq?.clear()
    }
    
    // =========================================================================
    // Basic DLQ Operations
    // =========================================================================
    
    def "test add entry to DLQ"() {
        given:
        def task = TaskFactory.createServiceTask("test-task", "Test Task", ctx)
        def exception = new RuntimeException("Test failure")
        
        when:
        def entryId = dlq.add(task, "input-value", exception)
        
        then:
        entryId != null
        dlq.size() == 1
        
        def entry = dlq.getEntry(entryId)
        entry.taskId == "test-task"
        entry.taskName == "Test Task"
        entry.exceptionMessage == "Test failure"
        entry.inputValue == "input-value"
    }
    
    def "test add multiple entries"() {
        given:
        def task1 = TaskFactory.createServiceTask("task-1", "Task 1", ctx)
        def task2 = TaskFactory.createServiceTask("task-2", "Task 2", ctx)
        def exception = new RuntimeException("Test failure")
        
        when:
        dlq.add(task1, "input-1", exception)
        dlq.add(task2, "input-2", exception)
        dlq.add(task1, "input-3", exception)
        
        then:
        dlq.size() == 3
        dlq.getEntriesByTaskId("task-1").size() == 2
        dlq.getEntriesByTaskId("task-2").size() == 1
    }
    
    def "test get entries by task ID"() {
        given:
        def task1 = TaskFactory.createServiceTask("task-1", "Task 1", ctx)
        def task2 = TaskFactory.createServiceTask("task-2", "Task 2", ctx)
        def exception = new RuntimeException("Test failure")
        
        when:
        dlq.add(task1, null, exception)
        dlq.add(task1, null, exception)
        dlq.add(task2, null, exception)
        
        then:
        def task1Entries = dlq.getEntriesByTaskId("task-1")
        task1Entries.size() == 2
        task1Entries.every { it.taskId == "task-1" }
        
        def task2Entries = dlq.getEntriesByTaskId("task-2")
        task2Entries.size() == 1
        task2Entries[0].taskId == "task-2"
    }
    
    def "test get entries by exception type"() {
        given:
        def task = TaskFactory.createServiceTask("test-task", "Test Task", ctx)
        
        when:
        dlq.add(task, null, new RuntimeException("runtime error"))
        dlq.add(task, null, new TimeoutException("timeout error"))
        dlq.add(task, null, new RuntimeException("another runtime error"))
        
        then:
        def runtimeEntries = dlq.getEntriesByExceptionType(RuntimeException)
        runtimeEntries.size() == 2
        
        def timeoutEntries = dlq.getEntriesByExceptionType(TimeoutException)
        timeoutEntries.size() == 1
    }
    
    def "test remove entry"() {
        given:
        def task = TaskFactory.createServiceTask("test-task", "Test Task", ctx)
        def exception = new RuntimeException("Test failure")
        def entryId = dlq.add(task, null, exception)
        
        when:
        def removed = dlq.remove(entryId)
        
        then:
        removed
        dlq.size() == 0
        !dlq.contains(entryId)
    }
    
    def "test remove all for task"() {
        given:
        def task1 = TaskFactory.createServiceTask("task-1", "Task 1", ctx)
        def task2 = TaskFactory.createServiceTask("task-2", "Task 2", ctx)
        def exception = new RuntimeException("Test failure")
        
        when:
        dlq.add(task1, null, exception)
        dlq.add(task1, null, exception)
        dlq.add(task2, null, exception)
        def removedCount = dlq.removeAllForTask("task-1")
        
        then:
        removedCount == 2
        dlq.size() == 1
        dlq.getEntriesByTaskId("task-1").isEmpty()
        dlq.getEntriesByTaskId("task-2").size() == 1
    }
    
    def "test clear DLQ"() {
        given:
        def task = TaskFactory.createServiceTask("test-task", "Test Task", ctx)
        def exception = new RuntimeException("Test failure")
        
        when:
        3.times { dlq.add(task, null, exception) }
        dlq.clear()
        
        then:
        dlq.isEmpty()
        dlq.size() == 0
    }
    
    // =========================================================================
    // Size Limits
    // =========================================================================
    
    def "test max size enforcement with remove oldest"() {
        given:
        dlq.maxSize = 3
        def task = TaskFactory.createServiceTask("test-task", "Test Task", ctx)
        def exception = new RuntimeException("Test failure")
        
        when:
        def entry1Id = dlq.add(task, "input-1", exception)
        Thread.sleep(10)  // Ensure different timestamps
        def entry2Id = dlq.add(task, "input-2", exception)
        Thread.sleep(10)
        def entry3Id = dlq.add(task, "input-3", exception)
        Thread.sleep(10)
        def entry4Id = dlq.add(task, "input-4", exception)  // Should trigger removal of oldest
        
        then:
        dlq.size() == 3
        !dlq.contains(entry1Id)  // Oldest removed
        dlq.contains(entry2Id)
        dlq.contains(entry3Id)
        dlq.contains(entry4Id)
    }
    
    def "test max size zero means unlimited"() {
        given:
        dlq.maxSize = 0
        def task = TaskFactory.createServiceTask("test-task", "Test Task", ctx)
        def exception = new RuntimeException("Test failure")
        
        when:
        10.times { dlq.add(task, null, exception) }
        
        then:
        dlq.size() == 10
    }
    
    // =========================================================================
    // Expiration
    // =========================================================================
    
    def "test remove expired entries"() {
        given:
        dlq.maxAge = Duration.ofMillis(100)
        def task = TaskFactory.createServiceTask("test-task", "Test Task", ctx)
        def exception = new RuntimeException("Test failure")
        
        when:
        dlq.add(task, null, exception)
        Thread.sleep(150)  // Wait for entries to expire
        dlq.add(task, null, exception)  // Add fresh entry
        def removedCount = dlq.removeExpiredEntries()
        
        then:
        removedCount == 1
        dlq.size() == 1
    }
    
    def "test no expiration when maxAge is null"() {
        given:
        dlq.maxAge = null
        def task = TaskFactory.createServiceTask("test-task", "Test Task", ctx)
        def exception = new RuntimeException("Test failure")
        
        when:
        dlq.add(task, null, exception)
        Thread.sleep(100)
        def removedCount = dlq.removeExpiredEntries()
        
        then:
        removedCount == 0
        dlq.size() == 1
    }
    
    // =========================================================================
    // Querying
    // =========================================================================
    
    def "test get entries with filter"() {
        given:
        def task = TaskFactory.createServiceTask("test-task", "Test Task", ctx)
        
        when:
        dlq.add(task, null, new RuntimeException("runtime error"))
        dlq.add(task, null, new TimeoutException("timeout error"))
        dlq.add(task, null, new IllegalArgumentException("illegal arg"))
        
        def runtimeEntries = dlq.getEntriesWhere { entry ->
            entry.exceptionType.contains("Runtime")
        }
        
        then:
        runtimeEntries.size() == 1
        runtimeEntries[0].exceptionType == "java.lang.RuntimeException"
    }
    
    def "test get entries newer than timestamp"() {
        given:
        def task = TaskFactory.createServiceTask("test-task", "Test Task", ctx)
        def exception = new RuntimeException("Test failure")
        
        when:
        dlq.add(task, null, exception)
        Thread.sleep(50)
        def cutoffTime = Instant.now()
        Thread.sleep(50)
        dlq.add(task, null, exception)
        dlq.add(task, null, exception)
        
        def recentEntries = dlq.getEntriesNewerThan(cutoffTime)
        
        then:
        recentEntries.size() == 2
    }
    
    // =========================================================================
    // Statistics
    // =========================================================================
    
    def "test DLQ statistics"() {
        given:
        dlq.maxSize = 10
        def task = TaskFactory.createServiceTask("test-task", "Test Task", ctx)
        def exception = new RuntimeException("Test failure")
        
        when:
        3.times { dlq.add(task, null, exception) }
        dlq.remove(dlq.getAllEntries()[0].entryId)
        
        def stats = dlq.getStats()
        
        then:
        stats.currentSize == 2
        stats.maxSize == 10
        stats.totalEntriesAdded == 3
        stats.totalEntriesRemoved == 1
        stats.utilizationPercent == 20.0
    }
    
    def "test task summary"() {
        given:
        def task1 = TaskFactory.createServiceTask("task-1", "Task 1", ctx)
        def task2 = TaskFactory.createServiceTask("task-2", "Task 2", ctx)
        def exception = new RuntimeException("Test failure")
        
        when:
        2.times { dlq.add(task1, null, exception) }
        3.times { dlq.add(task2, null, exception) }
        
        def summary = dlq.getTaskSummary()
        
        then:
        summary["task-1"] == 2
        summary["task-2"] == 3
    }
    
    def "test exception summary"() {
        given:
        def task = TaskFactory.createServiceTask("test-task", "Test Task", ctx)
        
        when:
        2.times { dlq.add(task, null, new RuntimeException("runtime error")) }
        1.times { dlq.add(task, null, new TimeoutException("timeout error")) }
        
        def summary = dlq.getExceptionSummary()
        
        then:
        summary["java.lang.RuntimeException"] == 2
        summary["java.util.concurrent.TimeoutException"] == 1
    }
    
    // =========================================================================
    // Callbacks
    // =========================================================================
    
    def "test onEntryAdded callback"() {
        given:
        def callbackInvoked = new AtomicInteger(0)
        def capturedEntry = null
        
        dlq.onEntryAdded = { entry ->
            callbackInvoked.incrementAndGet()
            capturedEntry = entry
        }
        
        def task = TaskFactory.createServiceTask("test-task", "Test Task", ctx)
        def exception = new RuntimeException("Test failure")
        
        when:
        dlq.add(task, "test-input", exception)
        
        then:
        callbackInvoked.get() == 1
        capturedEntry != null
        capturedEntry.taskId == "test-task"
    }
    
    def "test onEntryRemoved callback"() {
        given:
        def callbackInvoked = new AtomicInteger(0)
        def capturedEntry = null
        
        dlq.onEntryRemoved = { entry ->
            callbackInvoked.incrementAndGet()
            capturedEntry = entry
        }
        
        def task = TaskFactory.createServiceTask("test-task", "Test Task", ctx)
        def exception = new RuntimeException("Test failure")
        def entryId = dlq.add(task, null, exception)
        
        when:
        dlq.remove(entryId)
        
        then:
        callbackInvoked.get() == 1
        capturedEntry != null
        capturedEntry.entryId == entryId
    }
    
    def "test onQueueFull callback"() {
        given:
        def callbackInvoked = new AtomicInteger(0)
        def capturedSize = 0
        def capturedMaxSize = 0
        
        dlq.maxSize = 2
        dlq.onQueueFull = { currentSize, maxSize ->
            callbackInvoked.incrementAndGet()
            capturedSize = currentSize
            capturedMaxSize = maxSize
        }
        
        def task = TaskFactory.createServiceTask("test-task", "Test Task", ctx)
        def exception = new RuntimeException("Test failure")
        
        when:
        3.times { dlq.add(task, null, exception) }  // Third add should trigger callback
        
        then:
        callbackInvoked.get() == 1
        capturedSize == 2
        capturedMaxSize == 2
    }
    
    // =========================================================================
    // Entry Details
    // =========================================================================
    
    def "test entry captures stack trace"() {
        given:
        def task = TaskFactory.createServiceTask("test-task", "Test Task", ctx)
        def exception = new RuntimeException("Test failure")
        
        when:
        def entryId = dlq.add(task, null, exception)
        def entry = dlq.getEntry(entryId)
        
        then:
        entry.stackTrace != null
        entry.stackTrace.contains("RuntimeException")
        entry.stackTrace.contains("Test failure")
    }
    
    def "test entry captures metadata"() {
        given:
        def task = TaskFactory.createServiceTask("test-task", "Test Task", ctx)
        def exception = new RuntimeException("Test failure")
        def metadata = [
            userId: "user123",
            requestId: "req456",
            environment: "production"
        ]
        
        when:
        def entryId = dlq.add(task, null, exception, 1, null, metadata)
        def entry = dlq.getEntry(entryId)
        
        then:
        entry.metadata.userId == "user123"
        entry.metadata.requestId == "req456"
        entry.metadata.environment == "production"
    }
    
    def "test entry summary and details"() {
        given:
        def task = TaskFactory.createServiceTask("test-task", "Test Task", ctx)
        def exception = new RuntimeException("Test failure")
        
        when:
        def entryId = dlq.add(task, "input-value", exception, 3, "run-123")
        def entry = dlq.getEntry(entryId)
        
        def summary = entry.getSummary()
        def details = entry.getDetails()
        
        then:
        summary.contains("test-task")
        summary.contains("Test failure")
        
        details.taskId == "test-task"
        details.taskName == "Test Task"
        details.attemptCount == 3
        details.runId == "run-123"
        details.hasInput
    }
    
    // =========================================================================
    // Retry Tracking
    // =========================================================================
    
    def "test retry count tracking"() {
        given:
        def task = TaskFactory.createServiceTask("test-task", "Test Task", ctx)
        def exception = new RuntimeException("Test failure")
        def entryId = dlq.add(task, null, exception)
        def entry = dlq.getEntry(entryId)
        
        when:
        entry.recordRetry(DeadLetterQueueEntry.RetryResult.FAILED_SAME_ERROR)
        entry.recordRetry(DeadLetterQueueEntry.RetryResult.SUCCESS)
        
        then:
        entry.retryCount == 2
        entry.lastRetryResult == DeadLetterQueueEntry.RetryResult.SUCCESS
        entry.lastRetryTimestamp != null
    }
    
    def "test can retry check"() {
        given:
        def task = TaskFactory.createServiceTask("test-task", "Test Task", ctx)
        def exception = new RuntimeException("Test failure")
        def entryId = dlq.add(task, null, exception)
        def entry = dlq.getEntry(entryId)
        
        expect:
        entry.canRetry(3)
        
        when:
        3.times { entry.recordRetry(DeadLetterQueueEntry.RetryResult.FAILED_SAME_ERROR) }
        
        then:
        !entry.canRetry(3)
    }
    
    // =========================================================================
    // Edge Cases
    // =========================================================================
    
    def "test add entry with null input value"() {
        given:
        def task = TaskFactory.createServiceTask("test-task", "Test Task", ctx)
        def exception = new RuntimeException("Test failure")
        
        when:
        def entryId = dlq.add(task, null, exception)
        def entry = dlq.getEntry(entryId)
        
        then:
        entry != null
        entry.inputValue == null
    }
    
    def "test add entry with complex input value"() {
        given:
        def task = TaskFactory.createServiceTask("test-task", "Test Task", ctx)
        def exception = new RuntimeException("Test failure")
        def complexInput = [
            data: [1, 2, 3],
            metadata: [key: "value"],
            nested: [deep: [deeper: "value"]]
        ]
        
        when:
        def entryId = dlq.add(task, complexInput, exception)
        def entry = dlq.getEntry(entryId)
        
        then:
        entry.inputValue == complexInput
    }
    
    def "test concurrent access to DLQ"() {
        given:
        def task = TaskFactory.createServiceTask("test-task", "Test Task", ctx)
        def exception = new RuntimeException("Test failure")
        def threads = 10
        def entriesPerThread = 10
        
        when:
        def futures = (1..threads).collect {
            ctx.pool.execute {
                entriesPerThread.times {
                    dlq.add(task, null, exception)
                }
            }
        }
        futures*.get()  // Wait for all threads
        
        then:
        dlq.size() == threads * entriesPerThread
    }
    
    def "test exception with no message"() {
        given:
        def task = TaskFactory.createServiceTask("test-task", "Test Task", ctx)
        def exception = new NullPointerException()  // No message
        
        when:
        def entryId = dlq.add(task, null, exception)
        def entry = dlq.getEntry(entryId)
        
        then:
        entry.exceptionMessage == "NullPointerException"
    }
}
