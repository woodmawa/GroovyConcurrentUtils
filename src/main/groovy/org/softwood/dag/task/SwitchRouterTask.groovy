package org.softwood.dag.task

import groovy.util.logging.Slf4j

/**
 * SwitchRouterTask (Multi-way Switch)
 *
 * Routes based on discrete value matching (like a switch/case statement).
 * Exactly ONE path is selected based on value equality.
 *
 * <p>This task extracts a value from the previous result and matches it
 * against registered cases. Each case can match single or multiple values.
 * A default case is required if no matches are found.</p>
 *
 * <h3>Use Cases:</h3>
 * <ul>
 *   <li>Status-based routing (NEW, PENDING, APPROVED, REJECTED)</li>
 *   <li>Type-based routing (ORDER, REFUND, INQUIRY)</li>
 *   <li>Priority-based routing (P1, P2, P3)</li>
 *   <li>Category-based routing</li>
 * </ul>
 *
 * <h3>Key Features:</h3>
 * <ul>
 *   <li>Type-safe value matching</li>
 *   <li>Support for multiple values per case</li>
 *   <li>Required default case (fail-fast if no match)</li>
 *   <li>Deterministic routing (same input → same output)</li>
 * </ul>
 *
 * <h3>DSL Example:</h3>
 * <pre>
 * task("route-by-status", TaskType.SWITCH_ROUTER) {
 *     switchOn { ticket -> ticket.status }  // Extract value to switch on
 *     
 *     case "NEW": route "triage-queue"
 *     case "ESCALATED": route "senior-support"
 *     case "PENDING_CUSTOMER": route "wait-for-response"
 *     case ["RESOLVED", "CLOSED"]: route "archive"
 *     
 *     default "error-handler"  // required
 * }
 * </pre>
 */
@Slf4j
class SwitchRouterTask extends RouterTask {

    /**
     * Closure to extract the switch value from previous task result.
     * Required - must be set before routing.
     */
    Closure<?> valueExtractor = null

    /**
     * Map of switch values to target task IDs.
     * Multiple values can map to the same target.
     */
    final Map<Object, String> caseMappings = [:]

    /**
     * Default target if no case matches.
     * Required unless allowNoMatch is true.
     */
    String defaultTarget = null

    /**
     * If true, allow routing to proceed with empty result when no case matches.
     * If false (default), throw exception when no match and no default.
     */
    boolean allowNoMatch = false

    SwitchRouterTask(String id, String name, TaskContext ctx) {
        super(id, name, ctx)
    }

    // =========================================================================
    // DSL Methods
    // =========================================================================

    /**
     * Set the value extractor closure.
     * This closure receives the previous task result and returns the value to switch on.
     *
     * @param extractor closure that extracts the switch value
     */
    void switchOn(Closure<?> extractor) {
        this.valueExtractor = extractor
        log.debug("SwitchRouterTask($id): set value extractor")
    }

    /**
     * Register a case mapping.
     * Supports both single values and collections of values.
     *
     * @param value single value or collection of values to match
     * @return CaseBuilder for fluent API
     */
    CaseBuilder case_(Object value) {
        return new CaseBuilder(this, value)
    }

    /**
     * Groovy-friendly alias for case_ (since 'case' is a keyword).
     */
    CaseBuilder getCase() {
        return { Object value -> case_(value) } as CaseBuilder
    }

    /**
     * Set the default target for unmatched values.
     *
     * @param targetId task to route to when no case matches
     */
    void default_(String targetId) {
        this.defaultTarget = targetId
        this.allowNoMatch = false
        
        // Add to targetIds
        targetIds.add(targetId)
        
        log.debug("SwitchRouterTask($id): set default target to '$targetId'")
    }

    /**
     * Groovy-friendly alias for default_ (since 'default' is a keyword).
     */
    void setDefault(String targetId) {
        default_(targetId)
    }

    // =========================================================================
    // Routing Logic
    // =========================================================================

    @Override
    protected List<String> route(Object prevValue) {

        if (valueExtractor == null) {
            throw new IllegalStateException(
                "SwitchRouterTask($id): valueExtractor not set. Use switchOn { ... } to configure."
            )
        }

        log.debug("SwitchRouterTask($id): extracting switch value from prevValue=$prevValue")

        // Extract the value to switch on
        Object switchValue
        try {
            switchValue = valueExtractor(prevValue)
            log.debug("SwitchRouterTask($id): extracted switch value: $switchValue (${switchValue?.class?.simpleName})")
        } catch (Throwable e) {
            log.error("SwitchRouterTask($id): value extraction failed → ${e.message}", e)
            throw new RuntimeException(
                "SwitchRouterTask($id): failed to extract switch value", 
                e
            )
        }

        // Look up target by value
        String targetId = caseMappings.get(switchValue)

        if (targetId != null) {
            log.debug("SwitchRouterTask($id): matched case '$switchValue' → routing to '$targetId'")
            return [targetId]
        }

        // No match found
        log.debug("SwitchRouterTask($id): no case matched for value '$switchValue'")

        if (defaultTarget) {
            log.debug("SwitchRouterTask($id): using default target '$defaultTarget'")
            return [defaultTarget]
        }

        if (!allowNoMatch) {
            throw new IllegalStateException(
                "SwitchRouterTask($id): no case matched for value '$switchValue' and no default target specified. " +
                "Available cases: ${caseMappings.keySet()}"
            )
        }

        log.debug("SwitchRouterTask($id): no match and allowNoMatch=true, returning empty list")
        return []
    }

    // =========================================================================
    // Internal Classes
    // =========================================================================

    /**
     * Fluent builder for case mappings.
     * Allows: case "value" route "targetId"
     */
    static class CaseBuilder {
        private final SwitchRouterTask task
        private final Object value

        CaseBuilder(SwitchRouterTask task, Object value) {
            this.task = task
            this.value = value
        }

        /**
         * Complete the case mapping by specifying the target task ID.
         *
         * @param targetId task to route to if value matches
         */
        void route(String targetId) {
            // Handle collections - add each value separately
            if (value instanceof Collection) {
                ((Collection) value).each { val ->
                    task.caseMappings[val] = targetId
                    task.log.debug("SwitchRouterTask(${task.id}): registered case '$val' → '$targetId'")
                }
            } else {
                task.caseMappings[value] = targetId
                task.log.debug("SwitchRouterTask(${task.id}): registered case '$value' → '$targetId'")
            }
            
            task.targetIds.add(targetId)
        }

        /**
         * Groovy property access support: case "value" to "targetId"
         */
        void to(String targetId) {
            route(targetId)
        }
    }
}
