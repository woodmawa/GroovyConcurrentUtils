package org.softwood.cluster

import com.hazelcast.config.Config
import com.hazelcast.config.JoinConfig
import com.hazelcast.config.NetworkConfig
import com.hazelcast.core.Hazelcast
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.hazelcast.topic.ITopic
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.softwood.actor.cluster.ActorLifecycleEvent
import org.softwood.actor.cluster.ActorRegistryEntry
import org.softwood.dag.cluster.ClusterTaskEvent
import org.softwood.dag.cluster.GraphExecutionState
import org.softwood.dag.cluster.TaskRuntimeState

/**
 * Singleton manager for Hazelcast clustering integration.
 * Provides centralized access to Hazelcast instance and distributed data structures.
 * 
 * <p>Usage:
 * <pre>
 * // Initialize (typically done at app startup)
 * def config = [
 *     enabled: true,
 *     port: 5701,
 *     cluster: [
 *         name: 'my-cluster',
 *         members: ['192.168.1.10:5701', '192.168.1.11:5701']
 *     ]
 * ]
 * HazelcastManager.instance.initialize(config)
 * 
 * // Get distributed map
 * IMap graphState = HazelcastManager.instance.getGraphStateMap()
 * 
 * // Shutdown
 * HazelcastManager.instance.shutdown()
 * </pre>
 * 
 * @author Will Woodman
 * @since 1.0
 */
@Slf4j
@CompileStatic
@Singleton
class HazelcastManager {
    
    private HazelcastInstance hazelcastInstance
    private boolean enabled = false
    private boolean initialized = false
    
    // Map names
    private static final String GRAPH_STATE_MAP = "taskgraph:graph-states"
    private static final String TASK_STATE_MAP = "taskgraph:task-states"
    private static final String TASK_EVENT_TOPIC = "taskgraph:task-events"
    private static final String ACTOR_REGISTRY_MAP = "actor:registry"
    private static final String ACTOR_EVENT_TOPIC = "actor:events"
    
    /**
     * Initialize Hazelcast with the provided configuration.
     * Safe to call multiple times - subsequent calls are ignored.
     * 
     * @param clusterConfig Map containing hazelcast configuration
     *        Expected keys: enabled, port, cluster.name, cluster.members
     */
    synchronized void initialize(Map clusterConfig) {
        if (initialized) {
            log.debug "HazelcastManager already initialized"
            return
        }
        
        // Reduce Hazelcast logging noise early
        quietHazelcastLogging()
        
        if (clusterConfig == null || !clusterConfig) {
            log.info "No cluster configuration provided, clustering disabled"
            enabled = false
            initialized = true
            return
        }
        
        // Check if clustering is explicitly enabled
        def enabledValue = clusterConfig.enabled
        if (enabledValue == null || enabledValue == false) {
            log.info "Clustering is disabled (enabled=false)"
            enabled = false
            initialized = true
            return
        }
        
        try {
            log.info "Initializing Hazelcast clustering..."
            
            Config config = new Config()
            
            // Set cluster name
            Map clusterMap = (clusterConfig.cluster ?: [:]) as Map
            String clusterName = (clusterMap?.name ?: 'taskgraph-cluster') as String
            config.setClusterName(clusterName)
            log.debug "Cluster name: $clusterName"
            
            // Configure network
            NetworkConfig networkConfig = config.getNetworkConfig()
            
            // Set port
            int port = (clusterConfig.port ?: 5701) as int
            networkConfig.setPort(port)
            networkConfig.setPortAutoIncrement(true)
            log.debug "Network port: $port"
            
            // Configure discovery
            JoinConfig joinConfig = networkConfig.getJoin()
            
            // Disable multicast by default (use TCP for production)
            joinConfig.getMulticastConfig().setEnabled(false)
            
            // Configure TCP/IP discovery
            def members = clusterMap?.members
            if (members && members instanceof List && !members.isEmpty()) {
                // TCP discovery with explicit members
                joinConfig.getTcpIpConfig()
                    .setEnabled(true)
                    .setMembers(members as List<String>)
                log.info "TCP/IP discovery enabled with members: $members"
            } else {
                // Enable multicast for local development (no explicit members)
                joinConfig.getMulticastConfig().setEnabled(true)
                log.info "Multicast discovery enabled (no explicit members configured)"
            }
            
            // Create Hazelcast instance
            hazelcastInstance = Hazelcast.newHazelcastInstance(config)
            enabled = true
            initialized = true
            
            log.info "Hazelcast clustering initialized successfully"
            log.info "Cluster member: ${hazelcastInstance.cluster.localMember}"
            
        } catch (Exception e) {
            log.error "Failed to initialize Hazelcast, clustering will be disabled. Error: ${e.class.name}: ${e.message}", e
            enabled = false
            initialized = true
            hazelcastInstance = null
        }
    }
    
