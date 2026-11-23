package org.softwood.security

import spock.lang.Specification

class SecretsResolverTest extends Specification {
    
    def setup() {
        // Reset to default before each test
        SecretsResolver.resetToDefault()
    }
    
    def "should resolve from environment variable"() {
        given:
        def key = "TEST_SECRET_${UUID.randomUUID()}"
        System.setProperty(key, "test-value")
        
        when:
        def value = SecretsResolver.resolve(key)
        
        then:
        value == "test-value"
        
        cleanup:
        System.clearProperty(key)
    }
    
    def "should return null for non-existent secret"() {
        when:
        def value = SecretsResolver.resolve("NON_EXISTENT_SECRET_${UUID.randomUUID()}")
        
        then:
        value == null
    }
    
    def "should resolve with default value"() {
        when:
        def value = SecretsResolver.resolve("NON_EXISTENT_SECRET", "default-value")
        
        then:
        value == "default-value"
    }
    
    def "should throw exception for required secret not found"() {
        when:
        SecretsResolver.resolveRequired("NON_EXISTENT_SECRET_${UUID.randomUUID()}")
        
        then:
        thrown(IllegalStateException)
    }
    
    def "should check if secret exists"() {
        given:
        def key = "TEST_EXIST_${UUID.randomUUID()}"
        System.setProperty(key, "value")
        
        expect:
        SecretsResolver.exists(key)
        !SecretsResolver.exists("NON_EXISTENT_${UUID.randomUUID()}")
        
        cleanup:
        System.clearProperty(key)
    }
    
    def "should use custom secret manager"() {
        given:
        def customManager = new SecretManager() {
            String getSecret(String key) { return "custom-${key}" }
            String getRequiredSecret(String key) { return "custom-${key}" }
            boolean secretExists(String key) { return true }
            String getManagerName() { return "Custom" }
            boolean isInitialized() { return true }
        }
        
        when:
        SecretsResolver.setSecretManager(customManager)
        def value = SecretsResolver.resolve("test")
        
        then:
        value == "custom-test"
        SecretsResolver.getSecretManager().getManagerName() == "Custom"
        
        cleanup:
        SecretsResolver.resetToDefault()
    }
    
    def "should mask secret values"() {
        when:
        def masked = SecretsResolver.maskValue("super-secret-password")
        
        then:
        masked == "<redacted>"
    }
}
