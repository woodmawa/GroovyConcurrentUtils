package org.softwood.dag

import groovy.transform.stc.ClosureParams
import groovy.transform.stc.FromString
import org.softwood.dag.task.ServiceTask
import org.softwood.dag.task.TaskContext
import org.softwood.dataflow.DataflowVariable
import org.softwood.promise.Promise
import org.softwood.promise.Promises

class JoinDsl {

    final TaskGraph graph
    final ServiceTask joinTask
    final List<String> fromIds = []

    JoinDsl(TaskGraph graph, ServiceTask joinTask) {
        this.graph = graph
        this.joinTask = joinTask
    }

    /**
     * DSL: from "a", "b"
     */
    void from(String... ids) {
        fromIds.addAll(ids)

        ids.each { pid ->
            // Link graph edges: predecessor -> this join task
            def pred = graph.tasks[pid]
            if (pred) {
                pred.successors.add(joinTask.id)
            }
            joinTask.predecessors.add(pid)
        }
    }

    /**
     * DSL: action { ctx, promises -> ... }
     *
     * The user's action receives:
     * - ctx: TaskContext
     * - promises: List<Promise<?>> from predecessor tasks
     */
    void action(
            @ClosureParams(value = FromString,
                    options = "org.softwood.dag.task.TaskContext,java.util.List<org.softwood.promise.Promise<?>>")
                    Closure userAction) {

        // Mark this as a join task so TaskGraph.run() knows to handle it specially
        joinTask.metaClass.isJoinTask = true
        joinTask.metaClass.joinFromIds = fromIds

        // Wrapper action that collects predecessor promises
        joinTask.action { TaskContext ctx, Optional prevOpt ->
            // Get results map from context (set by TaskGraph.run())
            def resultsMap = ctx.globals.__taskResults as Map<String, Optional<Promise<?>>>

            if (!resultsMap) {
                throw new IllegalStateException(
                        "Join task '${joinTask.id}' cannot access task results map from context"
                )
            }

            // Collect predecessor promises in order
            List<Promise<?>> predecessorPromises = fromIds.collect { taskId ->
                def promiseOpt = resultsMap[taskId]
                if (!promiseOpt?.isPresent()) {
                    throw new IllegalStateException(
                            "Join task '${joinTask.id}' cannot find result for predecessor '${taskId}'"
                    )
                }
                promiseOpt.get()
            }

            // Call user's join action with collected promises
            // CRITICAL: Do NOT use "as Promise" - it triggers type coercion which can leak DataflowVariable
            def result = userAction.call(ctx, predecessorPromises)

            // CRITICAL FIX: Ensure the result is always a Promise
            // If the user accidentally returned a DataflowVariable or other type,
            // wrap it properly to maintain the Promise abstraction layer
            return ensurePromise(result)        }
    }

    /**
     * Ensure the given result is a proper Promise.
     *
     * This method handles cases where user code might accidentally return:
     * - A raw DataflowVariable (wrap it in a Promise)
     * - Already a Promise (return as-is)
     * - A plain value (wrap in a completed Promise)
     * - null (return a failed Promise)
     */
    private Promise<?> ensurePromise(Object result) {
        // Already a Promise? Good!
        if (result instanceof Promise) {
            return (Promise<?>) result
        }

        // Raw DataflowVariable leaked out? Wrap it!
        if (result instanceof DataflowVariable) {
            DataflowVariable dfv = (DataflowVariable) result
            Promise promise = Promises.newPromise()

            // Wire the DFV to the Promise
            dfv.whenAvailable { value ->
                promise.accept(value)
            }
            dfv.whenError { error ->
                promise.fail(error)
            }

            return promise
        }

        // Plain value? Wrap in completed Promise
        if (result != null) {
            Promise promise = Promises.newPromise()
            promise.accept(result)
            return promise
        }

        // null? Return failed Promise
        Promise promise = Promises.newPromise()
        promise.fail(new NullPointerException("Join action returned null"))
        return promise
    }
}