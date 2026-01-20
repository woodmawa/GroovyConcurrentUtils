package org.softwood.dag.test

import groovy.util.logging.Slf4j
import org.junit.jupiter.api.BeforeEach
import org.softwood.dag.TaskGraph
import org.softwood.dag.task.ITask
import org.softwood.dag.task.TaskContext
import org.softwood.promise.Promise

import static org.junit.jupiter.api.Assertions.*

/**
 * Base class for TaskGraph testing with fluent, Spock-style API.
 *
 * Provides clean, expressive syntax for testing workflows:
 * - Fluent execution API
 * - Task execution tracking
 * - Input/output verification
 * - Execution order assertions
 * - Clean async handling (no Awaitility boilerplate)
 *
 * <h3>Example Usage:</h3>
 * <pre>
 * class MyWorkflowSpec extends TaskGraphSpec {
 *
 *     def "should process customer successfully"() {
 *         given: "a customer workflow"
 *         def workflow = CustomerWorkflows.processCustomer()
 *
 *         when: "workflow executes with valid input"
 *         def result = execute(workflow)
 *             .with([customerId: "123"])
 *             .await()
 *
 *         then: "customer is validated and enriched"
 *         result.validated == true
 *         result.creditScore != null
 *
 *         and: "tasks executed in order"
 *         verifyExecutionOrder("validate", "enrich", "save")
 *     }
 *
 *     def "should handle validation failure"() {
 *         given: "a validation workflow"
 *         def workflow = ValidationWorkflows.validate()
 *
 *         when: "invalid input provided"
 *         def result = execute(workflow)
 *             .with([customerId: null])
 *             .expectFailure()
 *
 *         then: "validation error captured"
 *         result.failed
 *         result.error.message.contains("customerId required")
 *     }
 * }
 * </pre>
 */
@Slf4j
abstract class TaskGraphSpec {

    /** Task context for test execution */
    protected TaskContext ctx

    /** Current test execution wrapper */
    protected TaskGraphExecution currentExecution

    /** Verifier for assertions */
    protected TaskGraphVerifier verifier

    @BeforeEach
    void setupTaskGraphSpec() {
        ctx = new TaskContext()
        verifier = new TaskGraphVerifier()
        log.debug("TaskGraphSpec setup complete")
    }

    // =========================================================================
    // Fluent Execution API
    // =========================================================================

    /**
     * Start workflow execution with fluent API.
     *
     * Usage:
     *   execute(workflow)
     *     .with([input: data])
     *     .await()
     */
    protected TaskGraphExecution execute(TaskGraph workflow) {
        currentExecution = new TaskGraphExecution(workflow, ctx, verifier)
        return currentExecution
    }

    /**
     * Execute workflow and await result (convenience method).
     *
     * Usage:
     *   def result = executeAndAwait(workflow, [input: data])
     */
    protected Object executeAndAwait(TaskGraph workflow, Object input = null) {
        return execute(workflow).with(input).await()
    }

    // =========================================================================
    // Verification API
    // =========================================================================

    /**
     * Verify tasks executed in specific order.
     *
     * Usage:
     *   verifyExecutionOrder("validate", "process", "save")
     */
    protected void verifyExecutionOrder(String... taskIds) {
        verifier.verifyExecutionOrder(taskIds as List)
    }

    /**
     * Verify task was executed.
     */
    protected void verifyTaskExecuted(String taskId) {
        assertTrue(verifier.wasTaskExecuted(taskId),
            "Expected task '${taskId}' to be executed")
    }

    /**
     * Verify task was NOT executed.
     */
    protected void verifyTaskNotExecuted(String taskId) {
        assertFalse(verifier.wasTaskExecuted(taskId),
            "Expected task '${taskId}' to NOT be executed")
    }

    /**
     * Get input received by a task.
     */
    protected Object taskInput(String taskId) {
        return verifier.getTaskInput(taskId)
    }

