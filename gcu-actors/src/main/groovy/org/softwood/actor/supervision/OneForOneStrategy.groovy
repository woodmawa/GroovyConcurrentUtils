package org.softwood.actor.supervision

import groovy.transform.CompileStatic

import java.time.Duration

/**
 * Supervision strategy that restarts only the failed actor.
 * 
 * <p>When an actor fails, only that actor is restarted. Sibling actors
 * are unaffected and continue processing normally.</p>
 * 
 * <p>This is the most common supervision strategy and is appropriate when
 * actor failures are independent.</p>
 * 
 * <h2>Example</h2>
 * <pre>
 * def strategy = new OneForOneStrategy(
 *     maxRestarts: 5,
 *     withinDuration: Duration.ofMinutes(1),
 *     useExponentialBackoff: true
 * ) { throwable ->
 *     switch(throwable) {
 *         case IllegalArgumentException:
 *             return SupervisorDirective.RESUME
 *         case IOException:
 *             return SupervisorDirective.RESTART
 *         default:
 *             return SupervisorDirective.ESCALATE
 *     }
 * }
 * 
 * system.actor {
 *     name 'worker'
 *     supervisionStrategy strategy
 *     onMessage { msg, ctx -> ... }
 * }
 * </pre>
 * 
 * @since 2.0.0
 */
@CompileStatic
class OneForOneStrategy extends SupervisionStrategy {
    
    /**
     * Create with default settings (restart on any failure).
     */
    OneForOneStrategy() {
        this.decider = { throwable -> SupervisorDirective.RESTART }
    }
    
    /**
     * Create with custom decider closure.
     * 
     * @param decider function that decides action based on exception
     */
    OneForOneStrategy(Closure<SupervisorDirective> decider) {
        this.decider = decider
    }
    
    /**
     * Create with configuration map and decider.
     * 
     * @param config configuration (maxRestarts, withinDuration, etc.)
     * @param decider function that decides action based on exception
     */
    OneForOneStrategy(Map config, Closure<SupervisorDirective> decider) {
        if (config.containsKey('maxRestarts')) {
            this.maxRestarts = config.maxRestarts as int
        }
        if (config.containsKey('withinDuration')) {
            this.withinDuration = config.withinDuration as Duration
        }
        if (config.containsKey('useExponentialBackoff')) {
            this.useExponentialBackoff = config.useExponentialBackoff as boolean
        }
        if (config.containsKey('initialBackoff')) {
            this.initialBackoff = config.initialBackoff as Duration
        }
        if (config.containsKey('maxBackoff')) {
            this.maxBackoff = config.maxBackoff as Duration
        }
        this.decider = decider
    }
    
    @Override
    String toString() {
        "OneForOneStrategy[maxRestarts=$maxRestarts, within=$withinDuration, backoff=$useExponentialBackoff]"
    }
}
