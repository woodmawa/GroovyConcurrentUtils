package org.softwood.actor.remote.security

import groovy.transform.CompileDynamic
import org.junit.jupiter.api.*

import static org.junit.jupiter.api.Assertions.*

/**
 * Tests for SecretsResolver.
 */
@CompileDynamic
class SecretsResolverTest {
    
    private static final String TEST_KEY = 'TEST_SECRET_KEY'
    private static final String TEST_VALUE = 'test-secret-value'
    
    @BeforeEach
    void setup() {
        // Clean up any existing test properties
        System.clearProperty(TEST_KEY)
    }
    
    @AfterEach
    void cleanup() {
        System.clearProperty(TEST_KEY)
    }
    
    @Test
    void test_resolve_from_system_property() {
        // Set system property
        System.setProperty(TEST_KEY, TEST_VALUE)
        
        // Resolve
        def result = SecretsResolver.resolve(TEST_KEY)
        
        assertEquals(TEST_VALUE, result)
    }
    
    @Test
    void test_resolve_with_default() {
        // Key doesn't exist
        def result = SecretsResolver.resolve('NON_EXISTENT_KEY', 'default-value')
        
        assertEquals('default-value', result)
    }
    
    @Test
    void test_resolve_required_success() {
        System.setProperty(TEST_KEY, TEST_VALUE)
        
        def result = SecretsResolver.resolveRequired(TEST_KEY)
        
        assertEquals(TEST_VALUE, result)
    }
    
    @Test
    void test_resolve_required_throws_when_missing() {
        assertThrows(IllegalStateException) {
            SecretsResolver.resolveRequired('NON_EXISTENT_KEY')
        }
    }
    
    @Test
    void test_exists() {
        assertFalse(SecretsResolver.exists('NON_EXISTENT_KEY'))
        
        System.setProperty(TEST_KEY, TEST_VALUE)
        assertTrue(SecretsResolver.exists(TEST_KEY))
    }
    
    @Test
    void test_mask_value() {
        def masked = SecretsResolver.maskValue('mySecretPassword123')
        
        // Should show first 2 and last 2 chars
        assertTrue(masked.startsWith('my'))
        assertTrue(masked.endsWith('23'))
        assertTrue(masked.contains('****'))
        assertNotEquals('mySecretPassword123', masked)
    }
    
    @Test
    void test_resolve_null_key() {
        def result = SecretsResolver.resolve(null)
        assertNull(result)
    }
    
    @Test
    void test_resolve_empty_key() {
        def result = SecretsResolver.resolve('')
        assertNull(result)
    }
    
    @Test
    void test_priority_environment_over_system_property() {
        // This test documents the priority order
        // In real execution, environment variables take precedence
        // But we can't easily set env vars in JUnit tests
        
        System.setProperty(TEST_KEY, 'from-system-property')
        def result = SecretsResolver.resolve(TEST_KEY)
        
        // Will get system property since we can't set env var in test
        assertEquals('from-system-property', result)
    }
}
