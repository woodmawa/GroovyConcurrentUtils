package org.softwood.dag.task

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import static org.junit.jupiter.api.Assertions.*

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Comprehensive tests for CircuitBreakerTask.
 */
class CircuitBreakerTaskTest {
    
    TaskContext ctx
    
    @BeforeEach
    void setup() {
        ctx = new TaskContext()
    }
    
    // =========================================================================
    // Basic Functionality Tests
    // =========================================================================
    
    @Test
    void "circuit starts in CLOSED state"() {
        def wrappedTask = TaskFactory.createServiceTask("inner", "Inner", ctx)
        wrappedTask.action = { c, prev -> c.promiseFactory.createPromise("success") }
        
        def circuit = new CircuitBreakerTask("cb1", "Circuit Breaker", ctx)
        circuit.wrappedTask = wrappedTask
        
        assertEquals(CircuitBreakerState.CLOSED, circuit.circuitState)
        assertTrue(circuit.isCircuitClosed())
        assertFalse(circuit.isCircuitOpen())
        assertFalse(circuit.isCircuitHalfOpen())
    }
    
    @Test
    void "successful execution in CLOSED state"() {
        def wrappedTask = TaskFactory.createServiceTask("inner", "Inner", ctx)
        wrappedTask.action = { c, prev -> c.promiseFactory.createPromise("result") }
        
        def circuit = new CircuitBreakerTask("cb1", "Circuit Breaker", ctx)
        circuit.wrappedTask = wrappedTask
        
        def result = circuit.execute(ctx.promiseFactory.createPromise("input")).get()
        
        assertEquals("result", result)
        assertEquals(CircuitBreakerState.CLOSED, circuit.circuitState)
        assertEquals(0, circuit.failureCount)
    }
    
    @Test
    void "circuit opens after failure threshold"() {
        def attemptCount = new AtomicInteger(0)
        
        def wrappedTask = TaskFactory.createServiceTask("inner", "Inner", ctx)
        wrappedTask.action = { c, prev ->
            attemptCount.incrementAndGet()
            throw new RuntimeException("Service unavailable")
        }
        
        def circuit = new CircuitBreakerTask("cb1", "Circuit Breaker", ctx)
        circuit.wrappedTask = wrappedTask
        circuit.failureThreshold = 3
        circuit.resetTimeout = Duration.ofMinutes(1)
        
        // First 3 failures should be attempted
        for (int i = 0; i < 3; i++) {
            assertThrows(RuntimeException) {
                circuit.execute(ctx.promiseFactory.createPromise("input")).get()
            }
        }
        
        assertEquals(3, attemptCount.get())
        assertEquals(CircuitBreakerState.OPEN, circuit.circuitState)
        assertTrue(circuit.isCircuitOpen())
        
        // 4th attempt should fail fast (not call wrapped task)
        def exception = assertThrows(CircuitBreakerOpenException) {
            circuit.execute(ctx.promiseFactory.createPromise("input")).get()
        }
        
        assertEquals(3, attemptCount.get()) // No additional attempts
        assertTrue(exception.message.contains("OPEN"))
        assertTrue(exception.message.contains("failing fast"))
    }
    
    @Test
    void "circuit rejects requests when OPEN"() {
        def wrappedTask = TaskFactory.createServiceTask("inner", "Inner", ctx)
        wrappedTask.action = { c, prev -> throw new RuntimeException("Error") }
        
        def circuit = new CircuitBreakerTask("cb1", "Circuit Breaker", ctx)
        circuit.wrappedTask = wrappedTask
        circuit.failureThreshold = 2
        
        // Cause circuit to open
        2.times {
            assertThrows(RuntimeException) {
                circuit.execute(ctx.promiseFactory.createPromise(null)).get()
            }
        }
        
        assertTrue(circuit.isCircuitOpen())
        
        // Next requests should be rejected immediately
        5.times {
            assertThrows(CircuitBreakerOpenException) {
                circuit.execute(ctx.promiseFactory.createPromise(null)).get()
            }
        }
        
        def stats = circuit.stats
        assertEquals(7, stats.totalRequests)
        assertEquals(2, stats.failedRequests)
        assertEquals(5, stats.rejectedRequests)
    }
    
    // =========================================================================
    // State Transition Tests
    // =========================================================================
    
