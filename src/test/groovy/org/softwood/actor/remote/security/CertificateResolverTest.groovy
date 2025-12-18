package org.softwood.actor.remote.security

import groovy.transform.CompileDynamic
import org.junit.jupiter.api.*
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.*

/**
 * Tests for CertificateResolver.
 * 
 * Tests the multi-layered certificate resolution strategy including:
 * - Explicit paths
 * - System properties
 * - Environment variables
 * - Config files
 * - Classpath resources
 * - Development mode
 */
@CompileDynamic
class CertificateResolverTest {
    
    @TempDir
    Path tempDir
    
    private CertificateResolver resolver
    private File testCertFile
    
    @BeforeEach
    void setup() {
        resolver = new CertificateResolver(null, false)
        
        // Create a test certificate file
        testCertFile = tempDir.resolve("test-keystore.jks").toFile()
        testCertFile.text = "fake keystore content"
    }
    
    @AfterEach
    void cleanup() {
        // Clean up system properties
        System.clearProperty('test.cert.path')
        System.clearProperty('actor.tls.keystore.path')
    }
    
    @Test
    void test_resolve_with_explicit_path() {
        // Given: A valid file path
        def path = testCertFile.absolutePath
        
        // When: Resolving with explicit path
        def resolved = resolver.resolve(
            path,
            'some.property',
            'SOME_ENV_VAR',
            '/some/resource'
        )
        
        // Then: The explicit path is returned
        assertNotNull(resolved)
        assertTrue(resolved.contains(testCertFile.name))
    }
    
    @Test
    void test_resolve_with_system_property() {
        // Given: A system property is set
        System.setProperty('test.cert.path', testCertFile.absolutePath)
        
        // When: Resolving without explicit path
        def resolved = resolver.resolve(
            null,
            'test.cert.path',
            'SOME_ENV_VAR',
            '/some/resource'
        )
        
        // Then: The system property path is returned
        assertNotNull(resolved)
        assertTrue(resolved.contains(testCertFile.name))
    }
    
    @Test
    void test_resolve_with_nonexistent_explicit_path() {
        // Given: An invalid file path
        def path = tempDir.resolve("nonexistent.jks").toString()
        
        // When: Resolving with nonexistent path
        def resolved = resolver.resolve(
            path,
            'some.property',
            'SOME_ENV_VAR',
            '/some/resource'
        )
        
        // Then: Resolution fails (returns null)
        assertNull(resolved)
    }
    
    @Test
    void test_resolve_priority_explicit_over_system_property() {
        // Given: Both explicit path and system property
        System.setProperty('test.cert.path', '/wrong/path/cert.jks')
        
        // When: Resolving with both
        def resolved = resolver.resolve(
            testCertFile.absolutePath,
            'test.cert.path',
            'SOME_ENV_VAR',
            '/some/resource'
        )
        
        // Then: Explicit path takes priority
        assertNotNull(resolved)
        assertTrue(resolved.contains(testCertFile.name))
    }
    
    @Test
    void test_resolve_with_config_object() {
        // Given: A config object with certificate path
        // Use direct assignment to avoid Windows path escaping issues
        def config = new ConfigObject()
        config.'test.cert.path' = testCertFile.absolutePath
        
        def resolverWithConfig = new CertificateResolver(config, false)
        
        // When: Resolving from config
        def resolved = resolverWithConfig.resolve(
            null,
            'test.cert.path',
            'SOME_ENV_VAR',
            '/some/resource'
        )
        
        // Then: Config path is returned
        assertNotNull(resolved)
        assertTrue(resolved.contains(testCertFile.name))
    }
    
    @Test
    void test_resolve_classpath_resource() {
        // Given: A classpath resource exists (using this test class as resource)
        def resourcePath = '/org/softwood/actor/remote/security/CertificateResolverTest.class'
        
        // When: Resolving classpath resource
        def resolved = resolver.resolve(
            null,
            'some.property',
            'SOME_ENV_VAR',
            resourcePath
        )
        
        // Then: Classpath resource is found
        assertNotNull(resolved, "Classpath resource should be found")
        assertEquals(resourcePath, resolved)
    }
    
