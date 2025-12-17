package org.softwood.actor.cluster

import com.hazelcast.config.*
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

/**
 * Builds Hazelcast configuration from application config map.
 * 
 * <p>Supports multiple discovery mechanisms:
 * <ul>
 *   <li>Multicast (default for local development)</li>
 *   <li>TCP/IP (explicit member list)</li>
 *   <li>Kubernetes (for K8s deployments)</li>
 *   <li>AWS (for EC2 deployments)</li>
 * </ul>
 * 
 * <h2>Usage</h2>
 * <pre>
 * def appConfig = ConfigLoader.loadConfig()
 * Config hzConfig = HazelcastConfigBuilder.buildConfig(appConfig)
 * HazelcastInstance hz = Hazelcast.newHazelcastInstance(hzConfig)
 * </pre>
 * 
 * @since 2.0.0
 */
@Slf4j
@CompileStatic
class HazelcastConfigBuilder {
    
    /**
     * Builds Hazelcast Config from application configuration.
     * 
     * @param appConfig flattened application config map
     * @return Hazelcast Config ready for instance creation
     */
    static Config buildConfig(Map<String, Object> appConfig) {
        Config config = new Config()
        
        // ============================================================
        // Cluster Name
        // ============================================================
        String clusterName = getString(appConfig, 'hazelcast.cluster.name', 'dev-cluster')
        config.setClusterName(clusterName)
        log.info("Hazelcast cluster name: ${clusterName}")
        
        // ============================================================
        // Instance Name (optional)
        // ============================================================
        String instanceName = getString(appConfig, 'hazelcast.instance.name')
        if (instanceName) {
            config.setInstanceName(instanceName)
        }
        
        // ============================================================
        // Network Configuration
        // ============================================================
        NetworkConfig network = config.getNetworkConfig()
        
        // Port
        int port = getInt(appConfig, 'hazelcast.port', 5701)
        network.setPort(port)
        
        // Port auto-increment
        boolean portAutoIncrement = getBoolean(appConfig, 'hazelcast.port-auto-increment', true)
        network.setPortAutoIncrement(portAutoIncrement)
        
        // Port count
        int portCount = getInt(appConfig, 'hazelcast.port-count', 100)
        network.setPortCount(portCount)
        
        log.info("Hazelcast network: port=${port}, autoIncrement=${portAutoIncrement}")
        
        // ============================================================
        // Join Configuration (Discovery)
        // ============================================================
        JoinConfig join = network.getJoin()
        
        // Determine discovery mechanism
        String discoveryMode = getString(appConfig, 'hazelcast.discovery.mode', 'multicast')
        
        switch (discoveryMode) {
            case 'kubernetes':
                configureKubernetes(join, appConfig)
                break
            
            case 'tcp':
            case 'tcp-ip':
                configureTcpIp(join, appConfig)
                break
            
            case 'aws':
                configureAws(join, appConfig)
                break
            
            case 'multicast':
            default:
                configureMulticast(join, appConfig)
                break
        }
        
        // ============================================================
        // Map Configurations (for actor registry)
        // ============================================================
        configureActorMaps(config, appConfig)
        
        // ============================================================
        // Management Center (optional)
        // ============================================================
        configureManagementCenter(config, appConfig)
        
        // ============================================================
        // Lite Member (optional)
        // ============================================================
        boolean liteMember = getBoolean(appConfig, 'hazelcast.lite-member', false)
        if (liteMember) {
            config.setLiteMember(true)
            log.info("Hazelcast lite member enabled")
        }
        
        // ============================================================
        // Logging
        // ============================================================
        config.setProperty("hazelcast.logging.type", "slf4j")
        
        return config
    }
    
    // ============================================================
    // Discovery Mode Configuration
    // ============================================================
    
    /**
     * Configure multicast discovery (default for development).
     */
    private static void configureMulticast(JoinConfig join, Map<String, Object> appConfig) {
        join.getTcpIpConfig().setEnabled(false)
        join.getKubernetesConfig().setEnabled(false)
        join.getAwsConfig().setEnabled(false)
        
        MulticastConfig multicast = join.getMulticastConfig()
        multicast.setEnabled(true)
        
        // Multicast group
        String group = getString(appConfig, 'hazelcast.multicast.group', '224.2.2.3')
        multicast.setMulticastGroup(group)
        
        // Multicast port
        int port = getInt(appConfig, 'hazelcast.multicast.port', 54327)
        multicast.setMulticastPort(port)
        
        // Multicast timeout
        int timeout = getInt(appConfig, 'hazelcast.multicast.timeout', 2)
        multicast.setMulticastTimeoutSeconds(timeout)
        
        log.info("Hazelcast discovery: multicast (group=${group}, port=${port})")
    }
    
    /**
     * Configure TCP/IP discovery (explicit member list).
     */
    private static void configureTcpIp(JoinConfig join, Map<String, Object> appConfig) {
        join.getMulticastConfig().setEnabled(false)
        join.getKubernetesConfig().setEnabled(false)
        join.getAwsConfig().setEnabled(false)
        
        TcpIpConfig tcp = join.getTcpIpConfig()
        tcp.setEnabled(true)
        
        // Member list
        def members = appConfig['hazelcast.tcp.members']
        if (members instanceof String) {
            // Comma-separated list
            members.split(',').each { member ->
                String val = member as String
                tcp.addMember(val.trim())
            }
        } else if (members instanceof List) {
            members.each { member ->
                tcp.addMember(member.toString())
            }
        }
        
        // Connection timeout
        int timeout = getInt(appConfig, 'hazelcast.tcp.connection-timeout', 5)
        tcp.setConnectionTimeoutSeconds(timeout)
        
        log.info("Hazelcast discovery: TCP/IP (members=${tcp.getMembers()})")
    }
    
