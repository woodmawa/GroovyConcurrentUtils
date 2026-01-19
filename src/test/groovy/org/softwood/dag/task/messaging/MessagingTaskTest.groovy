package org.softwood.dag.task.messaging

import org.softwood.dag.TaskGraph
import org.softwood.dag.task.messaging.InMemoryProducer
import org.softwood.dag.task.messaging.InMemoryConsumer
import io.vertx.core.Vertx
import spock.lang.Specification

import java.time.Duration

/**
 * Comprehensive tests for MessagingTask with multiple producers.
 */
class MessagingTaskTest extends Specification {
    
    def setup() {
        // Clear in-memory queues before each test
        InMemoryProducer.clearAll()
    }
    
    def cleanup() {
        InMemoryProducer.clearAll()
    }
    
    // =========================================================================
    // InMemory Producer/Consumer Tests
    // =========================================================================
    
    def "should send and receive message using InMemory producer and consumer"() {
        when:
        def graph = TaskGraph.build {
            messagingTask("send") {
                destination "test-topic"
                message { [data: "test-message", id: 123] }
            }
            
            messagingTask("receive") {
                consumer new InMemoryConsumer()
                subscribe "test-topic"
                timeout Duration.ofSeconds(2)
                
                onMessage { ctx, msg ->
                    ctx.promiseFactory.executeAsync {
                        [received: msg.payload, source: msg.source]
                    }
                }
            }
            
            chainVia("send", "receive")
        }
        
        def result = graph.run().get()
        
        then:
        result.source == "test-topic"
        result.received.data == "test-message"
        result.received.id == 123
    }
    
    def "should send message with headers"() {
        when:
        def graph = TaskGraph.build {
            messagingTask("send") {
                destination "orders"
                message { [orderId: 456] }
                headers {
                    put "correlation-id", "abc-123"
                    put "message-type", "OrderCreated"
                }
            }
        }
        
        def result = graph.run().get()
        
        then:
        result.success
        result.destination == "orders"
        
        when:
        def queue = InMemoryProducer.getTopicQueue("orders")
        def msg = queue.peek()
        
        then:
        queue != null
        queue.size() == 1
        msg.headers["correlation-id"] == "abc-123"
        msg.headers["message-type"] == "OrderCreated"
    }
    
    def "should send message with key for partitioning"() {
        when:
        def graph = TaskGraph.build {
            messagingTask("send") {
                destination "events"
                key "customer-123"
                message { [eventType: "login"] }
            }
        }
        
        def result = graph.run().get()
        
        then:
        result.success
        result.key == "customer-123"
    }
    
    def "should receive multiple messages"() {
        given:
        def producer = new InMemoryProducer()
        5.times { i ->
            producer.send([destination: "multi-topic", message: [index: i, data: "message-$i"]])
        }
        
        when:
        def graph = TaskGraph.build {
            messagingTask("receive-multi") {
                consumer new InMemoryConsumer()
                subscribe "multi-topic"
                maxMessages 5
                timeout Duration.ofSeconds(2)
                
                onMessage { ctx, msg ->
                    msg.payload
                }
            }
        }
        
        def result = graph.run().get()
        
        then:
        result instanceof List
        result.size() == 5
        result[0].index == 0
        result[4].index == 4
    }
    
