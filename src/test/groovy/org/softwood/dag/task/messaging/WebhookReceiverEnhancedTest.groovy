package org.softwood.dag.task.messaging

import org.softwood.promise.Promise
import org.softwood.promise.Promises
import spock.lang.Specification
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Tests for enhanced WebhookReceiver with DLQ, retry, and persistence.
 */
class WebhookReceiverEnhancedTest extends Specification {
    
    WebhookReceiver receiver
    InMemoryProducer dlqProducer
    InMemoryReceiveStorage storage
    
    def setup() {
        receiver = new WebhookReceiver()
        dlqProducer = new InMemoryProducer()
        storage = new InMemoryReceiveStorage()
        
        receiver.dlqProducer = dlqProducer
        receiver.storage = storage
        receiver.initialize()
        
        // Clear static registry
        WebhookReceiver.clearAllPendingStatic()
    }
    
    def cleanup() {
        receiver.shutdown()
        storage.close()
    }
    
    // =========================================================================
    // Dead Letter Queue Tests
    // =========================================================================
    
    def "should send timeout to DLQ when configured"() {
        given:
        def promise = Promises.createPromise()
        def config = new ReceiveConfig(
            timeout: Duration.ofMillis(100),
            deadLetterQueue: "failed-receives"
        )
        
        when:
        receiver.register("test-123", promise, config)
        Thread.sleep(200)  // Wait for timeout
        
        then:
        def messages = dlqProducer.getMessages("failed-receives")
        messages.size() == 1
        messages[0].reason == "TIMEOUT"
        messages[0].correlationId == "test-123"
    }
    
    def "should send auth failure to DLQ"() {
        given:
        def promise = Promises.createPromise()
        def config = new ReceiveConfig(
            authenticator: { msg -> false },  // Always reject
            deadLetterQueue: "failed-receives"
        )
        
        when:
        receiver.register("test-456", promise, config)
        def delivered = receiver.deliverMessage("test-456", [data: "test"])
        
        then:
        !delivered
        def messages = dlqProducer.getMessages("failed-receives")
        messages.size() == 1
        messages[0].reason == "AUTH_FAILED"
        messages[0].correlationId == "test-456"
    }
    
    def "should send extractor failure to DLQ after retries"() {
        given:
        def promise = Promises.createPromise()
        def attemptCount = 0
        def config = new ReceiveConfig(
            extractor: { msg ->
                attemptCount++
                throw new RuntimeException("Extractor error")
            },
            maxRetries: 2,
            retryDelay: Duration.ofMillis(50),
            deadLetterQueue: "failed-receives"
        )
        
        when:
        receiver.register("test-789", promise, config)
        receiver.deliverMessage("test-789", [data: "test"])
        
        then:
        attemptCount == 3  // Initial + 2 retries
        def messages = dlqProducer.getMessages("failed-receives")
        messages.size() == 1
        messages[0].reason == "DELIVERY_FAILED"
        messages[0].retryCount == 2
    }
    
    def "should not send to DLQ when not configured"() {
        given:
        def promise = Promises.createPromise()
        def config = new ReceiveConfig(
            timeout: Duration.ofMillis(100)
            // No deadLetterQueue configured
        )
        
        when:
        receiver.register("test-no-dlq", promise, config)
        Thread.sleep(200)
        
        then:
        dlqProducer.getAllMessages().isEmpty()
    }
    
    // =========================================================================
    // Retry Logic Tests
    // =========================================================================
    
    def "should retry extractor on transient failure"() {
        given:
        def promise = Promises.createPromise()
        def attemptCount = 0
        def config = new ReceiveConfig(
            extractor: { msg ->
                attemptCount++
                if (attemptCount < 3) {
                    throw new RuntimeException("Transient error")
                }
                return [success: true, attempts: attemptCount]
            },
            maxRetries: 3,
            retryDelay: Duration.ofMillis(50)
        )
        
        when:
        receiver.register("test-retry", promise, config)
        receiver.deliverMessage("test-retry", [data: "test"])
        def result = promise.get(1, TimeUnit.SECONDS)
        
        then:
        result.success
        result.attempts == 3
        attemptCount == 3
    }
    
    def "should apply exponential backoff on retry"() {
        given:
        def promise = Promises.createPromise()
        def retryTimes = []
        def lastTime = System.currentTimeMillis()
        
        def config = new ReceiveConfig(
            extractor: { msg ->
                def now = System.currentTimeMillis()
                if (lastTime > 0) {
                    retryTimes << (now - lastTime)
                }
                lastTime = now
                
                if (retryTimes.size() < 2) {
                    throw new RuntimeException("Retry")
                }
                return [success: true]
            },
            maxRetries: 2,
            retryDelay: Duration.ofMillis(100)
        )
        
        when:
        receiver.register("test-backoff", promise, config)
        receiver.deliverMessage("test-backoff", [data: "test"])
        promise.get(2, TimeUnit.SECONDS)
        
        then:
        retryTimes.size() == 2
        // Second retry should take longer (exponential backoff)
        retryTimes[1] > retryTimes[0]
    }
    
    def "should exhaust retries and fail"() {
        given:
        def promise = Promises.createPromise()
        def attemptCount = 0
        def config = new ReceiveConfig(
            extractor: { msg ->
                attemptCount++
                throw new RuntimeException("Always fails")
            },
            maxRetries: 2,
            retryDelay: Duration.ofMillis(50)
        )
        
        when:
        receiver.register("test-exhaust", promise, config)
        receiver.deliverMessage("test-exhaust", [data: "test"])
        promise.get(1, TimeUnit.SECONDS)
        
        then:
        thrown(Exception)
        attemptCount == 3  // Initial + 2 retries
    }
    
