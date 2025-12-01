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

    def task(String id,
             Class<? extends Task> type = ServiceTask,
             @DelegatesTo(strategy = Closure.DELEGATE_FIRST,
                     genericTypeIndex = 0,
                     type = "T extends org.softwood.dag.task.Task")
                     Closure cl) {

        Task t = type.getConstructor(String).newInstance(id)
        cl.delegate = t
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl.call()

        graph.addTask(t)
        return t
    }

    def serviceTask(String id,
                    @DelegatesTo(strategy = Closure.DELEGATE_FIRST,
                            value = ServiceTask)
                            Closure cl) {

        task(id, ServiceTask, cl)
    }

    // ----------------------------------------------------------------------
    // Fork DSL
    // ----------------------------------------------------------------------

    void fork(String name,
              @DelegatesTo(strategy = Closure.DELEGATE_FIRST,
                      value = ForkDsl)
                      Closure cl) {
        def dsl = new ForkDsl(graph)
        cl.delegate = dsl
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl.call()

        // Apply any dynamic routing wiring (router task) after user config
        dsl.build()
    }

    // ----------------------------------------------------------------------
    // Join DSL
    // ----------------------------------------------------------------------

    void join(String id,
              JoinStrategy strategy = JoinStrategy.ALL_COMPLETED,
              @DelegatesTo(strategy = Closure.DELEGATE_FIRST,
                      value = JoinDsl)
                      Closure cl) {

        ServiceTask joinTask = new ServiceTask(id)
        joinTask.metaClass.joinStrategy = strategy

        JoinDsl jdsl = new JoinDsl(graph, joinTask)

        cl.delegate = jdsl
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl.call()

        // finish configuring joinTask
        jdsl.build()

        // then add to graph
        graph.addTask(joinTask)
    }

    // ----------------------------------------------------------------------
    // Task event listener DSL
    // ----------------------------------------------------------------------

    void onTaskEvent(Closure listener) {
        graph.addListener({ TaskEvent ev -> listener.call(ev) } as TaskListener)
    }
}