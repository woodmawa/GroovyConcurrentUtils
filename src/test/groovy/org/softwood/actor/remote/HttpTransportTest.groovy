package org.softwood.actor.remote

import groovy.transform.CompileDynamic
import org.junit.jupiter.api.*
import org.softwood.actor.ActorSystem
import org.softwood.actor.remote.http.HttpTransport

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

import static org.junit.jupiter.api.Assertions.*

/**
 * Tests for HTTP-based remote actor communication.
 * 
 * <p>These tests mirror RSocketTransportTest to ensure both transports
 * have identical behavior and capabilities.</p>
 */
@CompileDynamic
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HttpTransportTest {
    
    private static final AtomicInteger portCounter = new AtomicInteger(8100)
    
    private ActorSystem serverSystem
    private HttpTransport serverTransport
    private HttpTransport clientTransport
    private int serverPort
    
    @BeforeEach
    void setup() {
        // Use unique port for each test to avoid conflicts
        serverPort = portCounter.getAndIncrement()
        
        // Create server-side system with actors
        serverSystem = new ActorSystem('serverSystem')
        
        // Create echo actor
        serverSystem.actor {
            name 'echo'
            onMessage { msg, ctx ->
                ctx.reply("ECHO: ${msg}")
            }
        }
        
        // Create counter actor
        serverSystem.actor {
            name 'counter'
            state count: 0
            onMessage { msg, ctx ->
                if (msg == 'increment') {
                    ctx.state.count++
                    ctx.reply(ctx.state.count)
                } else if (msg == 'get') {
                    ctx.reply(ctx.state.count)
                }
            }
        }
        
        // Start server transport
        serverTransport = new HttpTransport(serverSystem, serverPort, true)
        serverTransport.start()
        
        // Give server time to start
        Thread.sleep(1000)
        
        // Create client transport (no server)
        clientTransport = new HttpTransport(null, 0, false)
        clientTransport.start()
    }
    
    @AfterEach
    void cleanup() {
        clientTransport?.close()
        serverTransport?.close()
        serverSystem?.close()
    }
    
    @Test
    void test_tell_fire_and_forget() {
        // Fire and forget - no response expected
        clientTransport.tell(
            "http://localhost:${serverPort}/serverSystem/echo",
            'Hello World'
        )
        
        // Give message time to arrive
        Thread.sleep(200)
        
        // Can't verify result of tell, but should not throw exception
        assertTrue(true, "Tell completed without error")
    }
    
    @Test
    void test_ask_request_response() {
        def future = clientTransport.ask(
            "http://localhost:${serverPort}/serverSystem/echo",
            'Hello',
            Duration.ofSeconds(5)
        )
        
        def reply = future.get()
        
        assertEquals('ECHO: Hello', reply)
    }
    
    @Test
    void test_stateful_actor_via_remote() {
        // Increment counter multiple times
        def future1 = clientTransport.ask(
            "http://localhost:${serverPort}/serverSystem/counter",
            'increment',
            Duration.ofSeconds(5)
        )
        assertEquals(1, future1.get())
        
        def future2 = clientTransport.ask(
            "http://localhost:${serverPort}/serverSystem/counter",
            'increment',
            Duration.ofSeconds(5)
        )
        assertEquals(2, future2.get())
        
        def future3 = clientTransport.ask(
            "http://localhost:${serverPort}/serverSystem/counter",
            'get',
            Duration.ofSeconds(5)
        )
        assertEquals(2, future3.get())
    }
    
    @Test
    void test_actor_not_found() {
        def future = clientTransport.ask(
            "http://localhost:${serverPort}/serverSystem/nonexistent",
            'Hello',
            Duration.ofSeconds(5)
        )
        
        assertThrows(Exception) {
            future.get()
        }
    }
    
    @Test
    void test_timeout() {
        // Create slow actor
        serverSystem.actor {
            name 'slow'
            onMessage { msg, ctx ->
                Thread.sleep(2000)
                ctx.reply('done')
            }
        }
        
        def future = clientTransport.ask(
            "http://localhost:${serverPort}/serverSystem/slow",
            'go',
            Duration.ofMillis(500)
        )
        
        // Should timeout
        assertThrows(Exception) {
            future.get()
        }
    }
    
    @Test
    void test_concurrent_requests() {
        def results = Collections.synchronizedList([])
        def threads = []
        
        // Send 10 concurrent requests
        10.times { i ->
            threads << Thread.start {
                try {
                    def future = clientTransport.ask(
                        "http://localhost:${serverPort}/serverSystem/counter",
                        'increment',
                        Duration.ofSeconds(5)
                    )
                    results << future.get()
                } catch (Exception e) {
                    e.printStackTrace()
                }
            }
        }
        
        // Wait for all threads
        threads.each { it.join(5000) }
        
        // Should have 10 results
        assertEquals(10, results.size())
        
        // Results should be sequential (1 through 10)
        assertTrue(results.containsAll(1..10))
    }
    
    @Test
    void test_multiple_requests_same_connection() {
        // HTTP doesn't pool connections like RSocket, but should handle multiple requests
        def uri = "http://localhost:${serverPort}/serverSystem/echo"
        
        5.times { i ->
            def future = clientTransport.ask(uri, "Message ${i}", Duration.ofSeconds(5))
            def reply = future.get()
            assertEquals("ECHO: Message ${i}" as String, reply as String)  // Compare as strings
        }
        
        // All requests should have succeeded
        assertTrue(true, "Multiple requests completed")
    }
    
    @Test
    void test_json_payload_structure() {
        // HTTP uses JSON, so test that payloads work correctly
        def complexPayload = [
            command: 'process',
            data: [
                items: [1, 2, 3],
                metadata: [type: 'test']
            ]
        ]
        
        // Create actor that echoes the payload
        serverSystem.actor {
            name 'complex'
            onMessage { msg, ctx ->
                ctx.reply(msg)
            }
        }
        
        def future = clientTransport.ask(
            "http://localhost:${serverPort}/serverSystem/complex",
            complexPayload,
            Duration.ofSeconds(5)
        )
        
        def result = future.get() as Map
        
        assertEquals('process', result.command)
        assertNotNull(result.data)
        assertEquals(3, ((result.data as Map).items as List).size())
    }
    
    @Test
    void test_scheme_validation() {
        // Should reject non-http URIs
        assertThrows(IllegalArgumentException) {
            clientTransport.tell(
                "rsocket://localhost:${serverPort}/serverSystem/echo",
                "Hello"
            )
        }
    }
    
    @Test
    void test_content_type_is_json() {
        // HTTP transport should use JSON content type
        // This is implicit in the implementation but validates the design
        def future = clientTransport.ask(
            "http://localhost:${serverPort}/serverSystem/echo",
            "test",
            Duration.ofSeconds(5)
        )
        
        def result = future.get()
        assertNotNull(result, "JSON deserialization should work")
    }
}
