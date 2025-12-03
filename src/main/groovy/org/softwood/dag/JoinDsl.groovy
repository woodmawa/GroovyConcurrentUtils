package org.softwood.dag

import groovy.transform.stc.ClosureParams
import groovy.transform.stc.FromString
import org.softwood.dag.task.ServiceTask
import org.softwood.dag.task.Task
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

    private final TaskGraph graph
    private final String id
    private final Set<String> inputIds = [] as Set
    private Closure joinAction

    JoinDsl(TaskGraph graph, String id) {
        this.graph = graph
        this.id = id
    }

    def configure(Closure body) {
        body.delegate = this
        body.resolveStrategy = Closure.DELEGATE_FIRST
        body.call()
    }

    def from(String... ids) {
        inputIds.addAll(ids)
    }

    def action(@DelegatesTo(ServiceTask) Closure c) {
        this.joinAction = c
    }

    // ----------------------------------------------------
    // Create + wire join task
    // ----------------------------------------------------
    void build() {

        def joinTask = new ServiceTask(id, id, graph.ctx)

        if (joinAction == null)
            throw new IllegalStateException("join($id) requires an action closure")

        joinTask.action(joinAction)
        graph.addTask(joinTask)

        inputIds.each { pid ->
            Task pred = graph.tasks[pid]
            if (!pred)
                throw new IllegalStateException("Unknown join source: $pid")

            joinTask.dependsOn(pred.id)
            pred.addSuccessor(joinTask.id)
        }
    }
}