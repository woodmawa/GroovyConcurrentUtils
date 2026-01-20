package org.softwood.dag.task.messaging

import org.softwood.dag.TaskGraph
import spock.lang.Specification
import java.time.Duration

/**
 * Tests for MessagingTask filtering and authentication features.
 */
class MessagingTaskFilterAuthTest extends Specification {
    
    def setup() {
        InMemoryProducer.clearAll()
    }
    
    def cleanup() {
        InMemoryProducer.clearAll()
    }
    
    // =========================================================================
    // Message Filtering Tests
    // =========================================================================
    
    def "should filter messages based on payload content"() {
        given:
        def producer = new InMemoryProducer()
        producer.send([destination: "orders", message: [amount: 50, status: "pending"]])   // Filtered out
        producer.send([destination: "orders", message: [amount: 150, status: "pending"]])  // Passes
        producer.send([destination: "orders", message: [amount: 200, status: "completed"]]) // Filtered out
        producer.send([destination: "orders", message: [amount: 500, status: "pending"]])  // Passes
        
        when:
        def graph = TaskGraph.build {
            messagingTask("filtered-consume") {
                consumer new InMemoryConsumer()
                subscribe "orders"
                
                // Only high-value pending orders
                filter { msg ->
                    msg.payload.amount > 100 && msg.payload.status == "pending"
                }
                
                maxMessages 10
                timeout Duration.ofSeconds(1)
                
                onMessage { ctx, msg ->
                    msg.payload
                }
            }
        }
        
        def result = graph.run().get()
        
        then:
        result instanceof List
        result.size() == 2
        result.every { it.amount > 100 && it.status == "pending" }
        result.collect { it.amount }.sort() == [150, 500]
    }
    
    def "should filter messages based on headers"() {
        given:
        def producer = new InMemoryProducer()
        producer.send([
            destination: "events",
            message: [data: "event1"],
            headers: ["X-Event-Type": "OrderCreated"]
        ])
        producer.send([
            destination: "events",
            message: [data: "event2"],
            headers: ["X-Event-Type": "OrderUpdated"]  // Filtered out
        ])
        producer.send([
            destination: "events",
            message: [data: "event3"],
            headers: ["X-Event-Type": "OrderCreated"]
        ])
        
        when:
        def graph = TaskGraph.build {
            messagingTask("filtered-events") {
                consumer new InMemoryConsumer()
                subscribe "events"
                
                filter { msg ->
                    msg.headers["X-Event-Type"] == "OrderCreated"
                }
                
                maxMessages 10
                timeout Duration.ofSeconds(1)
                
                onMessage { ctx, msg ->
                    msg.payload
                }
            }
        }
        
        def result = graph.run().get()
        
        then:
        result instanceof List
        result.size() == 2
        result.collect { it.data } == ["event1", "event3"]
    }
    
    def "should return empty list when all messages filtered"() {
        given:
        def producer = new InMemoryProducer()
        producer.send([destination: "orders", message: [amount: 10]])
        producer.send([destination: "orders", message: [amount: 20]])
        producer.send([destination: "orders", message: [amount: 30]])
        
        when:
        def graph = TaskGraph.build {
            messagingTask("all-filtered") {
                consumer new InMemoryConsumer()
                subscribe "orders"
                
                filter { msg ->
                    msg.payload.amount > 1000  // Nothing passes
                }
                
                maxMessages 10
                timeout Duration.ofSeconds(1)
                
                onMessage { ctx, msg ->
                    msg.payload
                }
            }
        }
        
        def result = graph.run().get()
        
        then:
        result instanceof List
        result.isEmpty()
    }
    
    def "should handle filter errors gracefully"() {
        given:
        def producer = new InMemoryProducer()
        producer.send([destination: "orders", message: [amount: 100]])
        producer.send([destination: "orders", message: [bad: "data"]])  // Will cause NPE in filter
        producer.send([destination: "orders", message: [amount: 200]])
        
        when:
        def graph = TaskGraph.build {
            messagingTask("filter-with-error") {
                consumer new InMemoryConsumer()
                subscribe "orders"
                
                filter { msg ->
                    msg.payload.amount > 50  // NPE on msg.bad.amount
                }
                
                maxMessages 10
                timeout Duration.ofSeconds(1)
                
                onMessage { ctx, msg ->
                    msg.payload
                }
            }
        }
        
        def result = graph.run().get()
        
        then:
        result instanceof List
        result.size() == 2  // Only the valid messages
        result.collect { it.amount }.sort() == [100, 200]
    }
    
    // =========================================================================
    // Authentication Tests
    // =========================================================================
    
