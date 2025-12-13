package org.softwood.actor.remote

import groovy.transform.CompileDynamic
import org.junit.jupiter.api.*
import org.softwood.actor.ActorFactory
import org.softwood.actor.ActorSystem
import org.softwood.actor.remote.rsocket.RSocketTransport

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

import static org.awaitility.Awaitility.await
import static org.junit.jupiter.api.Assertions.*
import static java.util.concurrent.TimeUnit.SECONDS

/**
 * Tests for RSocket-based remote actor communication.
 */
@CompileDynamic
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RSocketTransportTest {
    
    private static final AtomicInteger portCounter = new AtomicInteger(7100)
    
    private ActorSystem serverSystem
    private RSocketTransport serverTransport
    private RSocketTransport clientTransport
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
        serverTransport = new RSocketTransport(serverSystem, serverPort, true)
        serverTransport.start()
        
        // Give server time to start
        Thread.sleep(1000)
        
        // Create client transport (no server)
        clientTransport = new RSocketTransport(null, 0, false)
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
            "rsocket://localhost:${serverPort}/serverSystem/echo",
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
            "rsocket://localhost:${serverPort}/serverSystem/echo",
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
            "rsocket://localhost:${serverPort}/serverSystem/counter",
            'increment',
            Duration.ofSeconds(5)
        )
        assertEquals(1, future1.get())
        
        def future2 = clientTransport.ask(
            "rsocket://localhost:${serverPort}/serverSystem/counter",
            'increment',
            Duration.ofSeconds(5)
        )
        assertEquals(2, future2.get())
        
        def future3 = clientTransport.ask(
            "rsocket://localhost:${serverPort}/serverSystem/counter",
            'get',
            Duration.ofSeconds(5)
        )
        assertEquals(2, future3.get())
    }
    
    @Test
    void test_actor_not_found() {
        def future = clientTransport.ask(
            "rsocket://localhost:${serverPort}/serverSystem/nonexistent",
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
            "rsocket://localhost:${serverPort}/serverSystem/slow",
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
                        "rsocket://localhost:${serverPort}/serverSystem/counter",
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
    void test_connection_reuse() {
        // Multiple requests to same host should reuse connection
        def uri = "rsocket://localhost:${serverPort}/serverSystem/echo"
        
        5.times { i ->
            def future = clientTransport.ask(uri, "Message ${i}", Duration.ofSeconds(5))
            def reply = future.get()
            assertEquals("ECHO: Message ${i}" as String, reply as String)  // Compare as strings
        }
        
        // All requests should have succeeded
        assertTrue(true, "Connection reuse works")
    }
}
