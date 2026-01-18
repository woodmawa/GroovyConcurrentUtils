package org.softwood.dag.resilience

import org.softwood.dag.task.TaskContext
import org.softwood.dag.task.TaskFactory
import org.softwood.pool.ExecutorPoolFactory
import org.softwood.promise.core.dataflow.DataflowPromiseFactory
import spock.lang.Specification

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Integration tests for Idempotency with TaskBase.
 * Tests automatic caching and retrieval of task results.
 */
class IdempotencyTaskIntegrationTest extends Specification {
    
    TaskContext ctx
    IdempotencyCache cache
    
    def setup() {
        def pool = ExecutorPoolFactory.builder()
            .name("idempotency-test-pool")
            .build()
        ctx = new TaskContext(pool, new DataflowPromiseFactory())
        cache = new IdempotencyCache()
        ctx.idempotencyCache = cache
    }
    
    def cleanup() {
        ctx?.pool?.shutdown()
        cache?.clear()
    }
    
    // =========================================================================
    // Basic Idempotency
    // =========================================================================
    
    def "test task result is cached and reused on second execution"() {
        given:
        def executionCount = new AtomicInteger(0)
        
        def task = TaskFactory.createServiceTask("cached-task", "Task", ctx)
        task.idempotent("standard")
        task.action { taskCtx, prev ->
            executionCount.incrementAndGet()
            return taskCtx.promiseFactory.createPromise("result-${executionCount.get()}")
        }
        
        def input = ctx.promiseFactory.createPromise([id: "test-123"])
        
        when: "Execute first time"
        def result1 = task.execute(input).get()
        
        then: "Should execute and return result"
        result1 == "result-1"
        executionCount.get() == 1
        cache.size() == 1
        
        when: "Execute second time with same input"
        def result2 = task.execute(input).get()
        
        then: "Should return cached result without executing"
        result2 == "result-1"  // Same result as first execution
        executionCount.get() == 1  // Execution count unchanged
        cache.size() == 1
    }
    
    def "test different inputs produce different cache entries"() {
        given:
        def task = TaskFactory.createServiceTask("multi-input-task", "Task", ctx)
        task.idempotent("standard")
        task.action { taskCtx, prev ->
            def id = prev.id
            return taskCtx.promiseFactory.createPromise("result-for-${id}")
        }
        
        when:
        def result1 = task.execute(ctx.promiseFactory.createPromise([id: "A"])).get()
        def result2 = task.execute(ctx.promiseFactory.createPromise([id: "B"])).get()
        def result3 = task.execute(ctx.promiseFactory.createPromise([id: "A"])).get()  // Same as first
        
        then:
        result1 == "result-for-A"
        result2 == "result-for-B"
        result3 == "result-for-A"  // Cached
        cache.size() == 2  // Two different inputs
    }
    
    // =========================================================================
    // DSL Configuration
    // =========================================================================
    
    def "test idempotent DSL block configuration"() {
        given:
        def cacheHitCalled = false
        def cacheMissCalled = false
        def resultCachedCalled = false
        
        def task = TaskFactory.createServiceTask("dsl-task", "Task", ctx)
        task.idempotent {
            ttl Duration.ofMinutes(5)
            onCacheHit { key, result -> cacheHitCalled = true }
            onCacheMiss { key -> cacheMissCalled = true }
            onResultCached { key, result -> resultCachedCalled = true }
        }
        task.action { taskCtx, prev ->
            return taskCtx.promiseFactory.createPromise("test-result")
        }
        
        def input = ctx.promiseFactory.createPromise("input")
        
        when: "First execution"
        task.execute(input).get()
        
        then:
        cacheMissCalled
        resultCachedCalled
        !cacheHitCalled
        
        when: "Second execution"
        cacheMissCalled = false  // Reset
        task.execute(input).get()
        
        then:
        cacheHitCalled
        !cacheMissCalled
    }
    
    def "test idempotent preset configuration"() {
        given:
        def task = TaskFactory.createServiceTask("preset-task", "Task", ctx)
        task.idempotent("short")  // 5 minutes TTL
        task.action { taskCtx, prev ->
            return taskCtx.promiseFactory.createPromise("result")
        }
        
        when:
        def result = task.execute(null).get()
        
        then:
        result == "result"
        cache.size() == 1
    }
    
    // =========================================================================
    // Custom Key Generation
    // =========================================================================
    
    def "test custom key generator"() {
        given:
        def task = TaskFactory.createServiceTask("custom-key-task", "Task", ctx)
        task.idempotent {
            ttl Duration.ofMinutes(30)
            keyFrom { input -> input?.requestId ?: "default" }
        }
        task.action { taskCtx, prev ->
            return taskCtx.promiseFactory.createPromise("result-${prev.requestId}")
        }
        
        when:
        def result1 = task.execute(ctx.promiseFactory.createPromise([requestId: "REQ-1", data: "foo"])).get()
        def result2 = task.execute(ctx.promiseFactory.createPromise([requestId: "REQ-1", data: "bar"])).get()  // Different data, same requestId
        
        then:
        result1 == "result-REQ-1"
        result2 == "result-REQ-1"  // Cached based on requestId only
        cache.size() == 1
    }
    
