package org.softwood.dag.task

import groovy.util.logging.Slf4j
import org.softwood.promise.Promise

import java.time.Duration
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * BusinessRuleTask - Condition-Based Reactive Task Execution
 *
 * Executes an action when a business rule condition is met.
 * Supports multiple trigger mechanisms: signals, polling, or explicit conditions.
 *
 * <h3>Key Features:</h3>
 * <ul>
 *   <li>Signal-based triggering (event-driven)</li>
 *   <li>Polling-based triggering (condition checking)</li>
 *   <li>Rule evaluation with true/false actions</li>
 *   <li>Priority-based execution</li>
 *   <li>Retry on failure</li>
 * </ul>
 *
 * <h3>DSL Example - Signal Trigger:</h3>
 * <pre>
 * businessRule("approval") {
 *     when {
 *         signal "approval-request"
 *     }
 *     
 *     evaluate { ctx, data ->
 *         data.amount < 1000 && data.approved
 *     }
 *     
 *     action { ctx, data ->
 *         approveRequest(data.id)
 *         SignalTask.sendSignalGlobal("approved", data)
 *     }
 *     
 *     onFalse { ctx, data ->
 *         log.warn("Approval rule failed for ${data.id}")
 *     }
 * }
 * </pre>
 *
 * <h3>DSL Example - Polling Trigger:</h3>
 * <pre>
 * businessRule("threshold") {
 *     when {
 *         poll Duration.ofSeconds(10)
 *         condition { ctx.globals.pending > 100 }
 *     }
 *     
 *     evaluate { ctx, data ->
 *         ctx.globals.pending > ctx.globals.threshold
 *     }
 *     
 *     action { ctx, data ->
 *         handleHighLoad()
 *     }
 * }
 * </pre>
 *
 * <h3>DSL Example - Mixed Triggers:</h3>
 * <pre>
 * businessRule("alert") {
 *     when {
 *         signal "metric-update"
 *         poll Duration.ofSeconds(30)
 *     }
 *     
 *     evaluate { ctx, data ->
 *         data.value > threshold
 *     }
 *     
 *     action { ctx, data ->
 *         sendAlert(data)
 *     }
 *     
 *     priority 10
 *     retryOnFailure true
 * }
 * </pre>
 */
@Slf4j
class BusinessRuleTask extends TaskBase<Map> {

    // =========================================================================
    // Configuration
    // =========================================================================
    
    /** Signal name to wait for */
    String triggerSignal
    
    /** Polling interval for condition checking */
    Duration pollingInterval
    
    /** Trigger condition: (ctx) -> boolean */
    Closure triggerCondition
    
    /** Rule evaluation: (ctx, data) -> boolean */
    Closure evaluationRule
    
    /** Action when rule evaluates to true: (ctx, data) -> result */
    Closure trueAction
    
    /** Action when rule evaluates to false: (ctx, data) -> void */
    Closure falseAction
    
    /** Execution priority (higher = more important) */
    int priority = 0
    
    /** Retry on failure? */
    boolean retryOnFailure = false
    
    /** Maximum wait time for trigger */
    Duration timeout
    
    // =========================================================================
    // Internal State
    // =========================================================================
    
    private Promise<Map> resultPromise
    private volatile boolean triggered = false
    private volatile boolean completed = false
    private ScheduledFuture<?> pollingFuture
    private Timer timeoutTimer
    private Object triggerData

    BusinessRuleTask(String id, String name, TaskContext ctx) {
        super(id, name, ctx)
    }

    // =========================================================================
    // DSL Methods
    // =========================================================================
    
    /**
     * Configure trigger conditions.
     * Use nested closures for configuration.
     */
    void when(@DelegatesTo(TriggerConfig) Closure config) {
        def triggerConfig = new TriggerConfig()
        config.delegate = triggerConfig
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.call()
        
        this.triggerSignal = triggerConfig.signalName
        this.pollingInterval = triggerConfig.pollInterval
        this.triggerCondition = triggerConfig.condition
    }
    
    void evaluate(Closure rule) {
        this.evaluationRule = rule
    }
    
    void action(Closure action) {
        this.trueAction = action
    }
    
    void onFalse(Closure action) {
        this.falseAction = action
    }
    
    void onTrue(Closure action) {
        this.trueAction = action
    }
    
    void priority(int p) {
        this.priority = p
    }
    
    void retryOnFailure(boolean retry) {
        this.retryOnFailure = retry
    }
    
    void timeout(Duration d) {
        this.timeout = d
    }

    // =========================================================================
    // Task Execution
    // =========================================================================

