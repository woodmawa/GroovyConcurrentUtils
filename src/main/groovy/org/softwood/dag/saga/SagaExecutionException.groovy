package org.softwood.dag.saga

/**
 * Exception thrown when a saga execution fails.
 * 
 * <p>Contains information about the original error and which steps
 * were successfully compensated.</p>
 */
class SagaExecutionException extends RuntimeException {
    
    /** Steps that were successfully compensated */
    final List<SagaStep> compensatedSteps
    
    /**
     * Constructor.
     */
    SagaExecutionException(String message, Throwable cause, List<SagaStep> compensatedSteps) {
        super(message, cause)
        this.compensatedSteps = compensatedSteps ?: []
    }
    
    /**
     * Get the number of steps that were compensated.
     */
    int getCompensatedCount() {
        return compensatedSteps.size()
    }
}
