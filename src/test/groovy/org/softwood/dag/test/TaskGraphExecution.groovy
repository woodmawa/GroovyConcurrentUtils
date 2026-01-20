package org.softwood.dag.test

import groovy.util.logging.Slf4j
import org.softwood.dag.TaskGraph
import org.softwood.dag.task.TaskContext
import org.softwood.promise.Promise

import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Wrapper for TaskGraph execution in tests.
 *
 * Provides fluent API for configuring and executing workflows with
 * automatic tracking and verification support.
 */
@Slf4j
class TaskGraphExecution {

    private final TaskGraph workflow
    private final TaskContext ctx
    private final TaskGraphVerifier verifier

    private Object input
    private long timeoutMs = 5000
    private boolean expectFailure = false
    private Exception capturedException

    TaskGraphExecution(TaskGraph workflow, TaskContext ctx, TaskGraphVerifier verifier) {
        this.workflow = workflow
        this.ctx = ctx
        this.verifier = verifier
    }

    // =========================================================================
    // Configuration Methods
    // =========================================================================

    /**
     * Set input data for workflow.
     */
    TaskGraphExecution with(Object input) {
        this.input = input
        return this
    }

    /**
     * Set timeout for workflow execution (default: 5 seconds).
     */
    TaskGraphExecution timeout(long ms) {
        this.timeoutMs = ms
        return this
    }

    /**
     * Set timeout using duration.
     */
    TaskGraphExecution timeout(long value, TimeUnit unit) {
        this.timeoutMs = unit.toMillis(value)
        return this
    }

    /**
     * Expect workflow to fail (for negative testing).
     */
    TaskGraphExecution expectFailure() {
        this.expectFailure = true
        return this
    }

    // =========================================================================
    // Execution Methods
    // =========================================================================

    /**
     * Execute workflow and wait for completion.
     * Returns workflow result or execution metadata.
     */
    Object await() {
        verifier.reset()
        verifier.setWorkflow(workflow)

        long startTime = System.currentTimeMillis()

        try {
            log.debug("Starting workflow execution (timeout: ${timeoutMs}ms)")

            // Set up input if provided
            if (input != null) {
                injectInput()
            }

            // Execute workflow
            Promise<?> promise = workflow.run()

            // Wait for completion
            Object result = awaitWithTimeout(promise)

            long endTime = System.currentTimeMillis()
            verifier.setTotalExecutionTime(endTime - startTime)

            // Capture execution state
            captureExecutionState()

            if (expectFailure) {
                // Expected failure but got success
                throw new AssertionError("Expected workflow to fail but it succeeded with result: ${result}")
            }

            log.debug("Workflow completed successfully in ${endTime - startTime}ms")
            return result

        } catch (Exception e) {
            long endTime = System.currentTimeMillis()
            verifier.setTotalExecutionTime(endTime - startTime)

            capturedException = e
            captureExecutionState()

            if (expectFailure) {
                log.debug("Workflow failed as expected: ${e.class.simpleName}")
                return buildFailureResult(e)
            } else {
                log.error("Workflow execution failed unexpectedly", e)
                throw e
            }
        }
    }

    /**
     * Execute workflow without waiting (for async testing).
     */
    Promise<?> start() {
        if (input != null) {
            injectInput()
        }
        return workflow.run()
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private void injectInput() {
        // Find root tasks (tasks with no predecessors)
        def rootTasks = workflow.tasks.values().findAll { it.predecessors.isEmpty() }

        if (rootTasks.size() == 1) {
            rootTasks[0].setInjectedInput(input)
            log.debug("Injected input into single root task: ${rootTasks[0].id}")
        } else if (rootTasks.size() > 1) {
            rootTasks.each { it.setInjectedInput(input) }
            log.debug("Injected input into ${rootTasks.size()} root tasks")
        } else {
            log.warn("No root tasks found to inject input")
        }
    }

    private Object awaitWithTimeout(Promise<?> promise) {
        try {
            return promise.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (TimeoutException e) {
            throw new AssertionError("Workflow execution timed out after ${timeoutMs}ms", e)
        }
    }

    private void captureExecutionState() {
        // Capture execution order - tasks don't have timestamps, so just record in state order
        def executedTasks = workflow.tasks.values()
            .findAll { it.state == org.softwood.dag.task.TaskState.COMPLETED ||
                       it.state == org.softwood.dag.task.TaskState.FAILED }

        executedTasks.each { task ->
            // Extract result from completion promise if available
            def taskResult = null
            try {
                if (task.completionPromise != null && task.state == org.softwood.dag.task.TaskState.COMPLETED) {
                    taskResult = task.completionPromise.get()
                }
            } catch (Exception e) {
                // Ignore - result might not be available
            }

            verifier.recordTaskExecution(
                task.id,
                task,
                null,  // injectedInput not directly accessible
                taskResult,
                0L  // execution time not tracked at task level
            )
        }

        // Mark success/failure
        boolean allCompleted = workflow.tasks.values().every {
            it.state == org.softwood.dag.task.TaskState.COMPLETED
        }

        if (allCompleted && capturedException == null) {
            verifier.markSuccess()
        } else {
            verifier.markFailure(capturedException)
        }
    }

    private Map buildFailureResult(Exception e) {
        return [
            failed: true,
            error: [
                message: e.message,
                type: e.class.simpleName,
                exception: e
            ],
            executedTasks: verifier.getExecutedTaskIds(),
            totalTime: verifier.getTotalExecutionTime()
        ]
    }
}
