package org.softwood.dag.task

/**
 * Exception thrown when a circuit breaker is in OPEN state.
 * 
 * This exception indicates that the circuit breaker is currently open
 * and refusing to execute requests because too many recent failures
 * have occurred.
 * 
 * <h3>What This Means:</h3>
 * <ul>
 *   <li>The wrapped task/service has been failing repeatedly</li>
 *   <li>The circuit breaker is protecting the system by failing fast</li>
 *   <li>No actual request was made to the failing service</li>
 *   <li>The circuit will automatically attempt recovery after the reset timeout</li>
 * </ul>
 * 
 * <h3>Recovery:</h3>
 * <p>After the reset timeout expires, the circuit breaker will transition to
 * HALF_OPEN state and allow test requests through. If those succeed, the
 * circuit will close and normal operation will resume.</p>
 */
class CircuitBreakerOpenException extends RuntimeException {
    
    /** The ID of the task protected by the circuit breaker */
    String taskId
    
    /** Current state of the circuit breaker */
    CircuitBreakerState state
    
    /** Number of consecutive failures that opened the circuit */
    int failureCount
    
    /** When the circuit will transition to HALF_OPEN (milliseconds since epoch) */
    long resetTime
    
    /**
     * Create exception with basic message.
     * 
     * @param message error message
     */
    CircuitBreakerOpenException(String message) {
        super(message)
    }
    
    /**
     * Create exception with detailed circuit breaker state.
     * 
     * @param message error message
     * @param taskId ID of the protected task
     * @param state current circuit state
     * @param failureCount number of failures
     * @param resetTime when circuit will transition to HALF_OPEN
     */
    CircuitBreakerOpenException(String message, String taskId, CircuitBreakerState state, 
                                int failureCount, long resetTime) {
        super(message)
        this.taskId = taskId
        this.state = state
        this.failureCount = failureCount
        this.resetTime = resetTime
    }
    
    /**
     * Create exception with cause.
     * 
     * @param message error message
     * @param cause underlying cause
     */
    CircuitBreakerOpenException(String message, Throwable cause) {
        super(message, cause)
    }
    
    /**
     * Get human-readable message about when circuit will reset.
     * 
     * @return formatted message
     */
    String getResetMessage() {
        if (resetTime == 0) {
            return "Circuit will reset after timeout expires"
        }
        
        long now = System.currentTimeMillis()
        long remainingMs = resetTime - now
        
        if (remainingMs <= 0) {
            return "Circuit should be testing recovery now (HALF_OPEN)"
        }
        
        long remainingSeconds = remainingMs / 1000
        if (remainingSeconds < 60) {
            return "Circuit will reset in ${remainingSeconds} seconds"
        }
        
        long remainingMinutes = remainingSeconds / 60
        return "Circuit will reset in ${remainingMinutes} minute(s)"
    }
    
    @Override
    String toString() {
        def sb = new StringBuilder(super.toString())
        
        if (taskId) {
            sb.append("\n  Task: ").append(taskId)
        }
        if (state) {
            sb.append("\n  State: ").append(state)
        }
        if (failureCount > 0) {
            sb.append("\n  Failures: ").append(failureCount)
        }
        if (resetTime > 0) {
            sb.append("\n  Reset: ").append(getResetMessage())
        }
        
        return sb.toString()
    }
}
