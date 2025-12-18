package org.softwood.actor.remote.security

import groovy.transform.CompileDynamic
import org.junit.jupiter.api.*
import org.junit.jupiter.api.io.TempDir

import javax.net.ssl.SSLContext
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore

import static org.junit.jupiter.api.Assertions.*

/**
 * Tests for enhanced TlsContextBuilder with classpath support.
 * 
 * Tests the enhanced SSL/TLS context builder including:
 * - Classpath resource loading
 * - Filesystem path loading
 * - Certificate resolver integration
 * - Development mode
 * - Protocol configuration
 */
@CompileDynamic
class TlsContextBuilderEnhancedTest {
    
    @TempDir
    Path tempDir
    
    private File testKeystore
    private File testTruststore
    private static final String TEST_PASSWORD = 'changeit'
    
    @BeforeEach
    void setup() {
        // Create test keystore and truststore files
        // Note: These are dummy files for testing path resolution
        // Real SSL tests would need actual valid keystores
        testKeystore = tempDir.resolve("test-keystore.jks").toFile()
        testTruststore = tempDir.resolve("test-truststore.jks").toFile()
        
        // Create minimal valid JKS keystore structure
        createDummyKeystore(testKeystore)
        createDummyKeystore(testTruststore)
    }
    
    @AfterEach
    void cleanup() {
        System.clearProperty('actor.tls.keystore.path')
        System.clearProperty('actor.tls.truststore.path')
    }
    
    /**
     * Creates a minimal valid (but empty) JKS keystore file.
     */
    private void createDummyKeystore(File file) {
        // Create an empty keystore
        KeyStore ks = KeyStore.getInstance('JKS')
        ks.load(null, TEST_PASSWORD.toCharArray())
        
        file.withOutputStream { out ->
            ks.store(out, TEST_PASSWORD.toCharArray())
        }
    }
    
    @Test
    void test_builder_creation() {
        // When: Creating builder
        def builder = TlsContextBuilder.builder()
        
        // Then: Builder is created
        assertNotNull(builder)
    }
    
    @Test
    void test_builder_with_keystore_path() {
        // When: Setting keystore path
        def builder = TlsContextBuilder.builder()
            .keyStore(testKeystore.absolutePath, TEST_PASSWORD)
        
        // Then: Builder accepts the configuration
        assertNotNull(builder)
    }
    
    @Test
    void test_builder_with_truststore_path() {
        // When: Setting truststore path
        def builder = TlsContextBuilder.builder()
            .trustStore(testTruststore.absolutePath, TEST_PASSWORD)
        
        // Then: Builder accepts the configuration
        assertNotNull(builder)
    }
    
    @Test
    void test_builder_with_protocols() {
        // When: Setting protocols
        def builder = TlsContextBuilder.builder()
            .protocols(['TLSv1.3', 'TLSv1.2'])
        
        // Then: Builder accepts the configuration
        assertNotNull(builder)
    }
    
    @Test
    void test_builder_with_cipher_suites() {
        // When: Setting cipher suites
        def builder = TlsContextBuilder.builder()
            .cipherSuites(['TLS_AES_256_GCM_SHA384', 'TLS_AES_128_GCM_SHA256'])
        
        // Then: Builder accepts the configuration
        assertNotNull(builder)
    }
    
    @Test
    void test_builder_enable_resolver() {
        // When: Enabling resolver
        def builder = TlsContextBuilder.builder()
            .useResolver(true)
        
        // Then: Builder accepts the configuration
        assertNotNull(builder)
    }
    
    @Test
    void test_builder_with_development_mode() {
        // When: Setting development mode
        def builder = TlsContextBuilder.builder()
            .developmentMode(true)
        
        // Then: Builder accepts the configuration
        assertNotNull(builder)
    }
    
    @Test
    void test_builder_with_custom_resolver() {
        // Given: A custom resolver
        def resolver = new CertificateResolver(null, false)
        
        // When: Setting custom resolver
        def builder = TlsContextBuilder.builder()
            .resolver(resolver)
        
        // Then: Builder accepts the configuration
        assertNotNull(builder)
    }
    
    @Test
    void test_builder_with_keystore_property() {
        // When: Setting keystore via property
        def builder = TlsContextBuilder.builder()
            .keyStoreProperty('actor.tls.keystore.path', TEST_PASSWORD)
        
        // Then: Builder accepts the configuration and enables resolver
        assertNotNull(builder)
    }
    
    @Test
    void test_builder_with_keystore_env() {
        // When: Setting keystore via environment variable
        def builder = TlsContextBuilder.builder()
            .keyStoreEnv('ACTOR_TLS_KEYSTORE_PATH', TEST_PASSWORD)
        
        // Then: Builder accepts the configuration and enables resolver
        assertNotNull(builder)
    }
    
