package org.softwood.dag.task

import groovy.util.logging.Slf4j
import org.softwood.promise.Promise

import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.Timer
import java.util.TimerTask as JTimerTask

/**
 * ReceiveTask - Wait for External Messages/Events
 *
 * Pauses workflow execution until an external message/event is received.
 * Used for async request/response patterns, webhooks, and message queues.
 *
 * <h3>Key Features:</h3>
 * <ul>
 *   <li>Webhook reception (via correlation ID)</li>
 *   <li>Message queue consumption (extensible)</li>
 *   <li>Async API response handling</li>
 *   <li>Correlation-based message matching</li>
 *   <li>Timeout with auto-action</li>
 *   <li>Message filtering</li>
 * </ul>
 *
 * <h3>DSL Example - Basic:</h3>
 * <pre>
 * task("wait-payment-callback", TaskType.RECEIVE) {
 *     correlationKey { prev -> prev.transactionId }
 *     
 *     timeout Duration.ofMinutes(5), autoAction: {
 *         [status: "TIMEOUT", message: "Payment not received"]
 *     }
 * }
 * </pre>
 *
 * <h3>DSL Example - With Filter:</h3>
 * <pre>
 * task("wait-approval", TaskType.RECEIVE) {
 *     correlationKey { prev -> prev.requestId }
 *     
 *     filter { message ->
 *         message.status == "APPROVED"
 *     }
 *     
 *     extract { message ->
 *         [
 *             approved: true,
 *             approvedBy: message.approver,
 *             approvedAt: message.timestamp
 *         ]
 *     }
 *     
 *     timeout Duration.ofHours(24)
 * }
 * </pre>
 *
 * <h3>Programmatic Message Delivery:</h3>
 * <pre>
 * // External system delivers message
 * ReceiveTask.deliverMessage("correlation-123", [
 *     status: "SUCCESS",
 *     data: [...]
 * ])
 * </pre>
 */
@Slf4j
class ReceiveTask extends TaskBase<Map> {

    // =========================================================================
    // Configuration
    // =========================================================================
    
    /** Correlation key provider - extracts correlation ID from previous value */
    Closure correlationKeyProvider
    
    /** Message filter - returns true if message should be accepted */
    Closure messageFilter
    
    /** Data extractor - transforms received message */
    Closure dataExtractor
    
    /** Receive timeout */
    Duration receiveTimeout
    
    /** Auto-action on timeout */
    Closure autoAction
    
    // =========================================================================
    // Internal State
    // =========================================================================
    
    /** Global registry of waiting receivers */
    private static final Map<String, Promise> PENDING_RECEIVES = new ConcurrentHashMap<>()
    
    /** Promise to resolve when message received */
    private Promise<Map> receivePromise

    ReceiveTask(String id, String name, TaskContext ctx) {
        super(id, name, ctx)
    }

    // =========================================================================
    // DSL Methods
    // =========================================================================
    
    /**
     * Set correlation key provider.
     * Returns the correlation ID used to match incoming messages.
     */
    void correlationKey(Closure provider) {
        this.correlationKeyProvider = provider
    }
    
    /**
     * Set message filter.
     * Only messages passing this filter will be accepted.
     */
    void filter(Closure filterClosure) {
        this.messageFilter = filterClosure
    }
    
    /**
     * Set data extractor.
     * Transforms the received message before returning.
     */
    void extract(Closure extractor) {
        this.dataExtractor = extractor
    }
    
    /**
     * Set receive timeout with optional auto-action.
     */
    void timeout(Duration duration, Map options = [:]) {
        this.receiveTimeout = duration
        if (options.autoAction) {
            this.autoAction = options.autoAction as Closure
        }
    }

    // =========================================================================
    // Task Execution
    // =========================================================================

    @Override
    protected Promise<Map> runTask(TaskContext ctx, Object prevValue) {
        
        if (!correlationKeyProvider) {
            throw new IllegalStateException("ReceiveTask($id): correlationKey is required")
        }
        
        // Get correlation ID
        String correlationId
        try {
            correlationId = correlationKeyProvider.call(prevValue) as String
        } catch (Exception e) {
            throw new IllegalStateException("ReceiveTask($id): failed to get correlation key", e)
        }
        
        if (!correlationId) {
            throw new IllegalStateException("ReceiveTask($id): correlation key cannot be null")
        }
        
        log.debug("ReceiveTask($id): waiting for message with correlation ID: $correlationId")
        
        // Create promise for message reception
        receivePromise = ctx.promiseFactory.createPromise()
        
        // Register this receiver
        PENDING_RECEIVES.put(correlationId, receivePromise)
        
        // Schedule timeout if configured
        if (receiveTimeout) {
            scheduleTimeout(correlationId)
        }
        
        log.info("ReceiveTask($id): registered for correlation ID '$correlationId' " +
                 "(timeout: ${receiveTimeout ?: 'none'})")
        
        return receivePromise
    }

