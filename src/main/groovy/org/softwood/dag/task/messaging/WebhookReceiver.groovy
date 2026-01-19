package org.softwood.dag.task.messaging

import groovy.util.logging.Slf4j
import org.softwood.promise.Promise
import org.softwood.dag.task.ReceiveTask
import java.util.concurrent.ConcurrentHashMap
import java.util.Timer
import java.util.TimerTask as JTimerTask

/**
 * Webhook-based message receiver (default implementation).
 * 
 * <p>Maintains static registry for webhook callbacks. External systems
 * deliver messages via the static delivery API, and registered receivers
 * are notified when their correlation ID matches.</p>
 * 
 * <h3>Features:</h3>
 * <ul>
 *   <li>Static registry for webhook callbacks</li>
 *   <li>Correlation-based message matching</li>
 *   <li>Timeout handling with auto-action</li>
 *   <li>Message filtering and authentication</li>
 *   <li>Data extraction/transformation</li>
 * </ul>
 * 
 * <h3>Usage:</h3>
 * <pre>
 * // Explicit (recommended for clarity)
 * receiveTask("wait-callback") {
 *     receiver new WebhookReceiver()
 *     correlationKey { prev -> prev.txnId }
 * }
 * 
 * // Implicit (backward compatible - WebhookReceiver is default)
 * task("wait-callback", TaskType.RECEIVE) {
 *     correlationKey { prev -> prev.txnId }
 * }
 * 
 * // External delivery
 * WebhookReceiver.deliverMessage("txn-123", paymentResult)
 * // or
 * ReceiveTask.deliverMessage("txn-123", paymentResult)  // delegates to default receiver
 * </pre>
 * 
 * <h3>Advanced Features:</h3>
 * <pre>
 * receiveTask("secure-callback") {
 *     receiver new WebhookReceiver()
 *     correlationKey { prev -> prev.requestId }
 *     
 *     // Filter messages
 *     filter { msg -> 
 *         msg.status == "SUCCESS" && msg.amount > 0 
 *     }
 *     
 *     // Extract/transform data
 *     extract { msg ->
 *         [result: msg.data, processed: true]
 *     }
 *     
 *     // Verify webhook signature
 *     authenticate { msg ->
 *         verifySignature(msg.signature, secretKey)
 *     }
 *     
 *     timeout Duration.ofMinutes(5)
 * }
 * </pre>
 * 
 * @since 2.0.0
 */
@Slf4j
class WebhookReceiver implements IMessageReceiver {
    
    /** Global registry of waiting receivers */
    private static final Map<String, ReceiverEntry> PENDING_RECEIVES = new ConcurrentHashMap<>()
    
    /** Timer for handling timeouts */
    private final Timer timeoutTimer = new Timer("WebhookReceiver-Timeout", true)
    
    /**
     * Internal entry tracking a registered receiver.
     */
    private static class ReceiverEntry {
        String correlationId
        Promise promise
        ReceiveConfig config
        long registeredAt
    }
    
    // =========================================================================
    // IMessageReceiver Implementation
    // =========================================================================
    
    @Override
    void initialize() {
        log.debug("WebhookReceiver initialized")
    }
    
    @Override
    void register(String correlationId, Promise promise, ReceiveConfig config) {
        log.debug("WebhookReceiver: registering receiver for correlation ID: $correlationId")
        
        def entry = new ReceiverEntry(
            correlationId: correlationId,
            promise: promise,
            config: config,
            registeredAt: System.currentTimeMillis()
        )
        
        PENDING_RECEIVES.put(correlationId, entry)
        
        // Schedule timeout if configured
        if (config.timeout) {
            scheduleTimeout(correlationId, config)
        }
    }
    
    @Override
    void unregister(String correlationId) {
        def entry = PENDING_RECEIVES.remove(correlationId)
        if (entry) {
            log.debug("WebhookReceiver: unregistered receiver for correlation ID: $correlationId")
        }
    }
    
    @Override
    boolean deliverMessage(String correlationId, Object message) {
        log.debug("WebhookReceiver: attempting to deliver message for correlation ID: $correlationId")
        
        def entry = PENDING_RECEIVES.get(correlationId)
        
        if (!entry) {
            log.warn("WebhookReceiver: no receiver found for correlation ID: $correlationId")
            return false
        }
        
        try {
            // Apply authentication if configured
            if (entry.config.authenticator) {
                boolean authenticated = entry.config.authenticator.call(message)
                if (!authenticated) {
                    log.warn("WebhookReceiver: authentication failed for correlation ID: $correlationId")
                    return false
                }
            }
            
            // Apply filter if configured
            if (entry.config.filter) {
                boolean accepted = entry.config.filter.call(message)
                if (!accepted) {
                    log.debug("WebhookReceiver: message filtered out for correlation ID: $correlationId")
                    return false
                }
            }
            
            // Wrap message in standard format
            def messageData = message instanceof Map ? message : [data: message]
            messageData.receivedAt = System.currentTimeMillis()
            messageData.correlationId = correlationId
            
            // Apply extractor if configured
            if (entry.config.extractor) {
                try {
                    messageData = entry.config.extractor.call(messageData)
                } catch (Exception e) {
                    log.error("WebhookReceiver: error in data extractor for $correlationId", e)
                    throw e
                }
            }
            
            log.info("WebhookReceiver: delivering message for correlation ID: $correlationId")
            
            entry.promise.accept(messageData)
            PENDING_RECEIVES.remove(correlationId)
            
            return true
            
        } catch (Exception e) {
            log.error("WebhookReceiver: error delivering message for $correlationId", e)
            return false
        }
    }
    
