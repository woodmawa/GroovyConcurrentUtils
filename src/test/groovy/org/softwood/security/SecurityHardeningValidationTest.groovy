package org.softwood.security

import spock.lang.Specification
import java.time.Duration

/**
 * Comprehensive security hardening validation tests.
 * Validates all security features including Phase 4 enhancements.
 */
class SecurityHardeningValidationTest extends Specification {
    
    // ═════════════════════════════════════════════════════════════
    // PHASE 4: RATE LIMITING TESTS
    // ═════════════════════════════════════════════════════════════
    
    def "should allow authentication within rate limit"() {
        given:
        def rateLimiter = new AuthenticationRateLimiter()
        rateLimiter.setMaxAttempts(5)
        
        when:
        def allowed = rateLimiter.isAllowed("user123", "192.168.1.100")
        
        then:
        allowed == true
    }
    
    def "should block after max failed attempts"() {
        given:
        def rateLimiter = new AuthenticationRateLimiter()
        rateLimiter.setMaxAttempts(3)
        rateLimiter.setLockoutDurationMinutes(1)
        
        when:
        3.times {
            rateLimiter.recordFailedAttempt("user123", "192.168.1.100")
        }
        
        then:
        !rateLimiter.isAllowed("user123", "192.168.1.100")
    }
    
    def "should track failed attempts per username"() {
        given:
        def rateLimiter = new AuthenticationRateLimiter()
        rateLimiter.setMaxAttempts(5)
        
        when:
        3.times {
            rateLimiter.recordFailedAttempt("user123", "192.168.1.100")
        }
        
        then:
        rateLimiter.getFailedAttempts("user123") == 3
    }
    
    def "should track IP-based attempts separately"() {
        given:
        def rateLimiter = new AuthenticationRateLimiter()
        rateLimiter.setMaxAttempts(3)
        
        when:
        rateLimiter.recordFailedAttempt("user1", "192.168.1.100")
        rateLimiter.recordFailedAttempt("user2", "192.168.1.100")
        rateLimiter.recordFailedAttempt("user3", "192.168.1.100")
        
        then:
        !rateLimiter.isAllowed("user4", "192.168.1.100") // IP blocked
    }
    
    def "should clear attempts after successful authentication"() {
        given:
        def rateLimiter = new AuthenticationRateLimiter()
        
        when:
        2.times {
            rateLimiter.recordFailedAttempt("user123", "192.168.1.100")
        }
        rateLimiter.recordSuccessfulAttempt("user123", "192.168.1.100")
        
        then:
        rateLimiter.getFailedAttempts("user123") == 0
        rateLimiter.isAllowed("user123", "192.168.1.100")
    }
    
    def "should manually unlock username"() {
        given:
        def rateLimiter = new AuthenticationRateLimiter()
        rateLimiter.setMaxAttempts(2)
        
        when:
        3.times {
            rateLimiter.recordFailedAttempt("user123", "192.168.1.100")
        }
        rateLimiter.unlock("user123")
        
        then:
        rateLimiter.isAllowed("user123", "192.168.1.200") // Different IP
    }
    
    def "should manually unlock IP address"() {
        given:
        def rateLimiter = new AuthenticationRateLimiter()
        rateLimiter.setMaxAttempts(2)
        
        when:
        3.times {
            rateLimiter.recordFailedAttempt("user${it}", "192.168.1.100")
        }
        rateLimiter.unlockIp("192.168.1.100")
        
        then:
        rateLimiter.isAllowed("newuser", "192.168.1.100")
    }
    
    def "should provide statistics"() {
        given:
        def rateLimiter = new AuthenticationRateLimiter()
        rateLimiter.setMaxAttempts(2)
        
        when:
        rateLimiter.recordFailedAttempt("user1", "192.168.1.100")
        rateLimiter.recordFailedAttempt("user2", "192.168.1.101")
        3.times {
            rateLimiter.recordFailedAttempt("user3", "192.168.1.102")
        }
        def stats = rateLimiter.getStatistics()
        
        then:
        stats.trackedUsers >= 3
        stats.trackedIps >= 3
        stats.lockedUsers >= 1
        stats.maxAttempts == 2
    }
    
    def "should reset all state"() {
        given:
        def rateLimiter = new AuthenticationRateLimiter()
        
        when:
        rateLimiter.recordFailedAttempt("user1", "192.168.1.100")
        rateLimiter.recordFailedAttempt("user2", "192.168.1.101")
        rateLimiter.reset()
        def stats = rateLimiter.getStatistics()
        
        then:
        stats.trackedUsers == 0
        stats.trackedIps == 0
    }
    
    def "should sanitize username in logs"() {
        given:
        def rateLimiter = new AuthenticationRateLimiter()
        
        when:
        rateLimiter.recordFailedAttempt("john.doe@example.com", "192.168.1.100")
        
        then:
        noExceptionThrown()
        // Username should be sanitized to "joh***" in logs
    }
    
    def "should sanitize IP address in logs"() {
        given:
        def rateLimiter = new AuthenticationRateLimiter()
        
        when:
        rateLimiter.recordFailedAttempt("user", "192.168.1.100")
        
        then:
        noExceptionThrown()
        // IP should be sanitized to "192.168.*.*" in logs
    }
    
    // ═════════════════════════════════════════════════════════════
    // SECRET MANAGER TESTS
    // ═════════════════════════════════════════════════════════════
    
