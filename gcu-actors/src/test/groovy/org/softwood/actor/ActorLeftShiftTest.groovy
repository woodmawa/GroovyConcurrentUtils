package org.softwood.actor

import groovy.transform.CompileDynamic
import org.junit.jupiter.api.*

import static org.junit.jupiter.api.Assertions.*

/**
 * Tests for Groovy left-shift operator (<<) on actors.
 * Provides GPars-compatible syntax.
 */
@CompileDynamic
class ActorLeftShiftTest {
    
    private ActorSystem system
    
    @BeforeEach
    void setup() {
        system = new ActorSystem('testSystem')
    }
    
    @AfterEach
    void cleanup() {
        system?.shutdown()
    }
    
    @Test
    void test_leftShift_with_echo_actor() {
        // Create echo actor
        def actor = system.actor {
            name 'echo'
            onMessage { msg, ctx ->
                ctx.reply("ECHO: ${msg}")
            }
        }
        
        // Test: Use << operator
        def result = actor << "Hello"
        
        // Verify
        assertEquals("ECHO: Hello", result)
    }
    
    @Test
    void test_leftShift_with_counter() {
        // Create counter actor
        system.actor {
            name 'counter'
            state([count: 0])
            onMessage { msg, ctx ->
                if (msg == 'increment') {
                    ctx.state.count++
                    ctx.reply(ctx.state.count)
                } else if (msg == 'get') {
                    ctx.reply(ctx.state.count)
                }
            }
        }
        
        def actor = system.getActor('counter')
        
        // Test: Use << for multiple operations
        def result1 = actor << 'increment'
        def result2 = actor << 'increment'
        def result3 = actor << 'increment'
        def final_count = actor << 'get'
        
        // Verify
        assertEquals(1, result1)
        assertEquals(2, result2)
        assertEquals(3, result3)
        assertEquals(3, final_count)
    }
    
    @Test
    void test_leftShift_with_map_message() {
        // Create actor that processes map messages
        system.actor {
            name 'processor'
            onMessage { msg, ctx ->
                if (msg instanceof Map) {
                    def action = msg.action
                    def value = msg.value
                    ctx.reply("${action}: ${value}")
                }
            }
        }
        
        def actor = system.getActor('processor')
        
        // Test: Send map with <<
        def result = actor << [action: 'process', value: 42]
        
        // Verify
        assertEquals("process: 42", result)
    }
    
    @Test
    void test_leftShift_chaining() {
        // Create pipeline of actors
        system.actor {
            name 'doubler'
            onMessage { msg, ctx ->
                ctx.reply(msg * 2)
            }
        }
        
        system.actor {
            name 'incrementer'
            onMessage { msg, ctx ->
                ctx.reply(msg + 1)
            }
        }
        
        def doubler = system.getActor('doubler')
        def incrementer = system.getActor('incrementer')
        
        // Test: Chain operations
        def result = incrementer << (doubler << 5)
        
        // Verify: (5 * 2) + 1 = 11
        assertEquals(11, result)
    }
    
    @Test
    void test_leftShift_with_closure_result() {
        // Create actor that returns a closure
        system.actor {
            name 'factory'
            onMessage { msg, ctx ->
                ctx.reply({ it * 2 })
            }
        }
        
        def actor = system.getActor('factory')
        
        // Test: Get closure via <<
        def multiplier = actor << 'gimme'
        
        // Verify: Closure works
        assertTrue(multiplier instanceof Closure)
        assertEquals(10, multiplier(5))
    }
    
    @Test
    void test_leftShift_timeout() {
        // Create slow actor
        system.actor {
            name 'slow'
            onMessage { msg, ctx ->
                Thread.sleep(10000) // 10 seconds - will timeout
                ctx.reply("done")
            }
        }
        
        def actor = system.getActor('slow')
        
        // Test: Should timeout (default 5 seconds)
        assertThrows(Exception) {
            actor << "test"
        }
    }
    
    @Test
    void test_leftShift_gpars_compatibility() {
        // Test that << behaves like GPars actors
        // In GPars: def reply = actor << message (synchronous)
        
        system.actor {
            name 'gparsLike'
            onMessage { msg, ctx ->
                // GPars actors reply with the result
                ctx.reply(msg.toUpperCase())
            }
        }
        
        def actor = system.getActor('gparsLike')
        
        // Test: GPars-style usage
        def reply = actor << "hello"
        
        // Verify: Synchronous reply like GPars
        assertEquals("HELLO", reply)
    }
    
    @Test
    void test_leftShift_can_ignore_return_value() {
        // Test that we can use << and ignore the result
        def called = false
        
        system.actor {
            name 'sideEffect'
            onMessage { msg, ctx ->
                called = true
                ctx.reply("ok")
            }
        }
        
        def actor = system.getActor('sideEffect')
        
        // Test: Use << without capturing result
        actor << "trigger"
        
        // Verify: Message was processed
        assertTrue(called)
    }
}
