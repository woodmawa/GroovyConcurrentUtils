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
 * Integration tests for TaskGraph persistence.
 * Tests end-to-end persistence functionality with real graph executions.
 */
class TaskGraphPersistenceIntegrationTest {
    
    private Path testDir
    
    @BeforeEach
    void setup() {
        testDir = Paths.get("build/test-snapshots")
        Files.createDirectories(testDir)
        
        // Clean any existing .gsm files
        cleanGraphState()
    }
    
    @AfterEach
    void cleanup() {
        cleanGraphState()
    }
    
    private void cleanGraphState() {
        Path graphStateDir = testDir.resolve("graphState")
        if (Files.exists(graphStateDir)) {
            graphStateDir.toFile().deleteDir()
        }
    }
    
    @Test
    void testSingleTaskWithPersistence() {
        // Given - Single task graph with persistence
        def graph = TaskGraph.build {
            globals {
                graphId = "single-task-graph"
            }
            
            persistence {
                enabled true
                baseDir testDir.toString()
                snapshotOn "always"
            }
            
            serviceTask("onlyTask") {
                action { ctx, prev -> 
                    ctx.promiseFactory.executeAsync { 
                        "single-result" 
                    }
                }
            }
        }
        
        // When
        def resultPromise = graph.run()
        def result = resultPromise.get()
        
        // Wait for persistence to complete
        assertTrue(graph.awaitPersistence(), "Persistence should complete within timeout")
        
        // Then
        assertEquals("single-result", result)
    }
    
    @Test
    void testBasicPersistenceWithSuccessfulGraph() {
        // Given - A simple successful graph with persistence enabled
        def graph = TaskGraph.build {
            globals {
                graphId = "test-success-graph"
            }
            
            persistence {
                enabled true
                baseDir testDir.toString()
                snapshotOn "always"
            }
            
            serviceTask("task1") {
                action { ctx, prev -> 
                    ctx.promiseFactory.executeAsync { 
                        Thread.sleep(100)  // Small delay to ensure order
                        "result1" 
                    }
                }
            }
            
            serviceTask("task2") {
                dependsOn "task1"
                action { ctx, prev -> 
                    ctx.promiseFactory.executeAsync { 
                        "result2" 
                    }
                }
            }
        }
        
        // When
        def resultPromise = graph.run()
        def result = resultPromise.get()
        
        // Wait for persistence to complete
        assertTrue(graph.awaitPersistence(), "Persistence should complete within timeout")
        
        // Then - Verify result
        assertEquals("result2", result)
        
        // Verify snapshot file exists
        Path graphStateDir = testDir.resolve("graphState")
        assertTrue(Files.exists(graphStateDir), "graphState directory should exist")
        
        def snapshots = Files.list(graphStateDir)
            .filter { it.getFileName().toString().startsWith("test-success-graph_") }
            .filter { it.getFileName().toString().endsWith(".gsm") }
            .toList()
        
        assertEquals(1, snapshots.size(), "Should have exactly 1 snapshot")
        
        // Load and verify snapshot content
        Path snapshotFile = snapshots[0]
        assertTrue(Files.isDirectory(snapshotFile), "EclipseStore snapshot should be a directory")
        
        // Load snapshot to verify content
        GraphStateSnapshot snapshot = EclipseStoreManager.loadSnapshot(snapshotFile)
        assertNotNull(snapshot)
        assertEquals("test-success-graph", snapshot.graphId)
        assertEquals(GraphExecutionStatus.COMPLETED, snapshot.finalStatus)
        assertEquals(2, snapshot.taskStates.size())
        
        // Verify task states
        TaskStateSnapshot task1 = snapshot.taskStates.get("task1")
        assertNotNull(task1)
        assertEquals(TaskState.COMPLETED, task1.state)
        assertEquals("result1", task1.result)
        
        TaskStateSnapshot task2 = snapshot.taskStates.get("task2")
        assertNotNull(task2)
        assertEquals(TaskState.COMPLETED, task2.state)
        assertEquals("result2", task2.result)
    }
    
    @Test
    void testPersistenceWithFailedTask() {
        // Given - A graph that will fail
        def graph = TaskGraph.build {
            globals {
                graphId = "test-failure-graph"
            }
            
            persistence {
                enabled true
                baseDir testDir.toString()
                snapshotOn "always"
            }
            
            serviceTask("task1") {
                action { ctx, prev -> 
                    ctx.promiseFactory.executeAsync { "result1" }
                }
            }
            
            serviceTask("task2") {
                dependsOn "task1"
                action { ctx, prev -> 
                    throw new RuntimeException("Task 2 failed!")
                }
            }
            
            serviceTask("task3") {
                dependsOn "task2"
                action { ctx, prev -> 
                    ctx.promiseFactory.executeAsync { "result3" }
                }
            }
        }
        
        // When
        try {
            graph.run().get()
            fail("Expected RuntimeException")
        } catch (Exception e) {
            // The actual error may be the raw exception (no retry wrapping when maxAttempts=0)
            // Check for either the original message or task ID reference
            assertTrue(e.message.contains("Task 2 failed") || e.message.contains("task2") || e.message.contains("exceeded retry"),
                "Expected error message to contain failure info, but got: ${e.message}")
        }
        
        // Wait for persistence to complete
        assertTrue(graph.awaitPersistence(), "Persistence should complete within timeout")
        
        // Then - Verify snapshot exists
        Path graphStateDir = testDir.resolve("graphState")
        assertTrue(Files.exists(graphStateDir))
        
        def snapshots = Files.list(graphStateDir)
            .filter { it.getFileName().toString().startsWith("test-failure-graph_") }
            .toList()
        
        assertEquals(1, snapshots.size())
        
        // Load and verify snapshot
        GraphStateSnapshot snapshot = EclipseStoreManager.loadSnapshot(snapshots[0])
        assertNotNull(snapshot)
        assertEquals(GraphExecutionStatus.FAILED, snapshot.finalStatus)
        assertEquals("task2", snapshot.failureTaskId)
        // The error message may be raw (no retry wrapping) or wrapped depending on retry config
        assertTrue(snapshot.failureMessage.contains("Task 2 failed") || snapshot.failureMessage.contains("task2") || snapshot.failureMessage.contains("exceeded retry"),
            "Expected failure message to mention failure, but got: ${snapshot.failureMessage}")
        
        // Verify task states
        assertEquals(TaskState.COMPLETED, snapshot.taskStates.get("task1").state)
        assertEquals(TaskState.FAILED, snapshot.taskStates.get("task2").state)
        assertEquals(TaskState.SKIPPED, snapshot.taskStates.get("task3").state)
    }
    