    def "should timeout when no messages available"() {
        when:
        def graph = TaskGraph.build {
            messagingTask("receive-timeout") {
                consumer new InMemoryConsumer()
                subscribe "empty-topic"
                timeout Duration.ofMillis(100)
                
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
    
    def "should subscribe to multiple topics"() {
        given:
        def producer = new InMemoryProducer()
        producer.send([destination: "topic-a", message: [source: "A", data: "from-A"]])
        producer.send([destination: "topic-b", message: [source: "B", data: "from-B"]])
        producer.send([destination: "topic-c", message: [source: "C", data: "from-C"]])
        
        when:
        def graph = TaskGraph.build {
            messagingTask("receive-multi-topic") {
                consumer new InMemoryConsumer()
                subscribe "topic-a", "topic-b", "topic-c"
                maxMessages 3
                timeout Duration.ofSeconds(2)
                
                onMessage { ctx, msg ->
                    msg.payload
                }
            }
        }
        
        def result = graph.run().get()
        
        then:
        result.size() == 3
        def sources = result.collect { it.source }.sort()
        sources == ["A", "B", "C"]
    }
    
    // =========================================================================
    // Vert.x EventBus Tests
    // =========================================================================
    
    def "should send message using Vertx EventBus"() {
        given:
        def vertx = Vertx.vertx()
        def receivedMessages = []
        
        vertx.eventBus().consumer("vertx-topic") { message ->
            receivedMessages << message.body()
        }
        
        when:
        def graph = TaskGraph.build {
            messagingTask("send-vertx") {
                producer new VertxEventBusProducer(vertx)
                destination "vertx-topic"
                message { [orderId: 789, status: "created"] }
            }
        }
        
        def result = graph.run().get()
        
        then:
        result.success
        result.destination == "vertx-topic"
        result.mode == "send"
        
        when:
        Thread.sleep(100)
        
        then:
        receivedMessages.size() == 1
        receivedMessages[0].orderId == 789
        
        cleanup:
        vertx.close()
    }
    
    def "should publish to EventBus broadcast"() {
        given:
        def vertx = Vertx.vertx()
        def consumer1Messages = []
        def consumer2Messages = []
        
        vertx.eventBus().consumer("broadcast-topic") { message ->
            consumer1Messages << message.body()
        }
        
        vertx.eventBus().consumer("broadcast-topic") { message ->
            consumer2Messages << message.body()
        }
        
        when:
        def graph = TaskGraph.build {
            messagingTask("publish-vertx") {
                producer new VertxEventBusProducer(vertx, [publishMode: true])
                destination "broadcast-topic"
                message { [alert: "System warning"] }
            }
        }
        
        def result = graph.run().get()
        
        then:
        result.success
        result.mode == "publish"
        
        when:
        Thread.sleep(100)
        
        then:
        consumer1Messages.size() == 1
        consumer2Messages.size() == 1
        
        cleanup:
        vertx.close()
    }
    
    def "should send Vertx message with headers"() {
        given:
        def vertx = Vertx.vertx()
        def receivedHeaders = [:]
        
        vertx.eventBus().consumer("headers-topic") { message ->
            // Convert Vert.x HeadersMultiMap to regular map
            message.headers().forEach { entry ->
                receivedHeaders[entry.key] = entry.value
            }
        }
        
        when:
        def graph = TaskGraph.build {
            messagingTask("send-headers") {
                producer new VertxEventBusProducer(vertx)
                destination "headers-topic"
                message { [data: "test"] }
                headers {
                put "correlation-id", "xyz-789"
                put "content-type", "application/json"
                }
            }
        }
        
        graph.run().get()
        Thread.sleep(100)
        
        then:
        receivedHeaders.get("correlation-id") == "xyz-789"
        receivedHeaders.get("content-type") == "application/json"
        
        cleanup:
        vertx.close()
    }
    
    // =========================================================================
    // Integration Tests
    // =========================================================================
    
    def "should integrate with task pipeline"() {
        when:
        def graph = TaskGraph.build {
            serviceTask("create-order") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        [
                            id: UUID.randomUUID().toString(),
                            customerId: "cust-123",
                            amount: 99.99,
                            items: ["item1", "item2"]
                        ]
                    }
                }
            }
            
            messagingTask("publish-order") {
                destination "order-events"
                message { prev -> prev }
                headers {
                    put "event-type", "OrderCreated"
                    put "version", "1.0"
                }
            }
            
            messagingTask("process-event") {
                consumer new InMemoryConsumer()
                subscribe "order-events"
                timeout Duration.ofSeconds(2)
                
                onMessage { ctx, msg ->
                    ctx.promiseFactory.executeAsync {
                        [
                            processed: true,
                            orderId: msg.payload.id,
                            eventType: msg.headers["event-type"]
                        ]
                    }
                }
            }
            
            chainVia("create-order", "publish-order", "process-event")
        }
        
        def result = graph.run().get()
        
        then:
        result.processed
        result.orderId != null
        result.eventType == "OrderCreated"
    }
    
    def "should handle message processing errors"() {
        given:
        def producer = new InMemoryProducer()
        producer.send([destination: "error-topic", message: [shouldFail: true]])
        
        when:
        def graph = TaskGraph.build {
            messagingTask("process-with-error") {
                consumer new InMemoryConsumer()
                subscribe "error-topic"
                timeout Duration.ofSeconds(1)
                
                onMessage { ctx, msg ->
                    if (msg.payload.shouldFail) {
                        throw new RuntimeException("Processing failed!")
                    }
                    msg.payload
                }
            }
        }
        
        graph.run().get()
        
        then:
        def exception = thrown(Exception)
        // Check if error message contains our exception in the cause chain
        def found = false
        def current = exception
        while (current != null) {
            if (current.message?.contains("Processing failed!")) {
                found = true
                break
            }
            current = current.cause
        }
        found
    }
    
    def "should support custom message transformation"() {
        when:
        def graph = TaskGraph.build {
            messagingTask("send-raw") {
                destination "transform-topic"
                message { [amount: 100, currency: "USD"] }
            }
            
            messagingTask("receive-and-transform") {
                consumer new InMemoryConsumer()
                subscribe "transform-topic"
                timeout Duration.ofSeconds(2)
                
                onMessage { ctx, msg ->
                    ctx.promiseFactory.executeAsync {
                        [
                            amountCents: msg.payload.amount * 100,
                            currencySymbol: msg.payload.currency == "USD" ? "\$" : msg.payload.currency
                        ]
                    }
                }
            }
            
            chainVia("send-raw", "receive-and-transform")
        }
        
        def result = graph.run().get()
        
        then:
        result.amountCents == 10000
        result.currencySymbol == "\$"
    }
    
    // =========================================================================
    // Static Helper Tests
    // =========================================================================
    
    def "should clear all topics"() {
        given:
        def producer = new InMemoryProducer()
        producer.send([destination: "topic1", message: [data: "test1"]])
        producer.send([destination: "topic2", message: [data: "test2"]])
        producer.send([destination: "topic3", message: [data: "test3"]])
        
        expect:
        InMemoryProducer.getTopics().size() == 3
        
        when:
        InMemoryProducer.clearAll()
        
        then:
        InMemoryProducer.getTopics().size() == 0
    }
    
    def "should clear specific topic"() {
        given:
        def producer = new InMemoryProducer()
        producer.send([destination: "keep-topic", message: [data: "keep"]])
        producer.send([destination: "clear-topic", message: [data: "clear"]])
        
        expect:
        InMemoryProducer.getTopics().size() == 2
        
        when:
        InMemoryProducer.clearTopic("clear-topic")
        
        then:
        InMemoryProducer.getTopics().size() == 1
        InMemoryProducer.getTopics().contains("keep-topic")
    }
    
    def "should get message count"() {
        given:
        def producer = new InMemoryProducer()
        producer.send([destination: "count-topic", message: [index: 1]])
        producer.send([destination: "count-topic", message: [index: 2]])
        producer.send([destination: "count-topic", message: [index: 3]])
        
        expect:
        InMemoryProducer.getMessageCount("count-topic") == 3
    }
}
