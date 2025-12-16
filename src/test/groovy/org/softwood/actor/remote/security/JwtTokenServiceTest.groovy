package org.softwood.actor.remote.security

import groovy.transform.CompileDynamic
import org.junit.jupiter.api.*
import java.time.Duration
import java.time.Instant

import static org.junit.jupiter.api.Assertions.*

/**
 * Tests for JwtTokenService.
 */
@CompileDynamic
class JwtTokenServiceTest {
    
    private static final String TEST_SECRET = "this-is-a-very-secret-key-with-at-least-32-characters!"
    private JwtTokenService service
    
    @BeforeEach
    void setup() {
        service = new JwtTokenService(TEST_SECRET, Duration.ofHours(1))
    }
    
    @Test
    void test_generate_token_basic() {
        // Generate token
        def token = service.generateToken('john.doe', ['USER'])
        
        // Verify format (3 parts separated by dots)
        assertNotNull(token)
        def parts = token.split('\\.')
        assertEquals(3, parts.length, "Token should have 3 parts")
    }
    
    @Test
    void test_generate_and_validate_token() {
        // Generate token
        def token = service.generateToken('john.doe', ['USER', 'MANAGER'])
        
        // Validate token
        def claims = service.validateToken(token)
        
        // Verify claims
        assertEquals('john.doe', claims.subject)
        assertEquals(['USER', 'MANAGER'], claims.roles)
        assertNotNull(claims.issuedAt)
        assertNotNull(claims.expiresAt)
        assertTrue(claims.expiresAt.isAfter(claims.issuedAt))
    }
    
    @Test
    void test_validate_token_without_roles() {
        // Generate token without roles
        def token = service.generateToken('service-account')
        
        // Validate
        def claims = service.validateToken(token)
        
        assertEquals('service-account', claims.subject)
        assertEquals([], claims.roles)
    }
    
    @Test
    void test_invalid_token_format() {
        assertThrows(JwtTokenService.InvalidTokenException) {
            service.validateToken("invalid.token")
        }
    }
    
    @Test
    void test_null_token() {
        assertThrows(JwtTokenService.InvalidTokenException) {
            service.validateToken(null)
        }
    }
    
    @Test
    void test_empty_token() {
        assertThrows(JwtTokenService.InvalidTokenException) {
            service.validateToken("")
        }
    }
    
    @Test
    void test_token_with_invalid_signature() {
        // Generate valid token
        def token = service.generateToken('john.doe', ['USER'])
        
        // Tamper with signature
        def parts = token.split('\\.')
        def tamperedToken = "${parts[0]}.${parts[1]}.INVALID_SIGNATURE"
        
        // Should fail validation
        assertThrows(JwtTokenService.InvalidTokenException) {
            service.validateToken(tamperedToken)
        }
    }
    
    @Test
    void test_token_with_tampered_payload() {
        // Generate valid token
        def token = service.generateToken('john.doe', ['USER'])
        
        // Tamper with payload (change role to ADMIN)
        def parts = token.split('\\.')
        def tamperedPayload = Base64.urlEncoder.withoutPadding()
            .encodeToString('{"sub":"john.doe","roles":["ADMIN"]}'.getBytes())
        def tamperedToken = "${parts[0]}.${tamperedPayload}.${parts[2]}"
        
        // Should fail validation (signature won't match)
        assertThrows(JwtTokenService.InvalidTokenException) {
            service.validateToken(tamperedToken)
        }
    }
    
    @Test
    void test_expired_token() {
        // Generate token with very short expiry
        def shortLivedService = new JwtTokenService(TEST_SECRET, Duration.ofMillis(100))
        def token = shortLivedService.generateToken('john.doe', ['USER'])
        
        // Wait for expiry
        Thread.sleep(200)
        
        // Should throw expired exception
        assertThrows(JwtTokenService.ExpiredTokenException) {
            shortLivedService.validateToken(token)
        }
    }
    
    @Test
    void test_custom_expiry() {
        // Generate token with custom expiry
        def token = service.generateToken('john.doe', ['USER'], Duration.ofMinutes(30))
        
        // Validate
        def claims = service.validateToken(token)
        
        // Check expiry is ~30 minutes from now
        def expectedExpiry = Instant.now().plus(Duration.ofMinutes(30))
        def actualExpiry = claims.expiresAt
        
        // Allow 5 second tolerance
        def diff = Duration.between(expectedExpiry, actualExpiry).abs()
        assertTrue(diff.toSeconds() < 5, "Expiry should be ~30 minutes")
    }
    
