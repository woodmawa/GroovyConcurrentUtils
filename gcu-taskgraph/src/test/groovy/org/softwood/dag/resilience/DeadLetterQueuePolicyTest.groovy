package org.softwood.dag.resilience

import spock.lang.Specification

import java.time.Duration

/**
 * Tests for DeadLetterQueuePolicy configuration.
 */
class DeadLetterQueuePolicyTest extends Specification {
    
    // =========================================================================
    // Policy Construction
    // =========================================================================
    
    def "test default policy values"() {
        given:
        def policy = new DeadLetterQueuePolicy()
        
        expect:
        policy.maxSize == 1000
        policy.maxAge == null
        policy.autoRetry == false
        policy.maxRetries == 3
        policy.retryDelay == Duration.ofMinutes(5)
        policy.fullQueueBehavior == DeadLetterQueuePolicy.FullQueueBehavior.REMOVE_OLDEST
        policy.retryStrategy == DeadLetterQueuePolicy.RetryStrategy.EXPONENTIAL_BACKOFF
    }
    
    def "test policy construction with map"() {
        given:
        def policy = new DeadLetterQueuePolicy(
            maxSize: 500,
            maxAge: Duration.ofHours(12),
            autoRetry: true,
            maxRetries: 5
        )
        
        expect:
        policy.maxSize == 500
        policy.maxAge == Duration.ofHours(12)
        policy.autoRetry == true
        policy.maxRetries == 5
    }
    
    // =========================================================================
    // Fluent API
    // =========================================================================
    
    def "test fluent configuration"() {
        given:
        def policy = new DeadLetterQueuePolicy()
            .maxSize(200)
            .maxAge(Duration.ofHours(6))
            .autoRetry(true)
            .maxRetries(4)
            .retryDelay(Duration.ofMinutes(10))
        
        expect:
        policy.maxSize == 200
        policy.maxAge == Duration.ofHours(6)
        policy.autoRetry == true
        policy.maxRetries == 4
        policy.retryDelay == Duration.ofMinutes(10)
    }
    
    def "test fluent configuration returns policy"() {
        given:
        def policy = new DeadLetterQueuePolicy()
        
        when:
        def result = policy.maxSize(100)
        
        then:
        result.is(policy)  // Same instance
    }
    
    // =========================================================================
    // Capture Filtering
    // =========================================================================
    
    def "test shouldCapture with no filters returns true"() {
        given:
        def policy = new DeadLetterQueuePolicy()
        
        expect:
        policy.shouldCapture("any-task", new RuntimeException())
    }
    
    def "test shouldCapture with never capture task IDs"() {
        given:
        def policy = new DeadLetterQueuePolicy()
        policy.neverCaptureTask("ignored-task")
        
        expect:
        !policy.shouldCapture("ignored-task", new RuntimeException())
        policy.shouldCapture("other-task", new RuntimeException())
    }
    
    def "test shouldCapture with never capture exceptions"() {
        given:
        def policy = new DeadLetterQueuePolicy()
        policy.neverCapture(IllegalArgumentException)
        
        expect:
        !policy.shouldCapture("any-task", new IllegalArgumentException())
        policy.shouldCapture("any-task", new RuntimeException())
    }
    
    def "test shouldCapture with always capture task IDs"() {
        given:
        def policy = new DeadLetterQueuePolicy()
        policy.alwaysCaptureTask("critical-task")
        policy.neverCapture(RuntimeException)  // Would normally reject
        
        expect:
        policy.shouldCapture("critical-task", new RuntimeException())  // Always wins
        !policy.shouldCapture("other-task", new RuntimeException())
    }
    
    def "test shouldCapture with always capture exceptions"() {
        given:
        def policy = new DeadLetterQueuePolicy()
        policy.alwaysCapture(RuntimeException)
        
        expect:
        policy.shouldCapture("any-task", new RuntimeException())
    }
    
    def "test shouldCapture with custom filter"() {
        given:
        def policy = new DeadLetterQueuePolicy()
        policy.captureWhen { taskId, exception ->
            taskId.startsWith("api-") && exception instanceof IOException
        }
        
        expect:
        policy.shouldCapture("api-call", new IOException())
        !policy.shouldCapture("api-call", new RuntimeException())
        !policy.shouldCapture("other-task", new IOException())
    }
    
