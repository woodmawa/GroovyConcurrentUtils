package org.softwood.dag.task.manualtask

import groovy.util.logging.Slf4j

import java.time.Duration
import java.time.LocalDateTime

/**
 * Manages escalation rules for ManualTask.
 * 
 * An escalation policy defines a series of escalation rules that trigger
 * when a task remains incomplete for specified durations.
 */
@Slf4j
class EscalationPolicy {
    
    /** List of escalation rules, ordered by duration */
    List<EscalationRule> rules = []
    
    /** Track which escalation level has been triggered */
    private int currentEscalationLevel = 0
    
    /** Task start time (set when policy is activated) */
    private LocalDateTime startTime
    
    /** Flag indicating if policy is active */
    private boolean active = false
    
    /**
     * Add an escalation rule.
     */
    void addRule(EscalationRule rule) {
        rules << rule
        
        // Sort rules by duration (shortest first)
        rules.sort { it.afterDuration }
        
        // Assign escalation levels
        rules.eachWithIndex { r, idx ->
            r.level = idx + 1
        }
        
        log.debug("Added escalation rule: ${rule}")
    }
    
    /**
     * Add an escalation rule using builder pattern.
     */
    void after(Duration duration, Map params) {
        if (!params.to) {
            throw new IllegalArgumentException("Escalation rule must specify 'to' parameter")
        }
        
        def rule = new EscalationRule(duration, params.to as String)
        
        if (params.onEscalate) {
            rule.onEscalate = params.onEscalate as Closure
        }
        
        addRule(rule)
    }
    
    /**
     * Activate the escalation policy.
     */
    void activate() {
        this.startTime = LocalDateTime.now()
        this.active = true
        this.currentEscalationLevel = 0
        log.debug("Escalation policy activated at ${startTime}")
    }
    
    /**
     * Deactivate the escalation policy.
     */
    void deactivate() {
        this.active = false
        log.debug("Escalation policy deactivated")
    }
    
    /**
     * Check if any escalation rules should trigger.
     * Returns the next escalation rule to trigger, or null if none.
     */
    EscalationRule checkEscalation() {
        if (!active || !startTime) {
            return null
        }
        
        def now = LocalDateTime.now()
        def elapsed = Duration.between(startTime, now)
        
        // Find the next rule that should trigger
        for (rule in rules) {
            if (rule.level > currentEscalationLevel && rule.shouldEscalate(elapsed)) {
                currentEscalationLevel = rule.level
                log.info("Escalation triggered: level ${rule.level} to ${rule.escalateTo}")
                return rule
            }
        }
        
        return null
    }
    
    /**
     * Get the current escalation level (0 = no escalation yet).
     */
    int getCurrentLevel() {
        return currentEscalationLevel
    }
    
    /**
     * Check if policy has any rules.
     */
    boolean hasRules() {
        return !rules.isEmpty()
    }
    
    /**
     * Get the number of escalation rules.
     */
    int getRuleCount() {
        return rules.size()
    }
    
    @Override
    String toString() {
        return "EscalationPolicy(rules=${rules.size()}, currentLevel=${currentEscalationLevel}, active=${active})"
    }
}
