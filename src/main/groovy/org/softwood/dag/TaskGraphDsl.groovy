package org.softwood.dag

import org.softwood.dag.task.DefaultTaskEventDispatcher
import org.softwood.dag.task.TaskFactory
import org.softwood.dag.task.TaskType
import org.softwood.dag.task.ServiceTask
import org.softwood.dag.task.ITask

/**
 * DSL builder for TaskGraph.
 *
 * Provides declarative blocks:
 *  - serviceTask / task
 *  - fork { ... }
 *  - join { ... }
 *  - globals { ... }
 *  
 * Supports deferred wiring - forks can reference tasks declared later in the DSL.
 */
class TaskGraphDsl {

    private final TaskGraph graph
    
    // Store fork/join DSL instances for deferred wiring
    private final List<ForkDsl> deferredForks = []
    private final List<JoinDsl> deferredJoins = []

    TaskGraphDsl(TaskGraph graph) {
        this.graph = graph
    }

    // ============================================================================
    // ENHANCED: Task Creation with TaskFactory
    // ============================================================================

    /**
     * Create a service task (ENHANCED with TaskFactory)
     */
    ITask serviceTask(String id, @DelegatesTo(ServiceTask) Closure config) {
        // Use TaskFactory instead of direct instantiation
        def t = TaskFactory.createServiceTask(id, id, graph.ctx)
        t.eventDispatcher = new DefaultTaskEventDispatcher(graph)
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.delegate = t
        config.call()
        graph.addTask(t)
        return t
    }

    /**
     * Create a task by type string (for flexibility)
     * 
     * Usage:
     *   task("myTask", "service") { ... }
     *   task("router1", "dynamic-router") { ... }
     */
    ITask task(String id, String typeString, @DelegatesTo(ITask) Closure config) {
        // Parse type string and create task via factory
        def t = TaskFactory.createTask(typeString, id, id, graph.ctx)
        t.eventDispatcher = new DefaultTaskEventDispatcher(graph)
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.delegate = t
        config.call()
        graph.addTask(t)
        return t
    }

    /**
     * Create a task by TaskType enum (MOST TYPE-SAFE)
     * 
     * Usage:
     *   task("myTask", TaskType.SERVICE) { ... }
     *   task("router1", TaskType.DYNAMIC_ROUTER) { ... }
     */
    ITask task(String id, TaskType type, @DelegatesTo(ITask) Closure config) {
        def t = TaskFactory.createTask(type, id, id, graph.ctx)
        t.eventDispatcher = new DefaultTaskEventDispatcher(graph)
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.delegate = t
        config.call()
        graph.addTask(t)
        return t
    }

    /**
     * Generic task declaration (defaults to SERVICE type)
     * 
     * Usage:
     *   task("myTask") { ... }  // Creates ServiceTask
     */
    ITask task(String id, @DelegatesTo(ITask) Closure config) {
        return serviceTask(id, config)
    }

    // ----------------------------------------------------
    // Fork block → DEFERRED wiring
    // ----------------------------------------------------
    def fork(String id, @DelegatesTo(ForkDsl) Closure body) {
        def forkDsl = new ForkDsl(graph, id)
        body.delegate = forkDsl
        body.resolveStrategy = Closure.DELEGATE_FIRST
        body.call()
        
        // Store for later wiring instead of building immediately
        deferredForks << forkDsl
    }

    // ----------------------------------------------------
    // Join block → DEFERRED wiring
    // ----------------------------------------------------
    def join(String id, @DelegatesTo(JoinDsl) Closure body) {
        def joinDsl = new JoinDsl(graph, id)
        body.delegate = joinDsl
        body.resolveStrategy = Closure.DELEGATE_FIRST
        joinDsl.configure(body)
        
        // Store for later wiring instead of building immediately
        deferredJoins << joinDsl
    }

    // ----------------------------------------------------
    // Globals block → sets ctx.globals
    // ----------------------------------------------------
    def globals(@DelegatesTo(Map) Closure body) {
        body.delegate = graph.ctx.globals
        body.resolveStrategy = Closure.DELEGATE_FIRST
        body.call()
    }
    
    // ----------------------------------------------------
    // Wire all deferred forks and joins
    // Called by TaskGraph.build() after DSL completes
    // ----------------------------------------------------
    void wireDeferred() {
        // Wire joins FIRST - they create tasks that forks may reference
        deferredJoins.each { joinDsl ->
            joinDsl.build()
        }
        
        // Then wire forks - they reference existing tasks (including joins)
        deferredForks.each { forkDsl ->
            forkDsl.build()
        }
    }
}