    @Override
    protected Promise<Map> runTask(TaskContext ctx, Object prevValue) {
        
        log.debug("BusinessRuleTask($id): starting execution")
        
        // Validate configuration
        validateConfiguration()
        
        // Create result promise
        resultPromise = ctx.promiseFactory.createPromise()
        
        // Set up timeout if configured
        if (timeout) {
            scheduleTimeout()
        }
        
        // Set up triggers
        setupTriggers(ctx)
        
        return resultPromise
    }
    
    private void validateConfiguration() {
        if (!triggerSignal && !pollingInterval && !triggerCondition) {
            throw new IllegalStateException(
                "BusinessRuleTask($id): requires at least one trigger " +
                "(signal, polling, or condition)"
            )
        }
        
        if (!evaluationRule && !trueAction) {
            throw new IllegalStateException(
                "BusinessRuleTask($id): requires either evaluate or action"
            )
        }
    }

    // =========================================================================
    // Trigger Setup
    // =========================================================================
    
    private void setupTriggers(TaskContext ctx) {
        // Signal trigger
        if (triggerSignal) {
            log.debug("BusinessRuleTask($id): registering for signal '$triggerSignal'")
            
            // Synchronize with sendSignalGlobal to prevent race condition
            synchronized (SignalTask.SIGNAL_REGISTRY) {
                // Check if signal already exists
                def existingSignals = SignalTask.SIGNAL_REGISTRY.get(triggerSignal)
                if (existingSignals && !existingSignals.isEmpty()) {
                    // Signal already sent - process immediately
                    def signalData = existingSignals.remove(0)
                    if (existingSignals.isEmpty()) {
                        SignalTask.SIGNAL_REGISTRY.remove(triggerSignal)
                    }
                    onTrigger(ctx, signalData)
                } else {
                    // Register to wait for signal
                    SignalTask.WAITING_TASKS.computeIfAbsent(triggerSignal, { k -> 
                        Collections.synchronizedList([])
                    }).add(new SignalListener(ctx))
                }
            }
        }
        
        // Polling trigger
        if (pollingInterval) {
            log.debug("BusinessRuleTask($id): starting polling every ${pollingInterval.toMillis()}ms")
            startPolling(ctx)
        }
        
        // Immediate condition check
        if (triggerCondition && !triggerSignal && !pollingInterval) {
            log.debug("BusinessRuleTask($id): checking immediate condition")
            checkConditionAndTrigger(ctx, null)
        }
    }
    
    private void startPolling(TaskContext ctx) {
        def scheduler = ctx.promiseFactory.pool.scheduledExecutor
        
        pollingFuture = scheduler.scheduleWithFixedDelay(
            { checkConditionAndTrigger(ctx, null) },
            0,
            pollingInterval.toMillis(),
            TimeUnit.MILLISECONDS
        )
    }
    
    private void checkConditionAndTrigger(TaskContext ctx, Object data) {
        if (completed || triggered) {
            return
        }
        
        try {
            if (triggerCondition) {
                boolean shouldTrigger = triggerCondition.call(ctx)
                if (shouldTrigger) {
                    log.info("BusinessRuleTask($id): trigger condition met")
                    onTrigger(ctx, data)
                }
            } else {
                // No condition - trigger immediately
                onTrigger(ctx, data)
            }
        } catch (Exception e) {
            log.error("BusinessRuleTask($id): error checking trigger condition", e)
        }
    }

    // =========================================================================
    // Trigger Handling
    // =========================================================================
    
    private synchronized void onTrigger(TaskContext ctx, Object data) {
        if (completed || triggered) {
            return
        }
        
        triggered = true
        triggerData = data
        
        log.info("BusinessRuleTask($id): triggered")
        
        // Stop polling if active
        if (pollingFuture) {
            pollingFuture.cancel(false)
        }
        
        // Cancel timeout
        cancelTimeout()
        
        // Evaluate rule and execute action
        executeRule(ctx, data)
    }

    // =========================================================================
    // Rule Evaluation and Execution
    // =========================================================================
    
