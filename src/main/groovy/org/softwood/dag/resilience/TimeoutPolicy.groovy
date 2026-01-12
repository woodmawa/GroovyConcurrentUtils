package org.softwood.dag.resilience

import groovy.transform.CompileStatic
import java.time.Duration

/**
 * Policy for configuring execution timeouts on tasks.
 * 
 * Provides flexible timeout configuration with:
 * - Task-level execution timeout
 * - Optional warning threshold (triggers callback before timeout)
 * - Custom timeout handlers
 * - Enable/disable control
 */
@CompileStatic
class TimeoutPolicy {
    
    Duration timeout = null            // Execution timeout (null = no timeout)
    Duration warningThreshold = null   // Warning threshold (e.g. 80% of timeout)
    boolean enabled = false            // Opt-in by default
    Closure onWarning                  // Callback when warning threshold exceeded
    Closure onTimeout                  // Callback when timeout occurs
    
    /**
     * DSL method to set timeout duration
     */
    void timeout(Duration duration) {
        if (duration != null && (duration.isNegative() || duration.isZero())) {
            throw new IllegalArgumentException("Timeout must be positive, got: ${duration}")
        }
        this.timeout = duration
        // Auto-enable when timeout is set
        if (duration != null) {
            this.enabled = true
        }
    }
    
    /**
     * DSL method to set timeout with value and unit
     */
    void timeout(long value, java.time.temporal.ChronoUnit unit) {
        timeout(Duration.of(value, unit))
    }
    
    /**
     * DSL method to set warning threshold
     */
    void warningThreshold(Duration threshold) {
        if (threshold != null && (threshold.isNegative() || threshold.isZero())) {
            throw new IllegalArgumentException("Warning threshold must be positive, got: ${threshold}")
        }
        this.warningThreshold = threshold
    }
    
    /**
     * DSL method to set warning threshold with value and unit
     */
    void warningThreshold(long value, java.time.temporal.ChronoUnit unit) {
        warningThreshold(Duration.of(value, unit))
    }
    
    /**
     * DSL method to set warning callback
     */
    void onWarning(Closure callback) {
        this.onWarning = callback
    }
    
    /**
     * DSL method to set timeout callback
     */
    void onTimeout(Closure callback) {
        this.onTimeout = callback
    }
    
    /**
     * DSL method to enable/disable policy
     */
    void enabled(boolean isEnabled) {
        this.enabled = isEnabled
    }
    
    /**
     * Check if policy is properly configured and active
     */
    boolean isActive() {
        return enabled && timeout != null
    }
    
    /**
     * Get timeout in milliseconds (for compatibility)
     */
    Long getTimeoutMillis() {
        return timeout?.toMillis()
    }
    
    @Override
    String toString() {
        return "TimeoutPolicy[timeout=${timeout}, enabled=${enabled}]"
    }
}
