package org.softwood.security

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

/**
 * Default secret manager that retrieves secrets from environment variables
 * and system properties.
 * 
 * <p>This is the default implementation used when no external secret manager
 * is configured. It provides the same behavior as the original SecretsResolver.</p>
 * 
 * <h3>Priority Order:</h3>
 * <ol>
 *   <li>Environment variable</li>
 *   <li>System property</li>
 *   <li>Default value (if provided)</li>
 * </ol>
 * 
 * <h3>Usage:</h3>
 * <pre>
 * def manager = new EnvironmentSecretManager()
 * String password = manager.getSecret("DB_PASSWORD")
 * </pre>
 * 
 * @since 2.1.0
 */
@Slf4j
@CompileStatic
class EnvironmentSecretManager implements SecretManager {
    
    /**
     * Retrieves a secret from environment variables or system properties.
     * 
     * @param key the secret key
     * @return the secret value, or null if not found
     */
    @Override
    String getSecret(String key) {
        if (!key) {
            return null
        }
        
        // Try environment variable first
        String value = System.getenv(key)
        if (value) {
            log.trace("Secret resolved from environment variable")
            return value
        }
        
        // Fall back to system property
        value = System.getProperty(key)
        if (value) {
            log.trace("Secret resolved from system property")
            return value
        }
        
        return null
    }
    
    /**
     * Retrieves a required secret.
     * 
     * @param key the secret key
     * @return the secret value
     * @throws IllegalStateException if secret not found
     */
    @Override
    String getRequiredSecret(String key) {
        String value = getSecret(key)
        if (!value) {
            throw new IllegalStateException("Required secret not found")
        }
        return value
    }
    
    /**
     * Checks if a secret exists.
     * 
     * @param key the secret key
     * @return true if found in environment or system properties
     */
    @Override
    boolean secretExists(String key) {
        return getSecret(key) != null
    }
    
    /**
     * Gets the manager name.
     * 
     * @return "Environment"
     */
    @Override
    String getManagerName() {
        return "Environment"
    }
    
    /**
     * Checks if initialized.
     * Environment manager is always initialized.
     * 
     * @return true
     */
    @Override
    boolean isInitialized() {
        return true
    }
}
