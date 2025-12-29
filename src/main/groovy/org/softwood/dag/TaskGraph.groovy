package org.softwood.dag

import groovy.util.logging.Slf4j
import org.softwood.dag.task.*
import org.softwood.promise.Promise
import org.softwood.promise.Promises

@Slf4j
class TaskGraph {

    Map<String, ITask> tasks = [:]

    /** Fork → RouterTask mapping produced by ForkDsl */
    List<RouterTask> routers = []

    /** True once finalizeWiring() has run */
    boolean wired = false

    /** Task execution context */
    TaskContext ctx

    /** Graph completion tracking */
    private Promise graphCompletionPromise = null
    private int completedTaskCount = 0
    private int totalTaskCount = 0
    
    /** Reuse guard - ensures graph can only be started once */
    private volatile boolean hasStarted = false

    // --------------------------------------------------------------------
    // STATIC BUILDER METHOD
    // --------------------------------------------------------------------

    /**
     * Static factory method to build a TaskGraph using DSL
     * Usage: TaskGraph.build { ... DSL blocks ... }
     */
    static TaskGraph build(@DelegatesTo(TaskGraphDsl) Closure dslClosure) {
        TaskGraph graph = new TaskGraph()
        graph.ctx = new TaskContext()
        
        TaskGraphDsl dsl = new TaskGraphDsl(graph)
        dslClosure.delegate = dsl
        dslClosure.resolveStrategy = Closure.DELEGATE_FIRST
        dslClosure.call()

        // DEFERRED WIRING: Wire all forks/joins after DSL completes
        dsl.wireDeferred()

        return graph
    }
    
    /**
     * Static factory method to define a reusable TaskGraph structure.
     * Creates a TaskGraphFactory that can generate multiple isolated graph instances.
     * 
     * <p>Use this when you need to execute the same graph structure multiple times
     * with complete isolation (e.g., batch processing, retries, concurrent execution).</p>
     * 
     * <h3>Usage:</h3>
     * <pre>
     * def factory = TaskGraph.factory {
     *     httpTask("fetch") { url "https://api.example.com/data" }
     *     httpTask("process") { 
     *         dependsOn "fetch"
     *         action { ctx, data -> processData(data) }
     *     }
     * }
     * 
     * // Create isolated instances
     * def result1 = factory.create().start().get()
     * def result2 = factory.create().start().get()
     * </pre>
     * 
     * @param dslClosure DSL closure defining the graph structure
     * @return TaskGraphFactory that creates isolated instances
     * @see TaskGraphFactory
     */
    static TaskGraphFactory factory(@DelegatesTo(TaskGraphDsl) Closure dslClosure) {
        return TaskGraphFactory.define(dslClosure)
    }

    /**
     * Convenience method - alias for start()
     */
    Promise<?> run() {
        return start()
    }

    // --------------------------------------------------------------------
    // BUILD / WIRING
    // --------------------------------------------------------------------

    void addTask(ITask t) {
        tasks[t.id] = t
    }

    void registerRouter(RouterTask router) {
        routers << router
    }

    // Add this method:
    void notifyEvent(TaskEvent event) {
        // Event notification for task state changes
        log.debug "Task event: ${event.taskId} -> ${event.taskState}"
        // You can add event listeners here later if needed
    }

    /**
     * After ForkDSL has attached successors to router.targetIds, finalize DAG
     */
    void finalizeWiring() {
        if (wired) return
        wired = true

        log.debug "finalizeWiring: processing ${routers.size()} forks"

        tasks.values().each { t ->
            t.successors.each { succId ->
                tasks[succId]?.predecessors << t.id
            }
        }

        log.debug "Final graph structure:"
        tasks.values().each { t ->
            log.debug "  Task ${t.id}: predecessors=${t.predecessors} successors=${t.successors}"
        }
    }

    // --------------------------------------------------------------------
    // EXECUTION
    // --------------------------------------------------------------------

    /**
     * Start graph execution by scheduling all root tasks.
     * Returns a promise that resolves with terminal task results when ALL tasks complete.
     * 
     * <p><b>Important:</b> Each TaskGraph instance can only be started once.
     * For reusable graph definitions, use {@link TaskGraphFactory}.</p>
     * 
     * @return Promise that completes when all tasks finish
     * @throws IllegalStateException if this graph has already been started
     */
    Promise<?> start() {
        // Guard against reuse
        if (hasStarted) {
            throw new IllegalStateException(
                "TaskGraph has already been started and cannot be reused. " +
                "Each graph instance is single-use. For reusable graph definitions, " +
                "use TaskGraphFactory.define { ... }.create() to create fresh instances."
            )
        }
        hasStarted = true
        
        finalizeWiring()

        // Initialize completion tracking
        totalTaskCount = tasks.size()
        completedTaskCount = 0
        graphCompletionPromise = ctx.promiseFactory.createPromise()

        List<ITask> roots = tasks.values().findAll { it.predecessors.isEmpty() }
        log.debug "Root tasks: ${roots*.id}"

        roots.each { schedule(it) }

        return graphCompletionPromise
    }

