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
 * // Simple send
 * producer.send(topic: "orders", message: [orderId: 123])
 * 
 * // With partition/routing key
 * producer.send(topic: "orders", key: "customer-123", message: [orderId: 123])
 * 
 * // With headers
 * producer.send(
 *     topic: "orders", 
 *     message: [orderId: 123],
 *     headers: ["correlation-id": "abc-123"]
 * )
 * 
 * // All options
 * producer.send(
 *     topic: "orders",
 *     key: "customer-123",
 *     message: [orderId: 123],
 *     headers: ["correlation-id": "abc-123"]
 * )
 * </pre>
 * 
 * @since 2.1.0
 */
interface IMessageProducer {
    
    /**
     * Send a message using named parameters.
     * 
     * @param params Named parameters:
     *   - topic/destination (String, required): topic name, queue name, or routing key
     *   - message (Object, required): message payload (will be serialized)
     *   - key (String, optional): partition key (Kafka) or routing key (AMQP)
     *   - headers (Map, optional): additional metadata (e.g., correlation-id, message-type)
     * @return message metadata (ID, timestamp, partition, etc.)
     */
    Map<String, Object> send(Map<String, Object> params)
    
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
