package org.softwood.dag

import org.softwood.dag.task.ServiceTask

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
     * The user-defined action is attached to the underlying join task.
     */
    void action(@DelegatesTo(strategy = Closure.DELEGATE_FIRST) Closure cl) {
        joinTask.action(cl)
    }
}
