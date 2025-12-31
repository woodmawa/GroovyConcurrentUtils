package org.softwood.dag.cluster

import com.hazelcast.map.IMap
import groovy.util.logging.Slf4j
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.softwood.cluster.HazelcastManager
import org.softwood.dag.TaskGraph
import org.softwood.promise.Promise
import org.awaitility.Awaitility
import java.util.concurrent.TimeUnit

import static org.junit.jupiter.api.Assertions.*

/**
 * Integration tests for TaskGraph with Hazelcast clustering.
 * Tests backward compatibility and cluster state replication.
 */
@Slf4j
class TaskGraphClusteringTest {
    
    // Utility to await a promise
    private static <T> T awaitPromise(Promise<T> p) {
        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .until({ p.isDone() })
        return p.get()
    }
    
    @BeforeEach
    void setUp() {
        // Ensure clean slate
        HazelcastManager.instance.shutdown()
    }
    
    @AfterEach
    void tearDown() {
        HazelcastManager.instance.shutdown()
    }
    
    @Test
    void testGraphExecutionWithClusteringDisabled() {
        // Don't initialize Hazelcast - clustering should be disabled
        
        def graph = TaskGraph.build {
            globals {
                graphId = "test-graph-disabled"
            }
            
            serviceTask("task1") {
                action { ctx, input ->
                    log.info "Executing task1"
                    ctx.promiseFactory.executeAsync { "result1" }
                }
            }
            
            serviceTask("task2") {
                action { ctx, input ->
                    log.info "Executing task2 with input: $input"
                    ctx.promiseFactory.executeAsync { "result2" }
                }
            }
            
            fork("chain") {
                from "task1"
                to "task2"
            }
        }
        
        def result = awaitPromise(graph.start())
        
        assertEquals("result2", result)
        log.info "Graph completed without clustering: $result"
    }
    
    @Test
    void testGraphExecutionWithClusteringEnabled() {
        // Initialize Hazelcast
        def config = [
            enabled: true,
            port: 5705,
            cluster: [
                name: 'test-graph-cluster',
                members: []
            ]
        ]
        HazelcastManager.instance.initialize(config)
        
        def graph = TaskGraph.build {
            globals {
                graphId = "test-graph-enabled"
            }
            
            serviceTask("task1") {
                action { ctx, input ->
                    log.info "Executing task1 in cluster"
                    ctx.promiseFactory.executeAsync { "clustered-result1" }
                }
            }
            
            serviceTask("task2") {
                action { ctx, input ->
                    log.info "Executing task2 in cluster with input: $input"
                    ctx.promiseFactory.executeAsync { "clustered-result2" }
                }
            }
            
            fork("chain") {
                from "task1"
                to "task2"
            }
        }
        
        def result = awaitPromise(graph.start())
        
        assertEquals("clustered-result2", result)
        
        // Wait for cluster state to update (async operation)
        IMap<String, GraphExecutionState> graphStateMap = HazelcastManager.instance.getGraphStateMap()
        assertNotNull(graphStateMap, "Graph state map should be available")
        
        // Use Awaitility to wait for cluster state to update
        GraphExecutionState graphState = Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .until({
                    def states = graphStateMap.values().findAll { it.graphId == "test-graph-enabled" }
                    states.isEmpty() ? null : states[0]
                }, { it != null && it.status == GraphExecutionStatus.COMPLETED })
        
        assertEquals(GraphExecutionStatus.COMPLETED, graphState.status)
        assertTrue(graphState.taskStates.containsKey("task1"))
        assertTrue(graphState.taskStates.containsKey("task2"))
        
        log.info "Graph completed with clustering: $result"
        log.info "Graph state in cluster: $graphState"
    }
    