    /**
     * Configure Kubernetes discovery (for K8s deployments).
     */
    private static void configureKubernetes(JoinConfig join, Map<String, Object> appConfig) {
        join.getMulticastConfig().setEnabled(false)
        join.getTcpIpConfig().setEnabled(false)
        join.getAwsConfig().setEnabled(false)
        
        KubernetesConfig k8s = join.getKubernetesConfig()
        k8s.setEnabled(true)
        
        // Namespace
        String namespace = getString(appConfig, 'hazelcast.kubernetes.namespace', 'default')
        k8s.setProperty("namespace", namespace)
        
        // Service name
        String serviceName = getString(appConfig, 'hazelcast.kubernetes.service-name', 'hazelcast')
        k8s.setProperty("service-name", serviceName)
        
        // Service DNS (optional)
        String serviceDns = getString(appConfig, 'hazelcast.kubernetes.service-dns')
        if (serviceDns) {
            k8s.setProperty("service-dns", serviceDns)
        }
        
        // Service label
        String serviceLabel = getString(appConfig, 'hazelcast.kubernetes.service-label')
        if (serviceLabel) {
            k8s.setProperty("service-label-name", serviceLabel.split('=')[0])
            k8s.setProperty("service-label-value", serviceLabel.split('=')[1])
        }
        
        log.info("Hazelcast discovery: Kubernetes (namespace=${namespace}, service=${serviceName})")
    }
    
    /**
     * Configure AWS discovery (for EC2 deployments).
     */
    private static void configureAws(JoinConfig join, Map<String, Object> appConfig) {
        join.getMulticastConfig().setEnabled(false)
        join.getTcpIpConfig().setEnabled(false)
        join.getKubernetesConfig().setEnabled(false)
        
        AwsConfig aws = join.getAwsConfig()
        aws.setEnabled(true)
        
        // Region
        String region = getString(appConfig, 'hazelcast.aws.region', 'us-east-1')
        aws.setProperty("region", region)
        
        // Tag key/value (for filtering instances)
        String tagKey = getString(appConfig, 'hazelcast.aws.tag-key', 'hazelcast')
        String tagValue = getString(appConfig, 'hazelcast.aws.tag-value', 'true')
        aws.setProperty("tag-key", tagKey)
        aws.setProperty("tag-value", tagValue)
        
        // Security group (optional)
        String securityGroup = getString(appConfig, 'hazelcast.aws.security-group')
        if (securityGroup) {
            aws.setProperty("security-group-name", securityGroup)
        }
        
        log.info("Hazelcast discovery: AWS (region=${region}, tag=${tagKey}=${tagValue})")
    }
    
    // ============================================================
    // Map Configuration (for ActorRegistry)
    // ============================================================
    
    /**
     * Configure maps used by ActorRegistry.
     */
    private static void configureActorMaps(Config config, Map<String, Object> appConfig) {
        // Actor registry map
        MapConfig actorMap = new MapConfig("actors")
        
        // Backup count
        int backupCount = getInt(appConfig, 'hazelcast.map.backup-count', 1)
        actorMap.setBackupCount(backupCount)
        
        // Async backup count
        int asyncBackupCount = getInt(appConfig, 'hazelcast.map.async-backup-count', 0)
        actorMap.setAsyncBackupCount(asyncBackupCount)
        
        // Eviction policy (for memory management)
        String evictionPolicy = getString(appConfig, 'hazelcast.map.eviction-policy', 'NONE')
        if (evictionPolicy != 'NONE') {
            EvictionConfig eviction = new EvictionConfig()
            eviction.setEvictionPolicy(EvictionPolicy.valueOf(evictionPolicy))
            
            int maxSize = getInt(appConfig, 'hazelcast.map.max-size', 10000)
            eviction.setSize(maxSize)
            
            actorMap.setEvictionConfig(eviction)
        }
        
        config.addMapConfig(actorMap)
        
        log.info("Hazelcast map config: backups=${backupCount}, asyncBackups=${asyncBackupCount}")
    }
    
    // ============================================================
    // Management Center
    // ============================================================
    
    /**
     * Configure Hazelcast Management Center (optional).
     */
    private static void configureManagementCenter(Config config, Map<String, Object> appConfig) {
        boolean mcEnabled = getBoolean(appConfig, 'hazelcast.management.enabled', false)
        if (!mcEnabled) return
        
        ManagementCenterConfig mc = new ManagementCenterConfig()
        
        // Management Center URL
        String url = getString(appConfig, 'hazelcast.management.url', 'http://localhost:8080/hazelcast-mancenter')
        mc.setScriptingEnabled(true)
        
        config.setManagementCenterConfig(mc)
        
        log.info("Hazelcast Management Center enabled: ${url}")
    }
    
    // ============================================================
    // Helper Methods
    // ============================================================
    
    private static String getString(Map<String, Object> config, String key, String defaultValue = null) {
        Object value = config.get(key)
        return value != null ? value.toString() : defaultValue
    }
    
    private static int getInt(Map<String, Object> config, String key, int defaultValue) {
        Object value = config.get(key)
        if (value instanceof Number) {
            return ((Number) value).intValue()
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString())
            } catch (NumberFormatException e) {
                log.warn("Invalid integer for ${key}: ${value}, using default: ${defaultValue}")
            }
        }
        return defaultValue
    }
    
    private static boolean getBoolean(Map<String, Object> config, String key, boolean defaultValue) {
        Object value = config.get(key)
        if (value instanceof Boolean) {
            return (Boolean) value
        }
        if (value != null) {
            return value.toString().toLowerCase() in ['true', '1', 'yes', 'on']
        }
        return defaultValue
    }
}
