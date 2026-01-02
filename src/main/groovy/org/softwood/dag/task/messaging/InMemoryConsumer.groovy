package org.softwood.dag.task.messaging

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import java.time.Duration
import java.util.Arrays

/**
 * In-memory message consumer for testing and development.
 * 
 * <p><strong>ZERO DEPENDENCIES:</strong> Uses only Java standard library.
 * Reads messages from the shared in-memory topic store.</p>
 * 
 * @since 2.1.0
 */
@Slf4j
@CompileStatic
class InMemoryConsumer implements MessageConsumer {
    
    private final Set<String> subscriptions = Collections.synchronizedSet(new HashSet<>())
    private volatile boolean connected = true
    
    @Override
    void subscribe(String... destinations) {
        if (!destinations) {
            throw new IllegalArgumentException("Must subscribe to at least one destination")
        }
        
        subscriptions.addAll(Arrays.asList(destinations))
        log.debug("InMemoryConsumer: subscribed to ${destinations.length} topics: ${destinations}")
    }
    
    @Override
    Message poll(Duration timeout) {
        if (!connected) {
            throw new IllegalStateException("Consumer is closed")
        }
        
        if (subscriptions.isEmpty()) {
            throw new IllegalStateException("Consumer is not subscribed to any topics")
        }
        
        long startTime = System.currentTimeMillis()
        long timeoutMs = timeout.toMillis()
        
        while (true) {
            for (String topic : subscriptions) {
                def queue = InMemoryProducer.getTopicQueue(topic)
                if (queue) {
                    Message msg = (Message) queue.poll()
                    if (msg) {
                        log.debug("InMemoryConsumer: polled message from '${topic}'")
                        return msg
                    }
                }
            }
            
            long elapsed = System.currentTimeMillis() - startTime
            if (elapsed >= timeoutMs) {
                log.trace("InMemoryConsumer: poll timeout after ${elapsed}ms")
                return null
            }
            
            try {
                Thread.sleep(10)
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt()
                log.warn("InMemoryConsumer: poll interrupted")
                return null
            }
        }
    }
    
    @Override
    List<Message> poll(int maxMessages, Duration timeout) {
        if (!connected) {
            throw new IllegalStateException("Consumer is closed")
        }
        
        if (subscriptions.isEmpty()) {
            throw new IllegalStateException("Consumer is not subscribed to any topics")
        }
        
        if (maxMessages <= 0) {
            throw new IllegalArgumentException("maxMessages must be > 0")
        }
        
        List<Message> messages = []
        long startTime = System.currentTimeMillis()
        long timeoutMs = timeout.toMillis()
        
        while (messages.size() < maxMessages) {
            boolean foundMessage = false
            
            for (String topic : subscriptions) {
                if (messages.size() >= maxMessages) break
                
                def queue = InMemoryProducer.getTopicQueue(topic)
                if (queue) {
                    Message msg = (Message) queue.poll()
                    if (msg) {
                        messages.add(msg)
                        foundMessage = true
                    }
                }
            }
            
            if (messages.size() >= maxMessages) {
                log.debug("InMemoryConsumer: polled ${messages.size()} messages (max reached)")
                return messages
            }
            
            long elapsed = System.currentTimeMillis() - startTime
            if (elapsed >= timeoutMs) {
                log.trace("InMemoryConsumer: poll timeout, returning ${messages.size()} messages")
                return messages
            }
            
            if (!foundMessage) {
                try {
                    Thread.sleep(10)
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt()
                    log.warn("InMemoryConsumer: poll interrupted, returning ${messages.size()} messages")
                    return messages
                }
            }
        }
        
        return messages
    }
    
    @Override
    void commit(Message message) {
        log.trace("InMemoryConsumer: commit() called (auto-committed on poll)")
    }
    
    @Override
    void close() {
        connected = false
        subscriptions.clear()
        log.debug("InMemoryConsumer: closed")
    }
    
    @Override
    String getConsumerType() {
        return "InMemory"
    }
    
    @Override
    boolean isConnected() {
        return connected
    }
    
    Set<String> getSubscriptions() {
        return new HashSet<>(subscriptions)
    }
}
