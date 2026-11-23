package org.softwood.security

import groovy.transform.CompileStatic
import org.softwood.security.JwtTokenService.TokenClaims

/**
 * Authentication context for authenticated requests.
 * 
 * <p>Contains identity and authorization information from validated JWT token.</p>
 * 
 * @since 2.0.0
 */
@CompileStatic
class AuthContext {
    
    /** Whether request is authenticated */
    final boolean authenticated
    
    /** Token claims (null if not authenticated) */
    final TokenClaims claims
    
    /** Raw token string */
    final String token
    
    /**
     * Creates unauthenticated context.
     */
    AuthContext() {
        this.authenticated = false
        this.claims = null
        this.token = null
    }
    
    /**
     * Creates authenticated context with token claims.
     */
    AuthContext(TokenClaims claims, String token) {
        this.authenticated = true
        this.claims = claims
        this.token = token
    }
    
    /**
     * Gets the authenticated subject (username/service ID).
     * 
     * @return subject or null if not authenticated
     */
    String getSubject() {
        return claims?.subject
    }
    
    /**
     * Gets the user's roles.
     * 
     * @return roles list or empty if not authenticated
     */
    List<String> getRoles() {
        return claims?.roles ?: []
    }
    
    /**
     * Checks if user has a specific role.
     * 
     * @param role role name
     * @return true if user has role
     */
    boolean hasRole(String role) {
        return claims?.hasRole(role) ?: false
    }
    
    /**
     * Checks if user has any of the specified roles.
     * 
     * @param roles list of role names
     * @return true if user has at least one role
     */
    boolean hasAnyRole(List<String> roles) {
        return claims?.hasAnyRole(roles) ?: false
    }
    
    /**
     * Checks if user has all of the specified roles.
     * 
     * @param roles list of role names
     * @return true if user has all roles
     */
    boolean hasAllRoles(List<String> roles) {
        return claims?.hasAllRoles(roles) ?: false
    }
    
    /**
     * Requires authentication - throws if not authenticated.
     * 
     * @throws AuthenticationException if not authenticated
     */
    void requireAuthenticated() {
        if (!authenticated) {
            throw new AuthenticationException("Authentication required")
        }
    }
    
    /**
     * Requires specific role - throws if not present.
     * 
     * @param role required role
     * @throws AuthorizationException if role not present
     */
    void requireRole(String role) {
        requireAuthenticated()
        if (!hasRole(role)) {
            throw new AuthorizationException("Role '${role}' required")
        }
    }
    
    /**
     * Requires any of the specified roles - throws if none present.
     * 
     * @param roles required roles
     * @throws AuthorizationException if no roles present
     */
    void requireAnyRole(List<String> roles) {
        requireAuthenticated()
        if (!hasAnyRole(roles)) {
            throw new AuthorizationException("One of roles ${roles} required")
        }
    }
    
    /**
     * Requires all of the specified roles - throws if not all present.
     * 
     * @param roles required roles
     * @throws AuthorizationException if not all roles present
     */
    void requireAllRoles(List<String> roles) {
        requireAuthenticated()
        if (!hasAllRoles(roles)) {
            throw new AuthorizationException("All roles ${roles} required")
        }
    }
    
    @Override
    String toString() {
        if (authenticated) {
            return "AuthContext[authenticated=true, subject=${subject}, roles=${roles}]"
        } else {
            return "AuthContext[authenticated=false]"
        }
    }
    
    // ═════════════════════════════════════════════════════════════
    // Exceptions
    // ═════════════════════════════════════════════════════════════
    
    static class AuthenticationException extends RuntimeException {
        AuthenticationException(String message) {
            super(message)
        }
    }
    
    static class AuthorizationException extends RuntimeException {
        AuthorizationException(String message) {
            super(message)
        }
    }
}
