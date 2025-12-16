package org.softwood.actor.remote.rsocket

import groovy.transform.CompileDynamic
import org.junit.jupiter.api.*
import org.softwood.actor.ActorSystem
import org.softwood.actor.remote.security.AuthConfig
import org.softwood.actor.remote.security.JwtTokenService

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

import static org.junit.jupiter.api.Assertions.*

/**
 * Integration tests for RSocket authentication.
 */
@CompileDynamic
class RSocketAuthTest {
    
    private static final String TEST_SECRET = "this-is-a-very-secret-key-with-at-least-32-characters!"
    private static final AtomicInteger portCounter = new AtomicInteger(9000)
    
    private ActorSystem serverSystem
    private RSocketTransport serverTransport
    private RSocketTransport clientTransport
    private JwtTokenService tokenService
    private int testPort
    
    @BeforeEach
    void setup() {
        testPort = portCounter.getAndIncrement()
        serverSystem = new ActorSystem('authTestSystem')
        tokenService = new JwtTokenService(TEST_SECRET, Duration.ofHours(1))
    }
    
    @AfterEach
    void cleanup() {
        clientTransport?.close()
        serverTransport?.close()
        serverSystem?.shutdown()
    }
    
    @Test
    void test_valid_token_accepted() {
        // Setup: Create echo actor
        serverSystem.actor {
            name 'echo'
            onMessage { msg, ctx ->
                ctx.reply("ECHO: ${msg}")
            }
        }
        
        // Server with authentication enabled
        def authConfig = new AuthConfig(
            enabled: true,
            jwtSecret: TEST_SECRET,
            requireToken: true
        )
        
        serverTransport = new RSocketTransport(serverSystem, testPort, true, null, authConfig)
        serverTransport.start()
        
        Thread.sleep(500) // Let server start
        
        // Client with valid token
        clientTransport = new RSocketTransport(null, 0, false, null, null)
        clientTransport.start()
        
        def token = tokenService.generateToken('alice', ['USER'])
        clientTransport.setAuthToken(token)
        
        // Test: Send authenticated message
        def future = clientTransport.ask(
            "rsocket://localhost:${testPort}/authTestSystem/echo",
            "Hello Auth",
            Duration.ofSeconds(5)
        )
        
        def result = future.get()
        
        // Verify
        assertEquals("ECHO: Hello Auth", result)
    }
    
    @Test
    void test_invalid_token_rejected() {
        // Setup: Create echo actor
        serverSystem.actor {
            name 'echo'
            onMessage { msg, ctx ->
                ctx.reply("ECHO: ${msg}")
            }
        }
        
        // Server with authentication
        def authConfig = new AuthConfig(
            enabled: true,
            jwtSecret: TEST_SECRET,
            requireToken: true
        )
        
        serverTransport = new RSocketTransport(serverSystem, testPort, true, null, authConfig)
        serverTransport.start()
        
        Thread.sleep(500)
        
        // Client with INVALID token
        clientTransport = new RSocketTransport(null, 0, false, null, null)
        clientTransport.start()
        
        clientTransport.setAuthToken("invalid.token.here")
        
        // Test: Send message with invalid token
        def future = clientTransport.ask(
            "rsocket://localhost:${testPort}/authTestSystem/echo",
            "Hello",
            Duration.ofSeconds(5)
        )
        
        // Should fail with error
        assertThrows(Exception) {
            future.get()
        }
    }
    
    @Test
    void test_missing_token_rejected() {
        // Setup: Create echo actor
        serverSystem.actor {
            name 'echo'
            onMessage { msg, ctx ->
                ctx.reply("ECHO: ${msg}")
            }
        }
        
        // Server requires authentication
        def authConfig = new AuthConfig(
            enabled: true,
            jwtSecret: TEST_SECRET,
            requireToken: true
        )
        
        serverTransport = new RSocketTransport(serverSystem, testPort, true, null, authConfig)
        serverTransport.start()
        
        Thread.sleep(500)
        
        // Client WITHOUT token
        clientTransport = new RSocketTransport(null, 0, false, null, null)
        clientTransport.start()
        // No token set!
        
        // Test: Send message without token
        def future = clientTransport.ask(
            "rsocket://localhost:${testPort}/authTestSystem/echo",
            "Hello",
            Duration.ofSeconds(5)
        )
        
        // Should fail with authentication error
        assertThrows(Exception) {
            future.get()
        }
    }
    
