package org.softwood.cluster

import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.hazelcast.topic.ITopic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.softwood.dag.cluster.ClusterTaskEvent
import org.softwood.dag.cluster.GraphExecutionState
import org.softwood.dag.cluster.TaskRuntimeState

import static org.junit.jupiter.api.Assertions.*

/**
 * Test suite for HazelcastManager singleton.
 * Tests initialization, configuration, and graceful degradation.
 */
class HazelcastManagerTest {
    
    @BeforeEach
    void setUp() {
        // Reset singleton state before each test
        HazelcastManager.instance.shutdown()
    }
    
    @AfterEach
    void tearDown() {
        // Clean up after each test
        HazelcastManager.instance.shutdown()
    }
    
    @Test
    void testSingletonBehavior() {
        def instance1 = HazelcastManager.instance
        def instance2 = HazelcastManager.instance
        
        assertSame(instance1, instance2, "Should return same singleton instance")
    }
    
    @Test
    void testInitializationWithDisabled() {
        def config = [
            enabled: false,
            port: 5701
        ]
        
        HazelcastManager.instance.initialize(config)
        
        assertFalse(HazelcastManager.instance.isEnabled(), "Should be disabled")
        assertNull(HazelcastManager.instance.getInstance(), "Hazelcast instance should be null")
        assertNull(HazelcastManager.instance.getGraphStateMap(), "Graph state map should be null")
        assertNull(HazelcastManager.instance.getTaskStateMap(), "Task state map should be null")
        assertNull(HazelcastManager.instance.getTaskEventTopic(), "Task event topic should be null")
    }
    
    @Test
    void testInitializationWithNullConfig() {
        HazelcastManager.instance.initialize(null)
        
        assertFalse(HazelcastManager.instance.isEnabled(), "Should be disabled with null config")
        assertNull(HazelcastManager.instance.getInstance(), "Hazelcast instance should be null")
    }
    
    @Test
    void testInitializationWithEmptyConfig() {
        HazelcastManager.instance.initialize([:])
        
        assertFalse(HazelcastManager.instance.isEnabled(), "Should be disabled with empty config")
        assertNull(HazelcastManager.instance.getInstance(), "Hazelcast instance should be null")
    }
    
    @Test
    void testInitializationEnabled() {
        def config = [
            enabled: true,
            port: 5701,
            cluster: [
                name: 'test-cluster',
                members: []  // Empty = multicast for testing
            ]
        ]
        
        HazelcastManager.instance.initialize(config)
        
        assertTrue(HazelcastManager.instance.isEnabled(), "Should be enabled")
        assertNotNull(HazelcastManager.instance.getInstance(), "Hazelcast instance should not be null")
        
        // Verify data structures are available
        IMap<String, GraphExecutionState> graphMap = HazelcastManager.instance.getGraphStateMap()
        assertNotNull(graphMap, "Graph state map should be available")
        
        IMap<String, TaskRuntimeState> taskMap = HazelcastManager.instance.getTaskStateMap()
        assertNotNull(taskMap, "Task state map should be available")
        
        ITopic<ClusterTaskEvent> eventTopic = HazelcastManager.instance.getTaskEventTopic()
        assertNotNull(eventTopic, "Event topic should be available")
    }
    
    @Test
    void testDataStructureCreation() {
        def config = [
            enabled: true,
            port: 5702,  // Different port to avoid conflicts
            cluster: [
                name: 'test-cluster-2',
                members: []
            ]
        ]
        
        HazelcastManager.instance.initialize(config)
        
        // Test GraphExecutionState map
        IMap<String, GraphExecutionState> graphMap = HazelcastManager.instance.getGraphStateMap()
        GraphExecutionState graphState = new GraphExecutionState("graph1", "run1", "node1")
        graphMap.put("test-key", graphState)
        
        GraphExecutionState retrieved = graphMap.get("test-key")
        assertNotNull(retrieved, "Should retrieve graph state")
        assertEquals("graph1", retrieved.graphId)
        assertEquals("run1", retrieved.runId)
        assertEquals("node1", retrieved.ownerNode)
        
        // Test TaskRuntimeState map
        IMap<String, TaskRuntimeState> taskMap = HazelcastManager.instance.getTaskStateMap()
        TaskRuntimeState taskState = new TaskRuntimeState("task1", "run1", "RUNNING")
        taskMap.put("task-key", taskState)
        
        TaskRuntimeState retrievedTask = taskMap.get("task-key")
        assertNotNull(retrievedTask, "Should retrieve task state")
        assertEquals("task1", retrievedTask.taskId)
        assertEquals("RUNNING", retrievedTask.state)
    }
    
    @Test
    void testMultipleInitializationCalls() {
        def config = [
            enabled: true,
            port: 5703,
            cluster: [
                name: 'test-cluster-3',
                members: []
            ]
        ]
        
        HazelcastManager.instance.initialize(config)
        HazelcastInstance firstInstance = HazelcastManager.instance.getInstance()
        
        // Call initialize again - should be ignored
        HazelcastManager.instance.initialize(config)
        HazelcastInstance secondInstance = HazelcastManager.instance.getInstance()
        
        assertSame(firstInstance, secondInstance, "Multiple initialize calls should return same instance")
    }
    
    @Test
    void testShutdown() {
        def config = [
            enabled: true,
            port: 5704,
            cluster: [
                name: 'test-cluster-4',
                members: []
            ]
        ]
        
        HazelcastManager.instance.initialize(config)
        assertTrue(HazelcastManager.instance.isEnabled(), "Should be enabled before shutdown")
        
        HazelcastManager.instance.shutdown()
        
        assertFalse(HazelcastManager.instance.isEnabled(), "Should be disabled after shutdown")
        assertNull(HazelcastManager.instance.getInstance(), "Instance should be null after shutdown")
    }
    
    @Test
    void testShutdownIdempotent() {
        // Shutdown without initialization should not throw
        HazelcastManager.instance.shutdown()
        HazelcastManager.instance.shutdown()  // Second call should be safe
        // If we get here without exception, test passes
        assertTrue(true, "Shutdown should be idempotent")
    }
}
