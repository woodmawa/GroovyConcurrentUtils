package org.softwood.security

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
 * <p><strong>NEW in 2.1:</strong> Supports pluggable {@link SecretManager} implementations.
 * By default uses {@link EnvironmentSecretManager} (environment variables), but can
 * be configured to use Vault, AWS, Azure, or custom implementations.</p>
 * 
 * <h2>Basic Usage (Default - Environment Variables)</h2>
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
 * <h2>Advanced Usage (External Secret Manager)</h2>
 * <pre>
 * // Configure Vault (optional)
 * def vaultManager = new VaultSecretManager(
 *     vaultUrl: "https://vault.example.com",
 *     token: System.getenv("VAULT_TOKEN")
 * )
 * vaultManager.initialize()
 * SecretsResolver.setSecretManager(vaultManager)
 * 
 * // Now resolve uses Vault
 * String password = SecretsResolver.resolve('DB_PASSWORD')
 * </pre>
 * 
 * @since 2.0.0
 */
@Slf4j
@CompileStatic
class SecretsResolver {
    
    // Secret Manager (pluggable)
    private static SecretManager secretManager = new EnvironmentSecretManager()
    
    /**
     * Sets a custom secret manager implementation.
     * 
     * <p><strong>OPTIONAL:</strong> By default, SecretsResolver uses
     * {@link EnvironmentSecretManager}. Set a custom manager to use
     * Vault, AWS, Azure, or your own implementation.</p>
     * 
     * @param manager the secret manager to use
     */
    static void setSecretManager(SecretManager manager) {
        if (!manager) {
            throw new IllegalArgumentException("SecretManager cannot be null")
        }
        secretManager = manager
        log.info("Secret manager changed to: {}", manager.getManagerName())
    }
    
    /**
     * Gets the current secret manager.
     * 
     * @return the active secret manager
     */
    static SecretManager getSecretManager() {
        return secretManager
    }
    
    /**
     * Resets to default environment variable manager.
     * Useful for testing or reset scenarios.
     */
    static void resetToDefault() {
        secretManager = new EnvironmentSecretManager()
        log.info("Secret manager reset to default (Environment)")
    }
    
    /**
     * Resolves a secret using the configured {@link SecretManager}.
     * 
     * <p>By default uses environment variables and system properties.
     * If a custom SecretManager is configured (Vault, AWS, etc.), uses that instead.</p>
     * 
     * @param key secret name (e.g., 'TLS_KEYSTORE_PASSWORD')
     * @return secret value or null if not found
     */
    static String resolve(String key) {
        if (!key) {
            return null
        }
        
        try {
            String value = secretManager.getSecret(key)
            if (value) {
                log.trace("Secret resolved from {}", secretManager.getManagerName())
            } else {
                log.trace("Secret not found in {}", secretManager.getManagerName())
            }
            return value
        } catch (Exception e) {
            log.error("Error resolving secret from {}", secretManager.getManagerName(), e)
            return null
        }
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
     * Resolves a required secret using the configured {@link SecretManager}.
     * 
     * @param key secret name
     * @return secret value
     * @throws IllegalStateException if secret not found
     */
    static String resolveRequired(String key) {
        try {
            return secretManager.getRequiredSecret(key)
        } catch (Exception e) {
            // SECURITY: Don't reveal which key is missing
            throw new IllegalStateException("Required secret not found")
        }
    }
    
    /**
     * Checks if a secret exists in the configured {@link SecretManager}.
     * 
     * @param key secret name
     * @return true if secret exists
     */
    static boolean exists(String key) {
        return secretManager.secretExists(key)
    }
    
    /**
     * Completely masks a secret key for logging.
     * SECURITY: Returns generic placeholder to avoid any information leakage.
     */
    private static String maskKey(String key) {
        return "<secret>"
    }
    
    /**
     * Completely masks a secret value.
     * SECURITY: Never log any part of actual secrets.
     */
    static String maskValue(String value) {
        return "<redacted>"
    }
}
