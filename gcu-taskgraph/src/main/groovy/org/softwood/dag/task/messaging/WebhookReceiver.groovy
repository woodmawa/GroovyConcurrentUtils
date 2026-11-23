package org.softwood.dag.task.messaging

import groovy.util.logging.Slf4j
import org.softwood.promise.Promise
import org.softwood.dag.task.ReceiveTask
import java.util.concurrent.ConcurrentHashMap
import java.util.Timer
import java.util.TimerTask as JTimerTask
import java.time.Instant

/**
 * Webhook-based message receiver with production-grade features.
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
 *   <li><strong>NEW:</strong> Dead Letter Queue support</li>
 *   <li><strong>NEW:</strong> Retry logic with exponential backoff</li>
 *   <li><strong>NEW:</strong> Persistence support (interface)</li>
 * </ul>
 * 
 * <h3>Basic Usage:</h3>
 * <pre>
 * receiveTask("wait-callback") {
 *     receiver new WebhookReceiver()
 *     correlationKey { prev -> prev.txnId }
 *     timeout Duration.ofMinutes(5)
 * }
 * </pre>
 * 
 * <h3>Production Usage with DLQ:</h3>
 * <pre>
 * receiveTask("production-callback") {
 *     receiver new WebhookReceiver()
 *     correlationKey { prev -> prev.orderId }
 *     
 *     filter { msg -> msg.status in ['SUCCESS', 'FAILED'] }
 *     authenticate { msg -> verifySignature(msg) }
 *     
 *     timeout Duration.ofMinutes(10)
 *     deadLetterQueue "failed-receives"  // NEW: DLQ support
 *     
 *     maxRetries 3  // NEW: Retry support
 *     retryDelay Duration.ofSeconds(10)
 * }
 * </pre>
 * 
 * @since 2.0.0 (enhanced in 2.1.0 with DLQ and retry)
 */
@Slf4j
class WebhookReceiver implements IMessageReceiver {
    
    /** Global registry of waiting receivers */
    private static final Map<String, ReceiverEntry> PENDING_RECEIVES = new ConcurrentHashMap<>()
    
    /** Timer for handling timeouts */
    private final Timer timeoutTimer = new Timer("WebhookReceiver-Timeout", true)
    
    /** Optional DLQ producer for failed receives */
    private IMessageProducer dlqProducer
    
    /** Optional storage for persistence */
    private IReceiveStorage storage
    
    /**
     * Internal entry tracking a registered receiver.
     */
    private static class ReceiverEntry {
        String correlationId
        Promise promise
        ReceiveConfig config
        long registeredAt
        int retryCount = 0
        
        Map<String, Object> toMap() {
            return [
                correlationId: correlationId,
                registeredAt: registeredAt,
                retryCount: retryCount,
                hasTimeout: config.timeout != null,
                hasDLQ: config.deadLetterQueue != null,
                maxRetries: config.maxRetries
            ]
        }
    }
    
    // =========================================================================
    // Configuration
    // =========================================================================
    
    /**
     * Set DLQ producer for failed receives.
     * 
     * @param producer message producer (typically InMemoryProducer or KafkaProducer)
     */
    void setDlqProducer(IMessageProducer producer) {
        this.dlqProducer = producer
        log.info("WebhookReceiver: DLQ producer configured (${producer.producerType})")
    }
    
    /**
     * Set persistence storage for durable receives.
     * 
     * @param storage persistence implementation
     */
    void setStorage(IReceiveStorage storage) {
        this.storage = storage
        log.info("WebhookReceiver: persistence storage configured")
    }
    
    // =========================================================================
    // IMessageReceiver Implementation
    // =========================================================================
    