    /**
     * Called by schedule() when a task reaches a terminal state
     * Checks if graph execution is complete and resolves graphCompletionPromise if so
     */
    private void checkGraphCompletion() {
        synchronized (this) {
            int terminal = tasks.values().count { task ->
                task.isCompleted() || task.isFailed() || task.isSkipped()
            }

            if (terminal == totalTaskCount && graphCompletionPromise != null) {
                // All tasks have reached terminal state
                
                // Check if any task failed
                def failedTasks = tasks.values().findAll { it.isFailed() }
                
                if (failedTasks) {
                    // If any task failed, fail the graph promise with the first error
                    def firstFailure = failedTasks[0]
                    log.error "Graph execution failed due to task ${firstFailure.id}: ${firstFailure.error}"
                    graphCompletionPromise.fail(firstFailure.error)
                } else {
                    // All tasks completed successfully - collect results
                    List terminalResults = tasks.values()
                        .findAll { it.successors.isEmpty() && it.isCompleted() }
                        .collect { it.completionPromise?.get() }
                        .findAll { it != null }

                    // Resolve the graph completion promise
                    def result = terminalResults.size() == 1 ? terminalResults[0] : terminalResults
                    graphCompletionPromise.accept(result)
                    
                    log.debug "Graph execution completed with result: $result"
                }
            }
        }
    }

    // --------------------------------------------------------------------
    // Scheduler
    // --------------------------------------------------------------------

    private void schedule(ITask t) {

        if (t.hasStarted) {
            log.debug "schedule(): ${t.id} already scheduled"
            return
        }

        t.markScheduled()

        log.debug "schedule(): execute ${t.id}"

        Promise<?> prevPromise = t.buildPrevPromise(tasks)
        Promise<?> execPromise = t.execute(prevPromise)

        // -------------------------
        // Completion callbacks
        // -------------------------
        execPromise.onComplete { result ->
            log.debug "Task ${t.id} completed with result: $result"
            t.markCompleted()

            if (!(t instanceof RouterTask)) {
                scheduleNormalSuccessors(t)
            } else {
                // For routers, the result IS the list of chosen targets
                scheduleRouterSuccessors((RouterTask)t, result as List<String>)
            }

            // Check if graph execution is complete
            checkGraphCompletion()
        }

        execPromise.onError { error ->
            log.error "Task ${t.id} failed: $error"
            t.markFailed(error)
            
            // CRITICAL: When a task fails, mark all downstream tasks as skipped
            // so the graph can complete
            markDownstreamAsSkipped(t)
            
            // Check if graph execution is complete (even with failures)
            checkGraphCompletion()
        }
    }

    // --------------------------------------------------------------------
    // NORMAL SUCCESSOR SCHEDULING
    // --------------------------------------------------------------------

    private void scheduleNormalSuccessors(ITask t) {
        t.successors.each { succId ->
            ITask succ = tasks[succId]
            scheduleIfReady(succ)
        }
    }

    // --------------------------------------------------------------------
    // ROUTER SUCCESSOR SCHEDULING
    // --------------------------------------------------------------------

    private void scheduleRouterSuccessors(RouterTask router, List<String> chosen) {

        if (chosen == null) {
            log.error "Router ${router.id} completed but result was null!"
            return
        }

        Set<String> allTargets = router.targetIds

        log.debug "router ${router.id} selected targets: $chosen"
        log.debug "router ${router.id} all possible targets: $allTargets"

        // Mark unselected targets as SKIPPED (but not for sharding routers)
        if (!(router instanceof ShardingRouterTask)) {
            allTargets.each { tid ->
                if (!chosen.contains(tid)) {
                    tasks[tid]?.markSkipped()
                    // Check completion after marking tasks as skipped
                    checkGraphCompletion()
                }
            }
        }

        // Handle sharding router → ensure join successors scheduled only ONCE
        if (router instanceof ShardingRouterTask) {

            if (!router.scheduledShardSuccessors) {
                router.scheduledShardSuccessors = true

                // First schedule shard tasks
                chosen.each { sid ->
                    ITask shardTask = tasks[sid]

                    // Inject shard data into shard task
                    List shardData = router.getShardData(sid)
                    shardTask.setInjectedInput(shardData)

                    schedule(shardTask)
                }

                // AFTER scheduling shards → schedule successors of router
                router.successors.each { joinId ->
                    scheduleIfReady(tasks[joinId])
                }
            }

            return
        }

        // Non-sharding router: schedule only chosen targets
        // CRITICAL: Inject the router's INPUT data (not the routing result) into selected tasks
        chosen.each { tid ->
            ITask selectedTask = tasks[tid]
            if (selectedTask && router.routerInputData != null) {
                log.debug "Injecting router input data into task ${tid}: ${router.routerInputData}"
                selectedTask.setInjectedInput(router.routerInputData)
            }
            scheduleIfReady(selectedTask)
        }
    }

    // --------------------------------------------------------------------
    // READY CHECK
    // --------------------------------------------------------------------

    /**
     * When a task fails, recursively mark all downstream (successor) tasks as skipped
     * This ensures the graph can reach completion even when a task fails
     */
    private void markDownstreamAsSkipped(ITask failedTask) {
        def visited = new HashSet<String>()
        def queue = new LinkedList<String>()
        
        // Start with immediate successors
        queue.addAll(failedTask.successors)
        
        while (!queue.isEmpty()) {
            String taskId = queue.poll()
            
            if (visited.contains(taskId)) continue
            visited.add(taskId)
            
            ITask task = tasks[taskId]
            if (task && !task.hasStarted) {
                task.markSkipped()
                log.debug "Marked task ${taskId} as SKIPPED due to upstream failure"
                
                // Add its successors to the queue
                queue.addAll(task.successors)
            }
        }
    }

    private void scheduleIfReady(ITask t) {
        if (t == null) return

        // Synchronize on the task to prevent race conditions when multiple
        // predecessors complete simultaneously
        synchronized (t) {
            if (t.hasStarted) return

            boolean ready = t.predecessors.every { pid ->
                ITask pt = tasks[pid]
                pt.isCompleted() || pt.isSkipped()
            }

            if (ready) {
                schedule(t)
            }
        }
    }
}