    @Test
    void test_resolve_classpath_resource_without_leading_slash() {
        // Given: A classpath resource path without leading slash
        def resourcePath = 'org/softwood/actor/remote/security/CertificateResolverTest.class'
        
        // When: Resolving
        def resolved = resolver.resolve(
            null,
            'some.property',
            'SOME_ENV_VAR',
            resourcePath
        )
        
        // Then: Resource is found (resolver normalizes the path)
        assertNotNull(resolved, "Classpath resource should be found even without leading slash")
    }
    
    @Test
    void test_development_mode_fallback() {
        // Given: Resolver in development mode
        def devResolver = new CertificateResolver(null, true)
        
        // When: Resolving with all methods failing
        // Note: This will only work if test-certs exist in classpath
        def resolved = devResolver.resolve(
            null,
            'nonexistent.property',
            'NONEXISTENT_VAR',
            'server-keystore.jks'
        )
        
        // Then: Either finds test cert or returns null
        // (depends on whether test-certs are in classpath)
        // We just verify no exception is thrown
        // The actual result depends on build configuration
    }
    
    @Test
    void test_validate_path_with_valid_file() {
        // Given: A valid file path
        def path = testCertFile.absolutePath
        
        // When: Validating the path
        def isValid = resolver.validatePath(path)
        
        // Then: Path is valid
        assertTrue(isValid)
    }
    
    @Test
    void test_validate_path_with_invalid_file() {
        // Given: An invalid file path
        def path = tempDir.resolve("nonexistent.jks").toString()
        
        // When: Validating the path
        def isValid = resolver.validatePath(path)
        
        // Then: Path is invalid
        assertFalse(isValid)
    }
    
    @Test
    void test_validate_path_with_null() {
        // When: Validating null path
        def isValid = resolver.validatePath(null)
        
        // Then: Returns false
        assertFalse(isValid)
    }
    
    @Test
    void test_validate_path_with_classpath_resource() {
        // Given: A classpath resource
        def resourcePath = '/org/softwood/actor/remote/security/CertificateResolverTest.class'
        
        // When: Validating classpath resource
        def isValid = resolver.validatePath(resourcePath)
        
        // Then: Classpath resource is valid
        assertTrue(isValid, "Classpath resource should be valid")
    }
    
    @Test
    void test_open_stream_from_file() {
        // Given: A valid file
        def path = testCertFile.absolutePath
        
        // When: Opening stream
        def stream = resolver.openStream(path)
        
        // Then: Stream is opened successfully
        assertNotNull(stream)
        stream.close()
    }
    
    @Test
    void test_open_stream_from_classpath_resource() {
        // Given: A classpath resource
        def resourcePath = '/org/softwood/actor/remote/security/CertificateResolverTest.class'
        
        // When: Opening stream
        def stream = resolver.openStream(resourcePath)
        
        // Then: Stream is opened successfully
        assertNotNull(stream, "Should be able to open stream from classpath resource")
        
        // Verify we can read from it
        def firstByte = stream.read()
        assertTrue(firstByte >= 0, "Should be able to read from stream")
        
        stream.close()
    }
    
    @Test
    void test_open_stream_with_invalid_path() {
        // Given: An invalid path
        def path = tempDir.resolve("nonexistent.jks").toString()
        
        // When: Opening stream
        def stream = resolver.openStream(path)
        
        // Then: Returns null
        assertNull(stream)
    }
    
    @Test
    void test_open_stream_with_null() {
        // When: Opening stream with null
        def stream = resolver.openStream(null)
        
        // Then: Returns null
        assertNull(stream)
    }
    
    @Test
    void test_resolution_with_relative_path() {
        // Given: A file in temp directory with relative reference
        def relativePath = testCertFile.name
        
        // When: Resolving relative path
        // Note: This will likely fail unless we're in the temp directory
        def resolved = resolver.resolve(
            relativePath,
            'some.property',
            'SOME_ENV_VAR',
            '/some/resource'
        )
        
        // Then: Resolution depends on current directory
        // Just verify no exception is thrown
    }
    
