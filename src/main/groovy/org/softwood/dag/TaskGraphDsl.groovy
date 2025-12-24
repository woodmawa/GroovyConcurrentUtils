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

    // ============================================================================
    // Convenience Methods for New Task Types
    // ============================================================================

    /**
     * Create a timer task for periodic/scheduled execution.
     * 
     * Usage:
     *   timer("heartbeat") {
     *       interval Duration.ofSeconds(30)
     *       maxExecutions 10
     *       action { ctx -> ... }
     *   }
     */
    ITask timer(String id, @DelegatesTo(ITask) Closure config) {
        return task(id, TaskType.TIMER, config)
    }

    /**
     * Create a business rule task for condition-based execution.
     * 
     * Usage:
     *   businessRule("approval") {
     *       when { signal "request" }
     *       evaluate { ctx, data -> data.amount < 1000 }
     *       action { ctx, data -> approve(data) }
     *   }
     */
    ITask businessRule(String id, @DelegatesTo(ITask) Closure config) {
        return task(id, TaskType.BUSINESS_RULE, config)
    }

    /**
     * Create a call activity task for subprocess invocation.
     * 
     * Usage:
     *   callActivity("subprocess") {
     *       input { ctx -> ctx.globals.data }
     *       subProcess { ctx, input -> graph.run() }
     *       output { result -> result.status }
     *   }
     */
    ITask callActivity(String id, @DelegatesTo(ITask) Closure config) {
        return task(id, TaskType.CALL_ACTIVITY, config)
    }

    /**
     * Create a loop task for iteration over collections.
     * 
     * Usage:
     *   loop("process-items") {
     *       over { ctx -> ctx.globals.items }
     *       action { ctx, item, index -> processItem(item) }
     *       parallel 10  // Optional
     *   }
     */
    ITask loop(String id, @DelegatesTo(ITask) Closure config) {
        return task(id, TaskType.LOOP, config)
    }

    /**
     * Create a parallel gateway for AND-split/AND-join execution.
     * 
     * Usage:
     *   parallel("fan-out") {
     *       branches "task1", "task2", "task3"
     *       waitForAll true
     *       failFast false
     *   }
     */
    ITask parallel(String id, @DelegatesTo(ITask) Closure config) {
        return task(id, TaskType.PARALLEL_GATEWAY, config)
    }

    /**
     * Alternative name for parallel gateway - more explicit.
     */
    ITask parallelGateway(String id, @DelegatesTo(ITask) Closure config) {
        return parallel(id, config)
    }

    /**
     * Create an HTTP task for making REST API calls.
     * 
     * Usage:
     *   httpTask("fetch-user") {
     *       url "https://api.example.com/users/123"
     *       method GET
     *       headers {
     *           "Accept" "application/json"
     *       }
     *   }
     */
    ITask httpTask(String id, @DelegatesTo(ITask) Closure config) {
        return task(id, TaskType.HTTP, config)
    }

    // ============================================================================
    // Dependency Declaration - Simple Linear Dependencies
    // ============================================================================

    /**
     * Declare that a task depends on one or more other tasks.
     * The task will run after ALL dependencies complete.
     * 
     * Usage:
     *   dependsOn("B", "A")              // B depends on A
     *   dependsOn("D", "A", "B", "C")    // D depends on A, B, and C
     * 
     * For multiple dependencies, an implicit join task is created.
     */
    void dependsOn(String taskId, String... dependencyIds) {
        if (dependencyIds.length == 0) {
            throw new IllegalArgumentException("dependsOn requires at least one dependency")
        }
        
        def task = graph.tasks[taskId]
        if (!task) {
            throw new IllegalArgumentException("dependsOn: unknown task '${taskId}'")
        }
        
        if (dependencyIds.length == 1) {
            // Simple 1-to-1 dependency - direct edge
            def depTask = graph.tasks[dependencyIds[0]]
            if (!depTask) {
                throw new IllegalArgumentException("dependsOn: unknown dependency '${dependencyIds[0]}'")
            }
            
            depTask.addSuccessor(task)
            task.addPredecessor(depTask)
        } else {
            // Multiple dependencies → create implicit join task
            def joinId = "${taskId}-join-" + UUID.randomUUID().toString().substring(0, 8)
            def joinTask = TaskFactory.createTask(TaskType.SERVICE, joinId, joinId, graph.ctx)
            joinTask.action({ ctx, prevResults -> 
                // Pass through all results as a list wrapped in a Promise
                ctx.promiseFactory.createPromise(prevResults)
            })
            joinTask.eventDispatcher = new DefaultTaskEventDispatcher(graph)
            graph.addTask(joinTask)
            
            // Wire: dependencies → join → task
            dependencyIds.each { depId ->
                def depTask = graph.tasks[depId]
                if (!depTask) {
                    throw new IllegalArgumentException("dependsOn: unknown dependency '${depId}'")
                }
                depTask.addSuccessor(joinTask)
                joinTask.addPredecessor(depTask)
            }
            
            joinTask.addSuccessor(task)
            task.addPredecessor(joinTask)
        }
    }

    /**
     * Create a linear chain of tasks where each depends on the previous.
     * 
     * Usage:
     *   chainVia("A", "B", "C")  // Creates: A → B → C
     * 
     * This is syntactic sugar for:
     *   dependsOn("B", "A")
     *   dependsOn("C", "B")
     */
    void chainVia(String... taskIds) {
        if (taskIds.length < 2) {
            throw new IllegalArgumentException("chainVia requires at least 2 tasks")
        }
        
        for (int i = 0; i < taskIds.length - 1; i++) {
            dependsOn(taskIds[i + 1], taskIds[i])
        }
    }

    // ============================================================================
    // Fork and Join - Parallel Execution Patterns
    // ============================================================================

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
