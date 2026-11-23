package org.softwood.dag.task

import groovy.util.logging.Slf4j
import org.softwood.promise.Promise
import org.softwood.dag.task.messaging.IMessageReceiver
import org.softwood.dag.task.messaging.WebhookReceiver
import org.softwood.dag.task.messaging.ReceiveConfig

import java.time.Duration

/**
 * ReceiveTask - Wait for External Messages/Events
 *
 * Pauses workflow execution until an external message/event is received.
 * Used for async request/response patterns, webhooks, and message queues.
 *
 * <h3>Key Features:</h3>
 * <ul>
 *   <li>Webhook reception (via correlation ID)</li>
 *   <li>Message queue consumption (extensible via receivers)</li>
 *   <li>Async API response handling</li>
 *   <li>Correlation-based message matching</li>
 *   <li>Timeout with auto-action</li>
 *   <li>Message filtering</li>
 *   <li>Data extraction/transformation</li>
 *   <li>Authentication/authorization</li>
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
 * <h3>DSL Example - Advanced:</h3>
 * <pre>
 * receiveTask("wait-approval") {
 *     // Optional: specify receiver (defaults to WebhookReceiver)
 *     receiver new WebhookReceiver()
 *     
 *     correlationKey { prev -> prev.requestId }
 *     
 *     // Filter messages
 *     filter { message ->
 *         message.status == "APPROVED" && message.amount > 0
 *     }
 *     
 *     // Transform received data
 *     extract { message ->
 *         [
 *             approved: true,
 *             approvedBy: message.approver,
 *             approvedAt: message.timestamp,
 *             processed: true
 *         ]
 *     }
 *     
 *     // Verify webhook signature
 *     authenticate { message ->
 *         verifySignature(message.headers['X-Signature'])
 *     }
 *     
 *     timeout Duration.ofHours(24)
 * }
 * </pre>
 *
 * <h3>Programmatic Message Delivery:</h3>
 * <pre>
 * // External system delivers message (backward compatible)
 * ReceiveTask.deliverMessage("correlation-123", [
 *     status: "SUCCESS",
 *     data: [...]
 * ])
 * 
 * // Or via receiver directly
 * WebhookReceiver.deliverMessageStatic("correlation-123", data)
 * </pre>
 * 
 * @since 1.0.0 (enhanced with provider pattern in 2.0.0)
 */
@Slf4j
class ReceiveTask extends TaskBase<Map> {

    // =========================================================================
    // Configuration
    // =========================================================================
    
    /** Message receiver (defaults to WebhookReceiver for backward compatibility) */
    private IMessageReceiver receiver = new WebhookReceiver()
    
    /** Correlation key provider - extracts correlation ID from previous value */
    Closure correlationKeyProvider
    
    /** Message filter - returns true if message should be accepted */
    Closure messageFilter
    
    /** Data extractor - transforms received message */
    Closure dataExtractor
    
    /** Authentication/authorization check */
    Closure authenticator
    
    /** Receive timeout */
    Duration receiveTimeout
    
    /** Auto-action on timeout */
    Closure autoAction
    
    // =========================================================================
    // Internal State
    // =========================================================================
    
    /** Default receiver instance for static API backward compatibility */
    private static final WebhookReceiver DEFAULT_RECEIVER = new WebhookReceiver()
    
    static {
        DEFAULT_RECEIVER.initialize()
    }

    ReceiveTask(String id, String name, TaskContext ctx) {
        super(id, name, ctx)
    }

    // =========================================================================
    // DSL Methods
    // =========================================================================
    
    /**
     * Set the message receiver.
     * Defaults to WebhookReceiver if not specified.
     * 
     * @param receiver the receiver implementation
     */
    void receiver(IMessageReceiver receiver) {
        this.receiver = receiver
        this.receiver.initialize()
    }
    
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
     * 
     * @param filterClosure closure that returns true to accept, false to reject
     */
    void filter(Closure filterClosure) {
        this.messageFilter = filterClosure
    }
    
    /**
     * Set data extractor.
     * Transforms the received message before returning.
     * 
     * @param extractor closure that transforms message data
     */
    void extract(Closure extractor) {
        this.dataExtractor = extractor
    }
    
    /**
     * Set authentication check.
     * Verifies message authenticity (e.g., webhook signature).
     * 
     * @param authenticator closure that returns true if authenticated
     */
    void authenticate(Closure authenticator) {
        this.authenticator = authenticator
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
        def receivePromise = ctx.promiseFactory.createPromise()
        
        // Build receive configuration
        def config = new ReceiveConfig(
            timeout: receiveTimeout,
            filter: messageFilter,
            extractor: dataExtractor,
            authenticator: authenticator,
            autoAction: autoAction
        )
        
        // Register with receiver
        receiver.register(correlationId, receivePromise, config)
        
        log.info("ReceiveTask($id): registered for correlation ID '$correlationId' " +
                 "(receiver: ${receiver.receiverType}, timeout: ${receiveTimeout ?: 'none'})")
        
        return receivePromise
    }

    // =========================================================================
    // Static API (Backward Compatibility)
    // =========================================================================
    
    /**
     * Deliver a message to a waiting ReceiveTask.
     * Called by external systems (webhooks, message handlers, etc.)
     * 
     * <p>This static method delegates to the default WebhookReceiver
     * for backward compatibility.</p>
     *
     * @param correlationId the correlation ID to match
     * @param message the message payload
     * @return true if a receiver was found and notified
     */
    static boolean deliverMessage(String correlationId, Object message) {
        return WebhookReceiver.deliverMessageStatic(correlationId, message)
    }
    
    /**
     * Cancel a pending receive operation.
     * 
     * @param correlationId correlation ID to cancel
     * @return true if receiver was found and cancelled
     */
    static boolean cancelReceive(String correlationId) {
        return WebhookReceiver.cancelReceiveStatic(correlationId)
    }
    
    /**
     * Get count of pending receives (for monitoring).
     * 
     * @return number of pending receivers
     */
    static int getPendingCount() {
        return WebhookReceiver.getPendingCountStatic()
    }
    
    /**
     * Clear all pending receives (for testing/cleanup).
     */
    static void clearAllPending() {
        WebhookReceiver.clearAllPendingStatic()
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
