package org.softwood.dag.saga

/**
 * Execution strategy for saga compensation.
 * 
 * <p>Defines how a saga should respond to failures:</p>
 * <ul>
 *   <li><b>RETRY_THEN_BACKWARD</b>: Try to retry the failed step, if that fails, compensate all previous steps (most common)</li>
 *   <li><b>BACKWARD_ONLY</b>: Immediately compensate all previous steps on any failure (safest)</li>
 *   <li><b>FORWARD_ONLY</b>: Keep retrying the failed step, never compensate (use with caution)</li>
 * </ul>
 * 
 * <h3>Usage:</h3>
 * <pre>
 * saga("checkout") {
 *     strategy "retry-then-backward"  // Default
 *     // or
 *     strategy SagaExecutionStrategy.BACKWARD_ONLY
 * }
 * </pre>
 */
enum SagaExecutionStrategy {
    /**
     * Try to retry the failed step first.
     * If retries are exhausted, execute compensations in reverse order.
     * This is the most practical default.
     */
    RETRY_THEN_BACKWARD,
    
    /**
     * Immediately execute compensations on any failure.
     * No retry attempts - safest but least flexible.
     */
    BACKWARD_ONLY,
    
    /**
     * Keep retrying the failed step indefinitely.
     * Never execute compensations.
     * Use with extreme caution - can cause infinite loops.
     */
    FORWARD_ONLY
    
    /**
     * Parse strategy from string.
     */
    static SagaExecutionStrategy fromString(String str) {
        if (!str) {
            return RETRY_THEN_BACKWARD
        }
        
        switch (str.toLowerCase().replaceAll('[-_]', '')) {
            case 'retrythenbackward':
            case 'retrybackward':
                return RETRY_THEN_BACKWARD
                
            case 'backwardonly':
            case 'backward':
                return BACKWARD_ONLY
                
            case 'forwardonly':
            case 'forward':
                return FORWARD_ONLY
                
            default:
                throw new IllegalArgumentException(
                    "Unknown saga strategy: '${str}'. " +
                    "Valid options: retry-then-backward, backward-only, forward-only"
                )
        }
    }
}
