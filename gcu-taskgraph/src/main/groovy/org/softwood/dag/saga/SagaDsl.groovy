package org.softwood.dag.saga

import org.softwood.dag.task.ITask

import java.time.Duration

/**
 * DSL for configuring saga tasks.
 * 
 * <p>Provides a fluent interface for building distributed transactions
 * with compensation actions.</p>
 * 
 * <h3>Usage:</h3>
 * <pre>
 * saga("checkout") {
 *     // Add compensatable step
 *     compensatable(sqlTask("reserve-inventory") {
 *         withTransaction { conn -> ... }
 *     }) {
 *         compensate { ctx, result ->
 *             // Release reservation
 *         }
 *     }
 *     
 *     // Add another compensatable step
 *     compensatable(httpTask("charge-payment") {
 *         url "https://payment.api/charge"
 *     }) {
 *         compensate { result ->
 *             // Refund - only needs result
 *         }
 *     }
 *     
 *     // Add non-compensatable step
 *     nonCompensatable(serviceTask("send-email") {
 *         action { ctx, prev -> sendEmail() }
 *     })
 *     
 *     // Configure saga behavior
 *     strategy "retry-then-backward"
 *     maxRetries 3
 *     
 *     // Add callbacks
 *     onStepSuccess { step, result ->
 *         log.info "Completed: ${step.id}"
 *     }
 *     
 *     onSagaFailed { error, compensated ->
 *         log.error "Saga failed, compensated ${compensated.size()} steps"
 *     }
 * }
 * </pre>
 */
class SagaDsl {
    
    /** The saga task being configured */
    private final SagaTask saga
    
    /**
     * Constructor.
     */
    SagaDsl(SagaTask saga) {
        this.saga = saga
    }
    
    // =========================================================================
    // Step Configuration
    // =========================================================================
    
    /**
     * Add a compensatable step using a DSL block.
     * 
     * <h3>Usage:</h3>
     * <pre>
     * compensatable(myTask) {
     *     compensate { ctx, result ->
     *         // Undo action
     *     }
     * }
     * </pre>
     */
    void compensatable(ITask task, @DelegatesTo(CompensationDsl) Closure config) {
        def dsl = new CompensationDsl()
        config.delegate = dsl
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.call()
        
        if (!dsl.compensation) {
            throw new IllegalArgumentException(
                "Compensatable step ${task.id} must define a compensation action. " +
                "Use: compensatable(task) { compensate { ... } }"
            )
        }
        
        saga.addCompensatableStep(task, dsl.compensation)
    }
    
    /**
     * Add a non-compensatable step.
     * 
     * <p>Non-compensatable steps are executed but not rolled back on failure.
     * Use for idempotent operations like logging or notifications.</p>
     * 
     * <h3>Usage:</h3>
     * <pre>
     * nonCompensatable(notificationTask)
     * </pre>
     */
    void nonCompensatable(ITask task) {
        saga.addNonCompensatableStep(task)
    }
    
    // =========================================================================
    // Configuration Methods
    // =========================================================================
    
    /**
     * Set execution strategy.
     * 
     * <p>Available strategies:</p>
     * <ul>
     *   <li>"retry-then-backward" - Try retry first, then compensate (default)</li>
     *   <li>"backward-only" - Immediately compensate on failure</li>
     *   <li>"forward-only" - Keep retrying, never compensate</li>
     * </ul>
     */
    void strategy(String strategyName) {
        saga.strategy(strategyName)
    }
    
    /**
     * Set execution strategy.
     */
    void strategy(SagaExecutionStrategy strategy) {
        saga.strategy(strategy)
    }
    
    /**
     * Set maximum retry attempts for failed steps.
     */
    void maxRetries(int retries) {
        saga.maxRetries(retries)
    }
    
    /**
     * Set maximum compensation attempts.
     */
    void maxCompensationAttempts(int attempts) {
        saga.maxCompensationAttempts(attempts)
    }
    
    /**
     * Set compensation timeout.
     */
    void compensationTimeout(Duration timeout) {
        saga.compensationTimeout(timeout)
    }
    
    // =========================================================================
    // Callback Configuration
    // =========================================================================
    
    /**
     * Set callback for when a step succeeds.
     * 
     * <h3>Signature:</h3>
     * <pre>
     * (SagaStep step, Object result) -> void
     * </pre>
     */
    void onStepSuccess(Closure callback) {
        saga.onStepSuccess(callback)
    }
    
    /**
     * Set callback for when a step fails.
     * 
     * <h3>Signature:</h3>
     * <pre>
     * (SagaStep step, Throwable error) -> void
     * </pre>
     */
    void onStepFailure(Closure callback) {
        saga.onStepFailure(callback)
    }
    
    /**
     * Set callback for when compensation starts.
     * 
     * <h3>Signature:</h3>
     * <pre>
     * (SagaStep step) -> void
     * </pre>
     */
    void onCompensationStart(Closure callback) {
        saga.onCompensationStart(callback)
    }
    
    /**
     * Set callback for when compensation succeeds.
     * 
     * <h3>Signature:</h3>
     * <pre>
     * (SagaStep step) -> void
     * </pre>
     */
    void onCompensationSuccess(Closure callback) {
        saga.onCompensationSuccess(callback)
    }
    
    /**
     * Set callback for when compensation fails.
     * 
     * <h3>Signature:</h3>
     * <pre>
     * (SagaStep step, Throwable error) -> void
     * </pre>
     */
    void onCompensationFailure(Closure callback) {
        saga.onCompensationFailure(callback)
    }
    
    /**
     * Set callback for when saga completes successfully.
     * 
     * <h3>Signature:</h3>
     * <pre>
     * (Map results) -> void
     * </pre>
     */
    void onSagaComplete(Closure callback) {
        saga.onSagaComplete(callback)
    }
    
    /**
     * Set callback for when saga fails.
     * 
     * <h3>Signature:</h3>
     * <pre>
     * (Throwable error, List<SagaStep> compensated) -> void
     * </pre>
     */
    void onSagaFailed(Closure callback) {
        saga.onSagaFailed(callback)
    }
    
    // =========================================================================
    // Helper DSL Classes
    // =========================================================================
    
    /**
     * DSL for configuring compensation.
     */
    static class CompensationDsl {
        Closure compensation
        
        /**
         * Define the compensation action.
         * 
         * <p>The compensation closure can accept 0-3 parameters:</p>
         * <ul>
         *   <li>{ -> ... } - No parameters</li>
         *   <li>{ result -> ... } - Just the step result</li>
         *   <li>{ ctx, result -> ... } - Context and result</li>
         *   <li>{ ctx, prevResult, result -> ... } - Context, previous result, and step result</li>
         * </ul>
         */
        void compensate(Closure closure) {
            this.compensation = closure
        }
    }
}
