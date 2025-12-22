package org.softwood.dag.task

import groovy.util.logging.Slf4j
import org.softwood.promise.Promise

import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.Timer
import java.util.TimerTask as JTimerTask

/**
 * TimerTask - Periodic or Scheduled Task Execution
 *
 * Executes an action repeatedly at fixed intervals or according to a schedule.
 * Supports multiple stop conditions and optional result accumulation.
 *
 * <h3>Key Features:</h3>
 * <ul>
 *   <li>Fixed interval/rate execution</li>
 *   <li>Multiple stop conditions (count, timeout, dynamic, signal)</li>
 *   <li>Optional result accumulation across executions</li>
 *   <li>Action-controlled lifecycle</li>
 *   <li>Rich aggregate result for successors</li>
 * </ul>
 *
 * <h3>DSL Example - Simple Polling:</h3>
 * <pre>
 * task("poll", TaskType.TIMER) {
 *     interval Duration.ofSeconds(5)
 *     maxExecutions 20
 *     
 *     action { ctx ->
 *         def status = checkStatus()
 *         if (status.ready) {
 *             return TimerControl.stop(status)
 *         }
 *         return status
 *     }
 * }
 * </pre>
 *
 * <h3>DSL Example - Data Collection:</h3>
 * <pre>
 * task("collect", TaskType.TIMER) {
 *     interval Duration.ofMillis(100)
 *     maxExecutions 10
 *     
 *     accumulate { ctx, acc, sample ->
 *         acc.samples = acc.samples ?: []
 *         acc.samples << sample
 *         acc.avg = acc.samples.sum() / acc.samples.size()
 *         return acc
 *     }
 *     
 *     action { ctx ->
 *         return readSensor()
 *     }
 * }
 * </pre>
 *
 * <h3>Result Structure:</h3>
 * <pre>
 * [
 *     status: "completed",        // "completed", "stopped", "failed"
 *     executionCount: N,
 *     startedAt: LocalDateTime,
 *     completedAt: LocalDateTime,
 *     duration: Duration,
 *     lastResult: <result>,
 *     accumulator: <data>,
 *     stoppedBy: <reason>
 * ]
 * </pre>
 */
@Slf4j
class TimerTask extends TaskBase<Map> {

    // =========================================================================
    // Configuration
    // =========================================================================
    
    /** Interval between executions (scheduleWithFixedDelay) */
    Duration interval
    
    /** Fixed rate execution (scheduleAtFixedRate) */
    Duration rate
    
    /** Initial delay before first execution */
    Duration initialDelay = Duration.ZERO
    
    /** Maximum number of executions (stop condition) */
    Integer maxExecutions
    
    /** Maximum duration (stop condition) */
    Duration timeout
    
    /** Dynamic stop condition: (ctx, executionCount) -> boolean */
    Closure stopWhenCondition
    
    /** Signal name to listen for stop (stop condition) */
    String stopOnSignal
    
    /** Action to execute on each tick: (ctx) -> result */
    Closure timerAction
    
    /** Optional accumulator: (ctx, accumulator, lastResult) -> accumulator */
    Closure accumulatorFn
    
    /** Stop on first error? */
    boolean stopOnError = true
    
    // =========================================================================
    // Internal State
    // =========================================================================
    
    private volatile int executionCount = 0
    private volatile Map accumulator = [:]
    private volatile Object lastResult
    private volatile boolean stopped = false
    private volatile String stopReason
    private LocalDateTime startedAt
    private LocalDateTime completedAt
    
    private ScheduledFuture<?> scheduledFuture
    private Promise<Map> resultPromise
    private Timer timeoutTimer

    TimerTask(String id, String name, TaskContext ctx) {
        super(id, name, ctx)
    }

    // =========================================================================
    // DSL Methods
    // =========================================================================
    
    void interval(Duration duration) {
        this.interval = duration
    }
    
    void rate(Duration duration) {
        this.rate = duration
    }
    
    void initialDelay(Duration duration) {
        this.initialDelay = duration
    }
    
    void maxExecutions(int max) {
        this.maxExecutions = max
    }
    
    void timeout(Duration duration) {
        this.timeout = duration
    }
    
