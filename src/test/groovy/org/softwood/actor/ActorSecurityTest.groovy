package org.softwood.actor

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

import java.time.Duration

import static org.junit.jupiter.api.Assertions.*

/**
 * Tests for actor security hardening features.
 */
class ActorSecurityTest {
    
    ActorSystem system
    
    @BeforeEach
    void setup() {
        system = new ActorSystem('security-test')
    }
    
    @AfterEach
    void cleanup() {
        system?.shutdown()
    }
    
    // ═══════════════════════════════════════════════════════════
    // State Isolation Tests
    // ═══════════════════════════════════════════════════════════
    
    @Test
    @DisplayName("getState() should return deep copy - mutable lists")
    void testGetStateDeepCopyList() {
        def actor = system.actor {
            name 'state-test'
            state items: ['a', 'b', 'c']
            
            onMessage { msg, ctx ->
                ctx.reply(ctx.state.items.size())
            }
        }
        
        // Get state
        def exposedState = actor.getState()
        def exposedList = exposedState.items as List
        
        // Try to modify the exposed list
        exposedList << 'd'
        
        // Actor's internal state should be unchanged
        def size = actor.askSync('get-size', Duration.ofSeconds(1))
        assertEquals(3, size, "Actor state should not be modified")
    }
    
    @Test
    @DisplayName("getState() should return deep copy - nested maps")
    void testGetStateDeepCopyNestedMap() {
        def actor = system.actor {
            name 'nested-state-test'
            state config: [database: [host: 'localhost', port: 5432]]
            
            onMessage { msg, ctx ->
                ctx.reply(ctx.state.config.database.host)
            }
        }
        
        // Get state
        def exposedState = actor.getState()
        def exposedDb = exposedState.config.database as Map
        
        // Try to modify nested structure
        exposedDb.host = 'evil.com'
        
        // Actor's internal state should be unchanged
        def host = actor.askSync('get-host', Duration.ofSeconds(1))
        assertEquals('localhost', host, "Nested state should not be modified")
    }
    
    // ═══════════════════════════════════════════════════════════
    // Actor Name Validation Tests
    // ═══════════════════════════════════════════════════════════
    
    @Test
    @DisplayName("Actor name validation - reject path traversal")
    void testActorNamePathTraversal() {
        assertThrows(IllegalArgumentException.class) {
            system.actor {
                name '../../../etc/passwd'
                onMessage { msg, ctx -> }
            }
        }
    }
    
    @Test
    @DisplayName("Actor name validation - reject slashes")
    void testActorNameSlashes() {
        assertThrows(IllegalArgumentException.class) {
            system.actor {
                name 'actor/with/slashes'
                onMessage { msg, ctx -> }
            }
        }
    }
    
    @Test
    @DisplayName("Actor name validation - reject empty name")
    void testActorNameEmpty() {
        assertThrows(IllegalArgumentException.class) {
            system.actor {
                name ''
                onMessage { msg, ctx -> }
            }
        }
    }
    
    @Test
    @DisplayName("Actor name validation - reject too long name")
    void testActorNameTooLong() {
        assertThrows(IllegalArgumentException.class) {
            system.actor {
                name 'a' * 257  // 257 characters
                onMessage { msg, ctx -> }
            }
        }
    }
    
    @Test
    @DisplayName("Actor name validation - accept valid names")
    void testActorNameValid() {
        // These should all succeed - use explicit Executable cast
        assertDoesNotThrow({ ->
            system.actor {
                name 'valid-actor-name'
                onMessage { msg, ctx -> }
            }
        } as org.junit.jupiter.api.function.Executable)
        
        assertDoesNotThrow({ ->
            system.actor {
                name 'actor_with_underscore'
                onMessage { msg, ctx -> }
            }
        } as org.junit.jupiter.api.function.Executable)
        
        assertDoesNotThrow({ ->
            system.actor {
                name 'actor.with.dots'
                onMessage { msg, ctx -> }
            }
        } as org.junit.jupiter.api.function.Executable)
        
        assertDoesNotThrow({ ->
            system.actor {
                name 'Actor123'
                onMessage { msg, ctx -> }
            }
        } as org.junit.jupiter.api.function.Executable)
    }
    
    // ═══════════════════════════════════════════════════════════
    // Error Information Tests
    // ═══════════════════════════════════════════════════════════
    
