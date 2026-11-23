package org.softwood.security

/**
 * Interface for secret management providers.
 * 
 * <p>Provides a pluggable abstraction for retrieving secrets from various
 * secret management systems such as environment variables, HashiCorp Vault,
 * AWS Secrets Manager, Azure Key Vault, etc.</p>
 * 
 * <h3>Supported Implementations:</h3>
 * <ul>
 *   <li>{@link EnvironmentSecretManager} - Environment variables (default)</li>
 *   <li>{@link VaultSecretManager} - HashiCorp Vault (optional)</li>
 *   <li>{@link AwsSecretsManager} - AWS Secrets Manager (optional)</li>
 *   <li>{@link AzureKeyVaultManager} - Azure Key Vault (optional)</li>
 * </ul>
 * 
 * <h3>Usage:</h3>
 * <pre>
 * // Use default environment variable manager
 * SecretManager manager = new EnvironmentSecretManager()
 * 
 * // Or use Vault (when enabled)
 * SecretManager manager = new VaultSecretManager(
 *     vaultUrl: "https://vault.example.com",
 *     token: "vault-token"
 * )
 * 
 * // Retrieve secrets
 * String password = manager.getSecret("DB_PASSWORD")
 * </pre>
 * 
 * @since 2.1.0
 */
interface SecretManager {
    
    /**
     * Retrieves a secret by key.
     * 
     * @param key the secret key/name
     * @return the secret value, or null if not found
     */
    String getSecret(String key)
    
    /**
     * Retrieves a required secret by key.
     * Throws exception if secret is not found.
     * 
     * @param key the secret key/name
     * @return the secret value
     * @throws IllegalStateException if secret not found
     */
    String getRequiredSecret(String key)
    
    /**
     * Checks if a secret exists.
     * 
     * @param key the secret key/name
     * @return true if secret exists, false otherwise
     */
    boolean secretExists(String key)
    
    /**
     * Gets the name of this secret manager implementation.
     * Useful for logging and diagnostics.
     * 
     * @return manager name (e.g., "Environment", "Vault", "AWS")
     */
    String getManagerName()
    
    /**
     * Checks if this manager is properly initialized and ready to use.
     * 
     * @return true if initialized, false otherwise
     */
    boolean isInitialized()
}
