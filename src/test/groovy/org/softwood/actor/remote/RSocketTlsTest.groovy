package org.softwood.actor.remote.rsocket

import groovy.transform.CompileDynamic
import org.junit.jupiter.api.*
import org.softwood.actor.Actor
import org.softwood.actor.ActorSystem

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

import static org.junit.jupiter.api.Assertions.*

/**
 * Integration tests for RSocket TLS/SSL support.
 */
@CompileDynamic
class RSocketTlsTest {
    
    private static final AtomicInteger portCounter = new AtomicInteger(7500)
    
    private ActorSystem serverSystem
    private RSocketTransport serverTransport
    private RSocketTransport clientTransport
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
    void test_tls_end_to_end_communication() {
        // Setup: Create echo actor
        serverSystem.actor {
            name 'echo'
            onMessage { msg, ctx ->
                ctx.reply("ECHO: ${msg}")
            }
        }
        
        // Server TLS config
        def serverTlsConfig = new RSocketTransport.TlsConfig(
            enabled: true,
            keyStorePath: 'C:/Users/willw/IdeaProjects/GroovyConcurrentUtils/scripts/certs/server-keystore.jks',
            keyStorePassword: 'changeit',
            trustStorePath: 'C:/Users/willw/IdeaProjects/GroovyConcurrentUtils/scripts/certs/truststore.jks',
            trustStorePassword: 'changeit'
        )
        
        serverTransport = new RSocketTransport(serverSystem, testPort, true, serverTlsConfig)
        serverTransport.start()
        
        Thread.sleep(500) // Let server start
        
        // Client TLS config
        def clientTlsConfig = new RSocketTransport.TlsConfig(
            enabled: true,
            trustStorePath: 'C:/Users/willw/IdeaProjects/GroovyConcurrentUtils/scripts/certs/truststore.jks',
            trustStorePassword: 'changeit'
        )
        
        clientTransport = new RSocketTransport(null, 0, false, clientTlsConfig)
        clientTransport.start()
        
        // Test: Send encrypted message
        def future = clientTransport.ask(
            "rsocket://localhost:${testPort}/testSystem/echo",
            "Hello TLS",
            Duration.ofSeconds(5)
        )
        
        def result = future.get()
        
        // Verify
        assertEquals("ECHO: Hello TLS", result)
    }
    
    @Test
    void test_tls_fire_and_forget() {
        // Setup: Create counter actor
        def counter = new AtomicInteger(0)
        
        serverSystem.actor {
            name 'counter'
            onMessage { msg, ctx ->
                counter.incrementAndGet()
            }
        }
        
        // Server with TLS
        def serverTlsConfig = new RSocketTransport.TlsConfig(
            enabled: true,
            keyStorePath: 'C:/Users/willw/IdeaProjects/GroovyConcurrentUtils/scripts/certs/server-keystore.jks',
            keyStorePassword: 'changeit',
            trustStorePath: 'C:/Users/willw/IdeaProjects/GroovyConcurrentUtils/scripts/certs/truststore.jks',
            trustStorePassword: 'changeit'
        )
        
        serverTransport = new RSocketTransport(serverSystem, testPort, true, serverTlsConfig)
        serverTransport.start()
        
        Thread.sleep(500)
        
        // Client with TLS
        def clientTlsConfig = new RSocketTransport.TlsConfig(
            enabled: true,
            trustStorePath: 'C:/Users/willw/IdeaProjects/GroovyConcurrentUtils/scripts/certs/truststore.jks',
            trustStorePassword: 'changeit'
        )
        
        clientTransport = new RSocketTransport(null, 0, false, clientTlsConfig)
        clientTransport.start()
        
        // Test: Fire and forget over TLS
        clientTransport.tell(
            "rsocket://localhost:${testPort}/testSystem/counter",
            "increment"
        )
        
        Thread.sleep(1000) // Wait for message delivery
        
        // Verify
        assertEquals(1, counter.get())
    }
    