    @Test
    void test_multiple_resolution_attempts() {
        // Given: Multiple resolver instances
        def resolver1 = new CertificateResolver(null, false)
        def resolver2 = new CertificateResolver(null, false)
        
        // When: Resolving same path multiple times
        def resolved1 = resolver1.resolve(
            testCertFile.absolutePath,
            'prop',
            'VAR',
            '/res'
        )
        def resolved2 = resolver2.resolve(
            testCertFile.absolutePath,
            'prop',
            'VAR',
            '/res'
        )
        
        // Then: Both return same result
        assertEquals(resolved1, resolved2)
    }
    
    @Test
    void test_resolve_with_empty_strings() {
        // When: Resolving with empty strings
        def resolved = resolver.resolve('', '', '', '')
        
        // Then: Returns null
        assertNull(resolved)
    }
    
    @Test
    void test_resolve_with_whitespace_strings() {
        // When: Resolving with whitespace
        def resolved = resolver.resolve('   ', '   ', '   ', '   ')
        
        // Then: Returns null (whitespace is treated as empty)
        assertNull(resolved)
    }
    
    @Test
    void test_config_object_with_nested_properties() {
        // Given: A config with nested properties
        // Use direct assignment to avoid Windows path escaping issues
        def config = new ConfigObject()
        config.actor.tls.keystore.path = testCertFile.absolutePath
        
        def resolverWithConfig = new CertificateResolver(config, false)
        
        // When: Resolving nested property
        def resolved = resolverWithConfig.resolve(
            null,
            'actor.tls.keystore.path',
            'SOME_VAR',
            '/some/resource'
        )
        
        // Then: Nested property is resolved
        assertNotNull(resolved)
        assertTrue(resolved.contains(testCertFile.name))
    }
    
    @Test
    void test_system_property_overrides_config() {
        // Given: Both system property and config
        def otherCert = tempDir.resolve("other-keystore.jks").toFile()
        otherCert.text = "other content"
        
        // Use direct assignment to avoid Windows path escaping issues
        def config = new ConfigObject()
        config.'test.cert.path' = testCertFile.absolutePath
        
        System.setProperty('test.cert.path', otherCert.absolutePath)
        
        def resolverWithConfig = new CertificateResolver(config, false)
        
        // When: Resolving
        def resolved = resolverWithConfig.resolve(
            null,
            'test.cert.path',
            'SOME_VAR',
            '/some/resource'
        )
        
        // Then: System property takes priority over config
        assertNotNull(resolved)
        assertTrue(resolved.contains("other-keystore.jks"))
    }
    
    @Test
    void test_classpath_resource_normalization() {
        // Given: Various classpath path formats
        def paths = [
            '/org/softwood/actor/remote/security/CertificateResolverTest.class',
            'org/softwood/actor/remote/security/CertificateResolverTest.class',
            '//org/softwood/actor/remote/security/CertificateResolverTest.class'
        ]
        
        // When/Then: All formats should resolve
        paths.each { path ->
            def resolved = resolver.resolve(null, 'prop', 'VAR', path)
            assertNotNull(resolved, "Path ${path} should resolve")
        }
    }
    
    @Test
    void test_create_resolver_with_null_config() {
        // When: Creating resolver with null config
        def nullConfigResolver = new CertificateResolver(null, false)
        
        // Then: Resolver is created successfully
        assertNotNull(nullConfigResolver)
        
        // And can still resolve explicit paths
        def resolved = nullConfigResolver.resolve(
            testCertFile.absolutePath,
            'prop',
            'VAR',
            '/res'
        )
        assertNotNull(resolved)
    }
    
    @Test
    void test_development_mode_flag() {
        // Given: Resolvers with different development modes
        def prodResolver = new CertificateResolver(null, false)
        def devResolver = new CertificateResolver(null, true)
        
        // When: Creating resolvers
        // Then: They are created successfully with different modes
        assertNotNull(prodResolver)
        assertNotNull(devResolver)
        
        // Behavior difference would be in fallback to test certs
        // which depends on classpath configuration
    }
}
