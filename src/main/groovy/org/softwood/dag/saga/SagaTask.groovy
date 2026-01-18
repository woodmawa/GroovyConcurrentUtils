package org.softwood.dag.saga

import groovy.util.logging.Slf4j
import org.softwood.dag.task.ITask
import org.softwood.dag.task.TaskBase
import org.softwood.dag.task.TaskContext
import org.softwood.promise.Promise

import java.time.Duration

/**
 * Saga task for managing distributed transactions.
 * 
 * <p>A saga coordinates multiple tasks as a single logical transaction,
 * with compensation actions to undo completed steps if a later step fails.</p>
 * 
 * <h3>Basic Usage:</h3>
 * <pre>
 * saga("checkout") {
 *     compensatable(sqlTask("reserve-inventory") {
 *         withTransaction { conn -> ... }
 *     }) {
 *         compensate { ctx, result ->
 *             // Release reservation
 *         }
 *     }
 *     
 *     compensatable(httpTask("charge-payment") {
 *         url "https://payment.api/charge"
 *     }) {
 *         compensate { result ->
 *             // Refund payment
 *         }
 *     }
 *     
 *     nonCompensatable(serviceTask("send-email") {
 *         action { ctx, prev -> sendEmail() }
 *     })
 * }
 * </pre>
 * 
 * @since 2.3.0
 */
@Slf4j
class SagaTask extends TaskBase<Map<String, Object>> {
    
    // =========================================================================
    // Configuration
    // =========================================================================
    
    /** Saga steps (in execution order) */
    private final List<SagaStep> steps = []
    
    /** Execution strategy */
    private SagaExecutionStrategy strategy = SagaExecutionStrategy.RETRY_THEN_BACKWARD
    
    /** Maximum retry attempts for failed saga steps */
    private int maxStepRetries = 3
    
    /** Maximum attempts to compensate a step */
    private int maxCompensationAttempts = 3
    
    /** Timeout for compensation operations */
    private Duration compensationTimeout = Duration.ofSeconds(30)
    
    // =========================================================================
    // Callbacks
    // =========================================================================
    
    /** Callback when a step succeeds: (SagaStep, Object result) -> void */
    private Closure onStepSuccess
    
    /** Callback when a step fails: (SagaStep, Throwable error) -> void */
    private Closure onStepFailure
    
    /** Callback when compensation starts: (SagaStep) -> void */
    private Closure onCompensationStart
    
    /** Callback when compensation succeeds: (SagaStep) -> void */
    private Closure onCompensationSuccess
    
    /** Callback when compensation fails: (SagaStep, Throwable error) -> void */
    private Closure onCompensationFailure
    
    /** Callback when saga completes successfully: (Map results) -> void */
    private Closure onSagaComplete
    
    /** Callback when saga fails: (Throwable error, List<SagaStep> compensated) -> void */
    private Closure onSagaFailed
    
    // =========================================================================
    // Runtime State
    // =========================================================================
    
    /** Compensation log */
    private final CompensationLog compensationLog = new CompensationLog()
    
    /** Results from each step (keyed by task ID) */
    private final Map<String, Object> stepResults = [:]
    
    // =========================================================================
    // Constructor
    // =========================================================================
    
    SagaTask(String id, String name, ctx) {
        super(id, name, ctx)
        // Disable TaskBase retries - saga has its own compensation logic
        // Now that we renamed SagaTask's field to maxStepRetries,
        // this will set TaskBase's maxRetries property
        this.maxRetries = 1  // Only try saga once, then compensate
    }
    
    // =========================================================================
    // Step Configuration
    // =========================================================================
    
    /**
     * Add a compensatable step.
     */
    void addCompensatableStep(ITask task, Closure compensation) {
        // Disable TaskBase retries on saga steps - saga handles its own retry logic
        if (task instanceof TaskBase) {
            task.maxRetries = 1
        }
        
        def step = new SagaStep(task, compensation)
        steps << step
        log.debug "Saga ${id}: added compensatable step ${task.id}"
    }
    