    @Test
    void testTaskStateReplication() {
        // Initialize Hazelcast
        def config = [
            enabled: true,
            port: 5706,
            cluster: [
                name: 'test-task-state-cluster',
                members: []
            ]
        ]
        HazelcastManager.instance.initialize(config)
        
        def graph = TaskGraph.build {
            globals {
                graphId = "test-task-replication"
            }
            
            serviceTask("task-a") {
                action { ctx, input ->
                    Thread.sleep(100)  // Give time for state to replicate
                    ctx.promiseFactory.executeAsync { "A" }
                }
            }
            
            serviceTask("task-b") {
                action { ctx, input ->
                    ctx.promiseFactory.executeAsync { "B" }
                }
            }
            
            fork("chain") {
                from "task-a"
                to "task-b"
            }
        }
        
        awaitPromise(graph.start())
        
        // Verify task states in cluster
        IMap<String, TaskRuntimeState> taskStateMap = HazelcastManager.instance.getTaskStateMap()
        assertNotNull(taskStateMap, "Task state map should be available")
        
        def taskStates = taskStateMap.values().toList()
        assertTrue(taskStates.size() >= 2, "Should have at least 2 task states")
        
        // Check that states were replicated
        def taskAStates = taskStates.findAll { it.taskId == "task-a" }
        def taskBStates = taskStates.findAll { it.taskId == "task-b" }
        
        assertFalse(taskAStates.isEmpty(), "Task A state should be in cluster")
        assertFalse(taskBStates.isEmpty(), "Task B state should be in cluster")
        
        log.info "Task states replicated: ${taskStates.size()} states found"
    }
    
    @Test
    void testGraphFailureInCluster() {
        // Initialize Hazelcast
        def config = [
            enabled: true,
            port: 5707,
            cluster: [
                name: 'test-failure-cluster',
                members: []
            ]
        ]
        HazelcastManager.instance.initialize(config)
        
        def graph = TaskGraph.build {
            globals {
                graphId = "test-graph-failure"
            }
            
            serviceTask("task1") {
                action { ctx, input ->
                    ctx.promiseFactory.executeAsync { "OK" }
                }
            }
            
            serviceTask("task2") {
                action { ctx, input ->
                    ctx.promiseFactory.executeAsync {
                        throw new RuntimeException("Intentional failure")
                    }
                }
            }
            
            serviceTask("task3") {
                action { ctx, input ->
                    ctx.promiseFactory.executeAsync { "Should be skipped" }
                }
            }
            
            fork("chain1") {
                from "task1"
                to "task2"
            }
            
            fork("chain2") {
                from "task2"
                to "task3"
            }
        }
        
        Promise<?> result = graph.start()
        
        assertThrows(RuntimeException.class) {
            awaitPromise(result)
        }
        
        // Wait for cluster state to update (async operation)
        IMap<String, GraphExecutionState> graphStateMap = HazelcastManager.instance.getGraphStateMap()
        
        // Use Awaitility to wait for cluster state to update to FAILED
        GraphExecutionState graphState = Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .until({
                    def states = graphStateMap.values().findAll { it.graphId == "test-graph-failure" }
                    states.isEmpty() ? null : states[0]
                }, { it != null && it.status == GraphExecutionStatus.FAILED })
        assertEquals(GraphExecutionStatus.FAILED, graphState.status)
        assertEquals("task2", graphState.failedTaskId)
        
        // Verify task3 was skipped
        assertTrue(graphState.taskStates.containsKey("task3"))
        assertEquals("SKIPPED", graphState.taskStates.get("task3"))
        
        log.info "Graph failed as expected: ${graphState.errorMessage}"
    }
    
    @Test
    void testClusteringGracefulDegradation() {
        // Initialize with invalid config - clustering should fail gracefully
        def config = [
            enabled: true,
            port: -1,  // Invalid port
            cluster: [
                name: 'invalid-cluster',
                members: []
            ]
        ]
        
        // This should fail to initialize but not crash
        HazelcastManager.instance.initialize(config)
        
        // Graph should still execute locally
        def graph = TaskGraph.build {
            serviceTask("task1") {
                action { ctx, input ->
                    ctx.promiseFactory.executeAsync { "local-result" }
                }
            }
        }
        
        def result = awaitPromise(graph.start())
        assertEquals("local-result", result)
        
        log.info "Graph executed locally despite clustering failure"
    }
}
