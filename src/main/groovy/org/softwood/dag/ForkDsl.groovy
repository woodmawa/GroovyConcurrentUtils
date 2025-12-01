package org.softwood.dag

import org.softwood.dag.TaskGraph
import org.softwood.dag.task.ServiceTask
import org.softwood.dag.task.TaskContext
import org.softwood.promise.Promise
import org.softwood.promise.Promises
import org.softwood.dag.task.TaskState


/**
 * DSL builder for configuring fork patterns that enable parallel task execution with routing logic.
 *
 * <p>ForkDsl provides a fluent interface for defining fan-out execution patterns where a single
 * source task triggers multiple parallel tasks. Supports three execution modes:</p>
 * <ul>
 *   <li><b>Static Fork:</b> Fixed set of successors always executed in parallel</li>
 *   <li><b>Conditional Fork:</b> Subset of successors selected based on predicate</li>
 *   <li><b>Dynamic Fork:</b> Custom routing logic determines which successors to execute</li>
 * </ul>
 *
 * <h3>Architecture Overview</h3>
 * <p>The fork mechanism operates in two phases:</p>
 * <ol>
 *   <li><b>Static Wiring (immediate):</b> Dependencies established when to() is called</li>
 *   <li><b>Dynamic Routing (runtime):</b> Synthetic router task evaluates logic during execution</li>
 * </ol>
 *
 * <h3>Routing Architecture</h3>
 * <p>For dynamic routing (conditional or route-based), ForkDsl creates a synthetic router task:</p>
 * <pre>
 * sourceTask → __router_sourceTask_UUID → [target1, target2, target3]
 * </pre>
 * <p>The router task:</p>
 * <ul>
 *   <li>Receives the source task's result as input</li>
 *   <li>Evaluates routing logic (predicate or custom closure)</li>
 *   <li>Returns list of selected target task IDs</li>
 *   <li>Marks unselected targets as SKIPPED</li>
 *   <li>Allows selected targets to execute normally</li>
 * </ul>
 *
 * <h3>Static Fork Pattern</h3>
 * <p>All specified tasks execute unconditionally in parallel:</p>
 * <pre>
 * serviceTask("fetchData") { ... }
 * serviceTask("processA") { ... }
 * serviceTask("processB") { ... }
 * serviceTask("processC") { ... }
 *
 * fork("parallelProcessing") {
 *     from "fetchData"
 *     to "processA", "processB", "processC"
 *     // All three tasks execute in parallel
 * }
 * </pre>
 *
 * <h3>Conditional Fork Pattern</h3>
 * <p>Tasks execute only if predicate returns true:</p>
 * <pre>
 * fork("conditionalBranch") {
 *     from "validateInput"
 *     to "heavyProcess", "lightProcess"
 *
 *     // Only execute heavyProcess if input is large
 *     conditionalOn(["heavyProcess"]) { result ->
 *         result.size > 1000
 *     }
 *     // lightProcess always executes
 * }
 * </pre>
 *
 * <h3>Dynamic Routing Pattern</h3>
 * <p>Custom logic determines execution path:</p>
 * <pre>
 * fork("dynamicRouter") {
 *     from "classifyRequest"
 *     to "handlePriority", "handleStandard", "handleBatch"
 *
 *     route { classification ->
 *         switch(classification.type) {
 *             case "priority":
 *                 return ["handlePriority"]
 *             case "batch":
 *                 return ["handleBatch"]
 *             default:
 *                 return ["handleStandard"]
 *         }
 *     }
 * }
 * </pre>
 *
 * <h3>Execution Semantics</h3>
 * <ul>
 *   <li>Static successors are wired immediately during DSL evaluation</li>
 *   <li>Router task is created only if conditionalOn() or route() is called</li>
 *   <li>Router executes after source task completes</li>
 *   <li>Unselected tasks transition to SKIPPED state</li>
 *   <li>Selected tasks execute normally when dependencies are satisfied</li>
 *   <li>Graph execution continues through all active paths</li>
 * </ul>
 *
 * @author Softwood
 * @see TaskGraphDsl#fork(String, Closure)
 * @see ServiceTask
 * @see org.softwood.dag.task.TaskState
 */
