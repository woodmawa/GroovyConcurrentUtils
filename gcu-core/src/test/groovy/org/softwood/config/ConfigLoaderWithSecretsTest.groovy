package org.softwood.config

import groovy.transform.CompileDynamic
import org.junit.jupiter.api.Test
import org.softwood.security.SecretsResolver
import org.softwood.config.cache.ConfigCache

import static org.junit.jupiter.api.Assertions.*

/**
 * Test that ConfigLoader works with SecretsResolver in config.groovy
 */
@CompileDynamic
class ConfigLoaderWithSecretsTest {
    
    @Test
    void test_config_loader_resolves_secrets() {
        // Set a test secret
        System.setProperty('TLS_KEYSTORE_PASSWORD', 'test-password-from-env')
        
        try {
            // Clear the config cache so it re-parses config.groovy
            ConfigCache.clear()
            
            // Load config (which uses SecretsResolver)
            def config = ConfigLoader.loadConfig()
            
            // Check that the password was resolved
            def password = ConfigLoader.getString(
                config, 
                'actor.remote.rsocket.tls.keyStorePassword'
            )
            
            // Should have resolved from system property
            assertEquals('test-password-from-env', password)
            
        } finally {
            System.clearProperty('TLS_KEYSTORE_PASSWORD')
            ConfigCache.clear()
        }
    }
    
    @Test
    void test_config_loader_uses_default_when_secret_missing() {
        // Don't set any secret
        System.clearProperty('TLS_KEYSTORE_PASSWORD')
        
        // Clear cache to ensure fresh parse
        ConfigCache.clear()
        
        // Load config
        def config = ConfigLoader.loadConfig()
        
        // Check that the default was used
        def password = ConfigLoader.getString(
            config,
            'actor.remote.rsocket.tls.keyStorePassword'
        )
        
        // Should use default 'changeit'
        assertEquals('changeit', password)
    }
    
    @Test
    void test_secrets_resolver_directly() {
        // Test the SecretsResolver directly (bypassing config loading)
        System.setProperty('TEST_SECRET', 'secret-value')
        
        try {
            def value = SecretsResolver.resolve('TEST_SECRET')
            assertEquals('secret-value', value)
            
            def withDefault = SecretsResolver.resolve('MISSING_SECRET', 'default')
            assertEquals('default', withDefault)
            
        } finally {
            System.clearProperty('TEST_SECRET')
        }
    }
}
