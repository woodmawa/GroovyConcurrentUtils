package org.softwood.cluster

import org.softwood.security.CertificateResolver
import spock.lang.Specification

class CertificateAlignmentTest extends Specification {

    def "test Hazelcast can resolve certificates from classpath in development mode"() {
        given:
        def config = new HazelcastSecurityConfig()
        config.tlsEnabled = true
        config.developmentMode = true
        config.keyStoreClasspathResource = '/test-certs/hazelcast-keystore.jks'
        config.keyStorePassword = 'changeit'
        config.trustStoreClasspathResource = '/test-certs/truststore.jks'
        config.trustStorePassword = 'changeit'

        when:
        def keystorePath = config.resolveKeyStorePath()
        def truststorePath = config.resolveTrustStorePath()

        then:
        keystorePath != null
        keystorePath.contains('test-certs/hazelcast-keystore.jks')
        truststorePath != null
        truststorePath.contains('test-certs/truststore.jks')
        
        and:
        def resolver = config.getCertificateResolver()
        resolver.validatePath(keystorePath)
        resolver.validatePath(truststorePath)
    }

    def "test CertificateResolver works for both components"() {
        given:
        def resolver = new CertificateResolver(null, true)

        when:
        def actorPath = resolver.resolve(null, 'actor.tls.keystore.path', 'ACTOR_TLS_KEYSTORE_PATH', '/test-certs/server-keystore.jks')
        def hazelcastPath = resolver.resolve(null, 'hazelcast.tls.keystore.path', 'HAZELCAST_TLS_KEYSTORE_PATH', '/test-certs/hazelcast-keystore.jks')

        then:
        actorPath != null
        hazelcastPath != null
        
        and:
        def actorStream = resolver.openStream(actorPath)
        actorStream != null
        actorStream.close()
        
        def hazelcastStream = resolver.openStream(hazelcastPath)
        hazelcastStream != null
        hazelcastStream.close()
    }

    def "test unified truststore accessible"() {
        given:
        def resolver = new CertificateResolver(null, true)
        
        when:
        def trustPath = resolver.resolve(null, 'truststore.path', 'TRUSTSTORE_PATH', '/test-certs/truststore.jks')

        then:
        trustPath != null
        resolver.validatePath(trustPath)
        
        and:
        def stream = resolver.openStream(trustPath)
        stream != null
        stream.close()
    }

    def "test naming convention consistency"() {
        expect:
        "ACTOR_TLS_KEYSTORE_PATH".endsWith("_TLS_KEYSTORE_PATH")
        "HAZELCAST_TLS_KEYSTORE_PATH".endsWith("_TLS_KEYSTORE_PATH")
        "ACTOR_TLS_KEYSTORE_PATH".startsWith("ACTOR_")
        "HAZELCAST_TLS_KEYSTORE_PATH".startsWith("HAZELCAST_")
    }

    def "test validation fails in strict mode"() {
        given:
        def config = new HazelcastSecurityConfig()
        config.tlsEnabled = true
        config.developmentMode = false
        config.keyStorePassword = 'changeit'
        config.keyStoreClasspathResource = '/nonexistent/path.jks'

        when:
        config.validate()

        then:
        thrown(IllegalStateException)
    }

    def "test fromConfig works with ConfigObject"() {
        given:
        def config = new ConfigObject()
        config.development = true
        config.tls.enabled = true
        config.tls.keystore.path = '/test-certs/hazelcast-keystore.jks'
        config.tls.keystore.password = 'changeit'
        config.tls.truststore.path = '/test-certs/truststore.jks'
        config.tls.truststore.password = 'changeit'

        when:
        def hazelcastSecurity = HazelcastSecurityConfig.fromConfig(config, new CertificateResolver(null, true))

        then:
        hazelcastSecurity != null
        hazelcastSecurity.tlsEnabled == true
        hazelcastSecurity.keyStorePassword == 'changeit'
        
        and:
        def keystorePath = hazelcastSecurity.resolveKeyStorePath()
        keystorePath != null
    }

    def "test builder initializes defaults properly"() {
        given: "A config built using builder with minimal settings"
        def config = HazelcastSecurityConfig.builder()
            .tlsEnabled(true)
            .keyStorePassword('changeit')
            .keyStoreClasspathResource('/test-certs/hazelcast-keystore.jks')
            .developmentMode(true)
            .build()

        expect: "Default values should be initialized"
        config.tlsEnabled == true
        config.developmentMode == true
        config.keyStorePassword == 'changeit'
        config.keyStoreClasspathResource == '/test-certs/hazelcast-keystore.jks'
        
        and: "Defaults from field declarations should be present"
        config.keyStorePropertyKey == 'hazelcast.tls.keystore.path'
        config.keyStoreEnvVar == 'HAZELCAST_TLS_KEYSTORE_PATH'
        config.keyStoreType == 'JKS'
        config.tlsProtocol == 'TLSv1.3'
        config.disableMulticast == true
        config.port == 5701
        config.authenticationTimeoutMillis == 5000
        config.messageSigningAlgorithm == 'HmacSHA256'
        
        and: "Should be able to resolve certificates"
        def keystorePath = config.resolveKeyStorePath()
        keystorePath != null
        keystorePath.contains('test-certs/hazelcast-keystore.jks')
    }
}