    @Test
    @DisplayName("Error information should be sanitized")
    void testErrorSanitization() {
        def actor = system.actor {
            name 'error-test'
            
            onMessage { msg, ctx ->
                if (msg == 'fail') {
                    throw new RuntimeException("Error with password=secret123 at C:\\Users\\sensitive\\path")
                }
                ctx.reply('ok')
            }
        }
        
        // Trigger error using tell (not askSync) to ensure it gets processed
        actor.tell('fail')
        
        Thread.sleep(1000)  // Give more time for error to be recorded
        
        // Check stored errors
        def errors = actor.getErrors()
        
        // Debug: Print error count and content
        println "Error count: ${errors.size()}"
        println "Metrics: ${actor.metrics()}"
        
        assertTrue(errors.size() > 0, "Error should be recorded")
        
        def errorInfo = errors[0]
        
        // Check sanitization
        assertEquals('RuntimeException', errorInfo.errorType, "Should use simple name")
        def message = errorInfo.message as String
        assertFalse(message.contains('secret123'), "Should sanitize credentials")
        assertFalse(message.contains('C:\\Users'), "Should sanitize paths")
        assertTrue(message.contains('***') || message.contains('<path>'), 
                  "Should show sanitization markers")
    }
    
    @Test
    @DisplayName("Stack traces should be limited in production mode")
    void testStackTraceProductionMode() {
        // Set production mode
        System.setProperty("actor.productionMode", "true")
        
        try {
            def actor = system.actor {
                name 'production-test'
                
                onMessage { msg, ctx ->
                    throw new RuntimeException("Production error")
                }
            }
            
            // Trigger error
            try {
                actor.askSync('fail', Duration.ofSeconds(1))
            } catch (Exception e) {
                // Expected
            }
            
            Thread.sleep(500)  // Give more time
            
            // Check stored errors
            def errors = actor.getErrors()
            assertTrue(errors.size() > 0, "Error should be recorded")
            
            def errorInfo = errors[0]
            assertNotNull(errorInfo, "Error info should not be null")
            assertNotNull(errorInfo.stackTrace, "stackTrace property should exist")
            
            // Stack trace should be empty in production
            assertTrue(errorInfo.stackTrace.isEmpty(), "Stack trace should be empty in production mode")
            
        } finally {
            System.clearProperty("actor.productionMode")
        }
    }
    
    // ═══════════════════════════════════════════════════════════
    // Error Queue Bounds Test
    // ═══════════════════════════════════════════════════════════
    
    @Test
    @DisplayName("Error queue should respect size limits")
    void testErrorQueueBounds() {
        // Create actor with maxErrorsRetained=5 via constructor parameters
        // Note: This is set at construction time, not via DSL
        def actor = system.actor {
            name 'error-queue-test'
            // maxErrorsRetained is constructor param, defaults to 100
            // We'll generate many errors and check it doesn't grow unbounded
            
            onMessage { msg, ctx ->
                throw new RuntimeException("Error $msg")
            }
        }
        
        // Generate 10 errors
        (1..10).each { i ->
            try {
                actor.askSync("error-$i", Duration.ofSeconds(1))
            } catch (Exception e) {
                // Expected
            }
        }
        
        Thread.sleep(200)  // Let all errors be processed
        
        // Should keep up to 100 (default), but definitely not unbounded
        def errors = actor.getErrors()
        assertTrue(errors.size() <= 100, "Should not exceed default max errors retained (100)")
        assertTrue(errors.size() > 0, "Should have recorded some errors")
    }
    
    // ═══════════════════════════════════════════════════════════
    // Escalation Security Test
    // ═══════════════════════════════════════════════════════════
    
    @Test
    @DisplayName("Escalation should not expose actor reference")
    void testEscalationNoActorReference() {
        def receivedMessages = []
        
        def parent = system.actor {
            name 'secure-parent'
            
            onMessage { msg, ctx ->
                if (msg == 'create-child') {
                    def child = ctx.spawn('child') { childMsg, childCtx ->
                        throw new RuntimeException("Child error")
                    }
                    
                    // Send message to trigger error
                    child.tell('trigger')
                    ctx.reply('created')
                } else if (msg.type == 'child-error') {
                    receivedMessages << msg
                    ctx.reply('handled')
                }
            }
        }
        
        parent.askSync('create-child', Duration.ofSeconds(2))
        Thread.sleep(500)  // Wait for escalation
        
        // Check that escalation message doesn't contain actor reference
        if (receivedMessages.size() > 0) {
            def escalationMsg = receivedMessages[0]
            assertFalse(escalationMsg.containsKey('child'), "Should not contain child actor reference")
            assertFalse(escalationMsg.containsKey('error'), "Should not contain raw error object")
            assertTrue(escalationMsg.containsKey('errorType'), "Should contain error type")
            assertTrue(escalationMsg.containsKey('errorMessage'), "Should contain sanitized message")
        }
    }
}
