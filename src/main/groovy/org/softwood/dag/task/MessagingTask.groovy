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
 * <h3>Receive Mode (Consumer) with Resilience Features:</h3>
 * <pre>
 * messagingTask("consume-orders") {
 *     consumer new KafkaConsumer()
 *     subscribe "orders", "notifications"
 *     
 *     // Message filtering (NEW!)
 *     filter { msg -> 
 *         msg.payload.status == "pending" && msg.payload.amount > 100
 *     }
 *     
 *     // Authentication (NEW!)
 *     authenticate { msg ->
 *         verifySignature(msg.headers["X-Signature"], msg.payload)
 *     }
 *     
 *     // Idempotency (inherited from TaskBase)
 *     idempotent {
 *         ttl Duration.ofMinutes(30)
 *         keyFrom { msg -> msg.payload.orderId }
 *     }
 *     
 *     // Dead Letter Queue (inherited from TaskBase)
 *     deadLetterQueue {
 *         maxSize 1000
 *         autoRetry true
 *         maxRetries 3
 *     }
 *     
 *     // Rate limiting (inherited from TaskBase)
 *     rateLimit {
 *         name "order-processor"
 *         maxRate 100
 *         period Duration.ofSeconds(1)
 *     }
 *     
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
    
    // NEW: Filtering and authentication
    private Closure messageFilter
    private Closure messageAuthenticator
    
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
    
    /**
     * Configure message filtering.
     * Messages that don't pass the filter are skipped.
     * 
     * <h3>Example:</h3>
     * <pre>
     * filter { msg ->
     *     msg.payload.amount > 100 && msg.payload.status == "pending"
     * }
     * </pre>
     * 
     * @param predicate filter closure that returns boolean
     */
    void filter(Closure predicate) {
        this.messageFilter = predicate
    }
    
    /**
     * Configure message authentication.
     * Messages that fail authentication are rejected and optionally sent to DLQ.
     * 
     * <h3>Example:</h3>
     * <pre>
     * authenticate { msg ->
     *     def signature = msg.headers["X-Message-Signature"]
     *     return verifyHmac(msg.payload, signature, secretKey)
     * }
     * </pre>
     * 
     * @param validator authentication closure that returns boolean
     */
    void authenticate(Closure validator) {
        this.messageAuthenticator = validator
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
                    // Apply authentication if configured
                    if (messageAuthenticator && !authenticateMessage(msg)) {
                        log.warn("MessagingTask '{}': message authentication failed, skipping", id)
                        continue
                    }
                    
                    // Apply filter if configured
                    if (messageFilter && !filterMessage(msg)) {
                        log.debug("MessagingTask '{}': message filtered out, skipping", id)
                        continue
                    }
                    
                    def result = messageHandler 
                        ? messageHandler.call(ctx, msg)
                        : msg.payload
                    
                    results.add(result)
                    consumer.commit(msg)
                    
                } catch (Exception e) {
                    log.error("MessagingTask '{}': error processing message", id, e)
                    // Error will be caught by TaskBase and handled via DLQ if configured
                    throw e
                }
            }
            
            return maxMessages == 1 && results.size() == 1 ? results[0] : results
        }
    }
    
    /**
     * Authenticate a message using the configured authenticator.
     * 
     * @param msg message to authenticate
     * @return true if authentication passed, false otherwise
     */
    private boolean authenticateMessage(IMessageConsumer.Message msg) {
        try {
            return messageAuthenticator.call(msg) as boolean
        } catch (Exception e) {
            log.error("MessagingTask '{}': authentication error", id, e)
            return false
        }
    }
    
    /**
     * Filter a message using the configured filter.
     * 
     * @param msg message to filter
     * @return true if message passes filter, false otherwise
     */
    private boolean filterMessage(IMessageConsumer.Message msg) {
        try {
            return messageFilter.call(msg) as boolean
        } catch (Exception e) {
            log.error("MessagingTask '{}': filter error", id, e)
            return false
        }
    }
    
    static enum MessagingMode {
        SEND,
        RECEIVE
    }
}
