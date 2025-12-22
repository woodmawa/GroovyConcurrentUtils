package org.softwood.dag.task

/**
 * Control object returned by TimerTask actions to control timer lifecycle.
 * 
 * Allows the action closure to decide whether the timer should continue
 * or stop, and optionally provide a result value.
 * 
 * <h3>Usage:</h3>
 * <pre>
 * task("poll", TaskType.TIMER) {
 *     interval Duration.ofSeconds(5)
 *     maxExecutions 20  // Safety limit
 *     
 *     action { ctx ->
 *         def status = checkStatus()
 *         
 *         if (status.ready) {
 *             // Stop early with result
 *             return TimerControl.stop(status)
 *         }
 *         
 *         // Continue with current status
 *         return TimerControl.continueWith(status)
 *     }
 * }
 * </pre>
 */
class TimerControl {
    
    /**
     * Singleton for simple continue without result
     */
    static final TimerControl CONTINUE = new TimerControl(false, null)
    
    /**
     * Whether the timer should stop
     */
    final boolean shouldStop
    
    /**
     * Optional result value to return
     */
    final Object result
    
    private TimerControl(boolean shouldStop, Object result) {
        this.shouldStop = shouldStop
        this.result = result
    }
    
    /**
     * Continue timer execution with optional result.
     * The result is recorded as lastResult but timer continues.
     * 
     * @param result optional result to record
     * @return TimerControl indicating continue
     */
    static TimerControl continueWith(Object result) {
        return new TimerControl(false, result)
    }
    
    /**
     * Stop timer execution with optional result.
     * The timer will stop immediately and complete with this result.
     * 
     * @param result optional final result
     * @return TimerControl indicating stop
     */
    static TimerControl stop(Object result) {
        return new TimerControl(true, result)
    }
    
    /**
     * Continue timer execution without specific result.
     * 
     * @return TimerControl indicating continue
     */
    static TimerControl continue_() {
        return CONTINUE
    }
    
    @Override
    String toString() {
        return "TimerControl[shouldStop=${shouldStop}, result=${result}]"
    }
}
