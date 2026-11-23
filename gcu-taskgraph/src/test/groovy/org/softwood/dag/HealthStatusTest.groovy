package org.softwood.dag

import org.junit.jupiter.api.Test
import org.softwood.dag.task.TaskState
import java.time.Instant

import static org.junit.jupiter.api.Assertions.*

/**
 * Tests for HealthStatus API.
 */
class HealthStatusTest {

    @Test
    void testHealthStatusBasics() {
        // Given: A health status
        def health = new HealthStatus(
            graphId: "test-graph",
            runId: "run-123",
            state: GraphState.RUNNING,
            startTime: Instant.now(),
            durationMs: 5000L,
            totalTasks: 10,
            completedTasks: 7,
            failedTasks: 0,
            runningTasks: 2,
            scheduledTasks: 1,
            skippedTasks: 0
        )

        // Then: Basic properties work
        assertEquals("test-graph", health.graphId)
        assertEquals(GraphState.RUNNING, health.state)
        assertEquals(10, health.totalTasks)
        assertEquals(7, health.completedTasks)
        assertTrue(health.isHealthy())
        assertTrue(health.isRunning())
        assertFalse(health.isCompleted())
        assertFalse(health.isFailed())
    }

    @Test
    void testHealthStatusWithFailures() {
        // Given: A health status with failures
        def health = new HealthStatus(
            graphId: "test-graph",
            state: GraphState.FAILED,
            totalTasks: 10,
            completedTasks: 5,
            failedTasks: 3,
            runningTasks: 0,
            scheduledTasks: 2,
            skippedTasks: 0
        )

        // Then: Not healthy
        assertFalse(health.isHealthy())
        assertTrue(health.isFailed())
        assertEquals(3, health.failedTasks)
    }

    @Test
    void testCompletionPercentage() {
        // Given: Health with partial completion
        def health = new HealthStatus(
            totalTasks: 10,
            completedTasks: 7,
            failedTasks: 0,
            runningTasks: 3,
            scheduledTasks: 0,
            skippedTasks: 0
        )

        // Then: Percentage is correct
        assertEquals(70.0, health.getCompletionPercentage(), 0.01)
    }

    @Test
    void testRemainingTasks() {
        // Given: Health with mixed task states
        def health = new HealthStatus(
            totalTasks: 10,
            completedTasks: 5,
            failedTasks: 1,
            runningTasks: 2,
            scheduledTasks: 2,
            skippedTasks: 0
        )

        // Then: Remaining tasks calculated correctly
        assertEquals(4, health.getRemainingTasks())  // 10 - 5 - 1 - 0 = 4
    }

    @Test
    void testToMap() {
        // Given: Health status
        def health = new HealthStatus(
            graphId: "test",
            state: GraphState.COMPLETED,
            totalTasks: 5,
            completedTasks: 5,
            failedTasks: 0,
            runningTasks: 0,
            scheduledTasks: 0,
            skippedTasks: 0
        )

        // When: Convert to map
        def map = health.toMap()

        // Then: Map contains expected fields
        assertEquals("test", map.graphId)
        assertEquals("COMPLETED", map.state)
        assertEquals(5, map.totalTasks)
        assertEquals(100.0, map.completionPercentage, 0.01)
        assertTrue(map.healthy)
    }

    @Test
    void testResourceSnapshot() {
        // Given: Resource snapshot
        def resources = new ResourceSnapshot(
            runningTasks: 8,
            queuedTasks: 2,
            maxConcurrentTasks: 10,
            usedMemoryMB: 800,
            maxMemoryMB: 1000,
            totalMemoryMB: 2000,
            freeMemoryMB: 1200
        )

        // Then: Utilization calculated correctly
        assertEquals(80.0, resources.getConcurrencyUtilization(), 0.01)
        assertEquals(80.0, resources.getMemoryUtilization(), 0.01)
        assertEquals(2, resources.getAvailableSlots())
        assertTrue(resources.isNearLimit(0.75))
        assertFalse(resources.isNearLimit(0.85))
    }

    @Test
    void testLiveHealthCheck() {
        // Given: A workflow
        def workflow = TaskGraph.build {
            serviceTask("task1") {
                action { ctx, prev ->
                    ctx.promiseFactory.createPromise("result1")
                }
            }

            serviceTask("task2") {
                action { ctx, prev ->
                    ctx.promiseFactory.createPromise("result2")
                }
            }

            chainVia("task1", "task2")
        }

        // When: Get health before start
        def healthBefore = workflow.getHealth()

        // Then: Not started
        assertEquals(GraphState.NOT_STARTED, healthBefore.state)
        assertEquals(2, healthBefore.totalTasks)

        // When: Start workflow
        workflow.start().get()
        def healthAfter = workflow.getHealth()

        // Then: Completed
        assertEquals(GraphState.COMPLETED, healthAfter.state)
        assertEquals(2, healthAfter.completedTasks)
        assertTrue(healthAfter.isHealthy())
        assertTrue(healthAfter.durationMs > 0)
    }

    @Test
    void testExecutionSnapshot() {
        // Given: A workflow
        def workflow = TaskGraph.build {
            serviceTask("task1") {
                action { ctx, prev ->
                    ctx.promiseFactory.createPromise("result1")
                }
            }

            serviceTask("task2") {
                action { ctx, prev ->
                    ctx.promiseFactory.createPromise("result2")
                }
            }

            chainVia("task1", "task2")
        }

        // When: Execute and get snapshot
        workflow.start().get()
        def snapshot = workflow.getSnapshot()

        // Then: Snapshot has full details
        assertNotNull(snapshot.health)
        assertEquals(2, snapshot.taskSnapshots.size())

        def task1 = snapshot.getTask("task1")
        assertNotNull(task1)
        assertEquals("task1", task1.id)
        assertEquals(TaskState.COMPLETED, task1.state)
        assertEquals([], task1.predecessors as List)
        assertEquals(["task2"] as Set, task1.successors)

        def task2 = snapshot.getTask("task2")
        assertEquals(["task1"] as Set, task2.predecessors)
    }

    @Test
    void testFailedWorkflowHealth() {
        // Given: A failing workflow
        def workflow = TaskGraph.build {
            serviceTask("failing-task") {
                action { ctx, prev ->
                    throw new RuntimeException("Intentional failure")
                }
            }
        }

        // When: Execute (expecting failure)
        try {
            workflow.start().get()
        } catch (Exception e) {
            // Expected
        }

        // Then: Health shows failure
        def health = workflow.getHealth()
        assertFalse(health.isHealthy())
        assertTrue(health.isFailed())
        assertEquals(1, health.failedTasks)

        // And: Snapshot has error details
        def snapshot = workflow.getSnapshot()
        def failedTasks = snapshot.getFailedTasks()
        assertEquals(1, failedTasks.size())
        assertNotNull(failedTasks[0].errorMessage)
    }
}