    private void executeRule(TaskContext ctx, Object data) {
        try {
            boolean ruleResult = true
            
            // Evaluate rule if configured
            if (evaluationRule) {
                log.debug("BusinessRuleTask($id): evaluating rule")
                ruleResult = evaluationRule.call(ctx, data)
                log.info("BusinessRuleTask($id): rule evaluated to $ruleResult")
            }
            
            Object actionResult = null
            
            // Execute appropriate action
            if (ruleResult && trueAction) {
                log.debug("BusinessRuleTask($id): executing true action")
                actionResult = trueAction.call(ctx, data)
            } else if (!ruleResult && falseAction) {
                log.debug("BusinessRuleTask($id): executing false action")
                actionResult = falseAction.call(ctx, data)
            }
            
            // Complete successfully
            completeRule(ruleResult, actionResult, null)
            
        } catch (Exception e) {
            log.error("BusinessRuleTask($id): error executing rule", e)
            
            if (retryOnFailure) {
                log.info("BusinessRuleTask($id): will retry on next trigger")
                
                // Reset state to allow retry
                triggered = false
                
                // Re-register for signal if signal-based trigger
                if (triggerSignal) {
                    synchronized (SignalTask.SIGNAL_REGISTRY) {
                        // Check if signal already exists (might have arrived while processing)
                        def existingSignals = SignalTask.SIGNAL_REGISTRY.get(triggerSignal)
                        if (existingSignals && !existingSignals.isEmpty()) {
                            // Signal already waiting - process it
                            def signalData = existingSignals.remove(0)
                            if (existingSignals.isEmpty()) {
                                SignalTask.SIGNAL_REGISTRY.remove(triggerSignal)
                            }
                            onTrigger(ctx, signalData)
                        } else {
                            // Re-register listener for next signal
                            SignalTask.WAITING_TASKS.computeIfAbsent(triggerSignal, { k -> 
                                Collections.synchronizedList([])
                            }).add(new SignalListener(ctx))
                            log.debug("BusinessRuleTask($id): re-registered listener for retry")
                        }
                    }
                }
                
                // Note: Polling-based triggers will automatically retry on next poll
            } else {
                failRule(e)
            }
        }
    }

    // =========================================================================
    // Timeout Handling
    // =========================================================================
    
    private void scheduleTimeout() {
        log.debug("BusinessRuleTask($id): scheduling timeout for ${timeout.toMillis()}ms")
        
        timeoutTimer = new Timer("BusinessRuleTask-${id}-Timeout", true)
        timeoutTimer.schedule(new java.util.TimerTask() {
            @Override
            void run() {
                handleTimeout()
            }
        }, timeout.toMillis())
    }
    
    private void handleTimeout() {
        synchronized (this) {
            if (!completed && !triggered) {
                log.warn("BusinessRuleTask($id): timeout waiting for trigger")
                failRule(new java.util.concurrent.TimeoutException(
                    "BusinessRuleTask timeout waiting for trigger"
                ))
            }
        }
    }
    
    private void cancelTimeout() {
        if (timeoutTimer) {
            log.debug("BusinessRuleTask($id): cancelling timeout")
            timeoutTimer.cancel()
            timeoutTimer = null
        }
    }

    // =========================================================================
    // Completion
    // =========================================================================
    
    private synchronized void completeRule(boolean ruleResult, Object actionResult, String reason) {
        if (completed) {
            return
        }
        
        completed = true
        
        // Stop polling if active
        if (pollingFuture) {
            pollingFuture.cancel(false)
        }
        
        // Cancel timeout
        cancelTimeout()
        
        // Build result
        def result = [
            status: "completed",
            ruleResult: ruleResult,
            actionResult: actionResult,
            triggerData: triggerData,
            priority: priority,
            reason: reason
        ]
        
        log.info("BusinessRuleTask($id): completed, rule=$ruleResult")
        
        // Resolve promise
        resultPromise.accept(result)
    }
    
    private synchronized void failRule(Throwable error) {
        if (completed) {
            return
        }
        
        completed = true
        
        // Stop polling if active
        if (pollingFuture) {
            pollingFuture.cancel(false)
        }
        
        // Cancel timeout
        cancelTimeout()
        
        log.error("BusinessRuleTask($id): failed")
        
        // Fail promise
        resultPromise.fail(error)
    }

    // =========================================================================
    // Signal Listener
    // =========================================================================
    
    private class SignalListener implements ISignalReceiver {
        final TaskContext ctx
        
        SignalListener(TaskContext ctx) {
            this.ctx = ctx
        }
        
        @Override
        void receiveSignal(Map signalData) {
            log.info("BusinessRuleTask($id): received signal '$triggerSignal'")
            onTrigger(ctx, signalData)
        }
    }

    // =========================================================================
    // Trigger Configuration DSL
    // =========================================================================
    
    static class TriggerConfig {
        String signalName
        Duration pollInterval
        Closure condition
        
        void signal(String name) {
            this.signalName = name
        }
        
        void poll(Duration interval) {
            this.pollInterval = interval
        }
        
        void condition(Closure cond) {
            this.condition = cond
        }
    }
}