    def "should authenticate messages with valid signature"() {
        given:
        def secretKey = "my-secret-key"
        def producer = new InMemoryProducer()
        
        // Message with valid signature
        producer.send([
            destination: "secure",
            message: [orderId: "123"],
            headers: ["X-Signature": "valid-hmac-123"]
        ])
        
        // Message with invalid signature
        producer.send([
            destination: "secure",
            message: [orderId: "456"],
            headers: ["X-Signature": "invalid-signature"]
        ])
        
        // Message with valid signature
        producer.send([
            destination: "secure",
            message: [orderId: "789"],
            headers: ["X-Signature": "valid-hmac-789"]
        ])
        
        when:
        def graph = TaskGraph.build {
            messagingTask("authenticated-consume") {
                consumer new InMemoryConsumer()
                subscribe "secure"
                
                authenticate { msg ->
                    def signature = msg.headers["X-Signature"]
                    // Simple validation: signature must start with "valid-hmac"
                    return signature?.startsWith("valid-hmac")
                }
                
                maxMessages 10
                timeout Duration.ofSeconds(1)
                
                onMessage { ctx, msg ->
                    msg.payload
                }
            }
        }
        
        def result = graph.run().get()
        
        then:
        result instanceof List
        result.size() == 2
        result.collect { it.orderId }.sort() == ["123", "789"]
    }
    
    def "should reject messages without signature"() {
        given:
        def producer = new InMemoryProducer()
        
        producer.send([
            destination: "secure",
            message: [data: "msg1"],
            headers: ["X-Signature": "valid-sig"]
        ])
        
        producer.send([
            destination: "secure",
            message: [data: "msg2"]
            // No signature header
        ])
        
        when:
        def graph = TaskGraph.build {
            messagingTask("require-auth") {
                consumer new InMemoryConsumer()
                subscribe "secure"
                
                authenticate { msg ->
                    def sig = msg.headers["X-Signature"]
                    return sig != null && sig.startsWith("valid-")
                }
                
                maxMessages 10
                timeout Duration.ofSeconds(1)
                
                onMessage { ctx, msg ->
                    msg.payload
                }
            }
        }
        
        def result = graph.run().get()
        
        then:
        result instanceof List
        result.size() == 1
        result[0].data == "msg1"
    }
    
    def "should handle authentication errors gracefully"() {
        given:
        def producer = new InMemoryProducer()
        producer.send([destination: "secure", message: [data: "msg1"], headers: ["X-Sig": "abc"]])
        producer.send([destination: "secure", message: [data: "msg2"], headers: ["X-Sig": null]])
        producer.send([destination: "secure", message: [data: "msg3"], headers: ["X-Sig": "def"]])
        
        when:
        def graph = TaskGraph.build {
            messagingTask("auth-with-error") {
                consumer new InMemoryConsumer()
                subscribe "secure"
                
                authenticate { msg ->
                    // Will NPE on null signature
                    msg.headers["X-Sig"].startsWith("a")
                }
                
                maxMessages 10
                timeout Duration.ofSeconds(1)
                
                onMessage { ctx, msg ->
                    msg.payload
                }
            }
        }
        
        def result = graph.run().get()
        
        then:
        result instanceof List
        result.size() == 1  // Only msg1 passes (msg2 causes auth error, msg3 fails auth)
        result[0].data == "msg1"
    }
    
    // =========================================================================
    // Combined Filter + Authentication Tests
    // =========================================================================
    
    def "should apply both filter and authentication"() {
        given:
        def producer = new InMemoryProducer()
        
        // Passes both
        producer.send([
            destination: "secure-orders",
            message: [amount: 150, status: "pending"],
            headers: ["X-Signature": "valid-sig-1"]
        ])
        
        // Fails auth
        producer.send([
            destination: "secure-orders",
            message: [amount: 200, status: "pending"],
            headers: ["X-Signature": "invalid"]
        ])
        
        // Fails filter
        producer.send([
            destination: "secure-orders",
            message: [amount: 50, status: "pending"],
            headers: ["X-Signature": "valid-sig-2"]
        ])
        
        // Passes both
        producer.send([
            destination: "secure-orders",
            message: [amount: 300, status: "pending"],
            headers: ["X-Signature": "valid-sig-3"]
        ])
        
        when:
        def graph = TaskGraph.build {
            messagingTask("secure-filtered") {
                consumer new InMemoryConsumer()
                subscribe "secure-orders"
                
                authenticate { msg ->
                    msg.headers["X-Signature"]?.startsWith("valid-")
                }
                
                filter { msg ->
                    msg.payload.amount > 100
                }
                
                maxMessages 10
                timeout Duration.ofSeconds(1)
                
                onMessage { ctx, msg ->
                    msg.payload
                }
            }
        }
        
        def result = graph.run().get()
        
        then:
        result instanceof List
        result.size() == 2
        result.collect { it.amount }.sort() == [150, 300]
    }
    
    def "should apply authentication before filter"() {
        given:
        def authCallCount = 0
        def filterCallCount = 0
        def producer = new InMemoryProducer()
        
        producer.send([
            destination: "test",
            message: [data: "test"],
            headers: ["X-Auth": "invalid"]
        ])
        
        when:
        def graph = TaskGraph.build {
            messagingTask("auth-then-filter") {
                consumer new InMemoryConsumer()
                subscribe "test"
                
                authenticate { msg ->
                    authCallCount++
                    return false  // Always fails
                }
                
                filter { msg ->
                    filterCallCount++
                    return true
                }
                
                maxMessages 10
                timeout Duration.ofSeconds(1)
                
                onMessage { ctx, msg ->
                    msg.payload
                }
            }
        }
        
        graph.run().get()
        
        then:
        authCallCount == 1
        filterCallCount == 0  // Never called because auth failed
    }
}
