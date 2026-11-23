package org.softwood.dag

/**
 * Builder for ExclusiveGateway (XOR) routing configuration.
 * Supports fluent when/route/otherwise syntax.
 * 
 * Usage within ForkDSL:
 * <pre>
 * fork("routing") {
 *     from "source"
 *     exclusiveGateway {
 *         when { data -> data.value > 100 } route "high"
 *         when { data -> data.value > 50 } route "medium"
 *         otherwise "low"
 *     }
 * }
 * </pre>
 */
class ExclusiveGatewayBuilder {
    List<Tuple2<Closure, String>> conditions = []
    String defaultTarget
    
    /**
     * Add a condition that routes to a target if true.
     * Returns a RouteBuilder to specify the target.
     */
    def when(Closure condition) {
        return new RouteBuilder(this, condition)
    }
    
    /**
     * Specify the default route when no conditions match.
     */
    def otherwise(String target) {
        defaultTarget = target
        return this
    }
    
    /**
     * Get all possible target task IDs.
     */
    List<String> getAllTargets() {
        def targets = conditions.collect { it.v2 }
        if (defaultTarget) targets << defaultTarget
        return targets.unique()
    }
    
    /**
     * Fluent builder for route specification.
     */
    static class RouteBuilder {
        ExclusiveGatewayBuilder parent
        Closure condition
        
        RouteBuilder(ExclusiveGatewayBuilder parent, Closure condition) {
            this.parent = parent
            this.condition = condition
        }
        
        /**
         * Specify the target task for this condition.
         */
        def route(String target) {
            parent.conditions << new Tuple2(condition, target)
            return parent
        }
    }
}