    /**
     * Add a non-compensatable step.
     */
    void addNonCompensatableStep(ITask task) {
        // Disable TaskBase retries on saga steps - saga handles its own retry logic
        if (task instanceof TaskBase) {
            task.maxRetries = 1
        }
        
        def step = new SagaStep(task)
        steps << step
        log.debug "Saga ${id}: added non-compensatable step ${task.id}"
    }
    
    // =========================================================================
    // Configuration Methods
    // =========================================================================
    
    /**
     * Set execution strategy.
     */
    void strategy(String strategyName) {
        this.strategy = SagaExecutionStrategy.fromString(strategyName)
    }
    
    /**
     * Set execution strategy.
     */
    void strategy(SagaExecutionStrategy strategy) {
        this.strategy = strategy
    }
    
    /**
     * Set maximum retry attempts for saga steps.
     */
    void maxStepRetries(int retries) {
        this.maxStepRetries = retries
    }
    
    /**
     * Set maximum compensation attempts.
     */
    void maxCompensationAttempts(int attempts) {
        this.maxCompensationAttempts = attempts
    }
    
    /**
     * Set compensation timeout.
     */
    void compensationTimeout(Duration timeout) {
        this.compensationTimeout = timeout
    }
    
    /**
     * Set step success callback.
     */
    void onStepSuccess(Closure callback) {
        this.onStepSuccess = callback
    }
    
    /**
     * Set step failure callback.
     */
    void onStepFailure(Closure callback) {
        this.onStepFailure = callback
    }
    
    /**
     * Set compensation start callback.
     */
    void onCompensationStart(Closure callback) {
        this.onCompensationStart = callback
    }
    
    /**
     * Set compensation success callback.
     */
    void onCompensationSuccess(Closure callback) {
        this.onCompensationSuccess = callback
    }
    
    /**
     * Set compensation failure callback.
     */
    void onCompensationFailure(Closure callback) {
        this.onCompensationFailure = callback
    }
    
    /**
     * Set saga complete callback.
     */
    void onSagaComplete(Closure callback) {
        this.onSagaComplete = callback
    }
    
    /**
     * Set saga failed callback.
     */
    void onSagaFailed(Closure callback) {
        this.onSagaFailed = callback
    }
    
    // =========================================================================
    // Task Execution
    // =========================================================================
    
    @Override
    protected Promise<Map<String, Object>> runTask(TaskContext ctx, Object prevValue) {
        log.info "Saga ${id}: starting with ${steps.size()} steps, strategy: ${strategy}"
        
        try {
            // Execute steps sequentially
            executeSteps(ctx, prevValue)
            
            // All steps succeeded
            log.info "Saga ${id}: completed successfully"
            invokeCallback(onSagaComplete, stepResults)
            
            // Return saga metadata
            def result = [
                success: true,
                completedSteps: steps.size(),
                results: stepResults
            ]
            return ctx.promiseFactory.createPromise(result)
            
        } catch (Throwable error) {
            log.error "Saga ${id}: failed at step, initiating compensation", error
            
            // Execute compensation based on strategy
            def compensated = performCompensation(ctx, prevValue, error)
            
            // Invoke failure callback
            invokeCallback(onSagaFailed, error, compensated)
            
            // Re-throw original error
            throw new SagaExecutionException(
                "Saga ${id} failed: ${error.message}",
                error,
                compensated
            )
        }
    }
    
    /**
     * Execute all saga steps sequentially.
     */
    private void executeSteps(TaskContext ctx, Object prevValue) {
        Object currentValue = prevValue
        
        for (SagaStep step : steps) {
            log.debug "Saga ${id}: executing step ${step.id}"
            
            try {
                // Execute the task
                def task = step.task
                task.ctx = ctx  // Ensure task has context
                
                def promise = task.execute(ctx.promiseFactory.createPromise(currentValue))
                def result = promise.get()
                
                // Record result
                stepResults[step.id] = result
                
                // Log for compensation
                compensationLog.record(step, result)
                
                // Invoke success callback
                invokeCallback(onStepSuccess, step, result)
                
                log.debug "Saga ${id}: step ${step.id} succeeded"
                
                // Pass result to next step
                currentValue = result
                
            } catch (Throwable error) {
                log.error "Saga ${id}: step ${step.id} failed", error
                invokeCallback(onStepFailure, step, error)
                throw error
            }
        }
    }
    
