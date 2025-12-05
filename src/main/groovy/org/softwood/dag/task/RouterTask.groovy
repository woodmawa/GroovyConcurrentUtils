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
 * Concrete subclasses (e.g. ConditionalForkTask, DynamicRouterTask)
 * implement the route(prevValue) method.
 */
@Slf4j
abstract class RouterTask extends Task<List<String>> {

    RouterTask(String id, String name, TaskContext ctx) {
        super(id, name, ctx)
    }

    /**
     * Subclasses implement the routing logic.
     *
     * @param prevValue – the resolved output of the predecessor task
     * @return List<String> – the IDs of successor tasks to activate
     */
    protected abstract List<String> route(Object prevValue)


    // ---------------------------------------------------------
    // Task → core execution
    // ---------------------------------------------------------
    @Override
    protected Promise<List<String>> runTask(TaskContext ctx, Optional<Promise<?>> prevOpt) {

        return Promises.async {

            // resolve predecessor value
            def prevValue = prevOpt.isPresent() ? prevOpt.get().get() : null
            log.debug("RouterTask($id): predecessor value = $prevValue")

            // evaluate routing strategy
            List<String> selected
            try {
                selected = route(prevValue)
            }
            catch (Throwable e) {
                log.error("RouterTask($id) routing error: ${e.message}", e)
                throw e
            }

            if (!(selected instanceof List)) {
                throw new IllegalStateException(
                        "RouterTask($id) route() must return a List<String>, got: ${selected}"
                )
            }

            log.debug("RouterTask($id): selected successors = $selected")
            return selected
        }
    }
}