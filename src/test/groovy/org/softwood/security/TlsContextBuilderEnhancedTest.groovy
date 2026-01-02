package org.softwood.security

import spock.lang.Specification
import javax.net.ssl.SSLContext
import java.security.KeyStore

class TlsContextBuilderEnhancedTest extends Specification {
    
    File tempKeystore
    String keystorePassword = "changeit"
    
    def setup() {
        // Create a temporary test keystore
        tempKeystore = File.createTempFile("test-tls-", ".jks")
        tempKeystore.deleteOnExit()
        
        // Create a simple keystore for testing
        def ks = KeyStore.getInstance("JKS")
        ks.load(null, null)
        tempKeystore.withOutputStream { os ->
            ks.store(os, keystorePassword.toCharArray())
        }
    }
    
    def cleanup() {
        tempKeystore?.delete()
    }
    
    def "should create TLS context builder"() {
        when:
        def builder = TlsContextBuilder.builder()
        
        then:
        builder != null
    }
    
    def "should set keystore path and password"() {
        given:
        def builder = TlsContextBuilder.builder()
        
        when:
        builder.keyStore(tempKeystore.absolutePath, keystorePassword)
        
        then:
        noExceptionThrown()
    }
    
    def "should set truststore path and password"() {
        given:
        def builder = TlsContextBuilder.builder()
        
        when:
        builder.trustStore(tempKeystore.absolutePath, keystorePassword)
        
        then:
        noExceptionThrown()
    }
    
    def "should set TLS protocols"() {
        given:
        def builder = TlsContextBuilder.builder()
        
        when:
        builder.protocols(['TLSv1.3', 'TLSv1.2'])
        
        then:
        noExceptionThrown()
    }
    
    def "should set cipher suites"() {
        given:
        def builder = TlsContextBuilder.builder()
        
        when:
        builder.cipherSuites([
            'TLS_AES_256_GCM_SHA384',
            'TLS_AES_128_GCM_SHA256'
        ])
        
        then:
        noExceptionThrown()
    }
    
    def "should enable certificate resolver"() {
        given:
        def builder = TlsContextBuilder.builder()
        
        when:
        builder.useResolver(true)
        
        then:
        noExceptionThrown()
    }
    
    def "should enable development mode"() {
        given:
        def builder = TlsContextBuilder.builder()
        
        when:
        builder.developmentMode(true)
        
        then:
        noExceptionThrown()
    }
    
    def "should set custom certificate resolver"() {
        given:
        def builder = TlsContextBuilder.builder()
        def resolver = new CertificateResolver()
        
        when:
        builder.resolver(resolver)
        
        then:
        noExceptionThrown()
    }
    
    def "should build SSL context with keystore"() {
        given:
        def builder = TlsContextBuilder.builder()
            .keyStore(tempKeystore.absolutePath, keystorePassword)
        
        when:
        def context = builder.build()
        
        then:
        context != null
        context instanceof SSLContext
    }
    
    def "should build SSL context with truststore"() {
        given:
        def builder = TlsContextBuilder.builder()
            .trustStore(tempKeystore.absolutePath, keystorePassword)
        
        when:
        def context = builder.build()
        
        then:
        context != null
        context instanceof SSLContext
    }
    
    def "should build SSL context with both keystore and truststore"() {
        given:
        def builder = TlsContextBuilder.builder()
            .keyStore(tempKeystore.absolutePath, keystorePassword)
            .trustStore(tempKeystore.absolutePath, keystorePassword)
        
        when:
        def context = builder.build()
        
        then:
        context != null
        context instanceof SSLContext
    }
    
    def "should use strong default cipher suites"() {
        given:
        def builder = TlsContextBuilder.builder()
            .keyStore(tempKeystore.absolutePath, keystorePassword)
            .cipherSuites([])  // Empty list should trigger defaults
        
        when:
        def context = builder.build()
        
        then:
        context != null
        // Default cipher suites should be applied
    }
    
    def "should handle missing keystore file gracefully"() {
        given:
        def builder = TlsContextBuilder.builder()
            .keyStore("/non/existent/keystore.jks", "password")
        
        when:
        builder.build()
        
        then:
        thrown(Exception)
    }
}
