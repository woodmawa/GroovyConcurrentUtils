package org.softwood.cluster

import com.hazelcast.config.Config
import com.hazelcast.config.SSLConfig
import com.hazelcast.config.security.RealmConfig
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
        initialize(clusterConfig, null)
    }

    /**
     * Initialize Hazelcast with the provided configuration and security settings (GCU 1.2.1).
     * With a security config a start-up failure is thrown, not swallowed: see {@link #buildConfig}.
     *
     * @param clusterConfig as for {@link #initialize(Map)}
     * @param security settings to apply, or null for the unsecured behaviour of earlier versions
     */
    synchronized void initialize(Map clusterConfig, HazelcastSecurityConfig security) {
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
            
            Config config = buildConfig(clusterConfig, security)
            
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
            if (security != null) {
                // A secured cluster was asked for and could not be started. Running unclustered silently, or
                // retrying without the settings, would both hide that: fail where the caller can see it.
                throw new IllegalStateException(
                        "Hazelcast could not be started with the requested security settings: ${e.message}".toString(), e)
            }
        }
    }

    /**
     * Builds the Hazelcast configuration this manager starts with (GCU 1.2.1).
     *
     * <p>With no security config the result is what earlier versions built: the given port with
     * auto-increment, TCP/IP discovery when {@code cluster.members} is set and multicast otherwise.</p>
     *
     * <p>With a {@link HazelcastSecurityConfig} the settings are validated and applied: bind address,
     * port and port auto-increment, the member allow-list, multicast only if the config allows it, TLS
     * and member authentication. There is no fallback to multicast: with multicast disabled and no
     * members named, this method refuses. TLS and member authentication are Hazelcast Enterprise
     * features; on the open-source edition Hazelcast itself refuses to start with them enabled, and
     * {@link #initialize(Map, HazelcastSecurityConfig)} reports that as an error.</p>
     */
    static Config buildConfig(Map clusterConfig, HazelcastSecurityConfig security) {
        Map cfgMap = clusterConfig ?: [:]
        Config config = new Config()

        Map clusterMap = (cfgMap.get('cluster') ?: [:]) as Map
        String clusterName = (clusterMap.get('name') ?: 'taskgraph-cluster') as String
        config.setClusterName(clusterName)

        NetworkConfig networkConfig = config.getNetworkConfig()
        JoinConfig joinConfig = networkConfig.getJoin()
        joinConfig.getMulticastConfig().setEnabled(false)

        Object members = clusterMap.get('members')
        List<String> memberList = (members instanceof List) ?
                ((List) members).collect { it.toString() } : new ArrayList<String>()

        if (security == null) {
            int legacyPort = (cfgMap.get('port') ?: 5701) as int
            networkConfig.setPort(legacyPort)
            networkConfig.setPortAutoIncrement(true)
            if (!memberList.isEmpty()) {
                joinConfig.getTcpIpConfig().setEnabled(true).setMembers(memberList)
                log.info "TCP/IP discovery enabled with members: $memberList"
            } else {
                // Local development only: any member with the same cluster name on the segment can join.
                joinConfig.getMulticastConfig().setEnabled(true)
                log.info "Multicast discovery enabled (no explicit members configured)"
            }
            return config
        }

        security.validate()

        int port = (cfgMap.get('port') ?: security.port) as int
        networkConfig.setPort(port)
        networkConfig.setPortAutoIncrement(security.portAutoIncrement)

        if (security.bindAddress) {
            networkConfig.getInterfaces().setEnabled(true).addInterface(security.bindAddress)
        }

        LinkedHashSet<String> allMembers = new LinkedHashSet<String>(memberList)
        if (security.allowedMembers != null) {
            allMembers.addAll(security.allowedMembers)
        }
        if (!allMembers.isEmpty()) {
            joinConfig.getTcpIpConfig().setEnabled(true).setMembers(new ArrayList<String>(allMembers))
        } else if (!security.disableMulticast) {
            joinConfig.getMulticastConfig().setEnabled(true)
        } else {
            throw new IllegalStateException(
                    'No way to find cluster members: multicast is disabled and neither cluster.members ' +
                    'nor allowedMembers names any')
        }

        if (security.tlsEnabled) {
            SSLConfig ssl = new SSLConfig().setEnabled(true)
            ssl.setProperty('keyStore', security.resolveKeyStorePath())
            ssl.setProperty('keyStorePassword', security.keyStorePassword)
            ssl.setProperty('keyStoreType', security.keyStoreType)
            String trustStore = security.resolveTrustStorePath()
            if (trustStore) {
                ssl.setProperty('trustStore', trustStore)
                if (security.trustStorePassword) {
                    ssl.setProperty('trustStorePassword', security.trustStorePassword)
                }
                ssl.setProperty('trustStoreType', security.trustStoreType)
            }
            ssl.setProperty('protocol', security.tlsProtocol)
            networkConfig.setSSLConfig(ssl)
        }

        if (security.authenticationEnabled) {
            RealmConfig realm = new RealmConfig()
                    .setUsernamePasswordIdentityConfig(security.clusterUsername, security.clusterPassword)
            config.getSecurityConfig().setEnabled(true).setMemberRealmConfig('gcu-members', realm)
        }

        return config
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
