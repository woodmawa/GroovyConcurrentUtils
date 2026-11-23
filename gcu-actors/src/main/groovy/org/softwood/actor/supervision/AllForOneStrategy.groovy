package org.softwood.actor.supervision

import groovy.transform.CompileStatic

import java.time.Duration

/**
 * Supervision strategy that restarts all sibling actors when one fails.
 * 
 * <p>When an actor fails, all actors under the same supervisor are restarted.
 * This is appropriate when actor failures indicate a systemic problem that
 * affects all siblings.</p>
 * 
 * <p><b>Use with caution:</b> This can cause cascading restarts if one actor
 * repeatedly fails.</p>
 * 
 * <h2>Example</h2>
 * <pre>
 * // All workers share a connection pool
 * // If one fails due to connection issue, restart all
 * def strategy = new AllForOneStrategy(
 *     maxRestarts: 3,
 *     withinDuration: Duration.ofMinutes(1)
 * ) { throwable ->
 *     if (throwable instanceof SQLException) {
 *         return SupervisorDirective.RESTART  // Restart all to reset connections
 *     } else {
 *         return SupervisorDirective.RESUME
 *     }
 * }
 * 
 * // Create worker pool with shared fate
 * system.actor {
 *     name 'workerSupervisor'
 *     supervisionStrategy strategy
 *     // ... manages worker actors
 * }
 * </pre>
 * 
 * @since 2.0.0
 */
@CompileStatic
class AllForOneStrategy extends SupervisionStrategy {
    
    /**
     * Create with default settings (restart on any failure).
     */
    AllForOneStrategy() {
        this.decider = { throwable -> SupervisorDirective.RESTART }
    }
    
    /**
     * Create with custom decider closure.
     * 
     * @param decider function that decides action based on exception
     */
    AllForOneStrategy(Closure<SupervisorDirective> decider) {
        this.decider = decider
    }
    
    /**
     * Create with configuration map and decider.
     * 
     * @param config configuration (maxRestarts, withinDuration, etc.)
     * @param decider function that decides action based on exception
     */
    AllForOneStrategy(Map config, Closure<SupervisorDirective> decider) {
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
        "AllForOneStrategy[maxRestarts=$maxRestarts, within=$withinDuration, backoff=$useExponentialBackoff]"
    }
}
