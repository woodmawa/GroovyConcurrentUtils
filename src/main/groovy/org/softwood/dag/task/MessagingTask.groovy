package org.softwood.dag.task

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.softwood.dag.task.messaging.InMemoryProducer
import org.softwood.dag.task.messaging.IMessageConsumer
import org.softwood.dag.task.messaging.IMessageProducer
import org.softwood.promise.Promise

import java.time.Duration
import java.util.Arrays

/**
 * Messaging task for sending/receiving messages via Kafka, AMQP, Vert.x EventBus, or in-memory queues.
 * 
 * <p><strong>ZERO DEPENDENCIES BY DEFAULT:</strong> Uses InMemoryProducer/Consumer
 * for testing. Add kafka-clients or amqp-client for production messaging, or use
 * VertxEventBusProducer with existing Vert.x dependency.</p>
 * 
 * <h3>Send Mode (Producer):</h3>
 * <pre>
 * messagingTask("publish-order") {
 *     // Use Vert.x EventBus (existing dependency!)
 *     producer new VertxEventBusProducer(vertx)
 *     
 *     destination "orders"
 *     message { prev -> 
 *         [orderId: prev.id, status: "created"] 
 *     }
 *     headers {
 *         "correlation-id" "abc-123"
 *         "message-type" "OrderCreated"
 *     }
 * }
 * </pre>
 * 
 * <h3>Receive Mode (Consumer):</h3>
 * <pre>
 * messagingTask("consume-orders") {
 *     consumer new InMemoryConsumer()
 *     subscribe "orders", "notifications"
 *     timeout Duration.ofSeconds(30)
 *     maxMessages 10
 *     
 *     onMessage { ctx, message ->
 *         processOrder(message.payload)
 *     }
 * }
 * </pre>
 * 
 * @since 2.1.0
 */
@Slf4j
@CompileStatic
class MessagingTask extends TaskBase<Object> {
    
    private MessagingMode mode = MessagingMode.SEND
    private IMessageProducer producer = new InMemoryProducer()
    private IMessageConsumer consumer
    private String destination
    private String key
    private Closure messageBuilder
    private Map<String, String> messageHeaders = [:]
    private Closure messageHandler
    private List<String> subscriptions = []
    private Duration pollTimeout = Duration.ofSeconds(5)
    private int maxMessages = 1
    
    MessagingTask(String id, String name, ctx) {
        super(id, name, ctx)
    }
    
    // =========================================================================
    // DSL Configuration Methods - Send Mode
    // =========================================================================
    
    void producer(IMessageProducer producer) {
        this.producer = producer
        this.mode = MessagingMode.SEND
    }
    
    void destination(String dest) {
        this.destination = dest
    }
    
    void key(String key) {
        this.key = key
    }
    
    void message(Closure builder) {
        this.messageBuilder = builder
    }
    
    void headers(@DelegatesTo(Map) Closure config) {
        def map = [:] as Map<String, String>
        config.delegate = map
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.call()
        this.messageHeaders.putAll(map)
    }
    
    // =========================================================================
    // DSL Configuration Methods - Receive Mode
    // =========================================================================
    
    void consumer(IMessageConsumer consumer) {
        this.consumer = consumer
        this.mode = MessagingMode.RECEIVE
    }
    
    void subscribe(String... topics) {
        this.subscriptions.addAll(Arrays.asList(topics))
        this.mode = MessagingMode.RECEIVE
    }
    
    void timeout(Duration timeout) {
        this.pollTimeout = timeout
    }
    
    void maxMessages(int max) {
        this.maxMessages = max
    }
    
    void onMessage(Closure handler) {
        this.messageHandler = handler
        this.mode = MessagingMode.RECEIVE
    }
    
    // =========================================================================
    // Task Execution
    // =========================================================================
    
    @Override
    protected Promise<Object> runTask(TaskContext ctx, Object prevValue) {
        if (mode == MessagingMode.SEND) {
            return sendMessage(ctx, prevValue)
        } else {
            return receiveMessages(ctx)
        }
    }
    
    private Promise<Object> sendMessage(TaskContext ctx, Object prevValue) {
        return ctx.promiseFactory.executeAsync {
            if (!destination) {
                throw new IllegalStateException("MessagingTask: destination not configured")
            }
            
            def message = messageBuilder ? messageBuilder.call(prevValue) : prevValue
            
            // Build parameters for send(Map) call
            def sendParams = [
                destination: destination,
                message: message,
                headers: messageHeaders
            ]
            if (key) {
                sendParams.key = key
            }
            
            def result = ((IMessageProducer)producer).send(sendParams)
            
            log.info("MessagingTask '{}': sent message to '{}' ({})", 
                id, destination, producer.producerType)
            
            return result as Object
        } as Promise<Object>
    }
    
    private Promise<Object> receiveMessages(TaskContext ctx) {
        return ctx.promiseFactory.executeAsync {
            if (!consumer) {
                throw new IllegalStateException("MessagingTask: consumer not configured")
            }
            
            if (subscriptions.isEmpty()) {
                throw new IllegalStateException("MessagingTask: no subscriptions configured")
            }
            
            consumer.subscribe(subscriptions as String[])
            
            List<IMessageConsumer.Message> messages = maxMessages == 1
                ? [consumer.poll(pollTimeout)].findAll { it != null }
                : consumer.poll(maxMessages, pollTimeout)
            
            log.info("MessagingTask '{}': received {} messages from {} ({})", 
                id, messages.size(), subscriptions, consumer.consumerType)
            
            def results = []
            for (msg in messages) {
                try {
                    def result = messageHandler 
                        ? messageHandler.call(ctx, msg)
                        : msg.payload
                    
                    results.add(result)
                    consumer.commit(msg)
                    
                } catch (Exception e) {
                    log.error("MessagingTask '{}': error processing message", id, e)
                    throw e
                }
            }
            
            return maxMessages == 1 && results.size() == 1 ? results[0] : results
        }
    }
    
    static enum MessagingMode {
        SEND,
        RECEIVE
    }
}
