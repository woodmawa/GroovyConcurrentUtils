package org.softwood.dag

import org.softwood.dag.task.ServiceTask
import org.softwood.dag.task.Task
import org.softwood.dag.task.TaskEvent
import org.softwood.dag.task.TaskListener

/**
 * DSL builder for TaskGraph with enhanced @DelegatesTo annotations
 * for better IDE support.
 */
class TaskGraphDsl {

    private final TaskGraph graph

    TaskGraphDsl(TaskGraph graph) {
        this.graph = graph
    }

    // ----------------------------------------------------
    // Create service task
    // ----------------------------------------------------
    Task serviceTask(String id, @DelegatesTo(ServiceTask) Closure config) {
        def t = new ServiceTask(id, id, graph.ctx)
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.delegate = t
        config.call()
        graph.addTask(t)
        return t
    }

    // Generic task declaration
    Task task(String id, @DelegatesTo(Task) Closure config) {
        return serviceTask(id, config)
    }

    // ----------------------------------------------------
    // Fork block
    // ----------------------------------------------------
    def fork(String id, @DelegatesTo(ForkDsl) Closure body) {
        def forkDsl = new ForkDsl(graph, id)
        body.delegate = forkDsl
        body.resolveStrategy = Closure.DELEGATE_FIRST
        body.call()
        forkDsl.build()
    }

    // ----------------------------------------------------
    // Join block
    // ----------------------------------------------------
    def join(String id, @DelegatesTo(JoinDsl) Closure body) {
        def joinDsl = new JoinDsl(graph, id)
        body.delegate = joinDsl
        body.resolveStrategy = Closure.DELEGATE_FIRST
        joinDsl.configure(body)
        joinDsl.build()
    }

    // ----------------------------------------------------
    // Globals block → sets ctx.globals
    // ----------------------------------------------------
    def globals(@DelegatesTo(Map) Closure body) {
        body.delegate = graph.ctx.globals
        body.resolveStrategy = Closure.DELEGATE_FIRST
        body.call()
    }
}