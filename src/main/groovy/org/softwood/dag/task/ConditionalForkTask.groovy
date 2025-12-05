package org.softwood.dag.task

import groovy.util.logging.Slf4j

/**
 * ConditionalForkTask
 *
 * Evaluates a set of (targetId → condition closure) rules,
 * and optional static targets.
 *
 * The task returns a List<String> of selected target task IDs.
 *
 * Used by ForkDsl to build conditional fan-out patterns.
 */
@Slf4j
class ConditionalForkTask extends RouterTask {

    /**
     * Static successors that always run.
     */
    final List<String> staticTargets = []

    /**
     * Conditional routing rules:
     *   targetId -> Closure<Boolean> condition(prevValue)
     */
    final Map<String, Closure<Boolean>> conditionalRules = [:]

    ConditionalForkTask(String id, String name, TaskContext ctx) {
        super(id, name, ctx)
    }

    /**
     * Implements routing strategy:
     * 1. Add all staticTargets
     * 2. For each conditional rule, apply condition(prevValue)
     * 3. Return all target IDs that should be activated
     */
    @Override
    protected List<String> route(Object prevValue) {

        log.debug("ConditionalForkTask($id): evaluating routing for prevValue=$prevValue")

        def selected = []

        // ---- 1. Handle static targets ----
        if (staticTargets) {
            log.debug("ConditionalForkTask($id): static targets = $staticTargets")
            selected.addAll(staticTargets)
        }

        // ---- 2. Evaluate conditional rules ----
        conditionalRules.each { String targetId, Closure<Boolean> cond ->

            boolean result = false
            try {
                result = cond(prevValue)
            }
            catch (Throwable e) {
                log.error("ConditionalForkTask($id): condition failed for $targetId → ${e.message}", e)
                throw e
            }

            log.debug("ConditionalForkTask($id): cond[$targetId] = $result")

            if (result) {
                selected << targetId
            }
        }

        // ---- 3. Clean and return ----
        def finalList = selected.unique()

        log.debug("ConditionalForkTask($id): final selected successors = $finalList")
        return finalList
    }
}