package org.softwood.security

import spock.lang.Specification
import java.time.Duration

class JwtTokenServiceTest extends Specification {
    
    JwtTokenService service
    
    def setup() {
        service = new JwtTokenService("this-is-a-very-long-secret-key-for-jwt-signing-purposes", Duration.ofMinutes(5))
    }
    
    def "should generate valid JWT token"() {
        when:
        def token = service.generateToken("testUser", ["USER", "ADMIN"])
        
        then:
        token != null
        token.split('\\.').length == 3 // header.payload.signature
    }
    
    def "should validate and extract claims from token"() {
        given:
        def token = service.generateToken("john.doe", ["USER", "MANAGER"])
        
        when:
        def claims = service.validateToken(token)
        
        then:
        claims.subject == "john.doe"
        claims.roles == ["USER", "MANAGER"]
        claims.issuedAt != null
        claims.expiresAt != null
    }
    
    def "should reject token with invalid signature"() {
        given:
        def token = service.generateToken("user", ["USER"])
        def parts = token.split('\\.')
        def tamperedToken = "${parts[0]}.${parts[1]}.invalidsignature"
        
        when:
        service.validateToken(tamperedToken)
        
        then:
        thrown(JwtTokenService.InvalidTokenException)
    }
    
    def "should reject expired token"() {
        given:
        def shortLivedService = new JwtTokenService("this-is-a-very-long-secret-key-for-jwt-signing", Duration.ofMillis(1))
        def token = shortLivedService.generateToken("user", ["USER"])
        
        when:
        Thread.sleep(10) // Wait for token to expire
        shortLivedService.validateToken(token)
        
        then:
        thrown(JwtTokenService.ExpiredTokenException)
    }
    
    def "should detect expired token without full validation"() {
        given:
        def shortLivedService = new JwtTokenService("this-is-a-very-long-secret-key-for-jwt-signing", Duration.ofMillis(1))
        def token = shortLivedService.generateToken("user", ["USER"])
        
        when:
        Thread.sleep(10)
        
        then:
        shortLivedService.isExpired(token)
    }
    
    def "should extract subject without full validation"() {
        given:
        def token = service.generateToken("alice", ["USER"])
        
        when:
        def subject = service.extractSubject(token)
        
        then:
        subject == "alice"
    }
    
    def "should check token claims for roles"() {
        given:
        def token = service.generateToken("user", ["USER", "ADMIN", "MANAGER"])
        def claims = service.validateToken(token)
        
        expect:
        claims.hasRole("USER")
        claims.hasRole("ADMIN")
        !claims.hasRole("SUPERUSER")
        claims.hasAnyRole(["ADMIN", "SUPERUSER"])
        claims.hasAllRoles(["USER", "ADMIN"])
        !claims.hasAllRoles(["USER", "SUPERUSER"])
    }
    
    def "should require minimum secret length"() {
        when:
        new JwtTokenService("short", Duration.ofMinutes(5))
        
        then:
        thrown(IllegalArgumentException)
    }
    
    def "should reject null or empty subject"() {
        when:
        service.generateToken(null, ["USER"])
        
        then:
        thrown(IllegalArgumentException)
    }
}
