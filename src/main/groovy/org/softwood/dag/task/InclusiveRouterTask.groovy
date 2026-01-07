package org.softwood.dag.task

import groovy.util.logging.Slf4j

/**
 * InclusiveRouterTask - OR Gateway / Multi-Path Conditional Router
 *
 * Evaluates multiple conditions and routes to ALL matching paths.
 * This is the "OR" gateway in BPMN terms - between XOR (one path) and AND (all paths).
 *
 * <h3>Key Features:</h3>
 * <ul>
 *   <li>Evaluates ALL route conditions</li>
 *   <li>Routes to ALL matching targets (0 to N)</li>
 *   <li>Injects router input data into selected tasks</li>
 *   <li>Different from ExclusiveGateway (XOR - one path) and ParallelGateway (AND - all paths)</li>
 * </ul>
 *
 * <h3>DSL Example - Risk Assessment:</h3>
 * <pre>
 * inclusiveRouterTask("risk-checks") {
 *     route "credit-check" when { r ->
 *         r.prev.amount > 10000
 *     }
 *     
 *     route "fraud-check" when { r ->
 *         r.prev.riskScore > 50
 *     }
 *     
 *     route "compliance-check" when { r ->
 *         r.prev.country in ['US', 'UK', 'EU']
 *     }
 *     
 *     route "manager-approval" when { r ->
 *         r.prev.amount > 100000
 *     }
 *     
 *     // May execute 0, 1, 2, 3, or all 4 checks based on data
 * }
 * </pre>
 *
 * <h3>DSL Example - Multi-Region Deployment:</h3>
 * <pre>
 * inclusiveRouterTask("deploy-regions") {
 *     route "deploy-us-east" when { r ->
 *         'us-east' in r.prev.regions
 *     }
 *     
 *     route "deploy-us-west" when { r ->
 *         'us-west' in r.prev.regions
 *     }
 *     
 *     route "deploy-eu-west" when { r ->
 *         'eu-west' in r.prev.regions
 *     }
 *     
 *     route "deploy-ap-south" when { r ->
 *         'ap-south' in r.prev.regions
 *     }
 *     
 *     // Deploys to only selected regions
 * }
 * </pre>
 *
 * <h3>Comparison with Other Gateways:</h3>
 * <pre>
 * ExclusiveGateway (XOR):  Evaluates conditions in order, routes to FIRST match only
 * ParallelGateway (AND):   No conditions, routes to ALL paths always
 * InclusiveGateway (OR):   Evaluates ALL conditions, routes to ALL matches
 * </pre>
 */
@Slf4j
class InclusiveRouterTask extends RouterTask {

    // =========================================================================
    // Configuration
    // =========================================================================
    
    /** List of route conditions */
    private List<RouteCondition> routes = []
    
    /**
     * Route condition definition
     */
    static class RouteCondition {
        String targetId
        Closure<Boolean> condition
        
        RouteCondition(String targetId, Closure<Boolean> condition) {
            this.targetId = targetId
            this.condition = condition
        }
    }

    InclusiveRouterTask(String id, String name, TaskContext ctx) {
        super(id, name, ctx)
    }

    // =========================================================================
    // DSL Methods
    // =========================================================================
    
    /**
     * Define a conditional route with fluent DSL.
     *
     * Usage: route "target" when { r -> condition }
     */
    RouteBuilder route(String targetId) {
        return new RouteBuilder(this, targetId)
    }

    /**
     * Legacy method for backward compatibility
     * @param targetId ID of target task
     * @param args Map containing 'when' closure
     */
    void route(String targetId, Map args) {
        if (!args.when) {
            throw new IllegalArgumentException("InclusiveRouterTask($id): route requires 'when' condition")
        }

        routes << new RouteCondition(targetId, args.when)

        // Also add to targetIds for graph wiring
        if (!targetIds.contains(targetId)) {
            targetIds << targetId
        }
    }

    /**
     * Builder for fluent DSL syntax
     */
    static class RouteBuilder {
        private InclusiveRouterTask task
        private String targetId

        RouteBuilder(InclusiveRouterTask task, String targetId) {
            this.task = task
            this.targetId = targetId
        }

        /**
         * Define the condition: route "target" when { r -> condition }
         */
        void when(Closure<Boolean> condition) {
            task.routes << new RouteCondition(targetId, condition)

            // Also add to targetIds for graph wiring
            if (!task.targetIds.contains(targetId)) {
                task.targetIds << targetId
            }
        }
    }

    // =========================================================================
    // RouterTask Implementation - Routing Logic
    // =========================================================================

    @Override
    protected List<String> route(Object prevValue) {
        
        log.debug("InclusiveRouterTask($id): evaluating ${routes.size()} route conditions")
        
        // Validate configuration
        if (routes.isEmpty()) {
            throw new IllegalStateException("InclusiveRouterTask($id): requires at least one route")
        }
        
        // Create resolver for condition evaluation
        def resolver = new TaskResolver(prevValue, ctx)
        
        // Evaluate ALL conditions and collect matching targets
        List<String> selectedTargets = []
        
        routes.each { routeCondition ->
            try {
                boolean matches = evaluateCondition(routeCondition.condition, resolver)
                
                if (matches) {
                    log.debug("InclusiveRouterTask($id): condition matched for target '${routeCondition.targetId}'")
                    selectedTargets << routeCondition.targetId
                } else {
                    log.debug("InclusiveRouterTask($id): condition NOT matched for target '${routeCondition.targetId}'")
                }
                
            } catch (Exception e) {
                log.error("InclusiveRouterTask($id): error evaluating condition for '${routeCondition.targetId}'", e)
                // Continue evaluating other conditions
            }
        }
        
        log.info("InclusiveRouterTask($id): selected ${selectedTargets.size()} of ${routes.size()} routes: $selectedTargets")
        
        // Return selected targets (empty list is valid - means no paths matched)
        return selectedTargets
    }
    
    /**
     * Evaluate a route condition safely.
     */
    private boolean evaluateCondition(Closure<Boolean> condition, TaskResolver resolver) {
        try {
            // Bind resolver as delegate
            condition.delegate = resolver
            condition.resolveStrategy = Closure.DELEGATE_FIRST
            
            def result = condition.call(resolver)
            
            // Coerce to boolean
            return result ? true : false
            
        } catch (Exception e) {
            log.error("InclusiveRouterTask($id): condition evaluation failed", e)
            return false
        }
    }
}