    @Test
    void testPersistenceDisabledByDefault() {
        // Given - A graph without persistence block
        def graph = TaskGraph.build {
            globals {
                graphId = "test-no-persistence"
            }
            
            serviceTask("task1") {
                action { ctx, prev -> 
                    ctx.promiseFactory.executeAsync { "result1" }
                }
            }
        }
        
        // When
        def result = graph.run().get()
        
        // Then
        assertEquals("result1", result)
        
        // Verify NO snapshot was created
        Path graphStateDir = testDir.resolve("graphState")
        if (Files.exists(graphStateDir)) {
            def snapshots = Files.list(graphStateDir)
                .filter { it.getFileName().toString().startsWith("test-no-persistence_") }
                .count()
            
            assertEquals(0, snapshots, "Should have NO snapshots when persistence disabled")
        }
    }
    
    @Test
    void testSnapshotModeFailure() {
        // Given - Persistence enabled but only on failure
        def graph = TaskGraph.build {
            globals {
                graphId = "test-failure-mode"
            }
            
            persistence {
                enabled true
                baseDir testDir.toString()
                snapshotOn "failure"  // Only snapshot on failure
            }
            
            serviceTask("task1") {
                action { ctx, prev -> 
                    ctx.promiseFactory.executeAsync { "result1" }
                }
            }
        }
        
        // When - Successful execution
        graph.run().get()
        
        // Then - NO snapshot should be created (success, but mode is "failure")
        Path graphStateDir = testDir.resolve("graphState")
        if (Files.exists(graphStateDir)) {
            def snapshots = Files.list(graphStateDir)
                .filter { it.getFileName().toString().startsWith("test-failure-mode_") }
                .count()
            
            assertEquals(0, snapshots, "Should have NO snapshots on success when mode is 'failure'")
        }
    }
    
    @Test
    void testConcurrentGraphExecutions() {
        // Given - Multiple concurrent graph executions
        def graphs = (1..3).collect { index ->
            TaskGraph.build {
                globals {
                    graphId = "concurrent-graph-${index}"
                }
                
                persistence {
                    enabled true
                    baseDir testDir.toString()
                    snapshotOn "always"
                }
                
                serviceTask("task1") {
                    action { ctx, prev -> 
                        Thread.sleep(10)  // Simulate work
                        ctx.promiseFactory.executeAsync { "result-${index}" }
                    }
                }
            }
        }
        
        // When - Execute all graphs concurrently
        def results = graphs.collect { graph ->
            Thread.start {
                graph.run().get()
            }
        }*.join()
        
        // Then - All should complete successfully
        Path graphStateDir = testDir.resolve("graphState")
        assertTrue(Files.exists(graphStateDir))
        
        // Verify each graph has its own snapshot
        (1..3).each { index ->
            def snapshots = Files.list(graphStateDir)
                .filter { it.getFileName().toString().startsWith("concurrent-graph-${index}_") }
                .count()
            
            assertEquals(1, snapshots, "Each concurrent graph should have its own snapshot")
        }
    }
    
    @Test
    void testContextGlobalsPersistence() {
        // Given - Graph with context globals
        def graph = TaskGraph.build {
            globals {
                graphId = "test-globals"
                customValue = "test-value"
                numberValue = 42
            }
            
            persistence {
                enabled true
                baseDir testDir.toString()
                snapshotOn "always"
            }
            
            serviceTask("task1") {
                action { ctx, prev -> 
                    ctx.globals.runtimeValue = "added-at-runtime"
                    ctx.promiseFactory.executeAsync { "result1" }
                }
            }
        }
        
        // When
        graph.run().get()
        
        // Wait for persistence to complete
        assertTrue(graph.awaitPersistence(), "Persistence should complete within timeout")
        
        // Then
        Path graphStateDir = testDir.resolve("graphState")
        def snapshots = Files.list(graphStateDir)
            .filter { it.getFileName().toString().startsWith("test-globals_") }
            .toList()
        
        GraphStateSnapshot snapshot = EclipseStoreManager.loadSnapshot(snapshots[0])
        
        // Verify globals are persisted
        assertEquals("test-value", snapshot.contextGlobals.customValue)
        assertEquals(42, snapshot.contextGlobals.numberValue)
        assertEquals("added-at-runtime", snapshot.contextGlobals.runtimeValue)
    }
}
