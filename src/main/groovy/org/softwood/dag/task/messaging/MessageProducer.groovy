package org.softwood.dag.task.messaging

/**
 * Interface for message producers - enables pluggable messaging systems.
 * 
 * <p><strong>ZERO DEPENDENCIES:</strong> This interface is always available.
 * Concrete implementations (Kafka, AMQP, Vert.x) require their respective libraries.</p>
 * 
 * <h3>Available Implementations:</h3>
 * <ul>
 *   <li><b>InMemoryProducer</b> - Default, no dependencies (testing)</li>
 *   <li><b>VertxEventBusProducer</b> - Uses existing Vert.x dependency (already available!)</li>
 *   <li><b>KafkaProducer</b> - Requires kafka-clients dependency</li>
 *   <li><b>AmqpProducer</b> - Requires amqp-client dependency</li>
 * </ul>
 * 
 * <h3>Usage:</h3>
 * <pre>
 * // Default (in-memory)
 * def producer = new InMemoryProducer()
 * producer.send("my-topic", [orderId: 123, status: "completed"])
 * 
 * // Vert.x EventBus (uses existing dependency!)
 * def producer = new VertxEventBusProducer(vertx)
 * producer.send("orders", message)
 * 
 * // Kafka (requires dependency)
 * def producer = new KafkaProducer(bootstrapServers: "localhost:9092")
 * producer.send("orders", message)
 * 
 * // AMQP/RabbitMQ (requires dependency)
 * def producer = new AmqpProducer(host: "localhost", exchange: "orders")
 * producer.send("order.created", message)
 * </pre>
 * 
 * @since 2.1.0
 */
interface MessageProducer {
    
    /**
     * Send a message to a destination (topic/queue/exchange).
     * 
     * @param destination topic name, queue name, or routing key
     * @param message message payload (will be serialized)
     * @return message metadata (ID, timestamp, partition, etc.)
     */
    Map<String, Object> send(String destination, Object message)
    
    /**
     * Send a message with headers/metadata.
     * 
     * @param destination topic/queue/exchange
     * @param message message payload
     * @param headers additional metadata (e.g., correlation-id, message-type)
     * @return message metadata
     */
    Map<String, Object> send(String destination, Object message, Map<String, String> headers)
    
    /**
     * Send a message with a specific key (for partitioning).
     * 
     * @param destination topic/queue
     * @param key partition key (Kafka) or routing key (AMQP)
     * @param message message payload
     * @return message metadata
     */
    Map<String, Object> send(String destination, String key, Object message)
    
    /**
     * Send a message with key and headers.
     * 
     * @param destination topic/queue/exchange
     * @param key partition key (Kafka) or routing key (AMQP)
     * @param message message payload
     * @param headers additional metadata
     * @return message metadata
     */
    Map<String, Object> send(String destination, String key, Object message, Map<String, String> headers)
    
    /**
     * Flush any buffered messages (ensure delivery).
     */
    void flush()
    
    /**
     * Close the producer and release resources.
     */
    void close()
    
    /**
     * Get the producer name/type.
     * @return producer identifier (e.g., "Kafka", "AMQP", "InMemory", "VertxEventBus")
     */
    String getProducerType()
    
    /**
     * Check if producer is connected and ready.
     * @return true if ready to send
     */
    boolean isConnected()
}
