package org.softwood.dag.task.manualtask

import java.time.Duration

/**
 * Defines an escalation rule for ManualTask.
 * 
 * An escalation rule specifies that if a task is not completed within a certain
 * duration, it should be escalated to a different assignee.
 */
class EscalationRule {
    
    /** Time to wait before escalating */
    Duration afterDuration
    
    /** Who to escalate to (email, username, role) */
    String escalateTo
    
    /** Optional: Custom action to execute on escalation */
    Closure onEscalate
    
    /** Escalation level (1, 2, 3...) - set automatically by EscalationPolicy */
    int level = 1
    
    /**
     * Create an escalation rule.
     */
    EscalationRule(Duration afterDuration, String escalateTo) {
        this.afterDuration = afterDuration
        this.escalateTo = escalateTo
    }
    
    /**
     * Check if this escalation should trigger based on elapsed time.
     */
    boolean shouldEscalate(Duration elapsed) {
        return elapsed >= afterDuration
    }
    
    @Override
    String toString() {
        return "EscalationRule(level=${level}, after=${afterDuration}, to=${escalateTo})"
    }
}