    /**
     * Perform compensation based on strategy.
     */
    private List<SagaStep> performCompensation(TaskContext ctx, Object prevValue, Throwable originalError) {
        def compensated = []
        
        switch (strategy) {
            case SagaExecutionStrategy.BACKWARD_ONLY:
                compensated = compensateAllSteps(ctx, prevValue)
                break
                
            case SagaExecutionStrategy.FORWARD_ONLY:
                log.warn "Saga ${id}: forward-only strategy, no compensation performed"
                break
                
            case SagaExecutionStrategy.RETRY_THEN_BACKWARD:
                // Try to retry failed step
                // If retries exhausted, compensate
                log.info "Saga ${id}: retry-then-backward not yet implemented, falling back to backward"
                compensated = compensateAllSteps(ctx, prevValue)
                break
        }
        
        return compensated
    }
    
    /**
     * Compensate all completed steps in reverse order.
     */
    private List<SagaStep> compensateAllSteps(TaskContext ctx, Object prevValue) {
        def compensated = []
        def stepsToCompensate = compensationLog.getStepsToCompensate()
        
        if (stepsToCompensate.isEmpty()) {
            log.info "Saga ${id}: no steps to compensate"
            return compensated
        }
        
        log.info "Saga ${id}: compensating ${stepsToCompensate.size()} steps in reverse order"
        
        for (SagaStep step : stepsToCompensate) {
            try {
                compensateStep(step, ctx, prevValue)
                compensated << step
                
            } catch (SagaCompensationException e) {
                log.error "Saga ${id}: failed to compensate step ${step.id}", e
                invokeCallback(onCompensationFailure, step, e)
                
                // Continue compensating other steps even if one fails
                // This is debatable - could also stop here
            }
        }
        
        log.info "Saga ${id}: compensated ${compensated.size()} of ${stepsToCompensate.size()} steps"
        return compensated
    }
    
    /**
     * Compensate a single step with retries.
     */
    private void compensateStep(SagaStep step, TaskContext ctx, Object prevValue) {
        log.info "Saga ${id}: compensating step ${step.id}"
        invokeCallback(onCompensationStart, step)
        
        int attempt = 1
        Throwable lastError = null
        
        while (attempt <= maxCompensationAttempts) {
            try {
                step.compensate(ctx, prevValue)
                compensationLog.markCompensated(step)
                
                log.info "Saga ${id}: successfully compensated step ${step.id}"
                invokeCallback(onCompensationSuccess, step)
                return
                
            } catch (SagaCompensationException e) {
                lastError = e
                log.warn "Saga ${id}: compensation attempt ${attempt} failed for step ${step.id}: ${e.message}"
                
                if (attempt < maxCompensationAttempts) {
                    Thread.sleep(1000 * attempt)  // Exponential backoff
                }
                attempt++
            }
        }
        
        // All attempts failed
        throw new SagaCompensationException(
            "Failed to compensate step ${step.id} after ${maxCompensationAttempts} attempts",
            step,
            lastError
        )
    }
    
    /**
     * Safely invoke a callback.
     */
    private void invokeCallback(Closure callback, Object... args) {
        if (callback) {
            try {
                callback.call(*args)
            } catch (Exception e) {
                log.error "Saga ${id}: error in callback", e
            }
        }
    }
    
    // =========================================================================
    // Getters
    // =========================================================================
    
    /**
     * Get the compensation log.
     */
    CompensationLog getCompensationLog() {
        return compensationLog
    }
    
    /**
     * Get all steps.
     */
    List<SagaStep> getSteps() {
        return new ArrayList<>(steps)
    }
}