    def "test shouldCapture precedence: never > always > custom"() {
        given:
        def policy = new DeadLetterQueuePolicy()
        policy.alwaysCaptureTask("task-1")
        policy.neverCaptureTask("task-1")  // Never wins
        policy.captureWhen { taskId, exception -> true }
        
        expect:
        !policy.shouldCapture("task-1", new RuntimeException())
    }
    
    // =========================================================================
    // Retryable Filtering
    // =========================================================================
    
    def "test isRetryable with no filter returns true"() {
        given:
        def policy = new DeadLetterQueuePolicy()
        
        expect:
        policy.isRetryable(new RuntimeException())
    }
    
    def "test isRetryable with custom filter"() {
        given:
        def policy = new DeadLetterQueuePolicy()
        policy.retryWhen { exception ->
            !(exception instanceof IllegalArgumentException)
        }
        
        expect:
        policy.isRetryable(new RuntimeException())
        !policy.isRetryable(new IllegalArgumentException())
    }
    
    // =========================================================================
    // Callbacks
    // =========================================================================
    
    def "test callback configuration"() {
        given:
        def onEntryAddedCalled = false
        def onQueueFullCalled = false
        
        def policy = new DeadLetterQueuePolicy()
        policy.onEntryAdded { entry -> onEntryAddedCalled = true }
        policy.onQueueFull { size, max -> onQueueFullCalled = true }
        
        when:
        policy.onEntryAdded.call(null)
        policy.onQueueFull.call(10, 10)
        
        then:
        onEntryAddedCalled
        onQueueFullCalled
    }
    
    // =========================================================================
    // Validation
    // =========================================================================
    
    def "test maxSize validation"() {
        when:
        new DeadLetterQueuePolicy().maxSize(-1)
        
        then:
        thrown(IllegalArgumentException)
    }
    
    def "test maxRetries validation"() {
        when:
        new DeadLetterQueuePolicy().maxRetries(-1)
        
        then:
        thrown(IllegalArgumentException)
    }
    
    // =========================================================================
    // Preset Policies
    // =========================================================================
    
    def "test permissive preset"() {
        given:
        def policy = DeadLetterQueuePolicy.permissive()
        
        expect:
        policy.maxSize == 0  // Unlimited
        policy.maxAge == null
        policy.autoRetry == false
    }
    
    def "test strict preset"() {
        given:
        def policy = DeadLetterQueuePolicy.strict()
        
        expect:
        policy.maxSize == 100
        policy.maxAge == Duration.ofHours(1)
        policy.autoRetry == false
        policy.fullQueueBehavior == DeadLetterQueuePolicy.FullQueueBehavior.REJECT_NEW
    }
    
    def "test withAutoRetry preset"() {
        given:
        def policy = DeadLetterQueuePolicy.withAutoRetry()
        
        expect:
        policy.maxSize == 500
        policy.maxAge == Duration.ofHours(24)
        policy.autoRetry == true
        policy.maxRetries == 3
        policy.retryDelay == Duration.ofMinutes(5)
        policy.retryStrategy == DeadLetterQueuePolicy.RetryStrategy.EXPONENTIAL_BACKOFF
    }
    
    def "test debugging preset"() {
        given:
        def policy = DeadLetterQueuePolicy.debugging()
        
        expect:
        policy.maxSize == 0  // Unlimited
        policy.maxAge == Duration.ofDays(7)
        policy.autoRetry == false
    }
    
    // =========================================================================
    // Complex Scenarios
    // =========================================================================
    
    def "test multiple exception filters"() {
        given:
        def policy = new DeadLetterQueuePolicy()
        policy.alwaysCapture(RuntimeException)
        policy.alwaysCapture(IOException)
        policy.neverCapture(IllegalArgumentException)
        
        expect:
        policy.shouldCapture("task", new RuntimeException())
        policy.shouldCapture("task", new IOException())
        !policy.shouldCapture("task", new IllegalArgumentException())
    }
    
    def "test multiple task ID filters"() {
        given:
        def policy = new DeadLetterQueuePolicy()
        policy.alwaysCaptureTask("critical-1")
        policy.alwaysCaptureTask("critical-2")
        policy.neverCaptureTask("ignored-1")
        policy.neverCaptureTask("ignored-2")
        
        expect:
        policy.shouldCapture("critical-1", new RuntimeException())
        policy.shouldCapture("critical-2", new RuntimeException())
        !policy.shouldCapture("ignored-1", new RuntimeException())
        !policy.shouldCapture("ignored-2", new RuntimeException())
    }
}
