package org.softwood.dag.task

import groovy.util.logging.Slf4j
import org.softwood.promise.Promise
import org.softwood.promise.Promises

/**
 * Abstract RouterTask
 *
 * Evaluates a routing strategy using the predecessor output
 * and returns a List<String> of successor task IDs that should run.
 *
 * Concrete subclasses (e.g. ConditionalForkTask, DynamicRouterTask, ShardingRouterTask)
 * implement the route(prevValue) method.
 */
@Slf4j
abstract class RouterTask extends Task<List<String>> {

    /**
     * All possible successor task IDs this router may choose between.
     * Used by TaskGraph.schedule() to mark unselected branches as SKIPPED.
     */
    final Set<String> targetIds = [] as Set

    // --------------------------------------------------------------------
    // ONE-SHOT ROUTING FIXES
    // --------------------------------------------------------------------

    /** True once this router has executed route(prevValue) */
    boolean alreadyRouted = false

    /** Cached routing result when alreadyRouted=true */
    List<String> lastSelectedTargets = null

    /** For sharding routers: ensures shared successors (joins) scheduled only once */
    boolean scheduledShardSuccessors = false

    // --------------------------------------------------------------------

    RouterTask(String id, String name, TaskContext ctx) {
        super(id, name, ctx)
    }

    /**
     * Subclasses implement the routing logic.
     *
     * @param prevValue – resolved predecessor output
     * @return List<String> successor task IDs to activate
     */
    protected abstract List<String> route(Object prevValue)

    // ---------------------------------------------------------
    // Task → core execution
    // Receives Optional<?> (unwrapped value), not Optional<Promise<?>>
    // ---------------------------------------------------------
    @Override
    protected Promise<List<String>> runTask(TaskContext ctx, Object prevValue) {

        return ctx.promiseFactory.executeAsync {

            log.debug "RouterTask(${id}): runTask() called"

            // -------------------------------------------------
            // ONE-SHOT ROUTING: reuse cached decision
            // -------------------------------------------------
            if (alreadyRouted && lastSelectedTargets != null) {
                log.debug "RouterTask(${id}): already routed → reusing cached targets: ${lastSelectedTargets}"
                return lastSelectedTargets
            }


            // -------------------------------------------------
            // Call routing logic ONCE
            // -------------------------------------------------
            log.debug "RouterTask(${id}): calling route()"
            List<String> routedTargets = route(prevValue)

            if (!(routedTargets instanceof List)) {
                throw new IllegalStateException(
                        "RouterTask(${id}): route(prev) must return List<String>, got: $routedTargets"
                )
            }

            log.debug "RouterTask(${id}): route() returned targets: $routedTargets"

            // Cache for deterministic reuse
            alreadyRouted = true
            lastSelectedTargets = routedTargets

            return routedTargets
        }
    }
}