    @Test
    void test_isExpired() {
        // Generate token
        def token = service.generateToken('john.doe', ['USER'])
        
        // Should not be expired
        assertFalse(service.isExpired(token))
        
        // Generate expired token
        def shortService = new JwtTokenService(TEST_SECRET, Duration.ofMillis(10))
        def expiredToken = shortService.generateToken('jane.doe', ['USER'])
        Thread.sleep(50)
        
        // Should be expired
        assertTrue(service.isExpired(expiredToken))
    }
    
    @Test
    void test_extractSubject() {
        // Generate token
        def token = service.generateToken('john.doe', ['USER'])
        
        // Extract subject without validation
        def subject = service.extractSubject(token)
        
        assertEquals('john.doe', subject)
    }
    
    @Test
    void test_extractSubject_invalid_token() {
        def subject = service.extractSubject("invalid.token")
        assertNull(subject)
    }
    
    @Test
    void test_hasRole() {
        def token = service.generateToken('john.doe', ['USER', 'MANAGER'])
        def claims = service.validateToken(token)
        
        assertTrue(claims.hasRole('USER'))
        assertTrue(claims.hasRole('MANAGER'))
        assertFalse(claims.hasRole('ADMIN'))
    }
    
    @Test
    void test_hasAnyRole() {
        def token = service.generateToken('john.doe', ['USER', 'MANAGER'])
        def claims = service.validateToken(token)
        
        assertTrue(claims.hasAnyRole(['USER']))
        assertTrue(claims.hasAnyRole(['ADMIN', 'USER']))
        assertTrue(claims.hasAnyRole(['MANAGER', 'ANALYST']))
        assertFalse(claims.hasAnyRole(['ADMIN', 'ANALYST']))
    }
    
    @Test
    void test_hasAllRoles() {
        def token = service.generateToken('john.doe', ['USER', 'MANAGER', 'ANALYST'])
        def claims = service.validateToken(token)
        
        assertTrue(claims.hasAllRoles(['USER']))
        assertTrue(claims.hasAllRoles(['USER', 'MANAGER']))
        assertTrue(claims.hasAllRoles(['USER', 'MANAGER', 'ANALYST']))
        assertFalse(claims.hasAllRoles(['USER', 'ADMIN']))
    }
    
    @Test
    void test_multiple_tokens_different_subjects() {
        def token1 = service.generateToken('alice', ['USER'])
        def token2 = service.generateToken('bob', ['ADMIN'])
        
        def claims1 = service.validateToken(token1)
        def claims2 = service.validateToken(token2)
        
        assertEquals('alice', claims1.subject)
        assertEquals('bob', claims2.subject)
        assertEquals(['USER'], claims1.roles)
        assertEquals(['ADMIN'], claims2.roles)
    }
    
    @Test
    void test_secret_too_short() {
        assertThrows(IllegalArgumentException) {
            new JwtTokenService("short-secret", Duration.ofHours(1))
        }
    }
    
    @Test
    void test_null_secret() {
        assertThrows(IllegalArgumentException) {
            new JwtTokenService(null, Duration.ofHours(1))
        }
    }
    
    @Test
    void test_null_subject() {
        assertThrows(IllegalArgumentException) {
            service.generateToken(null, ['USER'])
        }
    }
    
    @Test
    void test_empty_subject() {
        assertThrows(IllegalArgumentException) {
            service.generateToken("", ['USER'])
        }
    }
    
    @Test
    void test_token_claims_toString() {
        def token = service.generateToken('john.doe', ['USER', 'MANAGER'])
        def claims = service.validateToken(token)
        
        def str = claims.toString()
        
        assertTrue(str.contains('john.doe'))
        assertTrue(str.contains('USER'))
        assertTrue(str.contains('MANAGER'))
    }
    
    @Test
    void test_different_secrets_produce_different_tokens() {
        def service1 = new JwtTokenService("secret-key-number-one-with-32-chars!", Duration.ofHours(1))
        def service2 = new JwtTokenService("secret-key-number-two-with-32-chars!", Duration.ofHours(1))
        
        def token1 = service1.generateToken('john.doe', ['USER'])
        def token2 = service2.generateToken('john.doe', ['USER'])
        
        // Tokens should be different
        assertNotEquals(token1, token2)
        
        // Service1 can't validate service2's token
        assertThrows(JwtTokenService.InvalidTokenException) {
            service1.validateToken(token2)
        }
    }
    
    @Test
    void test_token_with_special_characters_in_subject() {
        def token = service.generateToken('user@example.com', ['USER'])
        def claims = service.validateToken(token)
        
        assertEquals('user@example.com', claims.subject)
    }
    
    @Test
    void test_token_with_unicode_subject() {
        def token = service.generateToken('用户', ['USER'])
        def claims = service.validateToken(token)
        
        assertEquals('用户', claims.subject)
    }
}
