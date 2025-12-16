package org.softwood.actor.remote.security

import groovy.transform.CompileStatic
import java.time.Duration

/**
 * Authentication configuration for remote actor transports.
 * 
 * <p>Configures JWT-based authentication for securing actor communication.</p>
 * 
 * <h2>Usage</h2>
 * <pre>
 * // Server with authentication
 * def authConfig = new AuthConfig(
 *     enabled: true,
 *     jwtSecret: SecretsResolver.resolveRequired('JWT_SECRET'),
 *     tokenExpiry: Duration.ofHours(1)
 * )
 * 
 * def transport = new RSocketTransport(system, 7000, true, tlsConfig, authConfig)
 * transport.start()
 * 
 * // Client authenticates
 * def token = authService.authenticate('username', 'password')
 * client.setAuthToken(token)
 * </pre>
 * 
 * @since 2.0.0
 */
@CompileStatic
class AuthConfig {
    
    /** Whether authentication is enabled */
    boolean enabled = false
    
    /** JWT signing secret (min 32 characters) */
    String jwtSecret
    
    /** Token expiry duration */
    Duration tokenExpiry = Duration.ofHours(1)
    
    /** Whether to reject requests without tokens */
    boolean requireToken = true
    
    /** Token header name for HTTP */
    String tokenHeader = 'Authorization'
    
    /** Token prefix for HTTP (e.g., "Bearer ") */
    String tokenPrefix = 'Bearer '
    
    /**
     * Validates configuration.
     * 
     * @throws IllegalStateException if config is invalid
     */
    void validate() {
        if (enabled) {
            if (!jwtSecret) {
                throw new IllegalStateException("JWT secret is required when authentication is enabled")
            }
            if (jwtSecret.length() < 32) {
                throw new IllegalStateException("JWT secret must be at least 32 characters")
            }
            if (tokenExpiry.isNegative() || tokenExpiry.isZero()) {
                throw new IllegalStateException("Token expiry must be positive")
            }
        }
    }
    
    @Override
    String toString() {
        return "AuthConfig[enabled=${enabled}, tokenExpiry=${tokenExpiry}, requireToken=${requireToken}]"
    }
}