    /**
     * Get the Hazelcast instance if clustering is enabled.
     * @return HazelcastInstance or null if clustering is disabled
     */
    HazelcastInstance getInstance() {
        return hazelcastInstance
    }
    
    /**
     * Check if clustering is enabled and operational.
     * @return true if Hazelcast is running
     */
    boolean isEnabled() {
        return enabled && hazelcastInstance != null
    }
    
    /**
     * Get the distributed map for graph execution state.
     * @return IMap for GraphExecutionState or null if clustering disabled
     */
    IMap<String, GraphExecutionState> getGraphStateMap() {
        if (!isEnabled()) {
            return null
        }
        return hazelcastInstance.getMap(GRAPH_STATE_MAP)
    }
    
    /**
     * Get the distributed map for task runtime state.
     * @return IMap for TaskRuntimeState or null if clustering disabled
     */
    IMap<String, TaskRuntimeState> getTaskStateMap() {
        if (!isEnabled()) {
            return null
        }
        return hazelcastInstance.getMap(TASK_STATE_MAP)
    }
    
    /**
     * Get the distributed topic for task events.
     * @return ITopic for task events or null if clustering disabled
     */
    ITopic<ClusterTaskEvent> getTaskEventTopic() {
        if (!isEnabled()) {
            return null
        }
        return hazelcastInstance.getTopic(TASK_EVENT_TOPIC)
    }
    
    /**
     * Get the distributed map for actor registry.
     * @return IMap for ActorRegistryEntry or null if clustering disabled
     */
    IMap<String, ActorRegistryEntry> getActorRegistryMap() {
        if (!isEnabled()) {
            return null
        }
        return hazelcastInstance.getMap(ACTOR_REGISTRY_MAP)
    }
    
    /**
     * Get the distributed topic for actor lifecycle events.
     * @return ITopic for actor events or null if clustering disabled
     */
    ITopic<ActorLifecycleEvent> getActorEventTopic() {
        if (!isEnabled()) {
            return null
        }
        return hazelcastInstance.getTopic(ACTOR_EVENT_TOPIC)
    }
    
    /**
     * Gracefully shutdown Hazelcast.
     * Safe to call multiple times.
     * Resets state to allow re-initialization.
     */
    synchronized void shutdown() {
        if (hazelcastInstance != null) {
            try {
                log.info "Shutting down Hazelcast..."
                hazelcastInstance.shutdown()
                log.info "Hazelcast shutdown complete"
            } catch (Exception e) {
                log.error "Error during Hazelcast shutdown", e
            }
        }
        // Always reset state (even if hazelcastInstance was null)
        hazelcastInstance = null
        enabled = false
        initialized = false
    }
    
    /**
     * Reduce Hazelcast logging verbosity.
     * Sets Hazelcast loggers to WARNING level to reduce startup noise.
     */
    private void quietHazelcastLogging() {
        try {
            // Hazelcast uses java.util.logging by default
            java.util.logging.Logger.getLogger("com.hazelcast").level = java.util.logging.Level.WARNING
            
            // Also quiet specific noisy loggers
            java.util.logging.Logger.getLogger("com.hazelcast.system").level = java.util.logging.Level.WARNING
            java.util.logging.Logger.getLogger("com.hazelcast.instance").level = java.util.logging.Level.WARNING
            java.util.logging.Logger.getLogger("com.hazelcast.internal").level = java.util.logging.Level.WARNING
            java.util.logging.Logger.getLogger("com.hazelcast.core").level = java.util.logging.Level.WARNING
            
        } catch (Exception e) {
            // Ignore errors setting logging levels
            log.debug "Could not configure Hazelcast logging levels: ${e.message}"
        }
    }
}
