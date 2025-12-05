package org.softwood.dag

import org.softwood.dag.task.DefaultTaskEventDispatcher
import org.softwood.dag.task.ServiceTask
import org.softwood.dag.task.Task

/**
 * DSL builder for configuring join tasks that synchronize multiple parallel execution paths.
 *
 * A join is modelled as a plain ServiceTask that depends on one or more upstream tasks.
 * The join's action typically reads the upstream promises from ctx.globals.__taskResults.
 */
class JoinDsl {

    private final TaskGraph graph
    private final String id
    private final List<String> inputIds = []
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

    /** Declare which tasks must complete before this join executes. */
    def from(String... ids) { inputIds.addAll(ids) }

    /** Join action: action { ctx, promises -> ... } */
    def action(Closure c) { joinAction = c }

    void build() {

        if (!joinAction)
            throw new IllegalStateException("join($id) requires an action closure")

        def joinTask = new ServiceTask(id, id, graph.ctx)
        joinTask.eventDispatcher = new DefaultTaskEventDispatcher(graph)
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