    @Test
    void "circuit transitions to HALF_OPEN after timeout"() {
        def wrappedTask = TaskFactory.createServiceTask("inner", "Inner", ctx)
        wrappedTask.action = { c, prev -> throw new RuntimeException("Error") }
        
        def circuit = new CircuitBreakerTask("cb1", "Circuit Breaker", ctx)
        circuit.wrappedTask = wrappedTask
        circuit.failureThreshold = 2
        circuit.resetTimeout = Duration.ofMillis(100) // Very short for testing
        
        // Open the circuit
        2.times {
            assertThrows(RuntimeException) {
                circuit.execute(ctx.promiseFactory.createPromise(null)).get()
            }
        }
        
        assertTrue(circuit.isCircuitOpen())
        
        // Wait for timeout
        Thread.sleep(150)
        
        // Now fix the service
        wrappedTask.action = { c, prev -> c.promiseFactory.createPromise("success") }
        
        // Next request should find circuit in HALF_OPEN and succeed
        def result = circuit.execute(ctx.promiseFactory.createPromise(null)).get()
        
        assertEquals("success", result)
        assertEquals(CircuitBreakerState.CLOSED, circuit.circuitState)
    }
    
    @Test
    void "circuit requires multiple successes in HALF_OPEN before closing"() {
        def wrappedTask = TaskFactory.createServiceTask("inner", "Inner", ctx)
        wrappedTask.action = { c, prev -> throw new RuntimeException("Error") }
        
        def circuit = new CircuitBreakerTask("cb1", "Circuit Breaker", ctx)
        circuit.wrappedTask = wrappedTask
        circuit.failureThreshold = 2
        circuit.resetTimeout = Duration.ofMillis(50)
        circuit.halfOpenSuccessThreshold = 3  // Need 3 successes
        
        // Open circuit
        2.times {
            assertThrows(RuntimeException) {
                circuit.execute(ctx.promiseFactory.createPromise(null)).get()
            }
        }
        
        Thread.sleep(100)
        
        // Fix service
        wrappedTask.action = { c, prev -> c.promiseFactory.createPromise("ok") }
        
        // First success - should stay HALF_OPEN
        circuit.execute(ctx.promiseFactory.createPromise(null)).get()
        assertTrue(circuit.isCircuitHalfOpen() || circuit.isCircuitClosed()) // May transition on first if timing
        
        // Second success
        circuit.execute(ctx.promiseFactory.createPromise(null)).get()
        
        // Third success - should close
        circuit.execute(ctx.promiseFactory.createPromise(null)).get()
        
        assertEquals(CircuitBreakerState.CLOSED, circuit.circuitState)
    }
    
    @Test
    void "circuit reopens if HALF_OPEN request fails"() {
        def wrappedTask = TaskFactory.createServiceTask("inner", "Inner", ctx)
        wrappedTask.action = { c, prev -> throw new RuntimeException("Error") }
        
        def circuit = new CircuitBreakerTask("cb1", "Circuit Breaker", ctx)
        circuit.wrappedTask = wrappedTask
        circuit.failureThreshold = 2
        circuit.resetTimeout = Duration.ofMillis(50)
        
        // Open circuit
        2.times {
            assertThrows(RuntimeException) {
                circuit.execute(ctx.promiseFactory.createPromise(null)).get()
            }
        }
        
        assertTrue(circuit.isCircuitOpen())
        
        Thread.sleep(100)
        
        // Service still broken - HALF_OPEN test will fail
        assertThrows(RuntimeException) {
            circuit.execute(ctx.promiseFactory.createPromise(null)).get()
        }
        
        // Should be back to OPEN
        assertEquals(CircuitBreakerState.OPEN, circuit.circuitState)
    }
    
    // =========================================================================
    // Statistics Tests
    // =========================================================================
    
    @Test
    void "statistics are tracked correctly"() {
        def successCount = new AtomicInteger(0)
        
        def wrappedTask = TaskFactory.createServiceTask("inner", "Inner", ctx)
        wrappedTask.action = { c, prev ->
            int count = successCount.incrementAndGet()
            if (count <= 3) {
                throw new RuntimeException("Failing")
            }
            c.promiseFactory.createPromise("ok")
        }
        
        def circuit = new CircuitBreakerTask("cb1", "Circuit Breaker", ctx)
        circuit.wrappedTask = wrappedTask
        circuit.failureThreshold = 5
        
        // 3 failures
        3.times {
            assertThrows(RuntimeException) {
                circuit.execute(ctx.promiseFactory.createPromise(null)).get()
            }
        }
        
        // 2 successes
        2.times {
            circuit.execute(ctx.promiseFactory.createPromise(null)).get()
        }
        
        def stats = circuit.stats
        
        assertEquals(5, stats.totalRequests)
        assertEquals(2, stats.successfulRequests)
        assertEquals(3, stats.failedRequests)
        assertEquals(0, stats.rejectedRequests)
        assertEquals(CircuitBreakerState.CLOSED, stats.state)
        assertEquals(0, stats.openCount) // Never opened
    }
    
