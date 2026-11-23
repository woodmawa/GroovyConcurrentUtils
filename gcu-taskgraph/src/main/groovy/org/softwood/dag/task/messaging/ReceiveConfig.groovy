package org.softwood.dag.task.messaging

import groovy.transform.CompileStatic
import java.time.Duration

/**
 * Configuration for message reception behavior.
 * 
 * <p>Encapsulates all settings for how a receiver should handle incoming messages,
 * including timeouts, filtering, authentication, and persistence options.</p>
 * 
 * <h3>Basic Example:</h3>
 * <pre>
 * def config = new ReceiveConfig(
 *     timeout: Duration.ofMinutes(5),
 *     filter: { msg -> msg.status == "SUCCESS" },
 *     autoAction: { [status: "TIMEOUT"] }
 * )
 * </pre>
 * 
 * <h3>Advanced Example:</h3>
 * <pre>
 * def config = new ReceiveConfig(
 *     timeout: Duration.ofMinutes(10),
 *     filter: { msg -> msg.valid && msg.amount > 0 },
 *     extractor: { msg -> [result: msg.data, processed: true] },
 *     authenticator: { msg -> verifySignature(msg.signature) },
 *     autoAction: { [status: "TIMEOUT", retryable: true] },
 *     persist: true,
 *     deadLetterQueue: "failed-receives",
 *     maxRetries: 3,
 *     retryDelay: Duration.ofSeconds(30)
 * )
 * </pre>
 * 
 * @since 2.0.0
 */
@CompileStatic
class ReceiveConfig {
    
    /** 
     * Receive timeout duration.
     * If null, receiver will wait indefinitely until message arrives.
     */
    Duration timeout
    
    /** 
     * Message filter predicate (returns true to accept, false to reject).
     * 
     * <p>Example:</p>
     * <pre>
     * filter = { msg -> 
     *     msg.status == "SUCCESS" && msg.amount > 0 
     * }
     * </pre>
     * 
     * <p>Filtered messages are logged and ignored. The receiver continues waiting
     * for a matching message until timeout.</p>
     */
    Closure<Boolean> filter
    
    /** 
     * Data extractor (transforms received message before resolving promise).
     * 
     * <p>Example:</p>
     * <pre>
     * extractor = { msg ->
     *     [
     *         userId: msg.user.id,
     *         userName: msg.user.name,
     *         processed: true,
     *         timestamp: System.currentTimeMillis()
     *     ]
     * }
     * </pre>
     * 
     * <p>Receives the raw message and returns transformed data.
     * The promise is resolved with the extracted data, not the raw message.</p>
     */
    Closure extractor
    
    /** 
     * Authentication/authorization check (returns true if authenticated).
     * 
     * <p>Example - Webhook signature verification:</p>
     * <pre>
     * authenticator = { msg ->
     *     def signature = msg.headers?['X-Webhook-Signature']
     *     def computed = computeHmac(msg.body, secretKey)
     *     return signature == computed
     * }
     * </pre>
     * 
     * <p>If authentication fails, the message is rejected and logged as unauthorized.
     * The receiver continues waiting for an authenticated message.</p>
     */
    Closure<Boolean> authenticator
    
    /** 
     * Auto-action on timeout (returns fallback data).
     * 
     * <p>Example:</p>
     * <pre>
     * autoAction = {
     *     [
     *         status: "TIMEOUT",
     *         reason: "No response within timeout period",
     *         retryable: true,
     *         timestamp: System.currentTimeMillis()
     *     ]
     * }
     * </pre>
     * 
     * <p>If null, timeout will reject the promise with ReceiveTimeoutException.
     * If provided, timeout will resolve the promise with the auto-action result.</p>
     */
    Closure autoAction
    
    /** 
     * Enable message persistence (store pending receives to survive restarts).
     * 
     * <p>When true, pending receivers and their configuration are persisted
     * to external storage (e.g., Redis, database). This allows workflows to
     * survive application restarts.</p>
     * 
     * <p><strong>Note:</strong> Persistence implementation is provider-specific
     * and may not be supported by all receivers.</p>
     */
    boolean persist = false
    
    /** 
     * Storage key for persistence (optional, generated if not provided).
     * 
     * <p>Used as the key when persisting receiver state. If null, a key
     * will be generated from the correlation ID.</p>
     */
    String storageKey
    
    /** 
     * Dead letter queue destination (optional).
     * 
     * <p>When specified, messages that timeout or fail processing are
     * sent to this destination for later analysis or retry.</p>
     * 
     * <p>Example:</p>
     * <pre>
     * deadLetterQueue = "failed-receives"
     * </pre>
     */
    String deadLetterQueue
    
    /** 
     * Maximum retry attempts for message processing (default: 0 = no retries).
     * 
     * <p>If message processing (filter, extractor, authenticator) throws an exception,
     * the receiver can retry up to maxRetries times before giving up.</p>
     */
    int maxRetries = 0
    
    /** 
     * Retry delay duration (default: 30 seconds).
     * 
     * <p>Time to wait between retry attempts when message processing fails.</p>
     */
    Duration retryDelay = Duration.ofSeconds(30)
    
    /**
     * Convert this configuration to a map (for debugging/logging).
     * 
     * @return map representation
     */
    Map<String, Object> toMap() {
        return [
            timeout: timeout?.toString(),
            hasFilter: filter != null,
            hasExtractor: extractor != null,
            hasAuthenticator: authenticator != null,
            hasAutoAction: autoAction != null,
            persist: persist,
            storageKey: storageKey,
            deadLetterQueue: deadLetterQueue,
            maxRetries: maxRetries,
            retryDelay: retryDelay?.toString()
        ]
    }
    
    @Override
    String toString() {
        return "ReceiveConfig${toMap()}"
    }
}
