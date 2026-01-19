package org.softwood.dag.task.messaging

import org.softwood.promise.Promise

/**
 * Interface for message receivers - enables pluggable reception strategies.
 * 
 * <p><strong>ZERO DEPENDENCIES:</strong> This interface is always available.
 * Concrete implementations require their respective libraries.</p>
 * 
 * <h3>Available Implementations:</h3>
 * <ul>
 *   <li><b>WebhookReceiver</b> - Default, webhook/callback pattern (zero dependencies)</li>
 *   <li><b>QueueReceiver</b> - Poll from message queue (Kafka, AMQP) [Future]</li>
 *   <li><b>EventBusReceiver</b> - Vert.x EventBus consumer (existing dependency) [Future]</li>
 *   <li><b>InMemoryReceiver</b> - Testing/development (zero dependencies) [Future]</li>
 * </ul>
 * 
 * <h3>Usage in ReceiveTask:</h3>
 * <pre>
 * // Default (webhook/callback)
 * receiveTask("wait-callback") {
 *     // WebhookReceiver used by default
 *     correlationKey { prev -> prev.txnId }
 * }
 * 
 * // Explicit receiver
 * receiveTask("wait-callback") {
 *     receiver new WebhookReceiver()
 *     correlationKey { prev -> prev.txnId }
 * }
 * 
 * // Future: Queue-based
 * receiveTask("poll-queue") {
 *     receiver new QueueReceiver(topic: "responses")
 *     correlationKey { prev -> prev.orderId }
 * }
 * </pre>
 * 
 * @since 2.0.0
 * @see org.softwood.dag.task.ReceiveTask
 */
interface IMessageReceiver {
    
    /**
     * Initialize the receiver.
     * 
     * <p>Called once before any receive operations.
     * Use this to establish connections, validate configuration, etc.</p>
     * 
     * @throws IllegalStateException if initialization fails
     */
    void initialize()
    
    /**
     * Register a receiver for a correlation ID.
     * 
     * <p>When a message arrives with the matching correlation ID,
     * the promise will be resolved with the message data.</p>
     * 
     * @param correlationId unique correlation identifier
     * @param promise promise to resolve when message received
     * @param config receive configuration (timeout, filters, etc.)
     * @throws IllegalArgumentException if correlationId is null or empty
     */
    void register(String correlationId, Promise promise, ReceiveConfig config)
    
    /**
     * Unregister a receiver (cleanup after timeout/cancellation).
     * 
     * <p>Idempotent - safe to call multiple times for same correlation ID.</p>
     * 
     * @param correlationId correlation identifier to remove
     */
    void unregister(String correlationId)
    
    /**
     * Deliver a message to a registered receiver.
     * 
     * <p>If no receiver is registered for the correlation ID, returns false.
     * If a receiver is found, applies filters/authentication and resolves the promise.</p>
     * 
     * @param correlationId target correlation ID
     * @param message message payload (any type)
     * @return true if receiver found and notified, false otherwise
     */
    boolean deliverMessage(String correlationId, Object message)
    
    /**
     * Check if a receiver is registered for a correlation ID.
     * 
     * @param correlationId correlation ID to check
     * @return true if receiver exists
     */
    boolean hasReceiver(String correlationId)
    
    /**
     * Get count of pending receivers (for monitoring).
     * 
     * @return number of active receivers awaiting messages
     */
    int getPendingCount()
    
    /**
     * Get list of pending correlation IDs (for debugging).
     * 
     * @return list of correlation IDs currently awaiting messages
     */
    List<String> getPendingIds()
    
    /**
     * Shutdown the receiver and clean up resources.
     * 
     * <p>Must be idempotent (safe to call multiple times).
     * All pending receivers should be cancelled/rejected.</p>
     */
    void shutdown()
    
    /**
     * Get the receiver name/type.
     * 
     * @return receiver identifier (e.g., "Webhook", "Queue", "EventBus")
     */
    String getReceiverType()
}