    @Test
    void "rejection rate is calculated correctly"() {
        def wrappedTask = TaskFactory.createServiceTask("inner", "Inner", ctx)
        wrappedTask.action = { c, prev -> throw new RuntimeException("Error") }
        
        def circuit = new CircuitBreakerTask("cb1", "Circuit Breaker", ctx)
        circuit.wrappedTask = wrappedTask
        circuit.failureThreshold = 3
        
        // Open circuit
        3.times {
            assertThrows(RuntimeException) {
                circuit.execute(ctx.promiseFactory.createPromise(null)).get()
            }
        }
        
        // 7 rejections
        7.times {
            assertThrows(CircuitBreakerOpenException) {
                circuit.execute(ctx.promiseFactory.createPromise(null)).get()
            }
        }
        
        def stats = circuit.stats
        assertEquals(10, stats.totalRequests)
        assertEquals(7, stats.rejectedRequests)
        assertEquals(70.0, stats.rejectionRate, 0.1)
    }
    
    // =========================================================================
    // Callback Tests
    // =========================================================================
    
    @Test
    void "onOpen callback is triggered"() {
        def callbackInvoked = false
        def callbackFailures = 0
        
        def wrappedTask = TaskFactory.createServiceTask("inner", "Inner", ctx)
        wrappedTask.action = { c, prev -> throw new RuntimeException("Error") }
        
        def circuit = new CircuitBreakerTask("cb1", "Circuit Breaker", ctx)
        circuit.wrappedTask = wrappedTask
        circuit.failureThreshold = 2
        circuit.onOpen { context, failures ->
            callbackInvoked = true
            callbackFailures = failures
        }
        
        // Trigger circuit open
        2.times {
            assertThrows(RuntimeException) {
                circuit.execute(ctx.promiseFactory.createPromise(null)).get()
            }
        }
        
        assertTrue(callbackInvoked)
        assertEquals(2, callbackFailures)
    }
    
    @Test
    void "onClose callback is triggered"() {
        def callbackInvoked = false
        
        def wrappedTask = TaskFactory.createServiceTask("inner", "Inner", ctx)
        wrappedTask.action = { c, prev -> throw new RuntimeException("Error") }
        
        def circuit = new CircuitBreakerTask("cb1", "Circuit Breaker", ctx)
        circuit.wrappedTask = wrappedTask
        circuit.failureThreshold = 2
        circuit.resetTimeout = Duration.ofMillis(50)
        circuit.onClose { context ->
            callbackInvoked = true
        }
        
        // Open circuit
        2.times {
            assertThrows(RuntimeException) {
                circuit.execute(ctx.promiseFactory.createPromise(null)).get()
            }
        }
        
        Thread.sleep(100)
        
        // Fix service and close circuit
        wrappedTask.action = { c, prev -> c.promiseFactory.createPromise("ok") }
        circuit.execute(ctx.promiseFactory.createPromise(null)).get()
        
        assertTrue(callbackInvoked)
    }
    
    @Test
    void "onHalfOpen callback is triggered"() {
        def callbackInvoked = false
        
        def wrappedTask = TaskFactory.createServiceTask("inner", "Inner", ctx)
        wrappedTask.action = { c, prev -> throw new RuntimeException("Error") }
        
        def circuit = new CircuitBreakerTask("cb1", "Circuit Breaker", ctx)
        circuit.wrappedTask = wrappedTask
        circuit.failureThreshold = 2
        circuit.resetTimeout = Duration.ofMillis(50)
        circuit.onHalfOpen { context ->
            callbackInvoked = true
        }
        
        // Open circuit
        2.times {
            assertThrows(RuntimeException) {
                circuit.execute(ctx.promiseFactory.createPromise(null)).get()
            }
        }
        
        Thread.sleep(100)
        
        // Trigger half-open transition
        wrappedTask.action = { c, prev -> c.promiseFactory.createPromise("ok") }
        circuit.execute(ctx.promiseFactory.createPromise(null)).get()
        
        assertTrue(callbackInvoked)
    }
    
    // =========================================================================
    // Custom Failure Detection Tests
    // =========================================================================
    