    @Test
    void test_builder_with_truststore_property() {
        // When: Setting truststore via property
        def builder = TlsContextBuilder.builder()
            .trustStoreProperty('actor.tls.truststore.path', TEST_PASSWORD)
        
        // Then: Builder accepts the configuration
        assertNotNull(builder)
    }
    
    @Test
    void test_builder_with_truststore_env() {
        // When: Setting truststore via environment variable
        def builder = TlsContextBuilder.builder()
            .trustStoreEnv('ACTOR_TLS_TRUSTSTORE_PATH', TEST_PASSWORD)
        
        // Then: Builder accepts the configuration
        assertNotNull(builder)
    }
    
    @Test
    void test_build_with_keystore_only() {
        // When: Building with keystore only
        def sslContext = TlsContextBuilder.builder()
            .keyStore(testKeystore.absolutePath, TEST_PASSWORD)
            .build()
        
        // Then: SSL context is created
        assertNotNull(sslContext)
        assertTrue(sslContext instanceof SSLContext)
    }
    
    @Test
    void test_build_with_truststore_only() {
        // When: Building with truststore only
        def sslContext = TlsContextBuilder.builder()
            .trustStore(testTruststore.absolutePath, TEST_PASSWORD)
            .build()
        
        // Then: SSL context is created
        assertNotNull(sslContext)
        assertTrue(sslContext instanceof SSLContext)
    }
    
    @Test
    void test_build_with_both_stores() {
        // When: Building with both keystore and truststore
        def sslContext = TlsContextBuilder.builder()
            .keyStore(testKeystore.absolutePath, TEST_PASSWORD)
            .trustStore(testTruststore.absolutePath, TEST_PASSWORD)
            .build()
        
        // Then: SSL context is created
        assertNotNull(sslContext)
        assertTrue(sslContext instanceof SSLContext)
    }
    
    @Test
    void test_build_with_custom_protocols() {
        // When: Building with custom protocols
        def sslContext = TlsContextBuilder.builder()
            .keyStore(testKeystore.absolutePath, TEST_PASSWORD)
            .protocols(['TLSv1.3'])
            .build()
        
        // Then: SSL context is created
        assertNotNull(sslContext)
    }
    
    @Test
    void test_build_fails_with_invalid_keystore_path() {
        // Given: Invalid keystore path
        def invalidPath = tempDir.resolve("nonexistent.jks").toString()
        
        // When/Then: Building should fail
        assertThrows(FileNotFoundException) {
            TlsContextBuilder.builder()
                .keyStore(invalidPath, TEST_PASSWORD)
                .build()
        }
    }
    
    @Test
    void test_build_fails_with_wrong_password() {
        // When/Then: Building with wrong password should fail
        assertThrows(Exception) {
            TlsContextBuilder.builder()
                .keyStore(testKeystore.absolutePath, 'wrongpassword')
                .build()
        }
    }
    
    @Test
    void test_build_with_system_property_resolution() {
        // Given: System property set
        System.setProperty('actor.tls.keystore.path', testKeystore.absolutePath)
        
        // When: Building with property resolution
        def sslContext = TlsContextBuilder.builder()
            .useResolver(true)
            .keyStoreProperty('actor.tls.keystore.path', TEST_PASSWORD)
            .build()
        
        // Then: SSL context is created
        assertNotNull(sslContext)
    }
    
    @Test
    void test_build_fails_when_resolver_finds_nothing() {
        // When/Then: Building with resolver but no resolvable path should fail
        assertThrows(IllegalStateException) {
            TlsContextBuilder.builder()
                .useResolver(true)
                .keyStoreProperty('nonexistent.property', TEST_PASSWORD)
                .build()
        }
    }
    
    @Test
    void test_create_socket_factory() {
        // Given: An SSL context
        def sslContext = TlsContextBuilder.builder()
            .keyStore(testKeystore.absolutePath, TEST_PASSWORD)
            .build()
        
        def builder = TlsContextBuilder.builder()
            .protocols(['TLSv1.3', 'TLSv1.2'])
        
        // When: Creating socket factory
        def socketFactory = builder.createSocketFactory(sslContext)
        
        // Then: Socket factory is created
        assertNotNull(socketFactory)
    }
    
