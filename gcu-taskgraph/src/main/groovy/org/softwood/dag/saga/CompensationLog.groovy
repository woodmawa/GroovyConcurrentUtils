package org.softwood.dag.saga

import groovy.util.logging.Slf4j

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Log of executed saga steps for compensation tracking.
 * 
 * <p>Maintains a record of successfully executed steps so they can be
 * compensated in reverse order if the saga fails.</p>
 * 
 * <p>Thread-safe for concurrent access.</p>
 * 
 * <h3>Usage:</h3>
 * <pre>
 * def log = new CompensationLog()
 * 
 * // Record executed steps
 * log.record(step1, result1)
 * log.record(step2, result2)
 * 
 * // Get steps to compensate (in reverse order)
 * def toCompensate = log.getStepsToCompensate()  // [step2, step1]
 * </pre>
 */
@Slf4j
class CompensationLog {
    
    /** Queue of executed steps (FIFO for recording, reversed for compensation) */
    private final ConcurrentLinkedQueue<SagaStep> executedSteps = new ConcurrentLinkedQueue<>()
    
    /** Total steps recorded */
    private volatile int totalRecorded = 0
    
    /** Total steps compensated */
    private volatile int totalCompensated = 0
    
    /**
     * Record a successfully executed step.
     * 
     * @param step the step that was executed
     * @param result the result from executing the step
     */
    void record(SagaStep step, Object result) {
        if (!step.compensatable) {
            log.debug "Step ${step.id} is not compensatable, skipping log"
            return
        }
        
        step.result = result
        step.executed = true
        step.executedAt = System.currentTimeMillis()
        
        executedSteps.offer(step)
        totalRecorded++
        
        log.debug "Recorded step ${step.id} for potential compensation (total: ${totalRecorded})"
    }
    
    /**
     * Get all steps that need compensation, in reverse order.
     * 
     * @return list of steps to compensate (most recent first)
     */
    List<SagaStep> getStepsToCompensate() {
        return new ArrayList<>(executedSteps)
            .findAll { it.needsCompensation() }
            .reverse()
    }
    
    /**
     * Mark a step as compensated.
     */
    void markCompensated(SagaStep step) {
        step.compensated = true
        step.compensatedAt = System.currentTimeMillis()
        totalCompensated++
        
        log.debug "Marked step ${step.id} as compensated (total: ${totalCompensated})"
    }
    
    /**
     * Get the number of steps recorded.
     */
    int getRecordedCount() {
        return totalRecorded
    }
    
    /**
     * Get the number of steps compensated.
     */
    int getCompensatedCount() {
        return totalCompensated
    }
    
    /**
     * Get the number of steps that still need compensation.
     */
    int getPendingCompensationCount() {
        return getStepsToCompensate().size()
    }
    
    /**
     * Check if all compensatable steps have been compensated.
     */
    boolean isFullyCompensated() {
        return getPendingCompensationCount() == 0
    }
    
    /**
     * Clear the log (for testing or reuse).
     */
    void clear() {
        executedSteps.clear()
        totalRecorded = 0
        totalCompensated = 0
    }
    
    /**
     * Get all executed steps (in execution order).
     */
    List<SagaStep> getAllSteps() {
        return new ArrayList<>(executedSteps)
    }
    
    @Override
    String toString() {
        return "CompensationLog[recorded=${totalRecorded}, compensated=${totalCompensated}, pending=${pendingCompensationCount}]"
    }
}