    def "test key fields configuration"() {
        given:
        def task = TaskFactory.createServiceTask("fields-task", "Task", ctx)
        task.idempotent {
            ttl Duration.ofMinutes(30)
            keyFields(['userId', 'action'])
        }
        task.action { taskCtx, prev ->
            return taskCtx.promiseFactory.createPromise("result-${prev.userId}-${prev.action}")
        }
        
        when:
        def result1 = task.execute(ctx.promiseFactory.createPromise([userId: "U1", action: "read", timestamp: 100])).get()
        def result2 = task.execute(ctx.promiseFactory.createPromise([userId: "U1", action: "read", timestamp: 200])).get()  // Different timestamp
        
        then:
        result1 == "result-U1-read"
        result2 == "result-U1-read"  // Cached - timestamp ignored
        cache.size() == 1
    }
    
    // =========================================================================
    // TTL and Expiration
    // =========================================================================
    
    def "test cached result expires after TTL"() {
        given:
        def executionCount = new AtomicInteger(0)
        
        def task = TaskFactory.createServiceTask("ttl-task", "Task", ctx)
        task.idempotent {
            ttl Duration.ofMillis(100)  // Very short TTL
        }
        task.action { taskCtx, prev ->
            executionCount.incrementAndGet()
            return taskCtx.promiseFactory.createPromise("result-${executionCount.get()}")
        }
        
        def input = ctx.promiseFactory.createPromise("input")
        
        when: "Execute first time"
        def result1 = task.execute(input).get()
        
        then:
        result1 == "result-1"
        executionCount.get() == 1
        
        when: "Execute immediately after"
        def result2 = task.execute(input).get()
        
        then: "Should return cached result"
        result2 == "result-1"
        executionCount.get() == 1
        
        when: "Wait for expiration and execute again"
        Thread.sleep(150)  // Wait for TTL to expire
        def result3 = task.execute(input).get()
        
        then: "Should execute again"
        result3 == "result-2"
        executionCount.get() == 2
    }
    
    // =========================================================================
    // Disabled Idempotency
    // =========================================================================
    
    def "test task without idempotent configuration does not cache"() {
        given:
        def executionCount = new AtomicInteger(0)
        
        def task = TaskFactory.createServiceTask("no-cache-task", "Task", ctx)
        task.action { taskCtx, prev ->
            executionCount.incrementAndGet()
            return taskCtx.promiseFactory.createPromise("result-${executionCount.get()}")
        }
        
        def input = ctx.promiseFactory.createPromise("input")
        
        when:
        def result1 = task.execute(input).get()
        def result2 = task.execute(input).get()
        
        then:
        result1 == "result-1"
        result2 == "result-2"  // Not cached - executed twice
        executionCount.get() == 2
        cache.isEmpty()
    }
    
    // =========================================================================
    // Integration with Retry
    // =========================================================================
    
    def "test idempotency with retry - caches final successful result"() {
        given:
        def attemptCount = new AtomicInteger(0)
        
        def task = TaskFactory.createServiceTask("retry-idempotent", "Task", ctx)
        task.retry {
            maxAttempts 3
            initialDelay Duration.ofMillis(10)
        }
        task.idempotent("standard")
        task.action { taskCtx, prev ->
            def attempt = attemptCount.incrementAndGet()
            if (attempt < 3) {
                throw new IOException("Attempt ${attempt} failed")
            }
            return taskCtx.promiseFactory.createPromise("success-after-retries")
        }
        
        def input = ctx.promiseFactory.createPromise("input")
        
        when: "First execution with retries"
        def result1 = task.execute(input).get()
        
        then:
        result1 == "success-after-retries"
        attemptCount.get() == 3
        cache.size() == 1
        
        when: "Second execution"
        attemptCount.set(0)  // Reset
        def result2 = task.execute(input).get()
        
        then: "Should return cached result without retrying"
        result2 == "success-after-retries"
        attemptCount.get() == 0  // Not executed at all
    }
    
    // =========================================================================
    // Cache Statistics
    // =========================================================================
    
    def "test cache statistics track hits and misses"() {
        given:
        def task = TaskFactory.createServiceTask("stats-task", "Task", ctx)
        task.idempotent("standard")
        task.action { taskCtx, prev ->
            return taskCtx.promiseFactory.createPromise("result")
        }
        
        def input = ctx.promiseFactory.createPromise("input")
        
        when:
        task.execute(input).get()  // Miss
        task.execute(input).get()  // Hit
        task.execute(input).get()  // Hit
        
        def stats = cache.stats
        
        then:
        stats.totalMisses == 1
        stats.totalHits == 2
        stats.totalRequests == 3
        stats.hitRate > 60  // Should be ~66%
    }
    
    // =========================================================================
    // Null Input Handling
    // =========================================================================
    
    def "test idempotency with null input"() {
        given:
        def executionCount = new AtomicInteger(0)
        
        def task = TaskFactory.createServiceTask("null-input-task", "Task", ctx)
        task.idempotent("standard")
        task.action { taskCtx, prev ->
            executionCount.incrementAndGet()
            return taskCtx.promiseFactory.createPromise("result-for-null")
        }
        
        when:
        def result1 = task.execute(null).get()
        def result2 = task.execute(null).get()
        
        then:
        result1 == "result-for-null"
        result2 == "result-for-null"  // Cached
        executionCount.get() == 1
        cache.size() == 1
    }
    
    // =========================================================================
    // No Cache Available
    // =========================================================================
    
    def "test idempotent task works without cache in context"() {
        given:
        def noCacheCtx = new TaskContext(ctx.pool, ctx.promiseFactory)
        // Note: No idempotencyCache set
        
        def task = TaskFactory.createServiceTask("no-cache-ctx-task", "Task", noCacheCtx)
        task.idempotent("standard")
        task.action { taskCtx, prev ->
            return taskCtx.promiseFactory.createPromise("result")
        }
        
        when:
        def result = task.execute(null).get()
        
        then:
        result == "result"
        notThrown(Exception)
    }
}
