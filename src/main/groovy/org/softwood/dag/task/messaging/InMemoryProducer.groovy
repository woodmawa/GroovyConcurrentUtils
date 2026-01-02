package org.softwood.dag.task.messaging

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * In-memory message producer for testing and development.
 * 
 * <p><strong>ZERO DEPENDENCIES:</strong> Uses only Java standard library.
 * Messages are stored in memory using concurrent data structures.</p>
 * 
 * <h3>Features:</h3>
 * <ul>
 *   <li>Thread-safe</li>
 *   <li>Topic-based routing</li>
 *   <li>Message persistence in memory (cleared on restart)</li>
 *   <li>Perfect for unit testing</li>
 * </ul>
 * 
 * @since 2.1.0
 */
@Slf4j
@CompileStatic
class InMemoryProducer implements MessageProducer {
    
    // Global message store - shared across all InMemory producers/consumers
    private static final Map<String, Queue<MessageConsumer.Message>> TOPIC_STORE = new ConcurrentHashMap<>()
    
    private volatile boolean connected = true
    
    @Override
    Map<String, Object> send(String destination, Object message) {
        return send(destination, null, message, [:])
    }
    
    @Override
    Map<String, Object> send(String destination, Object message, Map<String, String> headers) {
        return send(destination, null, message, headers)
    }
    
    @Override
    Map<String, Object> send(String destination, String key, Object message) {
        return send(destination, key, message, [:])
    }
    
    @Override
    Map<String, Object> send(String destination, String key, Object message, Map<String, String> headers) {
        if (!connected) {
            throw new IllegalStateException("Producer is closed")
        }
        
        if (!destination) {
            throw new IllegalArgumentException("Destination cannot be null")
        }
        
        Queue<MessageConsumer.Message> queue = TOPIC_STORE.computeIfAbsent(destination, { k ->
            new ConcurrentLinkedQueue<MessageConsumer.Message>()
        })
        
        def msg = new MessageConsumer.Message(destination, key, message)
        msg.headers.putAll(headers ?: [:])
        msg.timestamp = System.currentTimeMillis()
        msg.offset = queue.size()
        
        queue.offer(msg)
        
        log.debug("InMemoryProducer: sent message to '${destination}' (offset=${msg.offset})")
        
        return [
            destination: destination,
            key: key,
            offset: msg.offset,
            timestamp: msg.timestamp,
            success: true
        ]
    }
    
    @Override
    void flush() {
        log.trace("InMemoryProducer: flush() called (no-op)")
    }
    
    @Override
    void close() {
        connected = false
        log.debug("InMemoryProducer: closed")
    }
    
    @Override
    String getProducerType() {
        return "InMemory"
    }
    
    @Override
    boolean isConnected() {
        return connected
    }
    
    static Queue<MessageConsumer.Message> getTopicQueue(String topic) {
        return TOPIC_STORE.get(topic)
    }
    
    static void clearAll() {
        TOPIC_STORE.clear()
        log.debug("InMemoryProducer: cleared all topics")
    }
    
    static void clearTopic(String topic) {
        def queue = TOPIC_STORE.remove(topic)
        if (queue) {
            log.debug("InMemoryProducer: cleared topic '${topic}' (${queue.size()} messages)")
        }
    }
    
    static int getMessageCount(String topic) {
        def queue = TOPIC_STORE.get(topic)
        return queue ? queue.size() : 0
    }
    
    static Set<String> getTopics() {
        return new HashSet<>(TOPIC_STORE.keySet())
    }
}
