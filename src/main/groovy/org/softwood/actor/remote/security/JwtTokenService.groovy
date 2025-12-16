package org.softwood.actor.remote.security

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.Duration

/**
 * JWT (JSON Web Token) service for actor authentication.
 * 
 * <p>Provides stateless authentication using signed tokens. Tokens contain:</p>
 * <ul>
 *   <li>Subject (username/service ID)</li>
 *   <li>Roles (for authorization)</li>
 *   <li>Issued at timestamp</li>
 *   <li>Expiry timestamp</li>
 *   <li>HMAC-SHA256 signature</li>
 * </ul>
 * 
 * <h2>Usage</h2>
 * <pre>
 * // Create service with secret
 * def secret = SecretsResolver.resolveRequired('JWT_SECRET')
 * def service = new JwtTokenService(secret, Duration.ofHours(1))
 * 
 * // Generate token
 * def token = service.generateToken('john.doe', ['USER', 'MANAGER'])
 * 
 * // Validate token
 * def claims = service.validateToken(token)
 * println "User: ${claims.subject}"
 * println "Roles: ${claims.roles}"
 * </pre>
 * 
 * @since 2.0.0
 */
@Slf4j
@CompileStatic
class JwtTokenService {
    
    private static final String ALGORITHM = "HmacSHA256"
    private static final String HEADER = '{"alg":"HS256","typ":"JWT"}'
    
    private final String secret
    private final Duration defaultExpiry
    
    /**
     * Creates JWT service with secret and default expiry.
     * 
     * @param secret signing secret (keep this safe!)
     * @param defaultExpiry token validity duration
     */
    JwtTokenService(String secret, Duration defaultExpiry = Duration.ofHours(1)) {
        if (!secret || secret.length() < 32) {
            throw new IllegalArgumentException("JWT secret must be at least 32 characters")
        }
        this.secret = secret
        this.defaultExpiry = defaultExpiry
    }
    
    /**
     * Generates JWT token for a subject with roles.
     * 
     * @param subject username or service identifier
     * @param roles list of role names
     * @param expiry custom expiry (optional)
     * @return signed JWT token
     */
    String generateToken(String subject, List<String> roles = [], Duration expiry = null) {
        if (!subject) {
            throw new IllegalArgumentException("Subject cannot be null or empty")
        }
        
        def now = Instant.now()
        def expiryTime = now.plus(expiry ?: defaultExpiry)
        
        // Build payload
        def payload = [
            sub: subject,
            roles: roles,
            iat: now.epochSecond,
            exp: expiryTime.epochSecond
        ]
        
        // Encode header and payload
        def encodedHeader = base64UrlEncode(HEADER)
        def encodedPayload = base64UrlEncode(toJson(payload))
        
        // Sign
        def signature = sign("${encodedHeader}.${encodedPayload}")
        
        // Combine
        def token = "${encodedHeader}.${encodedPayload}.${signature}"
        
        log.debug("Generated JWT for subject '${subject}' with roles ${roles}, expires at ${expiryTime}")
        
        return token
    }
    
    /**
     * Validates JWT token and extracts claims.
     * 
     * @param token JWT token string
     * @return token claims
     * @throws InvalidTokenException if token is invalid or expired
     */
    TokenClaims validateToken(String token) {
        if (!token) {
            throw new InvalidTokenException("Token is null or empty")
        }
        
        // Split token
        def parts = token.split('\\.')
        if (parts.length != 3) {
            throw new InvalidTokenException("Invalid token format (expected 3 parts)")
        }
        
        def encodedHeader = parts[0]
        def encodedPayload = parts[1]
        def providedSignature = parts[2]
        
        // Verify signature
        def expectedSignature = sign("${encodedHeader}.${encodedPayload}")
        if (providedSignature != expectedSignature) {
            throw new InvalidTokenException("Invalid signature")
        }
        
        // Decode payload
        def payloadJson = base64UrlDecode(encodedPayload)
        def payload = parseJson(payloadJson) as Map
        
        // Verify expiry
        def exp = payload.exp as long
        def now = Instant.now().epochSecond
        
        if (now >= exp) {
            throw new ExpiredTokenException("Token expired at ${Instant.ofEpochSecond(exp)}")
        }
        
        // Extract claims
        def subject = payload.sub as String
        def roles = (payload.roles ?: []) as List<String>
        def issuedAt = Instant.ofEpochSecond(payload.iat as long)
        def expiresAt = Instant.ofEpochSecond(exp)
        
        log.debug("Validated JWT for subject '${subject}' with roles ${roles}")
        
        return new TokenClaims(
            subject: subject,
            roles: roles,
            issuedAt: issuedAt,
            expiresAt: expiresAt
        )
    }
    
