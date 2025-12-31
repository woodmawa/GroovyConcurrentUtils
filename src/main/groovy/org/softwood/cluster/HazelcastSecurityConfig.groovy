package org.softwood.cluster

import groovy.util.logging.Slf4j
import org.softwood.actor.remote.security.CertificateResolver

/**
 * Security configuration for Hazelcast clustering.
 * 
 * Controls network encryption, authentication, and message validation
 * to prevent unauthorized access and tampering in distributed environments.
 * 
 * <p>Uses {@link CertificateResolver} for flexible certificate loading from
 * filesystem, classpath, environment variables, or system properties.</p>
 * 
 * @see CertificateResolver
 */
@Slf4j
class HazelcastSecurityConfig {
    
    // TLS/SSL Configuration
    boolean tlsEnabled = false
    String keyStoreExplicitPath
    String keyStorePropertyKey = 'hazelcast.tls.keystore.path'
    String keyStoreEnvVar = 'HAZELCAST_TLS_KEYSTORE_PATH'
    String keyStoreClasspathResource = '/certs/hazelcast-keystore.jks'
    String keyStorePassword
    String keyStoreType = "JKS"
    
    String trustStoreExplicitPath
    String trustStorePropertyKey = 'hazelcast.tls.truststore.path'
    String trustStoreEnvVar = 'HAZELCAST_TLS_TRUSTSTORE_PATH'
    String trustStoreClasspathResource = '/certs/truststore.jks'
    String trustStorePassword
    String trustStoreType = "JKS"
    
    String tlsProtocol = "TLSv1.3"
    boolean validateCertificates = true
    boolean developmentMode = false
    private CertificateResolver certificateResolver
    
    // Authentication Configuration
    boolean authenticationEnabled = false
    String clusterUsername
    String clusterPassword
    int authenticationTimeoutMillis = 5000
    
    // Message Signing Configuration
    boolean messageSigningEnabled = false
    String messageSigningKey
    String messageSigningAlgorithm = "HmacSHA256"
    
    // Network Configuration
    boolean disableMulticast = true
    List<String> allowedMembers = []
    String bindAddress
    int port = 5701
    boolean portAutoIncrement = false
    
    // Logging Configuration
    boolean logSecurityEvents = true
    boolean logAllMessages = false
    
    // =========================================================================
    // Certificate Resolution Methods
    // =========================================================================
    
    String resolveKeyStorePath() {
        def resolver = getCertificateResolver()
        return resolver.resolve(
            keyStoreExplicitPath,
            keyStorePropertyKey,
            keyStoreEnvVar,
            keyStoreClasspathResource
        )
    }
    
    String resolveTrustStorePath() {
        def resolver = getCertificateResolver()
        def trustPath = resolver.resolve(
            trustStoreExplicitPath,
            trustStorePropertyKey,
            trustStoreEnvVar,
            trustStoreClasspathResource
        )
        if (!trustPath) {
            log.debug("Truststore not configured, using keystore")
            return resolveKeyStorePath()
        }
        return trustPath
    }
    
    CertificateResolver getCertificateResolver() {
        if (certificateResolver == null) {
            certificateResolver = new CertificateResolver(null, developmentMode)
        }
        return certificateResolver
    }
    
    void setCertificateResolver(CertificateResolver resolver) {
        this.certificateResolver = resolver
    }
    
    // =========================================================================
    // Validation
    // =========================================================================
    
    void validate() {
        if (tlsEnabled) {
            def keystorePath = resolveKeyStorePath()
            if (!keystorePath) {
                throw new IllegalStateException(
                    "tlsEnabled=true requires keystore. Tried: " +
                    "explicitPath=${keyStoreExplicitPath}, " +
                    "property=${keyStorePropertyKey}, " +
                    "envVar=${keyStoreEnvVar}, " +
                    "classpath=${keyStoreClasspathResource}"
                )
            }
            if (!keyStorePassword) {
                throw new IllegalStateException(
                    "keyStorePassword required when tlsEnabled=true"
                )
            }
            def resolver = getCertificateResolver()
            if (!resolver.validatePath(keystorePath)) {
                throw new IllegalStateException(
                    "keyStore not accessible: ${keystorePath}"
                )
            }
            log.debug("Keystore validated: ${keystorePath}")
        }
        
        if (authenticationEnabled) {
            if (!clusterUsername || clusterUsername.length() < 8) {
                throw new IllegalStateException(
                    "clusterUsername must be at least 8 characters when authenticationEnabled=true"
                )
            }
            if (!clusterPassword || clusterPassword.length() < 8) {
                throw new IllegalStateException(
                    "clusterPassword must be at least 8 characters when authenticationEnabled=true"
                )
            }
        }
        
        if (messageSigningEnabled) {
            if (!messageSigningKey || messageSigningKey.length() < 32) {
                throw new IllegalStateException(
                    "messageSigningKey must be at least 32 characters when messageSigningEnabled=true. " +
                    "Current length: ${messageSigningKey?.length() ?: 0}"
                )
            }
        }
        
        if (!disableMulticast && allowedMembers.isEmpty()) {
            log.warn("⚠️  Multicast enabled with no member whitelist - cluster is discoverable by anyone on network!")
        }
        
        if (portAutoIncrement) {
            log.warn("⚠️  Port auto-increment enabled - may expose cluster to port scanning")
        }
    }
    
