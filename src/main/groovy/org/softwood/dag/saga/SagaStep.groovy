package org.softwood.dag.saga

import org.softwood.dag.task.ITask
import org.softwood.dag.task.TaskContext

/**
 * Represents a single step in a saga.
 * 
 * <p>A saga step wraps a task and an optional compensation action.
 * If the saga fails, compensation actions are executed in reverse order.</p>
 * 
 * <h3>Compensatable Step:</h3>
 * <pre>
 * def step = new SagaStep(
 *     task: myTask,
 *     compensatable: true,
 *     compensation: { ctx, result ->
 *         // Undo the operation
 *     }
 * )
 * </pre>
 * 
 * <h3>Non-Compensatable Step:</h3>
 * <pre>
 * def step = new SagaStep(
 *     task: notificationTask,
 *     compensatable: false
 * )
 * </pre>
 */
class SagaStep {
    
    /** The task to execute */
    final ITask task
    
    /** Whether this step can be compensated */
    final boolean compensatable
    
    /** Compensation action (if compensatable) */
    final Closure compensation
    
    /** Result from executing this step */
    Object result
    
    /** Whether this step has been executed */
    boolean executed = false
    
    /** Whether this step has been compensated */
    boolean compensated = false
    
    /** Timestamp when step was executed */
    long executedAt = 0
    
    /** Timestamp when step was compensated */
    long compensatedAt = 0
    
    /**
     * Constructor for compensatable step.
     */
    SagaStep(ITask task, Closure compensation) {
        this.task = task
        this.compensatable = true
        this.compensation = compensation
    }
    
    /**
     * Constructor for non-compensatable step.
     */
    SagaStep(ITask task) {
        this.task = task
        this.compensatable = false
        this.compensation = null
    }
    
    /**
     * Get the task ID.
     */
    String getId() {
        return task.id
    }
    
    /**
     * Execute the compensation action.
     * 
     * <p>The compensation closure can accept 0-3 parameters:</p>
     * <ul>
     *   <li>0 params: { -> ... }</li>
     *   <li>1 param: { result -> ... }</li>
     *   <li>2 params: { ctx, result -> ... }</li>
     *   <li>3 params: { ctx, prevResult, result -> ... }</li>
     * </ul>
     */
    void compensate(TaskContext ctx, Object prevResult) {
        if (!compensatable) {
            throw new IllegalStateException("Step ${id} is not compensatable")
        }
        
        if (!compensation) {
            throw new IllegalStateException("Step ${id} has no compensation defined")
        }
        
        if (compensated) {
            throw new IllegalStateException("Step ${id} has already been compensated")
        }
        
        // Call compensation with appropriate parameters based on closure signature
        int paramCount = compensation.maximumNumberOfParameters
        
        try {
            switch (paramCount) {
                case 0:
                    compensation.call()
                    break
                case 1:
                    compensation.call(result)
                    break
                case 2:
                    compensation.call(ctx, result)
                    break
                case 3:
                    compensation.call(ctx, prevResult, result)
                    break
                default:
                    // Fallback - call with all available params
                    compensation.call(ctx, prevResult, result)
            }
            
            compensated = true
            compensatedAt = System.currentTimeMillis()
            
        } catch (Exception e) {
            throw new SagaCompensationException(
                "Failed to compensate step ${id}: ${e.message}",
                this,
                e
            )
        }
    }
    
    /**
     * Check if this step needs compensation.
     */
    boolean needsCompensation() {
        return compensatable && executed && !compensated
    }
    
    @Override
    String toString() {
        def status = executed ? (compensated ? "COMPENSATED" : "EXECUTED") : "PENDING"
        return "SagaStep[${id}, compensatable=${compensatable}, status=${status}]"
    }
}
