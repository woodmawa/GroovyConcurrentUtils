package org.softwood.dag.persistence

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.softwood.dag.TaskGraph

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import static org.junit.jupiter.api.Assertions.*

/**
 * Test EclipseStore persistence for TaskGraph execution.
 * 
 * Tests the core persistence layer (EclipseStoreManager using EclipseStore) in isolation before integration.
 */
class EclipseStoreManagerTest {
    
    private Path testDir
    
    @BeforeEach
    void setup() {
        testDir = Paths.get("build/test-snapshots")
        Files.createDirectories(testDir)
        
        // Clean any existing .gsm files
        if (Files.exists(testDir.resolve("graphState"))) {
            testDir.resolve("graphState").toFile().deleteDir()
        }
    }
    
    @AfterEach
    void cleanup() {
        // Clean up test snapshots
        if (Files.exists(testDir.resolve("graphState"))) {
            testDir.resolve("graphState").toFile().deleteDir()
        }
    }
    
    @Test
    void testManagerInitialization() {
        // Given
        String graphId = "test-graph"
        String runId = UUID.randomUUID().toString()
        
        // When
        EclipseStoreManager manager = new EclipseStoreManager(
            graphId,
            runId,
            testDir.toString(),
            false,
            2
        )
        
        // Then
        assertNotNull(manager.getSnapshot())
        assertEquals(graphId, manager.getSnapshot().graphId)
        assertEquals(runId, manager.getSnapshot().runId)
        assertEquals(GraphExecutionStatus.RUNNING, manager.getSnapshot().finalStatus)
        assertNotNull(manager.getSnapshotFile())
        
        // Verify file exists
        assertTrue(Files.exists(manager.getSnapshotFile().getParent()))
        
        manager.close()
    }
    
    @Test
    void testUpdateTaskState() {
        // Given
        String graphId = "test-graph"
        String runId = UUID.randomUUID().toString()
        EclipseStoreManager manager = new EclipseStoreManager(
            graphId,
            runId,
            testDir.toString(),
            false,
            2
        )
        
        // When - Update task state to RUNNING
        manager.updateTaskState("task1", TaskState.SCHEDULED, [name: "Test Task 1"])
        manager.updateTaskState("task1", TaskState.RUNNING, [:])
        
        // Then
        TaskStateSnapshot taskSnapshot = manager.getSnapshot().taskStates.get("task1")
        assertNotNull(taskSnapshot)
        assertEquals("task1", taskSnapshot.taskId)
        assertEquals("Test Task 1", taskSnapshot.taskName)
        assertEquals(TaskState.RUNNING, taskSnapshot.state)
        assertNotNull(taskSnapshot.startTime)
        
        manager.close()
    }
    
    @Test
    void testMarkTaskCompleted() {
        // Given
        String graphId = "test-graph"
        String runId = UUID.randomUUID().toString()
        EclipseStoreManager manager = new EclipseStoreManager(
            graphId,
            runId,
            testDir.toString(),
            false,
            2
        )
        
        // When
        manager.updateTaskState("task1", TaskState.SCHEDULED, [name: "Task 1"])
        manager.updateTaskState("task1", TaskState.RUNNING, [:])
        manager.updateTaskState("task1", TaskState.COMPLETED, [result: "success"])
        
        // Then
        TaskStateSnapshot taskSnapshot = manager.getSnapshot().taskStates.get("task1")
        assertEquals(TaskState.COMPLETED, taskSnapshot.state)
        assertEquals("success", taskSnapshot.result)
        assertNotNull(taskSnapshot.endTime)
        
        manager.close()
    }
    
    @Test
    void testMarkTaskFailed() {
        // Given
        String graphId = "test-graph"
        String runId = UUID.randomUUID().toString()
        EclipseStoreManager manager = new EclipseStoreManager(
            graphId,
            runId,
            testDir.toString(),
            false,
            2
        )
        
        // When
        manager.updateTaskState("task1", TaskState.SCHEDULED, [name: "Task 1"])
        manager.updateTaskState("task1", TaskState.RUNNING, [:])
        manager.updateTaskState("task1", TaskState.FAILED, [
            errorMessage: "Test error",
            errorStackTrace: "Stack trace here"
        ])
        
        // Then
        TaskStateSnapshot taskSnapshot = manager.getSnapshot().taskStates.get("task1")
        assertEquals(TaskState.FAILED, taskSnapshot.state)
        assertEquals("Test error", taskSnapshot.errorMessage)
        assertNotNull(taskSnapshot.endTime)
        
        manager.close()
    }
    