    @Test
    void test_connection_fails_without_tls_on_client() {
        // Setup: TLS-enabled server
        serverSystem.actor {
            name 'echo'
            onMessage { msg, ctx -> ctx.reply("ECHO: ${msg}") }
        }
        
        def serverTlsConfig = new RSocketTransport.TlsConfig(
            enabled: true,
            keyStorePath: 'C:/Users/willw/IdeaProjects/GroovyConcurrentUtils/scripts/certs/server-keystore.jks',
            keyStorePassword: 'changeit',
            trustStorePath: 'C:/Users/willw/IdeaProjects/GroovyConcurrentUtils/scripts/certs/truststore.jks',
            trustStorePassword: 'changeit'
        )
        
        serverTransport = new RSocketTransport(serverSystem, testPort, true, serverTlsConfig)
        serverTransport.start()
        
        Thread.sleep(500)
        
        // Client WITHOUT TLS (plain TCP)
        clientTransport = new RSocketTransport(null, 0, false, null)
        clientTransport.start()
        
        // Test: Should fail to connect
        assertThrows(Exception) {
            def future = clientTransport.ask(
                "rsocket://localhost:${testPort}/testSystem/echo",
                "test",
                Duration.ofSeconds(5)
            )
            future.get()
        }
    }
    
    @Test
    void test_mutual_tls_with_client_certificate() {
        // Setup: Server requiring client certificate (mTLS)
        serverSystem.actor {
            name 'secure'
            onMessage { msg, ctx -> ctx.reply("SECURE: ${msg}") }
        }
        
        def serverTlsConfig = new RSocketTransport.TlsConfig(
            enabled: true,
            keyStorePath: 'C:/Users/willw/IdeaProjects/GroovyConcurrentUtils/scripts/certs/server-keystore.jks',
            keyStorePassword: 'changeit',
            trustStorePath: 'C:/Users/willw/IdeaProjects/GroovyConcurrentUtils/scripts/certs/truststore.jks',
            trustStorePassword: 'changeit'
        )
        
        serverTransport = new RSocketTransport(serverSystem, testPort, true, serverTlsConfig)
        serverTransport.start()
        
        Thread.sleep(500)
        
        // Client with certificate for mutual TLS
        def clientTlsConfig = new RSocketTransport.TlsConfig(
            enabled: true,
            keyStorePath: 'C:/Users/willw/IdeaProjects/GroovyConcurrentUtils/scripts/certs/client-keystore.jks',
            keyStorePassword: 'changeit',
            trustStorePath: 'C:/Users/willw/IdeaProjects/GroovyConcurrentUtils/scripts/certs/truststore.jks',
            trustStorePassword: 'changeit'
        )
        
        clientTransport = new RSocketTransport(null, 0, false, clientTlsConfig)
        clientTransport.start()
        
        // Test: mTLS communication
        def future = clientTransport.ask(
            "rsocket://localhost:${testPort}/testSystem/secure",
            "authenticated",
            Duration.ofSeconds(5)
        )
        
        def result = future.get()
        
        // Verify
        assertEquals("SECURE: authenticated", result)
    }
    
    @Test
    void test_tls_protocols_configuration() {
        // Test that we can configure specific TLS protocols
        def tlsConfig = new RSocketTransport.TlsConfig(
            enabled: true,
            keyStorePath: 'C:/Users/willw/IdeaProjects/GroovyConcurrentUtils/scripts/certs/server-keystore.jks',
            keyStorePassword: 'changeit',
            protocols: ['TLSv1.3'] // Only TLS 1.3
        )
        
        // Should not throw - just start and verify it works
        serverTransport = new RSocketTransport(serverSystem, testPort, true, tlsConfig)
        serverTransport.start()
        
        // If we get here without exception, it worked
        assertNotNull(serverTransport)
    }
}