    void stopWhen(Closure condition) {
        this.stopWhenCondition = condition
    }
    
    void stopOn(String signalName) {
        this.stopOnSignal = signalName
    }
    
    void action(Closure action) {
        this.timerAction = action
    }
    
    void accumulate(Closure accumulator) {
        this.accumulatorFn = accumulator
    }
    
    void stopOnError(boolean stop) {
        this.stopOnError = stop
    }

    // =========================================================================
    // Lifecycle Control
    // =========================================================================
    
    /**
     * Manually stop the timer.
     * Called by external code or signal handlers.
     */
    synchronized void stop() {
        if (!stopped) {
            log.info("TimerTask($id): stop() called")
            stopped = true
            stopReason = "manual"
            completeTimer()
        }
    }

    // =========================================================================
    // Task Execution
    // =========================================================================

    @Override
    protected Promise<Map> runTask(TaskContext ctx, Object prevValue) {
        
        log.debug("TimerTask($id): starting execution")
        
        // Validate configuration
        validateConfiguration()
        
        // Create result promise
        resultPromise = ctx.promiseFactory.createPromise()
        
        // Register for stop signal if configured
        if (stopOnSignal) {
            SignalTask.WAITING_TASKS.computeIfAbsent(stopOnSignal, { k -> 
                Collections.synchronizedList([])
            }).add(new SignalListener())
        }
        
        // Start timeout timer if configured
        if (timeout) {
            scheduleTimeout()
        }
        
        // Record start time
        startedAt = LocalDateTime.now()
        
        // Schedule the timer
        startTimer(ctx)
        
        return resultPromise
    }
    
    private void validateConfiguration() {
        if (!interval && !rate) {
            throw new IllegalStateException("TimerTask($id): requires interval or rate")
        }
        
        if (interval && rate) {
            throw new IllegalStateException("TimerTask($id): cannot specify both interval and rate")
        }
        
        if (!timerAction) {
            throw new IllegalStateException("TimerTask($id): requires action")
        }
        
        // In TaskGraph context, require at least one stop condition
        if (isInGraph() && !hasStopCondition()) {
            throw new IllegalStateException(
                "TimerTask($id): TaskGraph requires at least one stop condition " +
                "(maxExecutions, timeout, stopWhen, or stopOn)"
            )
        }
    }
    
    private boolean isInGraph() {
        // Task is in graph if it has predecessors or successors
        return !predecessors.isEmpty() || !successors.isEmpty()
    }
    
    private boolean hasStopCondition() {
        return maxExecutions != null || 
               timeout != null || 
               stopWhenCondition != null || 
               stopOnSignal != null
    }

    // =========================================================================
    // Timer Scheduling
    // =========================================================================
    
    private void startTimer(TaskContext ctx) {
        def scheduler = ctx.promiseFactory.pool.scheduledExecutor
        
        long delayMs = initialDelay.toMillis()
        long periodMs = (interval ?: rate).toMillis()
        
        if (interval) {
            // Fixed delay - wait after each execution completes
            log.debug("TimerTask($id): scheduling with fixed delay ${periodMs}ms")
            scheduledFuture = scheduler.scheduleWithFixedDelay(
                { executeTimerAction(ctx) },
                delayMs,
                periodMs,
                TimeUnit.MILLISECONDS
            )
        } else {
            // Fixed rate - execute at fixed intervals
            log.debug("TimerTask($id): scheduling with fixed rate ${periodMs}ms")
            scheduledFuture = scheduler.scheduleAtFixedRate(
                { executeTimerAction(ctx) },
                delayMs,
                periodMs,
                TimeUnit.MILLISECONDS
            )
        }
    }
    
    private void executeTimerAction(TaskContext ctx) {
        // Check if already stopped
        if (stopped) {
            return
        }
        
        executionCount++
        log.debug("TimerTask($id): execution #$executionCount")
        
        try {
            // Execute the action
            def result = timerAction.call(ctx)
            
            // Handle TimerControl response
            if (result instanceof TimerControl) {
                lastResult = result.result
                if (result.shouldStop) {
                    log.info("TimerTask($id): action requested stop")
                    stopReason = "action"
                    stopped = true
                }
            } else {
                lastResult = result
            }
            
            // Accumulate if configured
            if (accumulatorFn) {
                accumulator = accumulatorFn.call(ctx, accumulator, lastResult) ?: [:]
            }
            
            // Check stop conditions
            checkStopConditions(ctx)
            
            // Complete if stopped
            if (stopped) {
                completeTimer()
            }
            
        } catch (Exception e) {
            log.error("TimerTask($id): error in execution #$executionCount", e)
            
            if (stopOnError) {
                log.info("TimerTask($id): stopping due to error")
                stopReason = "error"
                stopped = true
                failTimer(e)
            }
        }
    }
    
