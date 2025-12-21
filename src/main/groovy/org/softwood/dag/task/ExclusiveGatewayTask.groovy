package org.softwood.dag.task

import groovy.util.logging.Slf4j

/**
 * ExclusiveGatewayTask (XOR Gateway)
 *
 * Routes to exactly ONE successor based on priority-ordered conditions.
 * First matching condition wins (XOR logic).
 *
 * <p>This is the BPMN Exclusive Gateway equivalent - a common pattern
 * where mutually exclusive paths need to be evaluated in priority order.</p>
 *
 * <h3>Use Cases:</h3>
 * <ul>
 *   <li>Order routing: Priority → Bulk → Standard</li>
 *   <li>Approval escalation levels</li>
 *   <li>SLA-based routing</li>
 *   <li>Status-based workflows</li>
 * </ul>
 *
 * <h3>Key Difference from ConditionalFork:</h3>
 * <ul>
 *   <li>ConditionalFork: Multiple paths can execute (OR logic)</li>
 *   <li>ExclusiveGateway: Exactly ONE path executes (XOR logic)</li>
 * </ul>
 *
 * <h3>DSL Example:</h3>
 * <pre>
 * task("route-order", TaskType.EXCLUSIVE_GATEWAY) {
 *     // First matching condition wins
 *     when { order -> order.value > 10000 } route "vip-processing"
 *     when { order -> order.type == "bulk" } route "bulk-processing"
 *     otherwise "standard-processing"  // default fallback
 * }
 * </pre>
 */
@Slf4j
class ExclusiveGatewayTask extends RouterTask {

    /**
     * Ordered list of condition rules.
     * Rules are evaluated in insertion order - first match wins.
     */
    final List<RoutingRule> orderedRules = []

    /**
     * Default target if no conditions match.
     * If not set and no conditions match, an exception is thrown.
     */
    String defaultTarget = null

    /**
     * If true, throw exception when no conditions match and no default is set.
     * If false, return empty list (no successors execute).
     */
    boolean failOnNoMatch = true

    ExclusiveGatewayTask(String id, String name, TaskContext ctx) {
        super(id, name, ctx)
    }

    // =========================================================================
    // DSL Methods
    // =========================================================================

    /**
     * Add a routing rule (condition → target).
     * Rules are evaluated in the order they are added.
     *
     * @param condition predicate closure that receives prevValue
     * @return RoutingRuleBuilder for fluent API
     */
    RoutingRuleBuilder when(Closure<Boolean> condition) {
        return new RoutingRuleBuilder(this, condition)
    }

    /**
     * Set the default target if no conditions match.
     *
     * @param targetId task ID to route to by default
     */
    void otherwise(String targetId) {
        this.defaultTarget = targetId
        this.failOnNoMatch = false
        
        // Add default to targetIds so TaskGraph knows about it
        targetIds.add(targetId)
        
        log.debug("ExclusiveGatewayTask($id): set default target to '$targetId'")
    }

    // =========================================================================
    // Routing Logic
    // =========================================================================

    @Override
    protected List<String> route(Object prevValue) {

        log.debug("ExclusiveGatewayTask($id): evaluating ${orderedRules.size()} rules for prevValue=$prevValue")

        // Evaluate rules in order - first match wins
        for (RoutingRule rule : orderedRules) {
            try {
                boolean matches = rule.condition(prevValue)
                
                log.debug("ExclusiveGatewayTask($id): rule to '${rule.targetId}' evaluated to: $matches")

                if (matches) {
                    log.debug("ExclusiveGatewayTask($id): MATCHED - routing to '${rule.targetId}'")
                    return [rule.targetId]
                }
                
            } catch (Throwable e) {
                log.error("ExclusiveGatewayTask($id): condition failed for target '${rule.targetId}' → ${e.message}", e)
                throw new RuntimeException(
                    "ExclusiveGatewayTask($id): condition evaluation failed for target '${rule.targetId}'", 
                    e
                )
            }
        }

        // No conditions matched
        log.debug("ExclusiveGatewayTask($id): no conditions matched")

        if (defaultTarget) {
            log.debug("ExclusiveGatewayTask($id): using default target '$defaultTarget'")
            return [defaultTarget]
        }

        if (failOnNoMatch) {
            throw new IllegalStateException(
                "ExclusiveGatewayTask($id): no conditions matched and no default target specified. " +
                "Use .otherwise('targetId') to set a default."
            )
        }

        log.debug("ExclusiveGatewayTask($id): no match and failOnNoMatch=false, returning empty list")
        return []
    }

    // =========================================================================
    // Internal Classes
    // =========================================================================

    /**
     * Represents a single routing rule: condition → target
     */
    static class RoutingRule {
        final Closure<Boolean> condition
        final String targetId

        RoutingRule(Closure<Boolean> condition, String targetId) {
            this.condition = condition
            this.targetId = targetId
        }
    }

    /**
     * Fluent builder for routing rules.
     * Allows: when { condition } route "targetId"
     */
    static class RoutingRuleBuilder {
        private final ExclusiveGatewayTask task
        private final Closure<Boolean> condition

        RoutingRuleBuilder(ExclusiveGatewayTask task, Closure<Boolean> condition) {
            this.task = task
            this.condition = condition
        }

        /**
         * Complete the rule by specifying the target task ID.
         *
         * @param targetId task to route to if condition matches
         */
        void route(String targetId) {
            RoutingRule rule = new RoutingRule(condition, targetId)
            task.orderedRules.add(rule)
            task.targetIds.add(targetId)
            
            task.log.debug("ExclusiveGatewayTask(${task.id}): added rule #${task.orderedRules.size()} → '$targetId'")
        }
    }
}