    @Override
    boolean hasReceiver(String correlationId) {
        return PENDING_RECEIVES.containsKey(correlationId)
    }
    
    @Override
    int getPendingCount() {
        return PENDING_RECEIVES.size()
    }
    
    @Override
    List<String> getPendingIds() {
        return new ArrayList<>(PENDING_RECEIVES.keySet())
    }
    
    @Override
    void shutdown() {
        log.info("WebhookReceiver: shutting down (${PENDING_RECEIVES.size()} pending)")
        timeoutTimer.cancel()
        PENDING_RECEIVES.clear()
    }
    
    @Override
    String getReceiverType() {
        return "Webhook"
    }
    
    // =========================================================================
    // Static API (For Backward Compatibility)
    // =========================================================================
    
    /**
     * Static delivery API for backward compatibility.
     * Directly accesses the static registry to deliver messages.
     * 
     * @param correlationId target correlation ID
     * @param message message payload
     * @return true if receiver found and notified
     */
    static boolean deliverMessageStatic(String correlationId, Object message) {
        log.debug("WebhookReceiver: static delivery for correlation ID: $correlationId")
        
        def entry = PENDING_RECEIVES.get(correlationId)
        
        if (!entry) {
            log.warn("WebhookReceiver: no receiver found for correlation ID: $correlationId")
            return false
        }
        
        try {
            // Apply authentication if configured
            if (entry.config.authenticator) {
                boolean authenticated = entry.config.authenticator.call(message)
                if (!authenticated) {
                    log.warn("WebhookReceiver: authentication failed for correlation ID: $correlationId")
                    return false
                }
            }
            
            // Apply filter if configured
            if (entry.config.filter) {
                boolean accepted = entry.config.filter.call(message)
                if (!accepted) {
                    log.debug("WebhookReceiver: message filtered out for correlation ID: $correlationId")
                    return false
                }
            }
            
            // Wrap message in standard format
            def messageData = message instanceof Map ? message : [data: message]
            messageData.receivedAt = System.currentTimeMillis()
            messageData.correlationId = correlationId
            
            // Apply extractor if configured
            if (entry.config.extractor) {
                try {
                    messageData = entry.config.extractor.call(messageData)
                } catch (Exception e) {
                    log.error("WebhookReceiver: error in data extractor for $correlationId", e)
                    throw e
                }
            }
            
            log.info("WebhookReceiver: delivering message for correlation ID: $correlationId")
            
            entry.promise.accept(messageData)
            PENDING_RECEIVES.remove(correlationId)
            
            return true
            
        } catch (Exception e) {
            log.error("WebhookReceiver: error delivering message for $correlationId", e)
            return false
        }
    }
    
    /**
     * Cancel a pending receive operation (static API).
     * 
     * @param correlationId correlation ID to cancel
     * @return true if receiver was found and cancelled
     */
    static boolean cancelReceiveStatic(String correlationId) {
        def entry = PENDING_RECEIVES.remove(correlationId)
        if (entry) {
            log.info("WebhookReceiver: cancelled receive for correlation ID: $correlationId")
            return true
        }
        return false
    }
    
    /**
     * Get count of pending receives (static API).
     */
    static int getPendingCountStatic() {
        return PENDING_RECEIVES.size()
    }
    
    /**
     * Clear all pending receives (static API - for testing).
     */
    static void clearAllPendingStatic() {
        log.warn("WebhookReceiver: clearing ${PENDING_RECEIVES.size()} pending receives")
        PENDING_RECEIVES.clear()
    }
    
    // =========================================================================
    // Timeout Handling
    // =========================================================================
    
    private void scheduleTimeout(String correlationId, ReceiveConfig config) {
        log.debug("WebhookReceiver: scheduling timeout for $correlationId: ${config.timeout.toMillis()}ms")
        
        timeoutTimer.schedule(new JTimerTask() {
            @Override
            void run() {
                handleTimeout(correlationId, config)
            }
        }, config.timeout.toMillis())
    }
    
    private void handleTimeout(String correlationId, ReceiveConfig config) {
        log.warn("WebhookReceiver: timeout reached for correlation ID: $correlationId")
        
        def entry = PENDING_RECEIVES.remove(correlationId)
        
        if (entry) {
            if (config.autoAction) {
                try {
                    def timeoutResult = config.autoAction.call()
                    def result = timeoutResult instanceof Map ? timeoutResult : [data: timeoutResult]
                    result.timeout = true
                    result.correlationId = correlationId
                    
                    log.info("WebhookReceiver: applying auto-action on timeout for $correlationId")
                    entry.promise.accept(result)
                    
                } catch (Exception e) {
                    log.error("WebhookReceiver: error in auto-action for $correlationId", e)
                    entry.promise.reject(new ReceiveTask.ReceiveTimeoutException(
                        "Timeout and auto-action failed: ${e.message}",
                        correlationId
                    ))
                }
            } else {
                // No auto-action - reject with timeout exception
                entry.promise.reject(new ReceiveTask.ReceiveTimeoutException(
                    "Timeout waiting for message",
                    correlationId
                ))
            }
        }
    }
}
