package org.softwood.dag.task

import groovy.util.logging.Slf4j

/**
 * DynamicRouterTask
 *
 * This router evaluates a user-supplied closure:
 *      routingLogic(prevValue) → List<String>
 *
 * Allows completely arbitrary routing decisions.
 */
@Slf4j
class DynamicRouterTask extends RouterTask {

    /**
     * User-supplied closure: prevValue -> List<String>
     */
    Closure routingLogic

    /**
     * (Optional) restrict which IDs are allowed
     * routingLogic may return a superset; we can clamp it.
     */
    List<String> allowedTargets = []

    DynamicRouterTask(String id, String name, TaskContext ctx) {
        super(id, name, ctx)
    }

    @Override
    protected List<String> route(Object prevValue) {

        if (routingLogic == null)
            throw new IllegalStateException("DynamicRouterTask($id) requires routingLogic closure")

        log.debug("DynamicRouterTask($id): running routingLogic for prevValue=$prevValue")

        def raw = routingLogic(prevValue)

        if (!(raw instanceof List))
            throw new IllegalStateException(
                    "DynamicRouterTask($id) routingLogic must return List<String>, got: $raw"
            )

        // Optional filter if allowedTargets is set
        def result =
                allowedTargets ?
                        raw.intersect(allowedTargets) :
                        raw

        log.debug("DynamicRouterTask($id): final selected successors = $result")

        return result.unique()
    }
}