    @Test
    void test_expired_token_rejected() {
        // Setup: Create echo actor
        serverSystem.actor {
            name 'echo'
            onMessage { msg, ctx ->
                ctx.reply("ECHO: ${msg}")
            }
        }
        
        // Server with authentication
        def authConfig = new AuthConfig(
            enabled: true,
            jwtSecret: TEST_SECRET,
            requireToken: true
        )
        
        serverTransport = new RSocketTransport(serverSystem, testPort, true, null, authConfig)
        serverTransport.start()
        
        Thread.sleep(500)
        
        // Client with EXPIRED token
        clientTransport = new RSocketTransport(null, 0, false, null, null)
        clientTransport.start()
        
        // Generate token with very short expiry
        def shortLivedService = new JwtTokenService(TEST_SECRET, Duration.ofMillis(100))
        def token = shortLivedService.generateToken('bob', ['USER'])
        
        Thread.sleep(200) // Wait for expiry
        
        clientTransport.setAuthToken(token)
        
        // Test: Send message with expired token
        def future = clientTransport.ask(
            "rsocket://localhost:${testPort}/authTestSystem/echo",
            "Hello",
            Duration.ofSeconds(5)
        )
        
        // Should fail with expired token error
        assertThrows(Exception) {
            future.get()
        }
    }
    
    @Test
    void test_auth_disabled_works_without_token() {
        // Setup: Create echo actor
        serverSystem.actor {
            name 'echo'
            onMessage { msg, ctx ->
                ctx.reply("ECHO: ${msg}")
            }
        }
        
        // Server with authentication DISABLED
        def authConfig = new AuthConfig(
            enabled: false
        )
        
        serverTransport = new RSocketTransport(serverSystem, testPort, true, null, authConfig)
        serverTransport.start()
        
        Thread.sleep(500)
        
        // Client without token
        clientTransport = new RSocketTransport(null, 0, false, null, null)
        clientTransport.start()
        // No auth needed!
        
        // Test: Send message without token
        def future = clientTransport.ask(
            "rsocket://localhost:${testPort}/authTestSystem/echo",
            "Hello No Auth",
            Duration.ofSeconds(5)
        )
        
        def result = future.get()
        
        // Verify - should work fine
        assertEquals("ECHO: Hello No Auth", result)
    }
    
    @Test
    void test_token_management_methods() {
        clientTransport = new RSocketTransport(null, 0, false, null, null)
        
        // Test: Initially no token
        assertNull(clientTransport.getAuthToken())
        
        // Set token
        def token = "test.token.value"
        clientTransport.setAuthToken(token)
        assertEquals(token, clientTransport.getAuthToken())
        
        // Clear token
        clientTransport.clearAuthToken()
        assertNull(clientTransport.getAuthToken())
    }
    
    @Test
    void test_fire_and_forget_with_auth() {
        // Setup: Create counter actor
        def counter = new AtomicInteger(0)
        
        serverSystem.actor {
            name 'counter'
            onMessage { msg, ctx ->
                counter.incrementAndGet()
            }
        }
        
        // Server with authentication
        def authConfig = new AuthConfig(
            enabled: true,
            jwtSecret: TEST_SECRET,
            requireToken: true
        )
        
        serverTransport = new RSocketTransport(serverSystem, testPort, true, null, authConfig)
        serverTransport.start()
        
        Thread.sleep(500)
        
        // Client with valid token
        clientTransport = new RSocketTransport(null, 0, false, null, null)
        clientTransport.start()
        
        def token = tokenService.generateToken('charlie', ['USER'])
        clientTransport.setAuthToken(token)
        
        // Test: Fire and forget
        clientTransport.tell(
            "rsocket://localhost:${testPort}/authTestSystem/counter",
            "increment"
        )
        
        Thread.sleep(1000) // Wait for delivery
        
        // Verify
        assertEquals(1, counter.get())
    }
}