    @Override
    void initialize() {
        log.debug("WebhookReceiver initialized")
        
        // Restore pending receives from storage if available
        if (storage) {
            restorePendingReceives()
        }
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
        
        // Persist if configured
        if (config.persist && storage) {
            persistReceiveEntry(correlationId, entry)
        }
        
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
            
            // Remove from storage if persisted
            if (entry.config.persist && storage) {
                deletePersistedReceive(correlationId, entry)
            }
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
            // Process message with retry logic
            def processedMessage = processMessageWithRetry(correlationId, message, entry)
            
            if (processedMessage != null) {
                // Success - deliver to receiver
                log.info("WebhookReceiver: delivering message for correlation ID: $correlationId")
                entry.promise.accept(processedMessage)
                PENDING_RECEIVES.remove(correlationId)
                
                // Remove from storage if persisted
                if (entry.config.persist && storage) {
                    deletePersistedReceive(correlationId, entry)
                }
                
                return true
            } else {
                // Message was rejected by filter/auth
                return false
            }
            
        } catch (Exception e) {
            log.error("WebhookReceiver: error delivering message for $correlationId (after ${entry.retryCount} retries)", e)
            
            // Send to DLQ if configured
            sendToDeadLetterQueue(
                correlationId,
                "DELIVERY_FAILED",
                entry,
                message,
                e
            )
            
            // Reject promise
            entry.promise.reject(e)
            PENDING_RECEIVES.remove(correlationId)
            
            // Remove from storage if persisted
            if (entry.config.persist && storage) {
                deletePersistedReceive(correlationId, entry)
            }
            
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
    // Message Processing with Retry Logic
    // =========================================================================
    
    /**
     * Process message with retry logic for transient failures.
     * 
     * @return processed message data, or null if rejected by filter/auth
     * @throws Exception if all retries exhausted
     */
    private Object processMessageWithRetry(String correlationId, Object message, ReceiverEntry entry) {
        int maxRetries = entry.config.maxRetries
        long retryDelayMs = entry.config.retryDelay?.toMillis() ?: 30000L
        
        Exception lastError = null
        
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                entry.retryCount = attempt
                
                // Apply authentication if configured
                if (entry.config.authenticator) {
                    boolean authenticated = entry.config.authenticator.call(message)
                    if (!authenticated) {
                        log.warn("WebhookReceiver: authentication failed for correlation ID: $correlationId")
                        
                        // Send to DLQ if configured
                        sendToDeadLetterQueue(
                            correlationId,
                            "AUTH_FAILED",
                            entry,
                            message,
                            null
                        )
                        
                        return null  // Not an error - message rejected
                    }
                }
                
                // Apply filter if configured
                if (entry.config.filter) {
                    boolean accepted = entry.config.filter.call(message)
                    if (!accepted) {
                        log.debug("WebhookReceiver: message filtered out for correlation ID: $correlationId")
                        
                        // Optionally send filtered messages to DLQ
                        // (commented out by default as filters are expected behavior)
                        // sendToDeadLetterQueue(correlationId, "FILTERED", entry, message, null)
                        
                        return null  // Not an error - message rejected
                    }
                }
                
                // Wrap message in standard format
                def messageData = message instanceof Map ? message : [data: message]
                messageData.receivedAt = System.currentTimeMillis()
                messageData.correlationId = correlationId
                messageData.retryCount = attempt
                
                // Apply extractor if configured
                if (entry.config.extractor) {
                    messageData = entry.config.extractor.call(messageData)
                }
                
                // Success!
                return messageData
                
            } catch (Exception e) {
                lastError = e
                
                if (attempt < maxRetries) {
                    // Calculate backoff (exponential with jitter)
                    long backoffMs = (long) (retryDelayMs * Math.pow(1.5, attempt))
                    long jitter = (long) (backoffMs * 0.1 * Math.random())
                    long sleepMs = backoffMs + jitter
                    
                    log.warn("WebhookReceiver: retry ${attempt + 1}/${maxRetries} for $correlationId after ${sleepMs}ms (error: ${e.message})")
                    
                    try {
                        Thread.sleep(sleepMs)
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt()
                        throw new RuntimeException("Interrupted during retry delay", ie)
                    }
                } else {
                    log.error("WebhookReceiver: all retries exhausted for $correlationId", e)
                }
            }
        }
        