    // =========================================================================
    // Builder Pattern
    // =========================================================================
    
    static Builder builder() {
        new Builder()
    }
    
    static class Builder {
        private HazelcastSecurityConfig instance = new HazelcastSecurityConfig()
        
        Builder tlsEnabled(boolean val) { instance.tlsEnabled = val; this }
        Builder keyStoreExplicitPath(String val) { instance.keyStoreExplicitPath = val; this }
        Builder keyStorePropertyKey(String val) { instance.keyStorePropertyKey = val; this }
        Builder keyStoreEnvVar(String val) { instance.keyStoreEnvVar = val; this }
        Builder keyStoreClasspathResource(String val) { instance.keyStoreClasspathResource = val; this }
        Builder keyStorePassword(String val) { instance.keyStorePassword = val; this }
        Builder keyStoreType(String val) { instance.keyStoreType = val; this }
        Builder trustStoreExplicitPath(String val) { instance.trustStoreExplicitPath = val; this }
        Builder trustStorePropertyKey(String val) { instance.trustStorePropertyKey = val; this }
        Builder trustStoreEnvVar(String val) { instance.trustStoreEnvVar = val; this }
        Builder trustStoreClasspathResource(String val) { instance.trustStoreClasspathResource = val; this }
        Builder trustStorePassword(String val) { instance.trustStorePassword = val; this }
        Builder trustStoreType(String val) { instance.trustStoreType = val; this }
        Builder tlsProtocol(String val) { instance.tlsProtocol = val; this }
        Builder validateCertificates(boolean val) { instance.validateCertificates = val; this }
        Builder developmentMode(boolean val) { instance.developmentMode = val; this }
        Builder certificateResolver(CertificateResolver val) { instance.certificateResolver = val; this }
        Builder authenticationEnabled(boolean val) { instance.authenticationEnabled = val; this }
        Builder clusterUsername(String val) { instance.clusterUsername = val; this }
        Builder clusterPassword(String val) { instance.clusterPassword = val; this }
        Builder authenticationTimeoutMillis(int val) { instance.authenticationTimeoutMillis = val; this }
        Builder messageSigningEnabled(boolean val) { instance.messageSigningEnabled = val; this }
        Builder messageSigningKey(String val) { instance.messageSigningKey = val; this }
        Builder messageSigningAlgorithm(String val) { instance.messageSigningAlgorithm = val; this }
        Builder disableMulticast(boolean val) { instance.disableMulticast = val; this }
        Builder allowedMembers(List<String> val) { instance.allowedMembers = val; this }
        Builder bindAddress(String val) { instance.bindAddress = val; this }
        Builder port(int val) { instance.port = val; this }
        Builder portAutoIncrement(boolean val) { instance.portAutoIncrement = val; this }
        Builder logSecurityEvents(boolean val) { instance.logSecurityEvents = val; this }
        Builder logAllMessages(boolean val) { instance.logAllMessages = val; this }
        
        HazelcastSecurityConfig build() { instance }
    }
    
    // =========================================================================
    // Factory Methods
    // =========================================================================
    
    static HazelcastSecurityConfig insecure() {
        return builder()
            .tlsEnabled(false)
            .authenticationEnabled(false)
            .messageSigningEnabled(false)
            .disableMulticast(false)
            .portAutoIncrement(true)
            .logSecurityEvents(false)
            .developmentMode(true)
            .build()
    }
    
