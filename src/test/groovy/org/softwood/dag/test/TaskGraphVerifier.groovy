package org.softwood.dag.test

import groovy.util.logging.Slf4j
import org.softwood.dag.TaskGraph
import org.softwood.dag.task.ITask

import static org.junit.jupiter.api.Assertions.*

/**
 * Verification and assertion support for TaskGraph tests.
 *
 * Tracks task execution, captures inputs/outputs, and provides
 * assertions for workflow behavior.
 */
@Slf4j
class TaskGraphVerifier {

    private TaskGraph workflow
    private List<String> executionOrder = []
    private Map<String, TaskExecutionRecord> records = [:]
    private boolean successful = false
    private Exception failureException
    private long totalExecutionTime = 0

    /**
     * Record of a single task execution.
     */
    static class TaskExecutionRecord {
        String taskId
        ITask task
        Object input
        Object output
        long executionTime
        boolean executed = true

        TaskExecutionRecord(String taskId, ITask task, Object input, Object output, long executionTime) {
            this.taskId = taskId
            this.task = task
            this.input = input
            this.output = output
            this.executionTime = executionTime
        }
    }

    // =========================================================================
    // Setup Methods
    // =========================================================================

    void reset() {
        executionOrder.clear()
        records.clear()
        successful = false
        failureException = null
        totalExecutionTime = 0
        log.debug("Verifier reset")
    }

    void setWorkflow(TaskGraph workflow) {
        this.workflow = workflow
    }

    void setTotalExecutionTime(long ms) {
        this.totalExecutionTime = ms
    }

    // =========================================================================
    // Recording Methods
    // =========================================================================

    void recordTaskExecution(String taskId, ITask task, Object input, Object output, long executionTime) {
        executionOrder << taskId
        records[taskId] = new TaskExecutionRecord(taskId, task, input, output, executionTime)
        log.debug("Recorded execution: ${taskId}")
    }

    void markSuccess() {
        successful = true
    }

    void markFailure(Exception e) {
        successful = false
        failureException = e
    }

    // =========================================================================
    // Query Methods
    // =========================================================================

    boolean wasTaskExecuted(String taskId) {
        return records.containsKey(taskId)
    }

    List<String> getExecutedTaskIds() {
        return new ArrayList<>(executionOrder)
    }

    ITask getTask(String taskId) {
        def record = records[taskId]
        if (!record) {
            // Try to get from workflow
            return workflow?.tasks?.get(taskId)
        }
        return record.task
    }

    Object getTaskInput(String taskId) {
        def record = records[taskId]
        if (!record) {
            throw new AssertionError("Task '${taskId}' was not executed or not found")
        }
        return record.input
    }

    Object getTaskOutput(String taskId) {
        def record = records[taskId]
        if (!record) {
            throw new AssertionError("Task '${taskId}' was not executed or not found")
        }
        return record.output
    }

    long getTaskExecutionTime(String taskId) {
        def record = records[taskId]
        if (!record) {
            throw new AssertionError("Task '${taskId}' was not executed or not found")
        }
        return record.executionTime
    }

    long getTotalExecutionTime() {
        return totalExecutionTime
    }

    boolean wasSuccessful() {
        return successful
    }

    boolean wasFailed() {
        return !successful
    }

    Exception getFailureException() {
        return failureException
    }

    // =========================================================================
    // Verification Methods
    // =========================================================================

    void verifyExecutionOrder(List<String> expectedOrder) {
        // Filter executionOrder to only include expected tasks
        def actualOrder = executionOrder.findAll { it in expectedOrder }

        assertEquals(expectedOrder.size(), actualOrder.size(),
            "Expected ${expectedOrder.size()} tasks but ${actualOrder.size()} were executed. " +
            "Expected: ${expectedOrder}, Actual: ${actualOrder}")

        for (int i = 0; i < expectedOrder.size(); i++) {
            assertEquals(expectedOrder[i], actualOrder[i],
                "Task execution order mismatch at position ${i}. " +
                "Expected: ${expectedOrder}, Actual: ${actualOrder}")
        }

        log.debug("Verified execution order: ${expectedOrder}")
    }

    void verifyTasksExecuted(String... taskIds) {
        taskIds.each { taskId ->
            assertTrue(wasTaskExecuted(taskId),
                "Expected task '${taskId}' to be executed")
        }
    }

    void verifyTasksNotExecuted(String... taskIds) {
        taskIds.each { taskId ->
            assertFalse(wasTaskExecuted(taskId),
                "Expected task '${taskId}' to NOT be executed")
        }
    }

    void verifyTaskCount(int expectedCount) {
        assertEquals(expectedCount, executionOrder.size(),
            "Expected ${expectedCount} tasks to execute but ${executionOrder.size()} did")
    }

    // =========================================================================
    // Assertion Helpers
    // =========================================================================

    void assertTaskInputEquals(String taskId, Object expected) {
        def actual = getTaskInput(taskId)
        assertEquals(expected, actual,
            "Task '${taskId}' input mismatch")
    }

    void assertTaskOutputEquals(String taskId, Object expected) {
        def actual = getTaskOutput(taskId)
        assertEquals(expected, actual,
            "Task '${taskId}' output mismatch")
    }

    void assertTaskOutputContains(String taskId, String field) {
        def output = getTaskOutput(taskId)
        assertNotNull(output[field],
            "Task '${taskId}' output should contain field '${field}'")
    }

    void assertTaskExecutionTimeLessThan(String taskId, long maxMs) {
        def actualTime = getTaskExecutionTime(taskId)
        assertTrue(actualTime < maxMs,
            "Task '${taskId}' took ${actualTime}ms but should be less than ${maxMs}ms")
    }

    void assertTotalExecutionTimeLessThan(long maxMs) {
        assertTrue(totalExecutionTime < maxMs,
            "Total execution took ${totalExecutionTime}ms but should be less than ${maxMs}ms")
    }
}