    @Test
    void test_create_socket_factory_with_cipher_suites() {
        // Given: An SSL context
        def sslContext = TlsContextBuilder.builder()
            .keyStore(testKeystore.absolutePath, TEST_PASSWORD)
            .build()
        
        def builder = TlsContextBuilder.builder()
            .protocols(['TLSv1.3'])
            .cipherSuites(['TLS_AES_256_GCM_SHA384'])
        
        // When: Creating socket factory with cipher suites
        def socketFactory = builder.createSocketFactory(sslContext)
        
        // Then: Socket factory is created
        assertNotNull(socketFactory)
    }
    
    @Test
    void test_builder_fluent_api() {
        // When: Using fluent API
        def sslContext = TlsContextBuilder.builder()
            .keyStore(testKeystore.absolutePath, TEST_PASSWORD)
            .trustStore(testTruststore.absolutePath, TEST_PASSWORD)
            .protocols(['TLSv1.3', 'TLSv1.2'])
            .cipherSuites(['TLS_AES_256_GCM_SHA384'])
            .useResolver(false)
            .developmentMode(false)
            .build()
        
        // Then: SSL context is created with all configurations
        assertNotNull(sslContext)
    }
    
    @Test
    void test_build_with_null_protocols() {
        // When: Building without setting protocols (uses defaults)
        def sslContext = TlsContextBuilder.builder()
            .keyStore(testKeystore.absolutePath, TEST_PASSWORD)
            .build()
        
        // Then: SSL context is created with default protocols
        assertNotNull(sslContext)
    }
    
    @Test
    void test_build_with_empty_protocols() {
        // When: Building with empty protocols list
        def sslContext = TlsContextBuilder.builder()
            .keyStore(testKeystore.absolutePath, TEST_PASSWORD)
            .protocols([])
            .build()
        
        // Then: SSL context is created
        assertNotNull(sslContext)
    }
    
    @Test
    void test_builder_configuration_methods_return_builder() {
        // Given: A builder
        def builder = TlsContextBuilder.builder()
        
        // When/Then: All configuration methods return the builder
        assertSame(builder, builder.keyStore(testKeystore.absolutePath, TEST_PASSWORD))
        assertSame(builder, builder.trustStore(testTruststore.absolutePath, TEST_PASSWORD))
        assertSame(builder, builder.protocols(['TLSv1.3']))
        assertSame(builder, builder.cipherSuites(['TLS_AES_256_GCM_SHA384']))
        assertSame(builder, builder.useResolver(true))
        assertSame(builder, builder.developmentMode(true))
    }
    
    @Test
    void test_multiple_builds_from_same_builder() {
        // Given: A configured builder
        def builder = TlsContextBuilder.builder()
            .keyStore(testKeystore.absolutePath, TEST_PASSWORD)
            .trustStore(testTruststore.absolutePath, TEST_PASSWORD)
        
        // When: Building multiple times
        def context1 = builder.build()
        def context2 = builder.build()
        
        // Then: Both contexts are created (they may or may not be the same instance)
        assertNotNull(context1)
        assertNotNull(context2)
    }
    
    @Test
    void test_build_with_resolver_and_explicit_path() {
        // Given: Both resolver and explicit path
        def builder = TlsContextBuilder.builder()
            .useResolver(true)
            .keyStore(testKeystore.absolutePath, TEST_PASSWORD)
        
        // When: Building
        def sslContext = builder.build()
        
        // Then: SSL context is created (explicit path is used)
        assertNotNull(sslContext)
    }
    
    @Test
    void test_protocol_validation() {
        // When: Setting various protocol combinations
        def validProtocols = [
            ['TLSv1.3'],
            ['TLSv1.2'],
            ['TLSv1.3', 'TLSv1.2'],
            []
        ]
        
        // Then: All valid protocols are accepted
        validProtocols.each { protocols ->
            def sslContext = TlsContextBuilder.builder()
                .keyStore(testKeystore.absolutePath, TEST_PASSWORD)
                .protocols(protocols)
                .build()
            
            assertNotNull(sslContext)
        }
    }
    
    @Test
    void test_resolver_with_custom_config() {
        // Given: Custom config and resolver
        // Use direct assignment to avoid Windows path escaping issues
        def config = new ConfigObject()
        config.'custom.keystore.path' = testKeystore.absolutePath
        
        def customResolver = new CertificateResolver(config, false)
        
        // When: Building with custom resolver
        // Note: We still need to set the path explicitly since resolver
        // is used internally for resolution, not for initial path setting
        System.setProperty('custom.keystore.path', testKeystore.absolutePath)
        
        def sslContext = TlsContextBuilder.builder()
            .resolver(customResolver)
            .keyStoreProperty('custom.keystore.path', TEST_PASSWORD)
            .build()
        
        // Then: SSL context is created
        assertNotNull(sslContext)
    }
}