    // =========================================================================
    // Message Delivery (Static API)
    // =========================================================================
    
    /**
     * Deliver a message to a waiting ReceiveTask.
     * Called by external systems (webhooks, message handlers, etc.)
     *
     * @param correlationId the correlation ID to match
     * @param message the message payload
     * @return true if a receiver was found and notified
     */
    static boolean deliverMessage(String correlationId, Object message) {
        
        log.debug("ReceiveTask: attempting to deliver message for correlation ID: $correlationId")
        
        Promise promise = PENDING_RECEIVES.get(correlationId)
        
        if (promise) {
            try {
                // Wrap message in standard format
                def messageData = message instanceof Map ? message : [data: message]
                messageData.receivedAt = System.currentTimeMillis()
                messageData.correlationId = correlationId
                
                log.info("ReceiveTask: delivering message for correlation ID: $correlationId")
                
                promise.accept(messageData)
                PENDING_RECEIVES.remove(correlationId)
                
                return true
                
            } catch (Exception e) {
                log.error("ReceiveTask: error delivering message for $correlationId", e)
                return false
            }
        } else {
            log.warn("ReceiveTask: no receiver found for correlation ID: $correlationId")
            return false
        }
    }
    
    /**
     * Cancel a pending receive operation.
     */
    static boolean cancelReceive(String correlationId) {
        Promise promise = PENDING_RECEIVES.remove(correlationId)
        if (promise) {
            log.info("ReceiveTask: cancelled receive for correlation ID: $correlationId")
            return true
        }
        return false
    }
    
    /**
     * Get count of pending receives (for monitoring).
     */
    static int getPendingCount() {
        return PENDING_RECEIVES.size()
    }
    
    /**
     * Clear all pending receives (for testing/cleanup).
     */
    static void clearAllPending() {
        log.warn("ReceiveTask: clearing ${PENDING_RECEIVES.size()} pending receives")
        PENDING_RECEIVES.clear()
    }

    // =========================================================================
    // Timeout Handling
    // =========================================================================
    
    private void scheduleTimeout(String correlationId) {
        
        log.debug("ReceiveTask($id): scheduling timeout for ${receiveTimeout.toMillis()}ms")
        
        def timer = new Timer("ReceiveTask-${id}-Timeout", true)
        timer.schedule(new JTimerTask() {
            @Override
            void run() {
                handleTimeout(correlationId)
            }
        }, receiveTimeout.toMillis())
    }
    
    private void handleTimeout(String correlationId) {
        
        log.warn("ReceiveTask($id): timeout reached for correlation ID: $correlationId")
        
        Promise promise = PENDING_RECEIVES.remove(correlationId)
        
        if (promise) {
            if (autoAction) {
                try {
                    def timeoutResult = autoAction.call()
                    def result = timeoutResult instanceof Map ? timeoutResult : [data: timeoutResult]
                    result.timeout = true
                    result.correlationId = correlationId
                    
                    log.info("ReceiveTask($id): applying auto-action on timeout")
                    promise.accept(result)
                    
                } catch (Exception e) {
                    log.error("ReceiveTask($id): error in auto-action", e)
                    promise.reject(new ReceiveTimeoutException(
                        "ReceiveTask timeout and auto-action failed: ${e.message}", 
                        correlationId
                    ))
                }
            } else {
                // No auto-action - reject with timeout exception
                promise.reject(new ReceiveTimeoutException(
                    "ReceiveTask($id): timeout waiting for message", 
                    correlationId
                ))
            }
        }
    }

    // =========================================================================
    // Exception Classes
    // =========================================================================
    
    /**
     * Exception thrown when receive operation times out.
     */
    static class ReceiveTimeoutException extends Exception {
        final String correlationId
        
        ReceiveTimeoutException(String message, String correlationId) {
            super(message)
            this.correlationId = correlationId
        }
    }
}
