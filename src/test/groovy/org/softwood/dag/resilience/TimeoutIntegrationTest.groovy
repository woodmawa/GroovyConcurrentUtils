package org.softwood.dag.resilience

import org.softwood.dag.task.TaskContext
import org.softwood.dag.task.TaskFactory
import org.softwood.pool.ExecutorPoolFactory
import org.softwood.promise.core.dataflow.DataflowPromiseFactory
import spock.lang.Specification
import spock.lang.Timeout as SpockTimeout

import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Integration tests for execution timeout functionality.
 */
class TimeoutIntegrationTest extends Specification {
    
    TaskContext ctx
    
    def setup() {
        def pool = ExecutorPoolFactory.builder()
            .name("timeout-test-pool")
            .build()
        ctx = new TaskContext(pool, new DataflowPromiseFactory())
    }
    
    def cleanup() {
        ctx?.pool?.shutdown()
    }
    
    @SpockTimeout(10)
    def "test basic task timeout"() {
        given:
        def task = TaskFactory.createServiceTask("slow-task", "Slow Task", ctx)
        task.timeout(Duration.ofSeconds(1))
        task.action { taskCtx, prev ->
            Thread.sleep(3000)
            return taskCtx.promiseFactory.createPromise("should not complete")
        }
        
        when:
        task.execute(null).get()
        
        then:
        def ex = thrown(TaskTimeoutException)
        ex.taskId == "slow-task"
        ex.timeout == Duration.ofSeconds(1)
    }
    
    @SpockTimeout(5)
    def "test task completes within timeout"() {
        given:
        def task = TaskFactory.createServiceTask("fast-task", "Fast Task", ctx)
        task.timeout(Duration.ofSeconds(2))
        task.action { taskCtx, prev ->
            Thread.sleep(100)
            return taskCtx.promiseFactory.createPromise("success")
        }
        
        when:
        def result = task.execute(null).get()
        
        then:
        result == "success"
    }
    
    @SpockTimeout(10)
    def "test timeout with DSL closure and callback"() {
        given:
        def timeoutCalled = new AtomicBoolean(false)
        def task = TaskFactory.createServiceTask("dsl-timeout", "DSL Timeout", ctx)
        task.timeout {
            duration Duration.ofMillis(500)
            onTimeout { c -> timeoutCalled.set(true) }
        }
        task.action { taskCtx, prev ->
            Thread.sleep(2000)
            return taskCtx.promiseFactory.createPromise("never")
        }
        
        when:
        task.execute(null).get()
        
        then:
        thrown(TaskTimeoutException)
        timeoutCalled.get()
    }
    
    @SpockTimeout(10)
    def "test timeout preset quick"() {
        given:
        def task = TaskFactory.createServiceTask("quick-timeout", "Quick", ctx)
        task.timeout("quick")
        task.action { taskCtx, prev ->
            Thread.sleep(10000)
            return taskCtx.promiseFactory.createPromise("too slow")
        }
        
        when:
        task.execute(null).get()
        
        then:
        def ex = thrown(TaskTimeoutException)
        ex.timeout == Duration.ofSeconds(5)
    }
    
    def "test timeout preset none disables timeout"() {
        given:
        def task = TaskFactory.createServiceTask("no-timeout", "No Timeout", ctx)
        task.timeout("none")
        
        expect:
        !task.timeoutPolicy.isActive()
    }
    
    @SpockTimeout(10)
    def "test timeout is not retried"() {
        given:
        def attemptCount = new AtomicInteger(0)
        def task = TaskFactory.createServiceTask("timeout-no-retry", "No Retry", ctx)
        task.retry {
            maxAttempts 3
            initialDelay Duration.ofMillis(100)
        }
        task.timeout(Duration.ofMillis(500))
        task.action { taskCtx, prev ->
            attemptCount.incrementAndGet()
            Thread.sleep(2000)
            return taskCtx.promiseFactory.createPromise("timeout")
        }
        
        when:
        task.execute(null).get()
        
        then:
        thrown(TaskTimeoutException)
        attemptCount.get() == 1
    }
    
    @SpockTimeout(10)
    def "test legacy taskTimeoutMillis still works"() {
        given:
        def task = TaskFactory.createServiceTask("legacy-timeout", "Legacy", ctx)
        task.timeoutMillis = 1000L
        task.action { taskCtx, prev ->
            Thread.sleep(3000)
            return taskCtx.promiseFactory.createPromise("timeout")
        }
        
        when:
        task.execute(null).get()
        
        then:
        thrown(TaskTimeoutException)
    }
    
    def "test timeout disabled by default"() {
        given:
        def task = TaskFactory.createServiceTask("no-timeout-config", "No Config", ctx)
        
        expect:
        !task.timeoutPolicy.isActive()
    }
    
    @SpockTimeout(5)
    def "test zero timeout is rejected"() {
        when:
        def task = TaskFactory.createServiceTask("zero-timeout", "Zero", ctx)
        task.timeout(Duration.ofMillis(0))
        
        then:
        thrown(IllegalArgumentException)
    }
    
    @SpockTimeout(5)
    def "test negative timeout is rejected"() {
        when:
        def task = TaskFactory.createServiceTask("negative-timeout", "Negative", ctx)
        task.timeout(Duration.ofMillis(-1000))
        
        then:
        thrown(IllegalArgumentException)
    }
}
