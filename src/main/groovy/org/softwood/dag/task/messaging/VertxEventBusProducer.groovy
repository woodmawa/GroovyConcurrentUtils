package org.softwood.dag.task.messaging

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.vertx.core.Vertx
import io.vertx.core.eventbus.DeliveryOptions

/**
 * Vert.x EventBus message producer.
 * 
 * <p><strong>ZERO ADDITIONAL DEPENDENCIES:</strong> Uses existing Vert.x 5.0.5 dependency!
 * Perfect for in-process messaging, distributed eventbus, or clustered systems.</p>
 * 
 * <h3>Features:</h3>
 * <ul>
 *   <li>Uses existing Vert.x 5.0.5 dependency</li>
 *   <li>Supports local and clustered eventbus</li>
 *   <li>Point-to-point and publish/subscribe</li>
 *   <li>Headers and delivery options</li>
 *   <li>Non-blocking by design</li>
 * </ul>
 * 
 * <h3>Usage:</h3>
 * <pre>
 * // Point-to-point (send)
 * def producer = new VertxEventBusProducer(vertx)
 * producer.send("orders", [orderId: 123, status: "created"])
 * 
 * // Publish/subscribe
 * def producer = new VertxEventBusProducer(vertx, publishMode: true)
 * producer.send("notifications", message)
 * 
 * // With headers
 * producer.send("orders", message, [
 *     "correlation-id": "abc-123",
 *     "message-type": "OrderCreated"
 * ])
 * </pre>
 * 
 * @since 2.1.0
 */
@Slf4j
@CompileStatic
class VertxEventBusProducer implements MessageProducer {
    
    private final Vertx vertx
    private volatile boolean connected = true
    
    /** Use publish() instead of send() (broadcast to all consumers) */
    boolean publishMode = false
    
    /** Default send timeout in milliseconds */
    long sendTimeout = 30000  // 30 seconds
    
    VertxEventBusProducer(Vertx vertx) {
        if (!vertx) {
            throw new IllegalArgumentException("Vertx instance cannot be null")
        }
        this.vertx = vertx
        log.debug("VertxEventBusProducer: created with Vert.x instance")
    }
    
    VertxEventBusProducer(Vertx vertx, Map<String, Object> options) {
        this(vertx)
        
        if (options.publishMode != null) {
            this.publishMode = options.publishMode as boolean
        }
        if (options.sendTimeout != null) {
            this.sendTimeout = options.sendTimeout as long
        }
    }
    
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
            throw new IllegalArgumentException("Destination (address) cannot be null")
        }
        
        try {
            def options = new DeliveryOptions()
            options.setSendTimeout(sendTimeout)
            
            headers.each { k, v ->
                options.addHeader(k, v)
            }
            
            if (key) {
                options.addHeader("__key", key)
            }
            
            if (publishMode) {
                vertx.eventBus().publish(destination, message, options)
                log.debug("VertxEventBusProducer: published message to '{}'", destination)
            } else {
                vertx.eventBus().send(destination, message, options)
                log.debug("VertxEventBusProducer: sent message to '{}'", destination)
            }
            
            return [
                success: true,
                destination: destination,
                key: key,
                mode: publishMode ? "publish" : "send",
                timestamp: System.currentTimeMillis()
            ]
            
        } catch (Exception e) {
            log.error("VertxEventBusProducer: failed to send message to '{}'", destination, e)
            return [
                success: false,
                destination: destination,
                error: e.message
            ]
        }
    }
    
    @Override
    void flush() {
        log.trace("VertxEventBusProducer: flush() called (no-op for EventBus)")
    }
    
    @Override
    void close() {
        connected = false
        log.debug("VertxEventBusProducer: closed (Vert.x instance not closed)")
    }
    
    @Override
    String getProducerType() {
        return "VertxEventBus"
    }
    
    @Override
    boolean isConnected() {
        return connected
    }
}
