package org.softwood.actor.supervision

import groovy.transform.CompileStatic

import java.time.Duration

/**
 * Supervision strategy that determines how to handle actor failures.
 * 
 * <p>Strategies are applied when an actor throws an exception during message processing.
 * The strategy's decider function examines the exception and returns a directive.</p>
 * 
 * <h2>Usage</h2>
 * <pre>
 * // Restart on any exception
 * def strategy = SupervisionStrategy.restartAlways()
 * 
 * // Custom decider
 * def strategy = new OneForOneStrategy(
 *     maxRestarts: 3,
 *     withinDuration: Duration.ofMinutes(1)
 * ) { throwable ->
 *     if (throwable instanceof IllegalArgumentException) {
 *         return SupervisorDirective.RESUME
 *     } else {
 *         return SupervisorDirective.RESTART
 *     }
 * }
 * </pre>
 * 
 * @since 2.0.0
 */
@CompileStatic
abstract class SupervisionStrategy {
    
    /**
     * Maximum number of restarts within the time window.
     * -1 means unlimited.
     */
    int maxRestarts = 10
    
    /**
     * Time window for counting restarts.
     */
    Duration withinDuration = Duration.ofMinutes(1)
    
    /**
     * Whether to use exponential backoff between restarts.
     */
    boolean useExponentialBackoff = false
    
    /**
     * Initial backoff delay (if exponential backoff enabled).
     */
    Duration initialBackoff = Duration.ofMillis(100)
    
    /**
     * Maximum backoff delay (if exponential backoff enabled).
     */
    Duration maxBackoff = Duration.ofSeconds(10)
    
    /**
     * The decider function that determines what to do with an exception.
     */
    Closure<SupervisorDirective> decider
    
    /**
     * Decide what action to take for a given exception.
     * 
     * @param throwable the exception that occurred
     * @param actor the actor that failed (optional for context)
     * @return the directive to apply
     */
    SupervisorDirective decide(Throwable throwable, Object actor = null) {
        if (decider) {
            return decider.call(throwable)
        }
        return defaultDecision(throwable)
    }
    
    /**
     * Default decision if no decider is provided.
     * Subclasses can override this.
     */
    protected SupervisorDirective defaultDecision(Throwable throwable) {
        return SupervisorDirective.RESTART
    }
    
    /**
     * Called when an actor is about to be restarted.
     * Subclasses can override for custom behavior.
     */
    void onRestart(Object actor, Throwable cause) {
        // Override in subclasses
    }
    
    /**
     * Called when an actor is stopped due to supervision.
     * Subclasses can override for custom behavior.
     */
    void onStop(Object actor, Throwable cause) {
        // Override in subclasses
    }
    
    // ================================================================
    // Factory Methods
    // ================================================================
    
    /**
     * Strategy that always restarts on failure.
     */
    static SupervisionStrategy restartAlways() {
        return new OneForOneStrategy({ throwable ->
            SupervisorDirective.RESTART
        })
    }
    
    /**
     * Strategy that always resumes (ignores failures).
     */
    static SupervisionStrategy resumeAlways() {
        return new OneForOneStrategy({ throwable ->
            SupervisorDirective.RESUME
        })
    }
    
    /**
     * Strategy that always stops on failure.
     */
    static SupervisionStrategy stopAlways() {
        return new OneForOneStrategy({ throwable ->
            SupervisorDirective.STOP
        })
    }
    
    /**
     * Strategy that escalates all failures to parent.
     */
    static SupervisionStrategy escalateAlways() {
        return new OneForOneStrategy({ throwable ->
            SupervisorDirective.ESCALATE
        })
    }
    
    /**
     * Default strategy: restart with limits.
     */
    static SupervisionStrategy defaultStrategy() {
        def strategy = new OneForOneStrategy({ throwable ->
            SupervisorDirective.RESTART
        })
        strategy.maxRestarts = 10
        strategy.withinDuration = Duration.ofMinutes(1)
        strategy.useExponentialBackoff = true
        return strategy
    }
}
