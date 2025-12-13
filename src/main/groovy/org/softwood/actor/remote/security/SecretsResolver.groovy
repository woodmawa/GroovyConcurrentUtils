package org.softwood.actor.remote.security

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

/**
 * Simple secrets resolver that reads from environment variables and system properties.
 * 
 * <p>This is a basic implementation suitable for development and simple deployments.
 * For production, integrate with a proper secrets manager like:</p>
 * <ul>
 *   <li>AWS Secrets Manager</li>
 *   <li>Azure Key Vault</li>
 *   <li>Google Secret Manager</li>
 *   <li>HashiCorp Vault</li>
 * </ul>
 * 
 * <h2>Usage</h2>
 * <pre>
 * // In config.groovy
 * actor {
 *     remote {
 *         rsocket {
 *             tls {
 *                 enabled = true
 *                 keyStorePath = '/path/to/keystore.jks'
 *                 keyStorePassword = SecretsResolver.resolve('TLS_KEYSTORE_PASSWORD')
 *             }
 *         }
 *     }
 * }
 * 
 * // Set environment variable
 * export TLS_KEYSTORE_PASSWORD=mySecretPassword
 * 
 * // Or system property
 * java -DTLS_KEYSTORE_PASSWORD=mySecretPassword -jar app.jar
 * </pre>
 * 
 * @since 2.0.0
 */
@Slf4j
@CompileStatic
class SecretsResolver {
    
    /**
     * Resolves a secret from environment variables or system properties.
     * 
     * <p>Resolution order:</p>
     * <ol>
     *   <li>Environment variable with exact name</li>
     *   <li>System property with exact name</li>
     *   <li>Environment variable with prefix removed (ACTOR_)</li>
     *   <li>Returns null if not found</li>
     * </ol>
     * 
     * @param key secret name (e.g., 'TLS_KEYSTORE_PASSWORD')
     * @return secret value or null if not found
     */
    static String resolve(String key) {
        if (!key) {
            return null
        }
        
        // Try environment variable
        def value = System.getenv(key)
        if (value) {
            log.debug("Resolved secret '${maskKey(key)}' from environment variable")
            return value
        }
        
        // Try system property
        value = System.getProperty(key)
        if (value) {
            log.debug("Resolved secret '${maskKey(key)}' from system property")
            return value
        }
        
        // Try with ACTOR_ prefix in environment
        def prefixedKey = "ACTOR_${key}"
        value = System.getenv(prefixedKey)
        if (value) {
            log.debug("Resolved secret '${maskKey(key)}' from environment variable '${prefixedKey}'")
            return value
        }
        
        log.warn("Secret '${maskKey(key)}' not found in environment or system properties")
        return null
    }
    
    /**
     * Resolves a secret with a default value.
     * 
     * @param key secret name
     * @param defaultValue value to return if secret not found
     * @return secret value or default
     */
    static String resolve(String key, String defaultValue) {
        def value = resolve(key)
        return value ?: defaultValue
    }
    
    /**
     * Resolves a required secret, throwing exception if not found.
     * 
     * @param key secret name
     * @return secret value
     * @throws IllegalStateException if secret not found
     */
    static String resolveRequired(String key) {
        def value = resolve(key)
        if (!value) {
            throw new IllegalStateException("Required secret '${key}' not found in environment or system properties")
        }
        return value
    }
    
    /**
     * Checks if a secret exists.
     * 
     * @param key secret name
     * @return true if secret exists
     */
    static boolean exists(String key) {
        return resolve(key) != null
    }
    
    /**
     * Masks a secret key for logging (shows first 3 chars).
     */
    private static String maskKey(String key) {
        if (!key || key.length() <= 3) {
            return "***"
        }
        return key.substring(0, 3) + "***"
    }
    
    /**
     * Masks a secret value for logging.
     */
    static String maskValue(String value) {
        if (!value) {
            return "***"
        }
        if (value.length() <= 4) {
            return "****"
        }
        return value.substring(0, 2) + "****" + value.substring(value.length() - 2)
    }
}
