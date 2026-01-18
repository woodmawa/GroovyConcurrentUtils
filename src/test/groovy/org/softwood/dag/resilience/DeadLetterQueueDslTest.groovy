package org.softwood.dag.resilience

import spock.lang.Specification

import java.time.Duration

/**
 * Tests for simplified DeadLetterQueueDsl.
 */
class DeadLetterQueueDslTest extends Specification {
    
    DeadLetterQueueDsl dsl
    DeadLetterQueuePolicy policy
    
    def setup() {
        policy = new DeadLetterQueuePolicy()
        dsl = new DeadLetterQueueDsl(policy)
    }
    
    // =========================================================================
    // Basic Configuration
    // =========================================================================
    
    def "test maxSize configuration"() {
        when:
        dsl.maxSize(500)
        
        then:
        policy.maxSize == 500
    }
    
    def "test maxAge configuration"() {
        when:
        dsl.maxAge(Duration.ofHours(12))
        
        then:
        policy.maxAge == Duration.ofHours(12)
    }
    
    // =========================================================================
    // Callbacks
    // =========================================================================
    
    def "test onEntryAdded callback"() {
        given:
        def called = false
        
        when:
        dsl.onEntryAdded { entry -> called = true }
        policy.onEntryAdded.call(null)
        
        then:
        called
    }
    
    def "test onQueueFull callback"() {
        given:
        def calledWith = [:]
        
        when:
        dsl.onQueueFull { size, max ->
            calledWith.size = size
            calledWith.max = max
        }
        policy.onQueueFull.call(10, 20)
        
        then:
        calledWith.size == 10
        calledWith.max == 20
    }
    
    // =========================================================================
    // Filters
    // =========================================================================
    
    def "test captureWhen filter"() {
        given:
        dsl.captureWhen { taskId, exception ->
            taskId.startsWith("api-")
        }
        
        expect:
        policy.shouldCapture("api-call", new RuntimeException())
        !policy.shouldCapture("other-task", new RuntimeException())
    }
    
    def "test alwaysCapture exception"() {
        when:
        dsl.alwaysCapture(RuntimeException)
        
        then:
        policy.alwaysCaptureExceptions.contains(RuntimeException)
        policy.shouldCapture("any-task", new RuntimeException())
    }
    
    def "test neverCapture exception"() {
        when:
        dsl.neverCapture(IllegalArgumentException)
        
        then:
        policy.neverCaptureExceptions.contains(IllegalArgumentException)
        !policy.shouldCapture("any-task", new IllegalArgumentException())
    }
    
    def "test alwaysCaptureTask"() {
        when:
        dsl.alwaysCaptureTask("critical-task")
        
        then:
        policy.alwaysCaptureTaskIds.contains("critical-task")
        policy.shouldCapture("critical-task", new RuntimeException())
    }
    
    def "test neverCaptureTask"() {
        when:
        dsl.neverCaptureTask("ignored-task")
        
        then:
        policy.neverCaptureTaskIds.contains("ignored-task")
        !policy.shouldCapture("ignored-task", new RuntimeException())
    }
    
    // =========================================================================
    // Presets
    // =========================================================================
    
    def "test preset permissive"() {
        when:
        dsl.preset("permissive")
        
        then:
        policy.maxSize == 0
        policy.autoRetry == false
    }
    
    def "test preset strict"() {
        when:
        dsl.preset("strict")
        
        then:
        policy.maxSize == 100
        policy.maxAge == Duration.ofHours(1)
    }
    
    def "test preset autoretry"() {
        when:
        dsl.preset("autoretry")
        
        then:
        policy.autoRetry == true
        policy.maxRetries == 3
    }
    
    def "test preset with different case"() {
        expect:
        dsl.preset(input)
        // Just verify it doesn't throw
        true
        
        where:
        input << ["permissive", "Permissive", "PERMISSIVE",
                  "auto-retry", "autoRetry"]
    }
    
    def "test preset with invalid name"() {
        when:
        dsl.preset("invalid-preset")
        
        then:
        thrown(IllegalArgumentException)
    }
    
    def "test preset with override"() {
        when:
        dsl.preset("strict")
        dsl.maxSize(200)  // Override preset
        
        then:
        policy.maxSize == 200
        // Other strict preset values should remain
        policy.maxAge == Duration.ofHours(1)
    }
    
    // =========================================================================
    // Complete DSL Usage
    // =========================================================================
    
    def "test complete DSL configuration"() {
        given:
        def entryAddedCalled = false
        def queueFullCalled = false
        
        when:
        dsl.with {
            maxSize 1000
            maxAge Duration.ofHours(24)
            
            alwaysCapture IOException
            neverCapture IllegalArgumentException
            alwaysCaptureTask "critical-api"
            
            captureWhen { taskId, exception ->
                !taskId.startsWith("test-")
            }
            
            onEntryAdded { entry -> entryAddedCalled = true }
            onQueueFull { size, max -> queueFullCalled = true }
        }
        
        then:
        policy.maxSize == 1000
        policy.maxAge == Duration.ofHours(24)
        policy.alwaysCaptureExceptions.contains(IOException)
        policy.neverCaptureExceptions.contains(IllegalArgumentException)
        policy.alwaysCaptureTaskIds.contains("critical-api")
        
        when:
        policy.onEntryAdded.call(null)
        policy.onQueueFull.call(10, 20)
        
        then:
        entryAddedCalled
        queueFullCalled
    }
    
    def "test configure convenience method"() {
        when:
        dsl.configure(
            maxSize: 500,
            maxAge: Duration.ofHours(6)
        )
        
        then:
        policy.maxSize == 500
        policy.maxAge == Duration.ofHours(6)
    }
}
