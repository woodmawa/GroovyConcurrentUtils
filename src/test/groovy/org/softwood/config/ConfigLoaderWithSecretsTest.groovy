package org.softwood.config

import groovy.transform.CompileDynamic
import org.junit.jupiter.api.Test
import org.softwood.actor.remote.security.SecretsResolver

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
        }
    }
    
    @Test
    void test_config_loader_uses_default_when_secret_missing() {
        // Don't set any secret
        System.clearProperty('TLS_KEYSTORE_PASSWORD')
        
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
}