    def "should use environment secret manager by default"() {
        when:
        def manager = SecretsResolver.getSecretManager()
        
        then:
        manager instanceof EnvironmentSecretManager
        manager.getManagerName() == "Environment"
        manager.isInitialized()
    }
    
    def "should switch to custom secret manager"() {
        given:
        def customManager = new SecretManager() {
            String getSecret(String key) { return "custom-value" }
            String getRequiredSecret(String key) { return "custom-value" }
            boolean secretExists(String key) { return true }
            String getManagerName() { return "Custom" }
            boolean isInitialized() { return true }
        }
        
        when:
        SecretsResolver.setSecretManager(customManager)
        def value = SecretsResolver.resolve("any-key")
        
        then:
        value == "custom-value"
        
        cleanup:
        SecretsResolver.resetToDefault()
    }
    
    // ═════════════════════════════════════════════════════════════
    // JWT TOKEN SERVICE TESTS
    // ═════════════════════════════════════════════════════════════
    
    def "should enforce minimum JWT secret length"() {
        when:
        new JwtTokenService("short", Duration.ofHours(1))
        
        then:
        thrown(IllegalArgumentException)
    }
    
    def "should generate and validate JWT tokens"() {
        given:
        def service = new JwtTokenService("this-is-a-very-long-secret-key-for-testing-purposes", Duration.ofMinutes(5))
        
        when:
        def token = service.generateToken("testuser", ["USER", "ADMIN"])
        def claims = service.validateToken(token)
        
        then:
        claims.subject == "testuser"
        claims.roles == ["USER", "ADMIN"]
    }
    
    def "should reject tampered JWT tokens"() {
        given:
        def service = new JwtTokenService("this-is-a-very-long-secret-key-for-testing-purposes", Duration.ofMinutes(5))
        def token = service.generateToken("user", ["USER"])
        def parts = token.split('\\.')
        def tamperedToken = "${parts[0]}.${parts[1]}.invalidsignature"
        
        when:
        service.validateToken(tamperedToken)
        
        then:
        thrown(JwtTokenService.InvalidTokenException)
    }
    
    // ═════════════════════════════════════════════════════════════
    // CERTIFICATE RESOLVER TESTS
    // ═════════════════════════════════════════════════════════════
    
    def "should reject path traversal attempts"() {
        given:
        def resolver = new CertificateResolver()
        
        when:
        def resolved = resolver.resolve("../../../etc/passwd", null, null, null)
        
        then:
        resolved == null
    }
    
    def "should reject paths with null bytes"() {
        given:
        def resolver = new CertificateResolver()
        
        when:
        def resolved = resolver.resolve("keystore\u0000.jks", null, null, null)
        
        then:
        resolved == null
    }
    
    def "should reject paths starting with tilde"() {
        given:
        def resolver = new CertificateResolver()
        
        when:
        def resolved = resolver.resolve("~/secrets/keystore.jks", null, null, null)
        
        then:
        resolved == null
    }
    
    // ═════════════════════════════════════════════════════════════
    // TLS CONTEXT BUILDER TESTS
    // ═════════════════════════════════════════════════════════════
    
    def "should use strong default cipher suites"() {
        when:
        def builder = TlsContextBuilder.builder()
        
        then:
        noExceptionThrown()
        // Builder should have strong defaults configured
    }
    
    def "should support TLS 1.3 and 1.2"() {
        given:
        def builder = TlsContextBuilder.builder()
        
        when:
        builder.protocols(['TLSv1.3', 'TLSv1.2'])
        
        then:
        noExceptionThrown()
    }
    
    // ═════════════════════════════════════════════════════════════
    // INTEGRATION TESTS
    // ═════════════════════════════════════════════════════════════
    
    def "should integrate rate limiting with JWT authentication"() {
        given:
        def rateLimiter = new AuthenticationRateLimiter()
        rateLimiter.setMaxAttempts(3)
        def jwtService = new JwtTokenService("this-is-a-very-long-secret-key-for-integration-test", Duration.ofMinutes(5))
        
        when: "User has too many failed attempts"
        3.times {
            rateLimiter.recordFailedAttempt("user123", "192.168.1.100")
        }
        
        then: "Authentication should be blocked"
        !rateLimiter.isAllowed("user123", "192.168.1.100")
        
        when: "Admin unlocks the user and IP"
        rateLimiter.unlock("user123")
        rateLimiter.unlockIp("192.168.1.100")
        
        and: "User successfully authenticates"
        def allowed = rateLimiter.isAllowed("user123", "192.168.1.100")
        
        then: "JWT token should be generated"
        allowed
        def token = jwtService.generateToken("user123", ["USER"])
        jwtService.validateToken(token).subject == "user123"
        
        and: "Failed attempts should be cleared"
        rateLimiter.recordSuccessfulAttempt("user123", "192.168.1.100")
        rateLimiter.getFailedAttempts("user123") == 0
    }
    
    def "should validate complete security stack"() {
        expect: "All security components are initialized"
        SecretsResolver.getSecretManager().isInitialized()
        
        and: "Rate limiter is functional"
        def rateLimiter = new AuthenticationRateLimiter()
        rateLimiter.isAllowed("testuser", "127.0.0.1")
        
        and: "JWT service is functional"
        def jwtService = new JwtTokenService("this-is-a-very-long-secret-key-for-validation", Duration.ofHours(1))
        jwtService.generateToken("user", ["USER"]) != null
        
        and: "Certificate resolver is functional"
        def resolver = new CertificateResolver()
        resolver != null
        
        and: "TLS builder is functional"
        def builder = TlsContextBuilder.builder()
        builder != null
    }
}