    def "should not retry filter rejection"() {
        given:
        def promise = Promises.createPromise()
        def attemptCount = 0
        def config = new ReceiveConfig(
            filter: { msg ->
                attemptCount++
                return false  // Always reject
            },
            maxRetries: 3
        )
        
        when:
        receiver.register("test-no-filter-retry", promise, config)
        def delivered = receiver.deliverMessage("test-no-filter-retry", [data: "test"])
        
        then:
        !delivered
        attemptCount == 1  // No retries for filter rejection
    }
    
    // =========================================================================
    // Persistence Tests
    // =========================================================================
    
    def "should persist receive entry when configured"() {
        given:
        def promise = Promises.createPromise()
        def config = new ReceiveConfig(
            persist: true,
            timeout: Duration.ofMinutes(5)
        )
        
        when:
        receiver.register("test-persist", promise, config)
        
        then:
        storage.exists("receive:test-persist")
        def data = storage.get("receive:test-persist")
        data.correlationId == "test-persist"
    }
    
    def "should delete persisted entry on successful delivery"() {
        given:
        def promise = Promises.createPromise()
        def config = new ReceiveConfig(
            persist: true
        )
        
        when:
        receiver.register("test-cleanup", promise, config)
        receiver.deliverMessage("test-cleanup", [data: "test"])
        
        then:
        !storage.exists("receive:test-cleanup")
    }
    
    def "should delete persisted entry on timeout"() {
        given:
        def promise = Promises.createPromise()
        def config = new ReceiveConfig(
            persist: true,
            timeout: Duration.ofMillis(100)
        )
        
        when:
        receiver.register("test-timeout-cleanup", promise, config)
        Thread.sleep(200)
        
        then:
        !storage.exists("receive:test-timeout-cleanup")
    }
    
    def "should use custom storage key"() {
        given:
        def promise = Promises.createPromise()
        def config = new ReceiveConfig(
            persist: true,
            storageKey: "custom:my-receive"
        )
        
        when:
        receiver.register("test-custom-key", promise, config)
        
        then:
        storage.exists("custom:my-receive")
        !storage.exists("receive:test-custom-key")
    }
    
    // =========================================================================
    // Integration Tests
    // =========================================================================
    
    def "should handle complete workflow with DLQ and retry"() {
        given:
        def promise = Promises.createPromise()
        def attemptCount = 0
        def config = new ReceiveConfig(
            filter: { msg -> msg.valid == true },
            extractor: { msg ->
                attemptCount++
                if (attemptCount < 2) {
                    throw new RuntimeException("Transient error")
                }
                return [processed: msg.data, attempts: attemptCount]
            },
            maxRetries: 2,
            retryDelay: Duration.ofMillis(50),
            deadLetterQueue: "failed-receives",
            persist: true
        )
        
        when:
        receiver.register("integration-test", promise, config)
        
        // First message - filtered out (not sent to DLQ by default)
        receiver.deliverMessage("integration-test", [valid: false, data: "bad"])
        
        // Second message - succeeds after retry
        receiver.deliverMessage("integration-test", [valid: true, data: "good"])
        def result = promise.get(1, TimeUnit.SECONDS)
        
        then:
        result.processed == "good"
        result.attempts == 2
        !storage.exists("receive:integration-test")  // Cleaned up
    }
    
    def "should track retry count in DLQ message"() {
        given:
        def promise = Promises.createPromise()
        def config = new ReceiveConfig(
            extractor: { msg -> throw new RuntimeException("Fail") },
            maxRetries: 3,
            retryDelay: Duration.ofMillis(20),
            deadLetterQueue: "failed-receives"
        )
        
        when:
        receiver.register("dlq-retry-count", promise, config)
        receiver.deliverMessage("dlq-retry-count", [data: "test"])
        
        then:
        def messages = dlqProducer.getMessages("failed-receives")
        messages[0].retryCount == 3
    }
    
    // =========================================================================
    // Backward Compatibility Tests
    // =========================================================================
    
    def "should work without DLQ producer set"() {
        given:
        def receiverNoDlq = new WebhookReceiver()
        receiverNoDlq.initialize()
        
        def promise = Promises.createPromise()
        def config = new ReceiveConfig(
            timeout: Duration.ofMillis(100),
            deadLetterQueue: "failed-receives"  // Configured but no producer
        )
        
        when:
        receiverNoDlq.register("no-producer", promise, config)
        Thread.sleep(200)
        
        then:
        notThrown(Exception)  // Should not fail
        
        cleanup:
        receiverNoDlq.shutdown()
    }
    
    def "should work without storage set"() {
        given:
        def receiverNoStorage = new WebhookReceiver()
        receiverNoStorage.initialize()
        
        def promise = Promises.createPromise()
        def config = new ReceiveConfig(
            persist: true  // Configured but no storage
        )
        
        when:
        receiverNoStorage.register("no-storage", promise, config)
        
        then:
        notThrown(Exception)  // Should not fail
        
        cleanup:
        receiverNoStorage.shutdown()
    }
    
    def "static API should still work"() {
        given:
        def promise = Promises.createPromise()
        def config = new ReceiveConfig()
        
        when:
        receiver.register("static-api-test", promise, config)
        def delivered = WebhookReceiver.deliverMessageStatic("static-api-test", [data: "test"])
        def result = promise.get(1, TimeUnit.SECONDS)
        
        then:
        delivered
        result.data == "test"
    }
}
