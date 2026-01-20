package org.softwood.dag.test

import org.junit.jupiter.api.Test
import org.softwood.dag.TaskGraph
import org.softwood.dag.task.TaskType

import static org.junit.jupiter.api.Assertions.*

/**
 * Examples demonstrating TaskGraphSpec test harness usage.
 *
 * Shows clean, expressive testing patterns for TaskGraph workflows.
 */
class TaskGraphSpecExample extends TaskGraphSpec {

    // =========================================================================
    // Example Workflows (Library)
    // =========================================================================

    /**
     * Simple validation workflow for testing.
     */
    static TaskGraph createValidationWorkflow() {
        TaskGraph.build {
            serviceTask("validate-not-null") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        def valid = prev != null
                        [valid: valid, value: prev, step: "null-check"]
                    }
                }
            }

            serviceTask("validate-range") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        def number = prev.value instanceof Number ? prev.value : Double.parseDouble(prev.value.toString())
                        def inRange = number >= 0 && number <= 100
                        [valid: inRange, value: number, step: "range-check"]
                    }
                }
            }

            chainVia("validate-not-null", "validate-range")
        }
    }

    /**
     * Multi-step transformation workflow.
     */
    static TaskGraph createTransformWorkflow() {
        TaskGraph.build {
            serviceTask("parse") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        def value = prev instanceof Map ? prev.value : prev
                        [value: Double.parseDouble(value.toString()), step: "parsed"]
                    }
                }
            }

            serviceTask("double-it") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        [value: prev.value * 2, step: "doubled"]
                    }
                }
            }

            serviceTask("add-ten") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        [value: prev.value + 10, step: "added"]
                    }
                }
            }

            serviceTask("format") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        [result: "Result: ${prev.value}", step: "formatted"]
                    }
                }
            }

            chainVia("parse", "double-it", "add-ten", "format")
        }
    }

    /**
     * Workflow with conditional branching.
     */
    static TaskGraph createConditionalWorkflow() {
        TaskGraph.build {
            serviceTask("classify") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        def value = prev instanceof Number ? prev : Double.parseDouble(prev.toString())
                        def category = value < 50 ? "small" : value < 100 ? "medium" : "large"
                        [value: value, category: category]
                    }
                }
            }

            serviceTask("process") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        // Process based on category
                        def category = prev['category']
                        [result: "${category.capitalize()}: ${prev['value']}", category: category, processed: true]
                    }
                }
            }

            // Chain classify to process
            chainVia("classify", "process")
        }
    }

    // =========================================================================
    // Example 1: Basic Execution and Result Verification
    // =========================================================================

    @Test
    void testBasicWorkflowExecution() {
        // Given: A validation workflow
        def workflow = createValidationWorkflow()

        // When: Workflow executes with valid input
        def result = execute(workflow)
            .with("50")
            .await()

        // Then: Validation passes
        assertTrue(result.valid)
        assertEquals(50.0, result.value)
        assertEquals("range-check", result.step)
    }

    // =========================================================================
    // Example 2: Execution Order Verification
    // =========================================================================

    @Test
    void testExecutionOrderVerification() {
        // Given: A multi-step workflow
        def workflow = createTransformWorkflow()

        // When: Workflow executes
        def result = execute(workflow)
            .with("10")
            .await()

        // Then: Tasks executed in correct order
        verifyExecutionOrder("parse", "double-it", "add-ten", "format")

        // And: Final result is correct
        assertEquals("Result: 30.0", result.result.toString())  // (10 * 2) + 10 = 30
    }

    // =========================================================================
    // Example 3: Task Input/Output Tracking
    // =========================================================================

    @Test
    void testTaskInputOutputTracking() {
        // Given: A transformation workflow
        def workflow = createTransformWorkflow()

        // When: Workflow executes
        execute(workflow).with("5").await()

        // Then: Can verify each task's input and output
        def parseOutput = taskOutput("parse")
        assertEquals(5.0, parseOutput.value)

        def doubleOutput = taskOutput("double-it")
        assertEquals(10.0, doubleOutput.value)

        def addOutput = taskOutput("add-ten")
        assertEquals(20.0, addOutput.value)

        def formatOutput = taskOutput("format")
        assertEquals("Result: 20.0", formatOutput.result.toString())
    }

    // =========================================================================
    // Example 4: Task Execution Verification
    // =========================================================================

    @Test
    void testTaskExecutionVerification() {
        // Given: A workflow
        def workflow = createValidationWorkflow()

        // When: Workflow executes
        execute(workflow).with("75").await()

        // Then: Can verify specific tasks executed
        verifyTaskExecuted("validate-not-null")
        verifyTaskExecuted("validate-range")

        // And: Can check executed task list
        def executed = getExecutedTasks()
        assertEquals(2, executed.size())
        assertTrue(executed.contains("validate-not-null"))
        assertTrue(executed.contains("validate-range"))
    }

    // =========================================================================
    // Example 5: Failure Handling
    // =========================================================================

    @Test
    void testExpectedFailureHandling() {
        // Given: A workflow that will fail on invalid input
        def workflow = createTransformWorkflow()

        // When: Invalid input provided with failure expected
        def result = execute(workflow)
            .with("not-a-number")
            .expectFailure()
            .await()

        // Then: Failure captured cleanly
        assertTrue(result.failed)
        assertNotNull(result.error)
        assertTrue(result.error.message.contains("NumberFormat") || 
                   result.error.type.contains("NumberFormat"))
    }

    // =========================================================================
    // Example 6: Timeout Configuration
    // =========================================================================

    @Test
    void testTimeoutConfiguration() {
        // Given: A simple workflow
        def workflow = createValidationWorkflow()

        // When: Executed with custom timeout
        def result = execute(workflow)
            .with("50")
            .timeout(10000)  // 10 seconds
            .await()

        // Then: Completes within timeout
        assertTrue(result.valid)
    }

    // =========================================================================
    // Example 7: Fluent Assertions
    // =========================================================================

    @Test
    void testFluentAssertions() {
        // Given: A transformation workflow
        def workflow = createTransformWorkflow()

        // When: Workflow executes
        def result = execute(workflow).with("10").await()

        // Then: Can use fluent assertion helpers
        assertResultHasField(result, "result")
        assertResultHas(result, "step", "formatted")

        // And: Can verify workflow success
        verifySuccess()
    }

    // =========================================================================
    // Example 8: Timing Verification
    // =========================================================================

    @Test
    void testTimingVerification() {
        // Given: A simple workflow
        def workflow = createValidationWorkflow()

        // When: Workflow executes
        execute(workflow).with("50").await()

        // Then: Can verify execution times
        def totalTime = getTotalExecutionTime()
        assertTrue(totalTime > 0, "Total execution time should be > 0")
        assertTrue(totalTime < 5000, "Should complete in < 5 seconds")

        // And: Can check individual task times
        def task1Time = getTaskExecutionTime("validate-not-null")
        assertTrue(task1Time >= 0, "Task execution time should be >= 0")
    }

    // =========================================================================
    // Example 9: Test Data Builder
    // =========================================================================

    @Test
    void testDataBuilderPattern() {
        // Given: A workflow
        def workflow = createValidationWorkflow()

        // When: Using fluent test data builder
        def testInput = input()
            .with("value", "50")
            .with("metadata", "test")
            .build()

        // Note: For this simple example, just using string directly
        def result = execute(workflow).with("50").await()

        // Then: Workflow processes correctly
        assertTrue(result.valid)
    }

    // =========================================================================
    // Example 10: Convenience Method
    // =========================================================================

    @Test
    void testConvenienceExecutionMethod() {
        // Given: A workflow
        def workflow = createValidationWorkflow()

        // When: Using convenience method
        def result = executeAndAwait(workflow, "50")

        // Then: Same result, less boilerplate
        assertTrue(result.valid)
        assertEquals(50.0, result.value)
    }

    // =========================================================================
    // Example 11: Detailed Task Assertions
    // =========================================================================

    @Test
    void testDetailedTaskAssertions() {
        // Given: A workflow
        def workflow = createTransformWorkflow()

        // When: Workflow executes
        execute(workflow).with("5").await()

        // Then: Can assert task completion states
        assertTaskSucceeded("parse")
        assertTaskSucceeded("double-it")
        assertTaskSucceeded("add-ten")
        assertTaskSucceeded("format")
    }

    // =========================================================================
    // Example 12: Conditional Workflow Testing
    // =========================================================================

    @Test
    void testConditionalWorkflow() {
        // Given: A conditional workflow
        def workflow = createConditionalWorkflow()

        // When: Workflow executes with medium value
        def result = execute(workflow)
            .with(75.0)
            .await()

        // Then: Correct branch executed
        assertEquals("medium", result.category)
        assertTrue(result.processed)

        // And: Verify execution path
        verifyTaskExecuted("classify")
        verifyTaskExecuted("process")
    }
}