class ForkDsl {

    /** The TaskGraph being configured */
    private final TaskGraph graph

    /** Expose graph.ctx so routing closures can reference ctx.globals safely */
    final TaskContext ctx

    /** ID of the source task that triggers the fork */
    String fromId

    /** List of static target task IDs (wired immediately) */
    List<String> staticToIds = []

    /** Custom routing closure returning list of task IDs to execute */
    Closure<List<String>> router

    /** Conditional selector closure (internal - use conditionalOn() API) */
    Closure<List<String>> conditionalSelector

    ForkDsl(TaskGraph graph) {
        this.graph = graph
        this.ctx   = graph.ctx     // important: gives user routing closures access to ctx.globals
    }

    // ---------------------------------------------------------------------
    // Wiring
    // ---------------------------------------------------------------------

    void from(String id) {
        this.fromId = id
    }

    void to(String... ids) {
        staticToIds.addAll(ids)
        // Static fan-out wired immediately
        if (fromId && !staticToIds.isEmpty()) {
            staticToIds.each { tid ->
                graph.tasks[tid]?.dependsOn(fromId)
                graph.tasks[fromId]?.successors?.add(tid)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Conditional / dynamic routing API
    // ---------------------------------------------------------------------

    void conditionalOn(List<String> ids, Closure<Boolean> predicate) {
        // Wrap Boolean predicate into a selector that returns a list of IDs
        this.conditionalSelector = { value ->
            predicate.call(value) ? ids : []
        }
    }

    void route(Closure<List<String>> router) {
        this.router = router
    }

    // ---------------------------------------------------------------------
    // Build synthetic router (if needed)
    // ---------------------------------------------------------------------

    void build() {
        if (!fromId) {
            return
        }
        if (!conditionalSelector && !router) {
            // purely static fork; wiring already done in to()
            return
        }

        // Synthetic routing task
        String routerId = "__router_${fromId}_${UUID.randomUUID()}"
        def routerTask = new ServiceTask<List<String>>(routerId)
        routerTask.dependsOn(fromId)

        routerTask.action { TaskContext ctx, Optional<Promise<?>> prevOpt ->
            Promise<?> prev = prevOpt.orElse(null)

            return Promises.async {
                // upstream value from source
                def value = prev?.get()

                // Decide which targets to run
                List<String> selected
                if (router) {
                    // Allow router closure to use either (value) or (ctx, value)
                    if (router.maximumNumberOfParameters == 2) {
                        selected = router.call(ctx, value) as List<String>
                    } else {
                        selected = router.call(value) as List<String>
                    }
                } else if (conditionalSelector) {
                    if (conditionalSelector.maximumNumberOfParameters == 2) {
                        selected = conditionalSelector.call(ctx, value) as List<String>
                    } else {
                        selected = conditionalSelector.call(value) as List<String>
                    }
                } else {
                    selected = []
                }

                selected = selected ?: []

                // Mark unselected static successors as SKIPPED,
                // and (optionally) give them a null result promise so joins won't explode.
                def results = ctx.globals.__taskResults as Map<String, Optional<Promise<?>>>

                staticToIds.each { tid ->
                    if (!selected.contains(tid)) {
                        def t = graph.tasks[tid]
                        if (t && t.state == TaskState.PENDING) {
                            t.state = TaskState.SKIPPED
                        }
                        if (results != null && !results.containsKey(tid)) {
                            Promise<Object> p = Promises.newPromise()
                            p.accept(null)
                            results[tid] = Optional.of(p)
                        }
                    }
                }

                return selected
            }
        }

        graph.addTask(routerTask)

        // Ensure all targets depend on router instead of source
        staticToIds.each { tid ->
            graph.tasks[tid]?.dependsOn(routerTask.id)
        }
    }
}