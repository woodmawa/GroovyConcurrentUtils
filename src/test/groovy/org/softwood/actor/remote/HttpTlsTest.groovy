package org.softwood.actor.remote.http

import groovy.transform.CompileDynamic
import org.junit.jupiter.api.*
import org.softwood.actor.ActorSystem

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

import static org.junit.jupiter.api.Assertions.*

/**
 * Integration tests for HTTP/HTTPS transport with TLS support.
 */
@CompileDynamic
class HttpTlsTest {
    
    private static final AtomicInteger portCounter = new AtomicInteger(8500)
    
    private ActorSystem serverSystem
    private HttpTransport serverTransport
    private HttpTransport clientTransport
    private int testPort
    
    @BeforeEach
    void setup() {
        testPort = portCounter.getAndIncrement()
        serverSystem = new ActorSystem('testSystem')
    }
    
    @AfterEach
    void cleanup() {
        clientTransport?.close()
        serverTransport?.close()
        serverSystem?.shutdown()
    }
    
    @Test
    void test_https_end_to_end_communication() {
        // Setup: Create echo actor
        serverSystem.actor {
            name 'echo'
            onMessage { msg, ctx ->
                ctx.reply("ECHO: ${msg}")
            }
        }
        
        // Server HTTPS config
        def serverTlsConfig = new HttpTransport.TlsConfig(
            enabled: true,
            keyStorePath: 'C:/Users/willw/IdeaProjects/GroovyConcurrentUtils/scripts/certs/server-keystore.jks',
            keyStorePassword: 'changeit',
            trustStorePath: 'C:/Users/willw/IdeaProjects/GroovyConcurrentUtils/scripts/certs/truststore.jks',
            trustStorePassword: 'changeit'
        )
        
        serverTransport = new HttpTransport(serverSystem, testPort, true, serverTlsConfig)
        serverTransport.start()
        
        Thread.sleep(500) // Let server start
        
        // Client HTTPS config (same for simplicity)
        def clientTlsConfig = new HttpTransport.TlsConfig(
            enabled: true,
            trustStorePath: 'C:/Users/willw/IdeaProjects/GroovyConcurrentUtils/scripts/certs/truststore.jks',
            trustStorePassword: 'changeit'
        )
        
        clientTransport = new HttpTransport(null, 0, false, clientTlsConfig)
        clientTransport.start()
        
        // Test: Send encrypted message over HTTPS
        def future = clientTransport.ask(
            "https://localhost:${testPort}/testSystem/echo",
            "Hello HTTPS",
            Duration.ofSeconds(5)
        )
        
        def result = future.get()
        
        // Verify
        assertEquals("ECHO: Hello HTTPS", result)
    }
    
    @Test
    void test_https_fire_and_forget() {
        // Setup: Create counter actor
        def counter = new AtomicInteger(0)
        
        serverSystem.actor {
            name 'counter'
            onMessage { msg, ctx ->
                counter.incrementAndGet()
            }
        }
        
        // Server with HTTPS
        def serverTlsConfig = new HttpTransport.TlsConfig(
            enabled: true,
            keyStorePath: 'C:/Users/willw/IdeaProjects/GroovyConcurrentUtils/scripts/certs/server-keystore.jks',
            keyStorePassword: 'changeit',
            trustStorePath: 'C:/Users/willw/IdeaProjects/GroovyConcurrentUtils/scripts/certs/truststore.jks',
            trustStorePassword: 'changeit'
        )
        
        serverTransport = new HttpTransport(serverSystem, testPort, true, serverTlsConfig)
        serverTransport.start()
        
        Thread.sleep(500)
        
        // Client with HTTPS
        def clientTlsConfig = new HttpTransport.TlsConfig(
            enabled: true,
            trustStorePath: 'C:/Users/willw/IdeaProjects/GroovyConcurrentUtils/scripts/certs/truststore.jks',
            trustStorePassword: 'changeit'
        )
        
        clientTransport = new HttpTransport(null, 0, false, clientTlsConfig)
        clientTransport.start()
        
        // Test: Fire and forget over HTTPS
        clientTransport.tell(
            "https://localhost:${testPort}/testSystem/counter",
            "increment"
        )
        
        Thread.sleep(1000) // Wait for message delivery
        
        // Verify
        assertEquals(1, counter.get())
    }
    
    @Test
    void test_https_scheme_detection() {
        // Test that HTTPS scheme is properly recognized
        serverSystem.actor {
            name 'test'
            onMessage { msg, ctx -> ctx.reply("ok") }
        }
        
        def tlsConfig = new HttpTransport.TlsConfig(
            enabled: true,
            keyStorePath: 'C:/Users/willw/IdeaProjects/GroovyConcurrentUtils/scripts/certs/server-keystore.jks',
            keyStorePassword: 'changeit'
        )
        
        // Should start HTTPS server
        serverTransport = new HttpTransport(serverSystem, testPort, true, tlsConfig)
        serverTransport.start()
        
        // If we get here without exception, HTTPS started successfully
        assertNotNull(serverTransport)
    }
}