    private void checkStopConditions(TaskContext ctx) {
        if (stopped) return
        
        // Check maxExecutions
        if (maxExecutions != null && executionCount >= maxExecutions) {
            log.info("TimerTask($id): reached maxExecutions ($maxExecutions)")
            stopReason = "maxExecutions"
            stopped = true
            return
        }
        
        // Check stopWhen condition
        if (stopWhenCondition) {
            try {
                boolean shouldStop = stopWhenCondition.call(ctx, executionCount)
                if (shouldStop) {
                    log.info("TimerTask($id): stopWhen condition met")
                    stopReason = "condition"
                    stopped = true
                    return
                }
            } catch (Exception e) {
                log.error("TimerTask($id): error evaluating stopWhen condition", e)
            }
        }
    }

    // =========================================================================
    // Timeout Handling
    // =========================================================================
    
    private void scheduleTimeout() {
        log.debug("TimerTask($id): scheduling timeout for ${timeout.toMillis()}ms")
        
        timeoutTimer = new Timer("TimerTask-${id}-Timeout", true)
        timeoutTimer.schedule(new JTimerTask() {
            @Override
            void run() {
                handleTimeout()
            }
        }, timeout.toMillis())
    }
    
    private void handleTimeout() {
        synchronized (this) {
            if (!stopped) {
                log.warn("TimerTask($id): timeout after ${timeout}")
                stopReason = "timeout"
                stopped = true
                completeTimer()
            }
        }
    }
    
    private void cancelTimeout() {
        if (timeoutTimer) {
            log.debug("TimerTask($id): cancelling timeout")
            timeoutTimer.cancel()
            timeoutTimer = null
        }
    }

    // =========================================================================
    // Completion
    // =========================================================================
    
    private synchronized void completeTimer() {
        if (resultPromise.isDone()) {
            return
        }
        
        // Cancel scheduled execution
        if (scheduledFuture) {
            scheduledFuture.cancel(false)
        }
        
        // Cancel timeout
        cancelTimeout()
        
        // Unregister from signal
        if (stopOnSignal) {
            // Note: Would need reference to listener to remove
        }
        
        // Record completion time
        completedAt = LocalDateTime.now()
        
        // Build result
        def result = buildResult("completed")
        
        log.info("TimerTask($id): completed after $executionCount executions, reason: $stopReason")
        
        // Resolve promise
        resultPromise.accept(result)
    }
    
    private synchronized void failTimer(Throwable error) {
        if (resultPromise.isDone()) {
            return
        }
        
        // Cancel scheduled execution
        if (scheduledFuture) {
            scheduledFuture.cancel(false)
        }
        
        // Cancel timeout
        cancelTimeout()
        
        // Record completion time
        completedAt = LocalDateTime.now()
        
        // Build result
        def result = buildResult("failed")
        result.error = error.message
        result.exception = error.class.name
        
        log.error("TimerTask($id): failed after $executionCount executions")
        
        // Fail promise
        resultPromise.fail(error)
    }
    
    private Map buildResult(String status) {
        return [
            status: status,
            executionCount: executionCount,
            startedAt: startedAt,
            completedAt: completedAt,
            duration: completedAt ? 
                Duration.between(startedAt, completedAt) : 
                Duration.ZERO,
            lastResult: lastResult,
            accumulator: accumulator,
            stoppedBy: stopReason
        ]
    }

    // =========================================================================
    // Signal Listener
    // =========================================================================
    
    private class SignalListener {
        void receiveSignal(Map signalData) {
            log.info("TimerTask($id): received stop signal")
            stopReason = "signal"
            stopped = true
            completeTimer()
        }
    }
}