    /**
     * Checks if token is expired without full validation.
     * 
     * @param token JWT token
     * @return true if expired
     */
    boolean isExpired(String token) {
        try {
            def parts = token.split('\\.')
            if (parts.length != 3) return true
            
            def payloadJson = base64UrlDecode(parts[1])
            def payload = parseJson(payloadJson) as Map
            def exp = payload.exp as long
            def now = Instant.now().epochSecond
            
            return now >= exp
        } catch (Exception e) {
            return true
        }
    }
    
    /**
     * Extracts subject from token without full validation.
     * Useful for logging/debugging only - don't trust this for authorization!
     * 
     * @param token JWT token
     * @return subject or null
     */
    String extractSubject(String token) {
        try {
            def parts = token.split('\\.')
            if (parts.length != 3) return null
            
            def payloadJson = base64UrlDecode(parts[1])
            def payload = parseJson(payloadJson) as Map
            
            return payload.sub as String
        } catch (Exception e) {
            return null
        }
    }
    
    // ═════════════════════════════════════════════════════════════
    // Internal Helpers
    // ═════════════════════════════════════════════════════════════
    
    /**
     * Signs data using HMAC-SHA256.
     */
    private String sign(String data) {
        try {
            def mac = Mac.getInstance(ALGORITHM)
            def keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM)
            mac.init(keySpec)
            
            def signature = mac.doFinal(data.getBytes(StandardCharsets.UTF_8))
            return base64UrlEncode(signature)
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign token", e)
        }
    }
    
    /**
     * Base64URL encodes string.
     */
    private static String base64UrlEncode(String data) {
        return base64UrlEncode(data.getBytes(StandardCharsets.UTF_8))
    }
    
    /**
     * Base64URL encodes bytes.
     */
    private static String base64UrlEncode(byte[] data) {
        return Base64.urlEncoder.withoutPadding()
            .encodeToString(data)
    }
    
    /**
     * Base64URL decodes to string.
     */
    private static String base64UrlDecode(String encoded) {
        def bytes = Base64.urlDecoder.decode(encoded)
        return new String(bytes, StandardCharsets.UTF_8)
    }
    
    /**
     * Converts object to JSON.
     */
    private static String toJson(Object obj) {
        return groovy.json.JsonOutput.toJson(obj)
    }
    
    /**
     * Parses JSON string.
     */
    private static Object parseJson(String json) {
        return new groovy.json.JsonSlurper().parseText(json)
    }
    
    // ═════════════════════════════════════════════════════════════
    // Token Claims
    // ═════════════════════════════════════════════════════════════
    
    /**
     * JWT token claims.
     */
    static class TokenClaims {
        String subject
        List<String> roles
        Instant issuedAt
        Instant expiresAt
        
        boolean hasRole(String role) {
            return roles?.contains(role) ?: false
        }
        
        boolean hasAnyRole(List<String> requiredRoles) {
            return requiredRoles?.any { hasRole(it) } ?: false
        }
        
        boolean hasAllRoles(List<String> requiredRoles) {
            return requiredRoles?.every { hasRole(it) } ?: false
        }
        
        @Override
        String toString() {
            return "TokenClaims[subject=${subject}, roles=${roles}, expires=${expiresAt}]"
        }
    }
    
    // ═════════════════════════════════════════════════════════════
    // Exceptions
    // ═════════════════════════════════════════════════════════════
    
    static class InvalidTokenException extends RuntimeException {
        InvalidTokenException(String message) {
            super(message)
        }
    }
    
    static class ExpiredTokenException extends InvalidTokenException {
        ExpiredTokenException(String message) {
            super(message)
        }
    }
}
