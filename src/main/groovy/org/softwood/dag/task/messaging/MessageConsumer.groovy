package org.softwood.dag.task.messaging

/**
 * Interface for message consumers - enables pluggable messaging systems.
 * 
 * <p><strong>ZERO DEPENDENCIES:</strong> This interface is always available.
 * Concrete implementations (Kafka, AMQP, Vert.x) require their respective libraries.</p>
 * 
 * <h3>Available Implementations:</h3>
 * <ul>
 *   <li><b>InMemoryConsumer</b> - Default, no dependencies (testing)</li>
 *   <li><b>VertxEventBusConsumer</b> - Uses existing Vert.x dependency (already available!)</li>
 *   <li><b>KafkaConsumer</b> - Requires kafka-clients dependency</li>
 *   <li><b>AmqpConsumer</b> - Requires amqp-client dependency</li>
 * </ul>
 * 
 * @since 2.1.0
 */
interface MessageConsumer {
    
    /**
     * Subscribe to one or more topics/queues.
     * 
     * @param destinations topic names or queue names
     */
    void subscribe(String... destinations)
    
    /**
     * Poll for a single message with timeout.
     * Blocks until a message is available or timeout expires.
     * 
     * @param timeout how long to wait
     * @return message or null if timeout
     */
    Message poll(java.time.Duration timeout)
    
    /**
     * Poll for multiple messages with timeout.
     * 
     * @param maxMessages maximum number of messages to retrieve
     * @param timeout how long to wait
     * @return list of messages (may be empty)
     */
    List<Message> poll(int maxMessages, java.time.Duration timeout)
    
    /**
     * Commit message offset/acknowledgment.
     * 
     * @param message message to acknowledge
     */
    void commit(Message message)
    
    /**
     * Close the consumer and release resources.
     */
    void close()
    
    /**
     * Get the consumer name/type.
     * @return consumer identifier (e.g., "Kafka", "AMQP", "InMemory", "VertxEventBus")
     */
    String getConsumerType()
    
    /**
     * Check if consumer is connected and ready.
     * @return true if ready to receive
     */
    boolean isConnected()
    
    /**
     * Represents a received message with metadata.
     */
    static class Message {
        /** Message payload */
        Object payload
        
        /** Source topic/queue */
        String source
        
        /** Message key (partition key or routing key) */
        String key
        
        /** Message headers/metadata */
        Map<String, String> headers = [:]
        
        /** Message timestamp */
        long timestamp
        
        /** Message offset/sequence (Kafka) or delivery tag (AMQP) */
        Object offset
        
        /** Partition number (Kafka only) */
        Integer partition
        
        Message(Object payload) {
            this.payload = payload
            this.timestamp = System.currentTimeMillis()
        }
        
        Message(String source, Object payload) {
            this(payload)
            this.source = source
        }
        
        Message(String source, String key, Object payload) {
            this(source, payload)
            this.key = key
        }
        
        @Override
        String toString() {
            "Message[source=$source, key=$key, payload=$payload]"
        }
    }
}
