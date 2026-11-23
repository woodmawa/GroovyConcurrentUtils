package org.softwood.dag.task.messaging

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

/**
 * Apache Kafka message producer.
 * 
 * <p><strong>ZERO COMPILE-TIME DEPENDENCIES:</strong> Uses reflection to avoid
 * compile-time dependency on kafka-clients. Add org.apache.kafka:kafka-clients:3.4+
 * at runtime for full functionality. Runs in stub mode for testing if library not available.</p>
 * 
 * <h3>Features:</h3>
 * <ul>
 *   <li>Partitioning by key</li>
 *   <li>At-least-once delivery</li>
 *   <li>Compression</li>
 *   <li>Idempotence</li>
 * </ul>
 * 
 * <h3>Usage:</h3>
 * <pre>
 * // Basic
 * def producer = new KafkaProducer(
 *     bootstrapServers: "localhost:9092"
 * )
 * producer.send("orders", [orderId: 123])
 * 
 * // With partitioning
 * def producer = new KafkaProducer(
 *     bootstrapServers: "kafka1:9092,kafka2:9092,kafka3:9092",
 *     clientId: "my-app-producer",
 *     compression: "snappy"
 * )
 * producer.send("orders", "customer-123", message)  // Key for partitioning
 * </pre>
 * 
 * @since 2.1.0
 */
@Slf4j
@CompileStatic
class KafkaProducer implements IMessageProducer {
    
    // =========================================================================
    // Configuration
    // =========================================================================
    
    String bootstrapServers = "localhost:9092"
    String clientId = "kafka-producer"
    String compression = "none"  // none, gzip, snappy, lz4, zstd
    boolean idempotence = true
    int acks = -1  // all replicas must acknowledge
    int retries = Integer.MAX_VALUE
    Map<String, Object> additionalConfig = [:]
    
    // =========================================================================
    // Runtime - Using Object types to avoid compile-time dependency
    // =========================================================================
    
    private Object producer  // KafkaProducer
    private volatile boolean connected = false
    private boolean kafkaAvailable = false
    
    // =========================================================================
    // Initialization
    // =========================================================================
    
    void initialize() {
        if (connected) {
            log.warn("KafkaProducer already connected")
            return
        }
        
        try {
            // Check if Kafka client is available
            Class.forName("org.apache.kafka.clients.producer.KafkaProducer")
            kafkaAvailable = true
            
            // Build configuration
            def props = new Properties()
            props.put("bootstrap.servers", bootstrapServers)
            props.put("client.id", clientId)
            props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
            props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
            props.put("compression.type", compression)
            props.put("enable.idempotence", idempotence)
            props.put("acks", acks.toString())
            props.put("retries", retries)
            
            // Add additional config
            if (additionalConfig) {
                props.putAll(additionalConfig)
            }
            
            // Create producer using reflection
            def producerClass = Class.forName("org.apache.kafka.clients.producer.KafkaProducer")
            producer = producerClass.getDeclaredConstructor(Properties).newInstance(props)
            
            connected = true
            log.info("KafkaProducer connected: {} (clientId={})", bootstrapServers, clientId)
            
        } catch (ClassNotFoundException e) {
            log.warn("Kafka client not found. Producer will run in stub mode. " +
                    "Add org.apache.kafka:kafka-clients:3.4+ to classpath for full functionality.")
            kafkaAvailable = false
            connected = true  // Allow stub mode
        } catch (Exception e) {
            log.error("Failed to initialize KafkaProducer", e)
            throw new RuntimeException("Kafka initialization failed: ${e.message}", e)
        }
    }
    
    // =========================================================================
    // IMessageProducer Implementation
    // =========================================================================
    
