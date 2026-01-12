package org.softwood.dag.resilience

import groovy.transform.CompileStatic
import java.time.Duration
import java.time.temporal.ChronoUnit

/**
 * DSL for configuring task timeout behavior.
 * 
 * <h3>Example:</h3>
 * <pre>
 * serviceTask("slow-task") {
 *     timeout {
 *         duration Duration.ofSeconds(30)
 *         warningThreshold Duration.ofSeconds(25)
 *         onWarning { ctx -> log.warn("Task taking too long") }
 *         onTimeout { ctx -> log.error("Task timed out") }
 *     }
 *     action { ctx, prev -> ... }
 * }
 * </pre>
 */
@CompileStatic
class TimeoutDsl {
    
    private final TimeoutPolicy policy
    
    TimeoutDsl(TimeoutPolicy policy) {
        this.policy = policy
    }
    
    /**
     * Set timeout duration
     */
    void duration(Duration timeout) {
        policy.timeout(timeout)
    }
    
    /**
     * Set timeout with value and unit
     */
    void duration(long value, ChronoUnit unit) {
        policy.timeout(value, unit)
    }
    
    /**
     * Convenience alias for duration
     */
    void timeout(Duration timeout) {
        policy.timeout(timeout)
    }
    
    /**
     * Convenience alias for duration with value and unit
     */
    void timeout(long value, ChronoUnit unit) {
        policy.timeout(value, unit)
    }
    
    /**
     * Set warning threshold (triggers callback before timeout)
     */
    void warningThreshold(Duration threshold) {
        policy.warningThreshold(threshold)
    }
    
    /**
     * Set warning threshold with value and unit
     */
    void warningThreshold(long value, ChronoUnit unit) {
        policy.warningThreshold(value, unit)
    }
    
    /**
     * Set callback for warning threshold
     */
    void onWarning(Closure callback) {
        policy.onWarning(callback)
    }
    
    /**
     * Set callback for timeout
     */
    void onTimeout(Closure callback) {
        policy.onTimeout(callback)
    }
    
    /**
     * Enable or disable timeout
     */
    void enabled(boolean isEnabled) {
        policy.enabled(isEnabled)
    }
    
    /**
     * Apply a timeout preset by name.
     * 
     * Available presets:
     * - "quick": 5 seconds
     * - "standard": 30 seconds
     * - "long": 5 minutes
     * - "very-long": 30 minutes
     * - "none": disabled
     */
    void preset(String presetName) {
        switch (presetName.toLowerCase()) {
            case 'quick':
                policy.timeout(Duration.ofSeconds(5))
                break
            case 'standard':
                policy.timeout(Duration.ofSeconds(30))
                break
            case 'long':
                policy.timeout(Duration.ofMinutes(5))
                break
            case 'very-long':
                policy.timeout(Duration.ofMinutes(30))
                break
            case 'none':
                policy.enabled = false
                break
            default:
                throw new IllegalArgumentException(
                    "Unknown timeout preset: '${presetName}'. " +
                    "Valid options: quick, standard, long, very-long, none"
                )
        }
    }
}
