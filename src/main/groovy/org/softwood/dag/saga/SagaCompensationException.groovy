package org.softwood.dag.saga

/**
 * Exception thrown when a saga compensation fails.
 * 
 * <p>This exception wraps the underlying cause and includes
 * information about which step failed to compensate.</p>
 */
class SagaCompensationException extends RuntimeException {
    
    /** The step that failed to compensate */
    final SagaStep step
    
    /**
     * Constructor with step and cause.
     */
    SagaCompensationException(String message, SagaStep step, Throwable cause) {
        super(message, cause)
        this.step = step
    }
    
    /**
     * Constructor with step only.
     */
    SagaCompensationException(String message, SagaStep step) {
        super(message)
        this.step = step
    }
}
