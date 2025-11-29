package org.softwood.dag

import org.softwood.dag.task.ServiceTask
import org.softwood.dag.task.Task
import org.softwood.dag.task.TaskEvent
import org.softwood.dag.task.TaskListener

/**
 * DSL builder for TaskGraph.
 *
 * Supports:
 *   globals { ... }
 *   task("id", ServiceTask) { ... }
 *   serviceTask("id") { ... }
 *   fork("name") { from "..."; to "...","..." }
 *   join("id", JoinStrategy) { from "...","..."; action { ... } }
 *   onTaskEvent { event -> ... }
 */
class TaskGraphDsl {

    private final TaskGraph graph

    TaskGraphDsl(TaskGraph graph) {
        this.graph = graph
    }

    // ----------------------------------------------------------------------
    // Globals DSL
    // ----------------------------------------------------------------------

    void globals(@DelegatesTo(Map) Closure cl) {
        cl.delegate = graph.ctx.globals
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl.call()
    }

    // ----------------------------------------------------------------------
    // Task creation DSL
    // ----------------------------------------------------------------------

    def task(String id, Class<? extends Task> type = ServiceTask,
             @DelegatesTo(strategy = Closure.DELEGATE_FIRST) Closure cl) {

        Task t = type.getConstructor(String).newInstance(id)
        cl.delegate = t
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl.call()

        graph.addTask(t)
        return t
    }

    def serviceTask(String id,
                    @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = ServiceTask) Closure cl) {

        task(id, ServiceTask, cl)
    }

    // ----------------------------------------------------------------------
    // Fork DSL
    // ----------------------------------------------------------------------

    void fork(String name, @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = ForkDsl) Closure cl) {
        def dsl = new ForkDsl(graph)
        cl.delegate = dsl
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl.call()
    }

    // ----------------------------------------------------------------------
    // Join DSL (FULLY FIXED)
    // ----------------------------------------------------------------------

    /**
     * Corrected join DSL:
     *
     * join("summary", JoinStrategy.ALL_COMPLETED) {
     *     from "loadOrders", "loadInvoices"
     *
     *     action { ctx, promises -> ... }
     * }
     */
    void join(String id,
              JoinStrategy strategy = JoinStrategy.ALL_COMPLETED,
              @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = JoinDsl) Closure cl) {

        // Create the underlying task that performs the join action
        ServiceTask joinTask = new ServiceTask(id)
        joinTask.metaClass.joinStrategy = strategy

        // Build join-routing structure
        JoinDsl dsl = new JoinDsl(graph, joinTask)

        // Delegate user DSL to JoinDsl FIRST
        cl.delegate = dsl
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl.call()

        // Add the fully configured join task to the graph
        graph.addTask(joinTask)
    }

    // ----------------------------------------------------------------------
    // Task event listener DSL
    // ----------------------------------------------------------------------

    void onTaskEvent(Closure listener) {
        graph.addListener({ TaskEvent ev -> listener.call(ev) } as TaskListener)
    }
}



