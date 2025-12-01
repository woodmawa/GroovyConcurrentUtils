package org.softwood.dag

import groovy.transform.stc.ClosureParams
import groovy.transform.stc.FromString
import org.softwood.dag.task.ServiceTask
import org.softwood.dag.task.TaskContext
import org.softwood.dataflow.DataflowVariable
import org.softwood.promise.Promise
import org.softwood.promise.Promises

/**
 * DSL builder for configuring join tasks that synchronize multiple parallel execution paths.
 *
 * <p>JoinDsl provides a fluent interface for defining synchronization points in a task graph.
 * Join tasks collect results from multiple predecessor tasks and execute custom logic to
 * combine, aggregate, or process those results.</p>
 *
 * <h3>Architecture</h3>
 * <p>The join pattern implements a barrier synchronization mechanism:</p>
 * <ol>
 *   <li>Defines predecessor tasks whose results should be collected</li>
 *   <li>Wires DAG edges from all predecessors to the join task</li>
 *   <li>Wraps user action to access predecessor promises from context</li>
 *   <li>Passes ordered promise list to user action for processing</li>
 *   <li>Returns normalized Promise for downstream consumption</li>
 * </ol>
 *
 * <h3>Promise Resolution</h3>
 * <p>The join task accesses predecessor promises via the context's result map
 * ({@code ctx.globals.__taskResults}). This allows the join to wait for all
 * predecessors to complete before executing its action.</p>
 *
 * <h3>Usage Pattern</h3>
 * <pre>
 * join("aggregateResults", JoinStrategy.ALL_COMPLETED) {
 *     // Define which tasks to synchronize
 *     from "task1", "task2", "task3"
 *
 *     // Define how to combine their results
 *     action { ctx, promises ->
 *         // promises is ordered list matching from() declaration
 *         def result1 = promises[0].get()
 *         def result2 = promises[1].get()
 *         def result3 = promises[2].get()
 *
 *         // Combine results
 *         return [result1, result2, result3].sum()
 *     }
 * }
 * </pre>
 *
 * <h3>Error Handling Pattern</h3>
 * <pre>
 * join("resilientJoin") {
 *     from "unstableTask1", "unstableTask2"
 *     action { ctx, promises ->
 *         def results = promises.collect { promise ->
 *             try {
 *                 promise.get()
 *             } catch (Exception e) {
 *                 log.warn "Task failed: ${e.message}"
 *                 return null  // Graceful degradation
 *             }
 *         }.findAll { it != null }
 *
 *         return results.isEmpty() ? defaultValue : results
 *     }
 * }
 * </pre>
 *
 * @author Softwood
 * @see TaskGraphDsl#join(String, JoinStrategy, Closure)
 * @see Promise
 * @see ServiceTask
 */

/**
 * DSL builder for configuring join tasks that synchronize multiple parallel execution paths.
 */
class JoinDsl {

    final TaskGraph graph
    final ServiceTask joinTask
    final List<String> fromIds = []

    Closure userAction   // stored for build()

    JoinDsl(TaskGraph graph, ServiceTask joinTask) {
        this.graph = graph
        this.joinTask = joinTask
    }

    void from(String... ids) {
        fromIds.addAll(ids)
    }

    void action(
            @ClosureParams(
                    value = FromString,
                    options = "org.softwood.dag.task.TaskContext,java.util.List<org.softwood.promise.Promise<?>>"
            )
                    Closure cl
    ) {
        this.userAction = cl
    }

    void build() {

        if (fromIds.isEmpty()) {
            throw new IllegalStateException("Join '${joinTask.id}' has no 'from' tasks defined")
        }

        if (!userAction) {
            throw new IllegalStateException("Join '${joinTask.id}' must define an action { ... }")
        }

        // Mark this as a join task with metadata
        joinTask.metaClass.isJoinTask = true
        joinTask.metaClass.joinFromIds = fromIds

        // Wire DAG edges: predecessors → joinTask
        fromIds.each { pid ->
            def pred = graph.tasks[pid]
            if (pred) pred.successors.add(joinTask.id)
            joinTask.predecessors.add(pid)
        }

        // Install wrapper action that pulls predecessor promises from ctx.globals.__taskResults
        joinTask.action { TaskContext ctx, Optional prevOpt ->

            def resultsMap = ctx.globals.__taskResults as Map<String, Optional<Promise<?>>>
            if (!resultsMap) {
                throw new IllegalStateException(
                        "Join task '${joinTask.id}' cannot access task results map from context"
                )
            }

            List<Promise<?>> predecessorPromises = fromIds.collect { taskId ->
                def pOpt = resultsMap[taskId]
                if (!pOpt?.isPresent()) {
                    throw new IllegalStateException(
                            "Join task '${joinTask.id}' cannot find result for predecessor '${taskId}'"
                    )
                }
                pOpt.get()
            }

            def result = userAction.call(ctx, predecessorPromises)

            // Always normalize to Promise
            Promises.ensurePromise(result)
        }
    }
}