    @Test
    void testMarkGraphCompleted() {
        // Given
        String graphId = "test-graph"
        String runId = UUID.randomUUID().toString()
        EclipseStoreManager manager = new EclipseStoreManager(
            graphId,
            runId,
            testDir.toString(),
            false,
            2
        )
        
        // When
        manager.markGraphCompleted()
        
        // Then
        assertEquals(GraphExecutionStatus.COMPLETED, manager.getSnapshot().finalStatus)
        assertNotNull(manager.getSnapshot().endTime)
        assertNotNull(manager.getSnapshot().durationMillis)
        
        manager.close()
    }
    
    @Test
    void testMarkGraphFailed() {
        // Given
        String graphId = "test-graph"
        String runId = UUID.randomUUID().toString()
        EclipseStoreManager manager = new EclipseStoreManager(
            graphId,
            runId,
            testDir.toString(),
            false,
            2
        )
        
        // When
        Exception error = new RuntimeException("Graph failed")
        manager.markGraphFailed("task1", error)
        
        // Then
        assertEquals(GraphExecutionStatus.FAILED, manager.getSnapshot().finalStatus)
        assertEquals("task1", manager.getSnapshot().failureTaskId)
        assertEquals("Graph failed", manager.getSnapshot().failureMessage)
        assertNotNull(manager.getSnapshot().failureStackTrace)
        assertNotNull(manager.getSnapshot().endTime)
        
        manager.close()
    }
    
    @Test
    void testUpdateContextGlobals() {
        // Given
        String graphId = "test-graph"
        String runId = UUID.randomUUID().toString()
        EclipseStoreManager manager = new EclipseStoreManager(
            graphId,
            runId,
            testDir.toString(),
            false,
            2
        )
        
        // When
        manager.updateContextGlobals([
            stringValue: "test",
            numberValue: 42,
            booleanValue: true
        ])
        
        // Then
        assertEquals("test", manager.getSnapshot().contextGlobals.stringValue)
        assertEquals(42, manager.getSnapshot().contextGlobals.numberValue)
        assertEquals(true, manager.getSnapshot().contextGlobals.booleanValue)
        
        manager.close()
    }
    
    @Test
    void testThreadLocalAccess() {
        // Given
        String graphId = "test-graph"
        String runId = UUID.randomUUID().toString()
        EclipseStoreManager manager = new EclipseStoreManager(
            graphId,
            runId,
            testDir.toString(),
            false,
            2
        )
        
        // When
        Optional<EclipseStoreManager> current = EclipseStoreManager.current()
        
        // Then
        assertTrue(current.isPresent())
        assertSame(manager, current.get())
        
        manager.close()
        
        // After close, should be removed
        current = EclipseStoreManager.current()
        assertFalse(current.isPresent())
    }
    
    @Test
    void testSnapshotFileNaming() {
        // Given
        String graphId = "order-processing"
        String runId = UUID.randomUUID().toString()
        
        // When
        EclipseStoreManager manager = new EclipseStoreManager(
            graphId,
            runId,
            testDir.toString(),
            false,
            2
        )
        
        // Then
        Path snapshotFile = manager.getSnapshotFile()
        String filename = snapshotFile.getFileName().toString()
        
        assertTrue(filename.startsWith("order-processing_"))
        assertTrue(filename.contains(runId))
        assertTrue(filename.endsWith(".gsm"))
        
        manager.close()
    }
    
    @Test
    void testRetentionPolicy() {
        // Given
        String graphId = "test-graph"
        int maxSnapshots = 2
        
        // When - Create 3 snapshots
        for (int i = 0; i < 3; i++) {
            String runId = UUID.randomUUID().toString()
            EclipseStoreManager manager = new EclipseStoreManager(
                graphId,
                runId,
                testDir.toString(),
                false,
                maxSnapshots
            )
            manager.markGraphCompleted()
            manager.close()
            
            // Small delay to ensure different timestamps
            Thread.sleep(100)
        }
        
        // Then - Should only have 2 snapshots remaining
        Path graphStateDir = testDir.resolve("graphState")
        if (Files.exists(graphStateDir)) {
            long count = Files.list(graphStateDir)
                .filter { it.getFileName().toString().startsWith("test-graph_") }
                .count()
            
            assertTrue(count <= maxSnapshots, "Expected at most $maxSnapshots snapshots, but found $count")
        }
    }
}
