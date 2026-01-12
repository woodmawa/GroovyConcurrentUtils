package org.softwood.dag.task

/**
 * States for Circuit Breaker pattern.
 * 
 * The circuit breaker transitions between these states based on success/failure counts:
 * 
 * <pre>
 *     CLOSED ──(failures exceed threshold)──> OPEN
 *        ↑                                      │
 *        │                                      │
 *        │                            (timeout expires)
 *        │                                      │
 *        │                                      ↓
 *        └──────(success in half-open)─── HALF_OPEN
 * </pre>
 * 
 * <h3>State Descriptions:</h3>
 * <ul>
 *   <li><b>CLOSED</b> - Normal operation. Requests pass through to the wrapped task.
 *       Failures are counted. If failure threshold is exceeded, transitions to OPEN.</li>
 *   
 *   <li><b>OPEN</b> - Circuit is "broken". All requests fail immediately without calling
 *       the wrapped task. After reset timeout expires, transitions to HALF_OPEN.</li>
 *   
 *   <li><b>HALF_OPEN</b> - Testing if the service has recovered. Allows a limited number
 *       of requests through. If they succeed, transitions back to CLOSED. If they fail,
 *       transitions back to OPEN.</li>
 * </ul>
 */
enum CircuitBreakerState {
    /**
     * Circuit is closed - normal operation.
     * Requests pass through to the wrapped task.
     */
    CLOSED,
    
    /**
     * Circuit is open - failing fast.
     * All requests fail immediately without calling the wrapped task.
     */
    OPEN,
    
    /**
     * Circuit is half-open - testing recovery.
     * Limited requests are allowed through to test if service has recovered.
     */
    HALF_OPEN
}
