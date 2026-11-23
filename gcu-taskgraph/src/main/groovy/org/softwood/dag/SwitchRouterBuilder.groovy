package org.softwood.dag

/**
 * Builder for SwitchRouter (case/switch) routing configuration.
 * Supports fluent switchOn/case_/default_ syntax.
 * 
 * Usage within ForkDSL:
 * <pre>
 * fork("routing") {
 *     from "source"
 *     switchRouter {
 *         switchOn { ticket -> ticket.status }
 *         case_("NEW") route "triage"
 *         case_("ESCALATED") route "senior-support"
 *         case_(["RESOLVED", "CLOSED"]) route "archive"
 *         default_ "error-handler"
 *     }
 * }
 * </pre>
 */
class SwitchRouterBuilder {
    Closure valueExtractor
    Map<Object, String> cases = [:]
    String defaultTarget
    
    /**
     * Specify how to extract the value to switch on.
     */
    def switchOn(Closure extractor) {
        valueExtractor = extractor
        return this
    }
    
    /**
     * Add a case that routes based on value match.
     * Supports both single values and lists of values.
     * Returns a CaseRouteBuilder to specify the target.
     */
    def case_(Object value) {
        return new CaseRouteBuilder(this, value)
    }
    
    /**
     * Specify the default route when no cases match.
     */
    def default_(String target) {
        defaultTarget = target
        return this
    }
    
    /**
     * Get all possible target task IDs.
     */
    List<String> getAllTargets() {
        def targets = cases.values().toList()
        if (defaultTarget) targets << defaultTarget
        return targets.unique()
    }
    
    /**
     * Fluent builder for case route specification.
     */
    static class CaseRouteBuilder {
        SwitchRouterBuilder parent
        Object value
        
        CaseRouteBuilder(SwitchRouterBuilder parent, Object value) {
            this.parent = parent
            this.value = value
        }
        
        /**
         * Specify the target task for this case.
         */
        def route(String target) {
            // For list values, create multiple case entries
            if (value instanceof List) {
                value.each { v ->
                    parent.cases[v] = target
                }
            } else {
                parent.cases[value] = target
            }
            return parent
        }
    }
}