        throw lastError  // All retries exhausted
    }
    
    // =========================================================================
    // Dead Letter Queue Support
    // =========================================================================
    
    /**
     * Send failed receive to Dead Letter Queue.
     * 
     * @param correlationId correlation ID
     * @param reason failure reason
     * @param entry receiver entry
     * @param message original message (may be null for timeout)
     * @param error exception (may be null for auth/filter failures)
     */
    private void sendToDeadLetterQueue(
        String correlationId,
        String reason,
        ReceiverEntry entry,
        Object message,
        Exception error
    ) {
        // Check if DLQ is configured
        if (!entry.config.deadLetterQueue) {
            log.debug("WebhookReceiver: no DLQ configured for $correlationId, skipping")
            return
        }
        
        if (!dlqProducer) {
            log.warn("WebhookReceiver: DLQ destination configured but no producer set for $correlationId")
            return
        }
        
        try {
            def dlqMessage = [
                correlationId: correlationId,
                reason: reason,
                timestamp: Instant.now().toString(),
                registeredAt: new Date(entry.registeredAt).toString(),
                retryCount: entry.retryCount,
                config: entry.config.toMap(),
                message: message,
                error: error ? [
                    type: error.class.simpleName,
                    message: error.message,
                    stackTrace: error.stackTrace.take(5).collect { it.toString() }
                ] : null
            ]
            
            dlqProducer.send([
                destination: entry.config.deadLetterQueue,
                message: dlqMessage,
                headers: [
                    'X-DLQ-Reason': reason,
                    'X-Correlation-Id': correlationId,
                    'X-Retry-Count': entry.retryCount.toString()
                ]
            ])
            
            log.info("WebhookReceiver: sent to DLQ '${entry.config.deadLetterQueue}' for $correlationId (reason: $reason)")
            
        } catch (Exception dlqError) {
            log.error("WebhookReceiver: failed to send to DLQ for $correlationId", dlqError)
            // Don't throw - DLQ failure shouldn't break the main flow
        }
    }
    
    // =========================================================================
    // Persistence Support
    // =========================================================================
    
    /**
     * Persist receive entry to storage.
     */
    private void persistReceiveEntry(String correlationId, ReceiverEntry entry) {
        try {
            def key = entry.config.storageKey ?: "receive:$correlationId"
            
            def data = [
                correlationId: correlationId,
                registeredAt: entry.registeredAt,
                config: entry.config.toMap()
            ]
            
            storage.save(key, data)
            log.debug("WebhookReceiver: persisted receive entry for $correlationId")
            
        } catch (Exception e) {
            log.error("WebhookReceiver: failed to persist receive entry for $correlationId", e)
            // Don't throw - persistence failure shouldn't break registration
        }
    }
    
    /**
     * Delete persisted receive entry from storage.
     */
    private void deletePersistedReceive(String correlationId, ReceiverEntry entry) {
        try {
            def key = entry.config.storageKey ?: "receive:$correlationId"
            storage.delete(key)
            log.debug("WebhookReceiver: deleted persisted receive for $correlationId")
            
        } catch (Exception e) {
            log.error("WebhookReceiver: failed to delete persisted receive for $correlationId", e)
            // Don't throw - cleanup failure is not critical
        }
    }
    
    /**
     * Restore pending receives from storage on startup.
     */
    private void restorePendingReceives() {
        try {
            def keys = storage.listKeys("receive:*")
            log.info("WebhookReceiver: found ${keys.size()} persisted receives to restore")
            
            // Note: We can't fully restore Promise objects after restart
            // This is a limitation - promises are not serializable
            // A full implementation would require a message pump/poller
            
            log.warn("WebhookReceiver: persistence restore not fully implemented (promises not serializable)")
            
        } catch (Exception e) {
            log.error("WebhookReceiver: failed to restore pending receives", e)
        }
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
        
        // Create temporary receiver instance for delivery logic
        def receiver = new WebhookReceiver()
        return receiver.deliverMessage(correlationId, message)
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
            // Send to DLQ if configured
            sendToDeadLetterQueue(
                correlationId,
                "TIMEOUT",
                entry,
                null,  // No message received
                null   // No exception
            )
            
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
            
            // Remove from storage if persisted
            if (entry.config.persist && storage) {
                deletePersistedReceive(correlationId, entry)
            }
        }
    }
}
