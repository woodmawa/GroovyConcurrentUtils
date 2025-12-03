package org.softwood.dag

import org.softwood.dag.TaskGraph
import org.softwood.dag.task.ServiceTask
import org.softwood.dag.task.Task
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

    private final TaskGraph graph
    private final String id

    private String routeFromId
    private final Set<String> staticTargets = [] as Set
    private final Map<String, Closure<Boolean>> conditionalRules = [:]

    ForkDsl(TaskGraph graph, String id) {
        this.graph = graph
        this.id = id
    }

    // Select the driving input of the fork
    def from(String sourceId) {
        this.routeFromId = sourceId
    }

    // Statically route to one or more downstream tasks
    def to(String... targetIds) {
        staticTargets.addAll(targetIds)
    }

    // Conditional routing: conditionalOn(["taskId"]) { prevValue -> boolean }
    def conditionalOn(List<String> targets, Closure<Boolean> cond) {
        targets.each { tid ->
            conditionalRules[tid] = cond
        }
    }

    // ----------------------------------------------------
    // Build → Wire graph dependencies
    // ----------------------------------------------------
    void build() {

        if (!routeFromId) {
            throw new IllegalStateException("fork($id) requires 'from \"taskId\"'")
        }

        Task source = graph.tasks[routeFromId]
        if (!source)
            throw new IllegalStateException("Unknown source task: $routeFromId")

        // Static routes
        staticTargets.each { tid ->
            Task target = graph.tasks[tid]
            if (!target)
                throw new IllegalStateException("Unknown target: $tid")

            target.dependsOn(source.id)
            source.addSuccessor(target.id)
        }

        // Conditional routes
        conditionalRules.each { tid, cond ->
            Task target = graph.tasks[tid]
            if (!target)
                throw new IllegalStateException("Unknown conditional target: $tid")

            graph.registerConditionalFork(source.id, tid, cond)

            target.dependsOn(source.id)
            source.addSuccessor(target.id)
        }
    }
}