    static HazelcastSecurityConfig secure(
        String keyStoreExplicitPath = null,
        String keyStorePassword,
        String clusterUsername,
        String clusterPassword,
        String messageSigningKey,
        List<String> allowedMembers = []
    ) {
        return builder()
            .tlsEnabled(true)
            .keyStoreExplicitPath(keyStoreExplicitPath)
            .keyStorePassword(keyStorePassword)
            .keyStoreType("JKS")
            .tlsProtocol("TLSv1.3")
            .validateCertificates(true)
            .authenticationEnabled(true)
            .clusterUsername(clusterUsername)
            .clusterPassword(clusterPassword)
            .messageSigningEnabled(true)
            .messageSigningKey(messageSigningKey)
            .messageSigningAlgorithm("HmacSHA256")
            .disableMulticast(true)
            .allowedMembers(allowedMembers)
            .portAutoIncrement(false)
            .logSecurityEvents(true)
            .developmentMode(false)
            .build()
    }
    
    static HazelcastSecurityConfig fromEnvironment() {
        def builder = builder()
        
        if (System.getenv("HAZELCAST_TLS_ENABLED") == "true") {
            builder.tlsEnabled(true)
            builder.keyStorePassword(System.getenv("HAZELCAST_KEYSTORE_PASSWORD"))
            builder.trustStorePassword(System.getenv("HAZELCAST_TRUSTSTORE_PASSWORD"))
        }
        
        if (System.getenv("HAZELCAST_AUTH_ENABLED") == "true") {
            builder.authenticationEnabled(true)
            builder.clusterUsername(System.getenv("HAZELCAST_CLUSTER_USERNAME"))
            builder.clusterPassword(System.getenv("HAZELCAST_CLUSTER_PASSWORD"))
        }
        
        if (System.getenv("HAZELCAST_MESSAGE_SIGNING_ENABLED") == "true") {
            builder.messageSigningEnabled(true)
            builder.messageSigningKey(System.getenv("HAZELCAST_MESSAGE_SIGNING_KEY"))
        }
        
        def allowedMembers = System.getenv("HAZELCAST_ALLOWED_MEMBERS")
        if (allowedMembers) {
            builder.allowedMembers(allowedMembers.split(",").collect { it.trim() })
        }
        
        def bindAddress = System.getenv("HAZELCAST_BIND_ADDRESS")
        if (bindAddress) {
            builder.bindAddress(bindAddress)
        }
        
        def port = System.getenv("HAZELCAST_PORT")
        if (port) {
            builder.port(Integer.parseInt(port))
        }
        
        return builder.build()
    }
    
    static HazelcastSecurityConfig fromConfig(ConfigObject config, CertificateResolver resolver = null) {
        def builder = builder()
        
        if (resolver) {
            builder.certificateResolver(resolver)
        }
        
        if (config.containsKey('development')) {
            builder.developmentMode(config.development as boolean)
        }
        
        if (config.containsKey('tls')) {
            def tls = config.tls
            if (tls.enabled) {
                builder.tlsEnabled(true)
                if (tls.keystore?.path) {
                    builder.keyStoreExplicitPath(tls.keystore.path as String)
                }
                if (tls.keystore?.password) {
                    builder.keyStorePassword(tls.keystore.password as String)
                }
                if (tls.keystore?.type) {
                    builder.keyStoreType(tls.keystore.type as String)
                }
                if (tls.truststore?.path) {
                    builder.trustStoreExplicitPath(tls.truststore.path as String)
                }
                if (tls.truststore?.password) {
                    builder.trustStorePassword(tls.truststore.password as String)
                }
                if (tls.protocols) {
                    builder.tlsProtocol(tls.protocols[0] as String)
                }
            }
        }
        
        if (config.containsKey('authentication')) {
            def auth = config.authentication
            if (auth.enabled) {
                builder.authenticationEnabled(true)
                builder.clusterUsername(auth.username as String)
                builder.clusterPassword(auth.password as String)
            }
        }
        
        if (config.containsKey('messageSigning')) {
            def signing = config.messageSigning
            if (signing.enabled) {
                builder.messageSigningEnabled(true)
                builder.messageSigningKey(signing.key as String)
                if (signing.algorithm) {
                    builder.messageSigningAlgorithm(signing.algorithm as String)
                }
            }
        }
        
        if (config.containsKey('network')) {
            def network = config.network
            if (network.allowedMembers) {
                builder.allowedMembers(network.allowedMembers as List<String>)
            }
            if (network.bindAddress) {
                builder.bindAddress(network.bindAddress as String)
            }
            if (network.port) {
                builder.port(network.port as int)
            }
        }
        
        return builder.build()
    }
}