    /**
     * Get output produced by a task.
     */
    protected Object taskOutput(String taskId) {
        return verifier.getTaskOutput(taskId)
    }

    /**
     * Get task by ID from current execution.
     */
    protected ITask getTask(String taskId) {
        return verifier.getTask(taskId)
    }

    /**
     * Get all executed task IDs in order.
     */
    protected List<String> getExecutedTasks() {
        return verifier.getExecutedTaskIds()
    }

    /**
     * Verify workflow completed successfully.
     */
    protected void verifySuccess() {
        assertTrue(verifier.wasSuccessful(),
            "Expected workflow to complete successfully")
    }

    /**
     * Verify workflow failed.
     */
    protected void verifyFailure() {
        assertTrue(verifier.wasFailed(),
            "Expected workflow to fail")
    }

    // =========================================================================
    // Assertion Helpers
    // =========================================================================

    /**
     * Assert that result has specific field value.
     */
    protected void assertResultHas(Object result, String field, Object expectedValue) {
        def actualValue = result[field]
        assertEquals(expectedValue, actualValue,
            "Expected result.${field} to be ${expectedValue} but was ${actualValue}")
    }

    /**
     * Assert that result has field (exists, not null).
     */
    protected void assertResultHasField(Object result, String field) {
        assertNotNull(result[field],
            "Expected result to have field '${field}'")
    }

    /**
     * Assert task completed successfully.
     */
    protected void assertTaskSucceeded(String taskId) {
        def task = getTask(taskId)
        assertEquals(org.softwood.dag.task.TaskState.COMPLETED, task.state,
            "Expected task '${taskId}' to be COMPLETED but was ${task.state}")
    }

    /**
     * Assert task failed.
     */
    protected void assertTaskFailed(String taskId) {
        def task = getTask(taskId)
        assertEquals(org.softwood.dag.task.TaskState.FAILED, task.state,
            "Expected task '${taskId}' to be FAILED but was ${task.state}")
    }

    // =========================================================================
    // Timing Helpers
    // =========================================================================

    /**
     * Get execution time for a task in milliseconds.
     */
    protected long getTaskExecutionTime(String taskId) {
        return verifier.getTaskExecutionTime(taskId)
    }

    /**
     * Get total workflow execution time.
     */
    protected long getTotalExecutionTime() {
        return verifier.getTotalExecutionTime()
    }

    // =========================================================================
    // Mock Support
    // =========================================================================

    /**
     * Create a mock task that returns a specific value.
     *
     * Usage:
     *   mockTask("external-api", [result: "mocked"])
     */
    protected void mockTask(String taskId, Object mockResult) {
        // Store mock for later injection
        if (!ctx.globals.containsKey('__mocks__')) {
            ctx.globals['__mocks__'] = [:]
        }
        ctx.globals['__mocks__'][taskId] = mockResult
        log.debug("Mocked task '${taskId}' with result: ${mockResult}")
    }

    /**
     * Create a mock task that throws an exception.
     */
    protected void mockTaskFailure(String taskId, Exception exception) {
        if (!ctx.globals.containsKey('__mockFailures__')) {
            ctx.globals['__mockFailures__'] = [:]
        }
        ctx.globals['__mockFailures__'][taskId] = exception
        log.debug("Mocked task '${taskId}' to throw: ${exception.class.simpleName}")
    }

    // =========================================================================
    // Test Data Builders
    // =========================================================================

    /**
     * Build test input map with fluent API.
     */
    protected static TestInputBuilder input() {
        return new TestInputBuilder()
    }

    /**
     * Fluent builder for test input data.
     */
    static class TestInputBuilder {
        private Map data = [:]

        TestInputBuilder with(String key, Object value) {
            data[key] = value
            return this
        }

        Map build() {
            return data
        }

        // Convenience method to use directly in tests
        Map call() {
            return build()
        }
    }
}