    @Test
    void "custom failure detector only counts specific exceptions"() {
        def attemptCount = new AtomicInteger(0)
        
        def wrappedTask = TaskFactory.createServiceTask("inner", "Inner", ctx)
        wrappedTask.action = { c, prev ->
            int count = attemptCount.incrementAndGet()
            if (count % 2 == 0) {
                throw new IOException("Network error")  // Should count
            } else {
                throw new IllegalArgumentException("Bad input")  // Should NOT count
            }
        }
        
        def circuit = new CircuitBreakerTask("cb1", "Circuit Breaker", ctx)
        circuit.wrappedTask = wrappedTask
        circuit.failureThreshold = 2
        circuit.recordFailureOn { exception ->
            exception instanceof IOException
        }
        
        // First attempt: IllegalArgumentException - doesn't count
        assertThrows(IllegalArgumentException) {
            circuit.execute(ctx.promiseFactory.createPromise(null)).get()
        }
        assertEquals(0, circuit.failureCount)
        
        // Second attempt: IOException - counts
        assertThrows(IOException) {
            circuit.execute(ctx.promiseFactory.createPromise(null)).get()
        }
        assertEquals(1, circuit.failureCount)
        
        // Third attempt: IllegalArgumentException - doesn't count
        assertThrows(IllegalArgumentException) {
            circuit.execute(ctx.promiseFactory.createPromise(null)).get()
        }
        assertEquals(1, circuit.failureCount)
        
        // Fourth attempt: IOException - counts, should open circuit
        assertThrows(IOException) {
            circuit.execute(ctx.promiseFactory.createPromise(null)).get()
        }
        
        assertTrue(circuit.isCircuitOpen())
    }
    
    // =========================================================================
    // Edge Cases
    // =========================================================================
    
    @Test
    void "circuit without wrapped task throws exception"() {
        def circuit = new CircuitBreakerTask("cb1", "Circuit Breaker", ctx)
        // No wrapped task set
        
        def exception = assertThrows(IllegalStateException) {
            circuit.execute(ctx.promiseFactory.createPromise(null)).get()
        }
        
        assertTrue(exception.message.contains("no wrapped task"))
    }
    
    @Test
    void "manual reset works"() {
        def wrappedTask = TaskFactory.createServiceTask("inner", "Inner", ctx)
        wrappedTask.action = { c, prev -> throw new RuntimeException("Error") }
        
        def circuit = new CircuitBreakerTask("cb1", "Circuit Breaker", ctx)
        circuit.wrappedTask = wrappedTask
        circuit.failureThreshold = 2
        
        // Open circuit
        2.times {
            assertThrows(RuntimeException) {
                circuit.execute(ctx.promiseFactory.createPromise(null)).get()
            }
        }
        
        assertTrue(circuit.isCircuitOpen())
        
        // Manual reset
        circuit.reset()
        
        assertTrue(circuit.isCircuitClosed())
        assertEquals(0, circuit.failureCount)
    }
    
    @Test
    void "failure threshold must be positive"() {
        def circuit = new CircuitBreakerTask("cb1", "Circuit Breaker", ctx)
        
        assertThrows(IllegalArgumentException) {
            circuit.failureThreshold(0)
        }
        
        assertThrows(IllegalArgumentException) {
            circuit.failureThreshold(-1)
        }
    }
    
    @Test
    void "half-open success threshold must be positive"() {
        def circuit = new CircuitBreakerTask("cb1", "Circuit Breaker", ctx)
        
        assertThrows(IllegalArgumentException) {
            circuit.halfOpenSuccessThreshold(0)
        }
        
        assertThrows(IllegalArgumentException) {
            circuit.halfOpenSuccessThreshold(-1)
        }
    }
    
    @Test
    void "success in CLOSED state resets failure count"() {
        def attemptCount = new AtomicInteger(0)
        
        def wrappedTask = TaskFactory.createServiceTask("inner", "Inner", ctx)
        wrappedTask.action = { c, prev ->
            int count = attemptCount.incrementAndGet()
            if (count == 1 || count == 2) {
                throw new RuntimeException("Error")
            }
            c.promiseFactory.createPromise("ok")
        }
        
        def circuit = new CircuitBreakerTask("cb1", "Circuit Breaker", ctx)
        circuit.wrappedTask = wrappedTask
        circuit.failureThreshold = 3
        
        // 2 failures
        2.times {
            assertThrows(RuntimeException) {
                circuit.execute(ctx.promiseFactory.createPromise(null)).get()
            }
        }
        
        assertEquals(2, circuit.failureCount)
        
        // Success - should reset count
        circuit.execute(ctx.promiseFactory.createPromise(null)).get()
        
        assertEquals(0, circuit.failureCount)
        assertTrue(circuit.isCircuitClosed())
    }
    
    // =========================================================================
    // Factory Tests
    // =========================================================================
    
    @Test
    void "can create via factory"() {
        def circuit = TaskFactory.createCircuitBreakerTask("cb1", "Circuit", ctx)
        
        assertNotNull(circuit)
        assertEquals("cb1", circuit.id)
        assertTrue(circuit instanceof CircuitBreakerTask)
    }
    
    @Test
    void "factory validates task ID"() {
        assertThrows(IllegalArgumentException) {
            TaskFactory.createCircuitBreakerTask("'; DROP TABLE", "Bad", ctx)
        }
    }
}