    @Override
    Map<String, Object> send(Map<String, Object> params) {
        if (!connected) {
            throw new IllegalStateException("Producer not connected. Call initialize() first.")
        }
        
        // Extract and validate parameters
        String destination = params.destination ?: params.topic
        if (!destination) {
            throw new IllegalArgumentException("Either 'destination' or 'topic' parameter is required")
        }
        
        Object message = params.message
        if (message == null) {
            throw new IllegalArgumentException("'message' parameter is required")
        }
        
        String key = params.key
        Map<String, String> headers = (params.headers ?: [:]) as Map<String, String>
        
        if (!kafkaAvailable) {
            return stubSend(destination, key, message, headers)
        }
        
        try {
            // Serialize message to JSON
            def messageStr = serializeMessage(message)
            
            // Create ProducerRecord using reflection
            def recordClass = Class.forName("org.apache.kafka.clients.producer.ProducerRecord")
            def record
            
            if (key) {
                def constructor = recordClass.getDeclaredConstructor(String, Object, Object)
                record = constructor.newInstance(destination, key, messageStr)
            } else {
                def constructor = recordClass.getDeclaredConstructor(String, Object)
                record = constructor.newInstance(destination, messageStr)
            }
            
            // Add headers if provided
            if (headers) {
                def headersMethod = record.getClass().getMethod("headers")
                def recordHeaders = headersMethod.invoke(record)
                
                headers.each { k, v ->
                    def addMethod = recordHeaders.getClass().getMethod("add", String, byte[].class)
                    addMethod.invoke(recordHeaders, k, v.getBytes("UTF-8"))
                }
            }
            
            // Send message
            def sendMethod = producer.getClass().getMethod("send", recordClass)
            def future = sendMethod.invoke(producer, record)
            
            // Get metadata (blocking)
            def getMethod = future.getClass().getMethod("get")
            def metadata = getMethod.invoke(future)
            
            // Extract metadata
            def topicMethod = metadata.getClass().getMethod("topic")
            def partitionMethod = metadata.getClass().getMethod("partition")
            def offsetMethod = metadata.getClass().getMethod("offset")
            def timestampMethod = metadata.getClass().getMethod("timestamp")
            
            def result = [
                success: true,
                topic: topicMethod.invoke(metadata),
                partition: partitionMethod.invoke(metadata),
                offset: offsetMethod.invoke(metadata),
                timestamp: timestampMethod.invoke(metadata)
            ]
            
            log.debug("KafkaProducer: sent to topic='{}', partition={}, offset={}",
                result.topic, result.partition, result.offset)
            
            return result
            
        } catch (Exception e) {
            log.error("Error sending to Kafka", e)
            return [
                success: false,
                error: e.message,
                timestamp: System.currentTimeMillis()
            ]
        }
    }
    
    @Override
    void flush() {
        if (producer && kafkaAvailable) {
            try {
                producer.getClass().getMethod("flush").invoke(producer)
                log.trace("KafkaProducer: flushed")
            } catch (Exception e) {
                log.error("Error flushing Kafka producer", e)
            }
        }
    }
    
    @Override
    void close() {
        if (producer && kafkaAvailable) {
            try {
                producer.getClass().getMethod("close").invoke(producer)
                log.info("KafkaProducer: closed")
            } catch (Exception e) {
                log.error("Error closing Kafka producer", e)
            }
        }
        connected = false
    }
    
    @Override
    String getProducerType() {
        return "Kafka"
    }
    
    @Override
    boolean isConnected() {
        return connected
    }
    
    // =========================================================================
    // Helper Methods
    // =========================================================================
    
    private String serializeMessage(Object message) {
        if (message instanceof String) {
            return message as String
        }
        // Convert to JSON
        return groovy.json.JsonOutput.toJson(message)
    }
    
    // =========================================================================
    // Stub Mode (for testing without Kafka client)
    // =========================================================================
    
    private Map<String, Object> stubSend(String destination, String key, Object message, Map<String, String> headers) {
        log.warn("KafkaProducer running in stub mode: send(topic='{}', key='{}', message={})",
            destination, key, message)
        return [
            success: true,
            stub: true,
            topic: destination,
            partition: 0,
            offset: 0L,
            timestamp: System.currentTimeMillis()
        ]
    }
}
