package org.softwood.security

import spock.lang.Specification
import java.nio.file.Files
import java.nio.file.Paths

class CertificateResolverTest extends Specification {
    
    CertificateResolver resolver
    File tempKeystore
    
    def setup() {
        resolver = new CertificateResolver(null, false)
        
        // Create a temporary dummy keystore file for testing
        tempKeystore = File.createTempFile("test-keystore-", ".jks")
        tempKeystore.deleteOnExit()
        tempKeystore.text = "dummy keystore content"
    }
    
    def cleanup() {
        tempKeystore?.delete()
    }
    
    def "should resolve explicit path"() {
        when:
        def resolved = resolver.resolve(tempKeystore.absolutePath, null, null, null)
        
        then:
        resolved == tempKeystore.absolutePath
    }
    
    def "should resolve from system property"() {
        given:
        def key = "test.cert.path.${UUID.randomUUID()}"
        System.setProperty(key, tempKeystore.absolutePath)
        
        when:
        def resolved = resolver.resolve(null, key, null, null)
        
        then:
        resolved == tempKeystore.absolutePath
        
        cleanup:
        System.clearProperty(key)
    }
    
    def "should resolve from environment variable"() {
        given:
        def key = "TEST_CERT_PATH"
        // Note: Can't easily set env vars in tests, so we'll test the logic
        // by using system property as fallback
        System.setProperty(key, tempKeystore.absolutePath)
        
        when:
        def resolved = resolver.resolve(null, key, null, null)
        
        then:
        resolved == tempKeystore.absolutePath
        
        cleanup:
        System.clearProperty(key)
    }
    
    def "should reject path with directory traversal"() {
        when:
        def resolved = resolver.resolve("../../../etc/passwd", null, null, null)
        
        then:
        resolved == null
    }
    
    def "should reject path starting with tilde"() {
        when:
        def resolved = resolver.resolve("~/secrets/keystore.jks", null, null, null)
        
        then:
        resolved == null
    }
    
    def "should reject path with null bytes"() {
        when:
        def resolved = resolver.resolve("keystore\u0000.jks", null, null, null)
        
        then:
        resolved == null
    }
    
    def "should validate existing path"() {
        expect:
        resolver.validatePath(tempKeystore.absolutePath)
        !resolver.validatePath("/non/existent/path")
        !resolver.validatePath(null)
    }
    
    def "should open stream for valid path"() {
        when:
        def stream = resolver.openStream(tempKeystore.absolutePath)
        
        then:
        stream != null
        
        cleanup:
        stream?.close()
    }
    
    def "should return null stream for invalid path"() {
        when:
        def stream = resolver.openStream("/non/existent/path")
        
        then:
        stream == null
    }
    
    def "should return null for non-existent certificate"() {
        when:
        def resolved = resolver.resolve(null, null, null, "/non/existent/resource")
        
        then:
        resolved == null
    }
    
    def "should handle config object with nested properties"() {
        given:
        // Use forward slashes for cross-platform compatibility
        def pathWithForwardSlashes = tempKeystore.absolutePath.replace('\\', '/')
        def config = new ConfigSlurper().parse("""
            actor {
                tls {
                    keystore {
                        path = '${pathWithForwardSlashes}'
                    }
                }
            }
        """)
        def resolver = new CertificateResolver(config, false)
        
        when:
        def resolved = resolver.resolve(null, "actor.tls.keystore.path", null, null)
        
        then:
        resolved == tempKeystore.absolutePath
    }
}
