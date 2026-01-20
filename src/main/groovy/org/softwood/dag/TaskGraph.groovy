package org.softwood.dag

import com.hazelcast.map.IMap
import com.hazelcast.topic.ITopic
import groovy.util.logging.Slf4j
import org.softwood.cluster.HazelcastManager
import org.softwood.dag.cluster.*
import org.softwood.dag.persistence.EclipseStoreManager
import org.softwood.dag.persistence.PersistenceConfig
import org.softwood.dag.persistence.TaskState as PersistenceTaskState
import org.softwood.dag.resilience.GraphTimeoutException
import org.softwood.dag.resilience.ResourceLimitPolicy
import org.softwood.dag.resilience.ResourceMonitor
import org.softwood.dag.task.*
import org.softwood.promise.Promise
import org.softwood.promise.Promises

import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Slf4j
class TaskGraph {

    Map<String, ITask> tasks = [:]

    /** Fork → RouterTask mapping produced by ForkDsl */
    List<RouterTask> routers = []

    /** True once finalizeWiring() has run */
    boolean wired = false

    /** Task execution context */
    TaskContext ctx

    /** Persistence configuration (optional) */
    PersistenceConfig persistenceConfig
    
    /** Graph-level timeout (optional) */
    Duration graphTimeout = null
    
    /** Resource limit policy (optional) */
    ResourceLimitPolicy resourceLimitPolicy = null
    
    /** Resource monitor (created per run if limits configured) */
    private ResourceMonitor resourceMonitor = null
    
    /** Persistence manager (created per run) */
    private EclipseStoreManager persistenceManager

    /** Graph completion tracking */
    private Promise graphCompletionPromise = null
    private int completedTaskCount = 0
    private int totalTaskCount = 0
    
    /** Reuse guard - ensures graph can only be started once */
    private volatile boolean hasStarted = false
    
    /** Unique run ID (generated per execution) */
    private String runId

    /** Graph ID for observability */
    String id

    /** Graph start time */
    private java.time.Instant startTime

    /** Event listeners (thread-safe for concurrent registration) */
    private final java.util.concurrent.CopyOnWriteArrayList<GraphEventListener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>()

    /** Clustering support (optional) */
    private boolean clusteringEnabled = false
    private IMap<String, GraphExecutionState> clusterGraphState
    private IMap<String, TaskRuntimeState> clusterTaskState
    private ITopic<ClusterTaskEvent> clusterEventTopic
    private String localNodeName

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
    
    /**
     * Wait for persistence to complete (if persistence is enabled).
     * This method blocks until the graph's persistence manager has finished writing all data.
     * 
     * Useful in tests to ensure persistence is complete before making assertions.
     * 
     * @param timeout maximum time to wait (default 10 seconds)
     * @param unit time unit (default SECONDS)
     * @return true if persistence completed, false if timeout or persistence not enabled
     */
    boolean awaitPersistence(long timeout = 10, java.util.concurrent.TimeUnit unit = java.util.concurrent.TimeUnit.SECONDS) {
        if (persistenceManager == null) {
            return true  // No persistence, consider it "complete"
        }
        
        try {
            return persistenceManager.awaitPersistenceComplete(timeout, unit)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
            return false
        }
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

    /**
     * Notify task state change to all listeners.
     * Called by DefaultTaskEventDispatcher.
     */
    void notifyEvent(TaskEvent event) {
        log.debug "Task event: ${event.taskId} -> ${event.taskState}"

        // Convert TaskEvent to GraphEvent and broadcast
        GraphEventType eventType = mapTaskStateToEventType(event.taskState)
        if (eventType) {
            def graphEvent = new GraphEvent(eventType, this.id, this.runId, event)
            notifyListeners(graphEvent)
        }
    }

    /**
     * Map TaskState to GraphEventType.
     */
    private GraphEventType mapTaskStateToEventType(TaskState taskState) {
        switch (taskState) {
            case TaskState.SCHEDULED:
                return GraphEventType.TASK_SCHEDULED
            case TaskState.RUNNING:
                return GraphEventType.TASK_STARTED
            case TaskState.COMPLETED:
                return GraphEventType.TASK_COMPLETED
            case TaskState.FAILED:
                return GraphEventType.TASK_FAILED
            case TaskState.SKIPPED:
                return GraphEventType.TASK_SKIPPED
            default:
                return null
        }
    }

    /**
     * Broadcast event to all registered listeners.
     * Listener errors are caught and logged but don't interrupt execution.
     */
    private void notifyListeners(GraphEvent event) {
        listeners.each { listener ->
            try {
                if (listener.accepts(event)) {
                    listener.onEvent(event)
                }
            } catch (Exception e) {
                log.error("Listener ${listener.getName()} failed on event ${event.type}", e)
            }
        }
    }

    /**
     * Register an event listener.
     * Thread-safe - can be called during graph execution.
     *
     * @param listener the listener to register
     */
    void addListener(GraphEventListener listener) {
        if (listener) {
            listeners.add(listener)
            log.debug "Registered listener: ${listener.getName()}"
        }
    }

    /**
     * Remove an event listener.
     *
     * @param listener the listener to remove
     * @return true if the listener was removed
     */
    boolean removeListener(GraphEventListener listener) {
        boolean removed = listeners.remove(listener)
        if (removed) {
            log.debug "Removed listener: ${listener.getName()}"
        }
        return removed
    }

    /**
     * Remove all event listeners.
     */
    void clearListeners() {
        int count = listeners.size()
        listeners.clear()
        log.debug "Cleared ${count} listeners"
    }

    /**
     * Get count of registered listeners.
     */
    int getListenerCount() {
        return listeners.size()
    }

    /**
     * Emit a graph-level event.
     * Used internally to notify graph state changes.
     */
    private void emitGraphEvent(GraphEventType type, Map<String, Object> metrics = [:]) {
        def event = GraphEvent.builder(type, this.id, this.runId)
            .metrics(metrics)
            .build()
        notifyListeners(event)
    }

    /**
     * After ForkDSL has attached successors to router.targetIds, finalize DAG
     */
    void finalizeWiring() {
        if (wired) return
        wired = true

        log.debug "finalizeWiring: processing ${routers.size()} forks"

        // Wire successors based on predecessors
        // If task2 has task1 as predecessor, then task1 should have task2 as successor
        tasks.values().each { t ->
            t.predecessors.each { predId ->
                ITask pred = tasks[predId]
                if (pred && !pred.successors.contains(t.id)) {
                    pred.successors << t.id
                }
            }
        }
        
        // Also wire predecessors based on successors (for router tasks)
        tasks.values().each { t ->
            t.successors.each { succId ->
                ITask succ = tasks[succId]
                if (succ && !succ.predecessors.contains(t.id)) {
                    succ.predecessors << t.id
                }
            }
        }

        // Resolve source tasks for AggregatorTasks
        tasks.values().each { t ->
            if (t instanceof org.softwood.dag.task.AggregatorTask) {
                t.resolveSourceTasks(tasks)
                log.debug "Resolved source tasks for AggregatorTask ${t.id}"
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
     * Initialize clustering if enabled in configuration.
     * Obtains Hazelcast maps and topics for distributed state management.
     */
    private void initializeClusteredExecution() {
        def hazelcastManager = HazelcastManager.instance
        
        if (!hazelcastManager.isEnabled()) {
            log.debug "Hazelcast clustering is not enabled"
            return
        }
        
        try {
            // Get distributed data structures
            clusterGraphState = hazelcastManager.getGraphStateMap()
            clusterTaskState = hazelcastManager.getTaskStateMap()
            clusterEventTopic = hazelcastManager.getTaskEventTopic()
            
            // Get local node name
            localNodeName = hazelcastManager.getInstance().getCluster().getLocalMember().toString()
            
            clusteringEnabled = true
            log.info "Clustering enabled: node=$localNodeName"
            
            // Create and publish initial graph state
            String graphId = ctx.globals.graphId ?: "graph-${System.currentTimeMillis()}"
            GraphExecutionState graphState = new GraphExecutionState(graphId, runId, localNodeName)
            graphState.markRunning()
            
            String key = "${graphId}-${runId}"
            clusterGraphState.put(key, graphState)
            log.debug "Published initial graph state to cluster: $graphState"
            
        } catch (Exception e) {
            log.warn "Failed to initialize clustering, continuing in local mode", e
            clusteringEnabled = false
        }
    }
    
    /**
     * Initialize resource monitoring if resource limits are configured.
     */
    private void initializeResourceMonitoring() {
        if (resourceLimitPolicy == null || !resourceLimitPolicy.hasLimits()) {
            log.debug "Resource limiting is disabled"
            return
        }
        
        try {
            resourceMonitor = new ResourceMonitor(resourceLimitPolicy)
            
            // Make monitor available to tasks via context
            ctx.resourceMonitor = resourceMonitor
            
            log.info "Resource monitoring enabled: $resourceLimitPolicy"
            
        } catch (Exception e) {
            log.error "Failed to initialize resource monitoring", e
            resourceMonitor = null
        }
    }

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

        // Generate unique run ID
        runId = UUID.randomUUID().toString()

        // Set graph ID if not already set
        if (!this.id) {
            this.id = "graph-${runId.substring(0, 8)}"
        }

        // Record start time
        this.startTime = java.time.Instant.now()

        // Emit GRAPH_STARTED event
        emitGraphEvent(GraphEventType.GRAPH_STARTED, [totalTasks: tasks.size()])

        // Initialize clustering if enabled
        initializeClusteredExecution()
        
        // Initialize resource monitoring if enabled
        initializeResourceMonitoring()
        
        // Initialize persistence if enabled
        initializePersistence()

        // Initialize completion tracking
        totalTaskCount = tasks.size()
        completedTaskCount = 0
        graphCompletionPromise = ctx.promiseFactory.createPromise()

        List<ITask> roots = tasks.values().findAll { it.predecessors.isEmpty() }
        log.debug "Root tasks: ${roots*.id}"

        roots.each { schedule(it) }

        // If graph timeout is set, wrap the promise with timeout enforcement
        if (graphTimeout != null) {
            return enforceGraphTimeout(graphCompletionPromise, graphTimeout)
        }

        return graphCompletionPromise
    }
    
    /**
     * Enforce graph-level timeout by wrapping the completion promise.
     * If timeout expires before graph completes, cancels remaining tasks and fails the graph.
     */
    private Promise<?> enforceGraphTimeout(Promise<?> completionPromise, Duration timeout) {
        def wrappedPromise = ctx.promiseFactory.createPromise()
        def startTime = System.currentTimeMillis()
        
        // Start timeout monitor in background
        Thread.startVirtualThread {
            try {
                // Wait for either completion or timeout
                try {
                    def result = completionPromise.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    // Graph completed within timeout - success!
                    wrappedPromise.accept(result)
                } catch (TimeoutException timeoutEx) {
                    // Graph timeout exceeded!
                    def elapsed = Duration.ofMillis(System.currentTimeMillis() - startTime)
                    def completed = tasks.values().count { it.isCompleted() || it.isSkipped() }
                    
                    log.error "Graph timeout exceeded: ${elapsed} > ${timeout} (completed: ${completed}/${totalTaskCount})"
                    
                    // Cancel all running/scheduled tasks
                    cancelRemainingTasks()
                    
                    // Create timeout exception
                    String graphId = ctx.globals.graphId ?: "graph-${System.currentTimeMillis()}"
                    def timeoutError = new GraphTimeoutException(
                        graphId,
                        timeout,
                        elapsed,
                        completed,
                        totalTaskCount
                    )
                    
                    // Finalize persistence with timeout failure
                    finalizePersistence(true, "<graph-timeout>", timeoutError)
                    
                    // Fail the wrapped promise
                    wrappedPromise.fail(timeoutError)
                }
            } catch (Exception e) {
                log.error "Error in timeout monitor", e
                wrappedPromise.fail(e)
            }
        }
        
        return wrappedPromise
    }
    
    /**
     * Cancel all tasks that are still running or scheduled when graph timeout occurs.
     * Marks them as FAILED with a timeout message.
     */
    private void cancelRemainingTasks() {
        synchronized (this) {
            tasks.values().each { task ->
                if (!task.isCompleted() && !task.isFailed() && !task.isSkipped()) {
                    // Mark as failed due to graph timeout
                    def timeoutError = new GraphTimeoutException(
                        "Task cancelled due to graph timeout"
                    )
                    task.markFailed(timeoutError)
                    
                    // Persist FAILED state
                    persistTaskStateChange(task.id, PersistenceTaskState.FAILED, [
                        errorMessage: "Cancelled due to graph timeout",
                        errorStackTrace: "Graph execution exceeded maximum duration"
                    ])
                    
                    // Publish to cluster
                    publishTaskStateToCluster(task.id, "FAILED", "Graph timeout")
                    updateClusterGraphState(task.id, "FAILED")
                    
                    log.debug "Cancelled task ${task.id} due to graph timeout"
                }
            }
        }
    }

    // --------------------------------------------------------------------
    // CLUSTERING INTEGRATION
    // --------------------------------------------------------------------
    
    /**
     * Publish task state change to cluster (if clustering is enabled).
     */
    private void publishTaskStateToCluster(String taskId, String state, String errorMsg = null) {
        if (!clusteringEnabled) return
        
        try {
            // Update task state in distributed map
            String key = "${runId}-${taskId}"
            TaskRuntimeState taskState = clusterTaskState.get(key)
            
            if (taskState == null) {
                taskState = new TaskRuntimeState(taskId, runId, state)
                if (state == "RUNNING") {
                    taskState.markRunning(localNodeName)
                }
            } else {
                taskState.setState(state)
                if (state == "COMPLETED") {
                    taskState.markCompleted()
                } else if (state == "FAILED") {
                    taskState.markFailed(errorMsg)
                } else if (state == "SKIPPED") {
                    taskState.markSkipped()
                }
            }
            
            clusterTaskState.put(key, taskState)
            
            // Publish event to cluster topic
            ClusterTaskEvent event = errorMsg ? 
                new ClusterTaskEvent(taskId, runId, state, localNodeName, errorMsg) :
                new ClusterTaskEvent(taskId, runId, state, localNodeName)
            clusterEventTopic.publish(event)
            
            log.debug "Published task state to cluster: $event"
            
        } catch (Exception e) {
            log.warn "Failed to publish task state to cluster", e
        }
    }
    
    /**
     * Update graph state in cluster (if clustering is enabled).
     * Uses explicit replace to ensure Hazelcast detects the modification.
     */
    private void updateClusterGraphState(String taskId, String state) {
        if (!clusteringEnabled || clusterGraphState == null) return
        
        try {
            String graphId = ctx.globals.graphId ?: "graph-${System.currentTimeMillis()}"
            String key = "${graphId}-${runId}"
            
            // Get-modify-replace pattern to ensure Hazelcast detects the change
            synchronized (this) {  // Synchronize to avoid lost updates
                GraphExecutionState graphState = clusterGraphState.get(key)
                if (graphState != null) {
                    graphState.updateTaskState(taskId, state)
                    // Use replace() to explicitly mark entry as modified
                    clusterGraphState.replace(key, graphState)
                }
            }
        } catch (Exception e) {
            log.warn "Failed to update cluster graph state", e
        }
    }

    // --------------------------------------------------------------------
    // PERSISTENCE INTEGRATION
    // --------------------------------------------------------------------
    
    /**
     * Initialize persistence manager if enabled (using EclipseStore)
     */
    private void initializePersistence() {
        if (persistenceConfig == null || !persistenceConfig.enabled) {
            log.debug "Persistence is disabled"
            return
        }
        
        try {
            // Determine graph ID from context or generate one
            String graphId = ctx.globals.graphId ?: "graph-${System.currentTimeMillis()}"
            
            persistenceManager = new EclipseStoreManager(
                graphId,
                runId,
                persistenceConfig.baseDir,
                persistenceConfig.compression,
                persistenceConfig.maxSnapshots
            )
            
            log.info "Persistence enabled (EclipseStore): graphId=$graphId, runId=$runId"
            
        } catch (Exception e) {
            log.error "Failed to initialize persistence, continuing without it", e
            persistenceManager = null
        }
    }
    
    /**
     * Update task state in persistence
     */
    private void persistTaskStateChange(String taskId, PersistenceTaskState state, Map data = [:]) {
        if (persistenceManager == null) return
        
        try {
            persistenceManager.updateTaskState(taskId, state, data)
        } catch (Exception e) {
            log.warn "Failed to persist task state for $taskId", e
        }
    }
    
    /**
     * Finalize persistence on graph completion
     */
    private void finalizePersistence(boolean graphFailed, String failedTaskId = null, Throwable error = null) {
        if (persistenceManager == null) return
        
        // Check if we should snapshot based on mode
        if (!persistenceConfig.shouldSnapshot(graphFailed)) {
            log.debug "Skipping snapshot (mode=${persistenceConfig.snapshotOn}, failed=$graphFailed)"
            persistenceManager.closeWithoutStoring()  // Close without persisting
            persistenceManager = null
            return
        }
        
        try {
            // Update context globals
            persistenceManager.updateContextGlobals(ctx.globals.getAll())
            
            // Mark graph completion status
            if (graphFailed) {
                persistenceManager.markGraphFailed(failedTaskId, error)
            } else {
                persistenceManager.markGraphCompleted()
            }
            
            // Close and cleanup - storage.close() blocks until all data is flushed
            persistenceManager.close()
            
        } catch (Exception e) {
            log.error "Error finalizing persistence", e
        } finally {
            persistenceManager = null
        }
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
                    
                    // Finalize persistence with failure info
                    finalizePersistence(true, firstFailure.id, firstFailure.error)
                    
                    // Update cluster graph state
                    if (clusteringEnabled && clusterGraphState != null) {
                        try {
                            String graphId = ctx.globals.graphId ?: "graph-${System.currentTimeMillis()}"
                            String key = "${graphId}-${runId}"
                            GraphExecutionState graphState = clusterGraphState.get(key)
                            if (graphState != null) {
                                graphState.markFailed(firstFailure.id, firstFailure.error?.message)
                                clusterGraphState.put(key, graphState)
                            }
                        } catch (Exception e) {
                            log.warn "Failed to update cluster graph state on failure", e
                        }
                    }
                    
                    graphCompletionPromise.fail(firstFailure.error)
                } else {
                    // All tasks completed successfully - collect results
                    List terminalResults = tasks.values()
                        .findAll { it.successors.isEmpty() && it.isCompleted() }
                        .collect { it.completionPromise?.get() }
                        .findAll { it != null }

                    // Finalize persistence with success
                    finalizePersistence(false)
                    
                    // Update cluster graph state
                    if (clusteringEnabled && clusterGraphState != null) {
                        try {
                            String graphId = ctx.globals.graphId ?: "graph-${System.currentTimeMillis()}"
                            String key = "${graphId}-${runId}"
                            GraphExecutionState graphState = clusterGraphState.get(key)
                            if (graphState != null) {
                                graphState.markCompleted()
                                clusterGraphState.put(key, graphState)
                            }
                        } catch (Exception e) {
                            log.warn "Failed to update cluster graph state on completion", e
                        }
                    }
                    
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
        
        // Persist SCHEDULED state
        persistTaskStateChange(t.id, PersistenceTaskState.SCHEDULED, [name: t.name ?: t.id])
        
        // Publish to cluster
        publishTaskStateToCluster(t.id, "SCHEDULED")
        updateClusterGraphState(t.id, "SCHEDULED")

        log.debug "schedule(): execute ${t.id}"

        Promise<?> prevPromise = t.buildPrevPromise(tasks)
        
        // Persist RUNNING state
        persistTaskStateChange(t.id, PersistenceTaskState.RUNNING, [:])
        
        // Publish to cluster
        publishTaskStateToCluster(t.id, "RUNNING")
        updateClusterGraphState(t.id, "RUNNING")
        
        Promise<?> execPromise = t.execute(prevPromise)

        // -------------------------
        // Completion callbacks
        // -------------------------
        execPromise.onComplete { result ->
            log.debug "Task ${t.id} completed with result: $result"
            t.markCompleted()
            
            // Persist COMPLETED state
            persistTaskStateChange(t.id, PersistenceTaskState.COMPLETED, [result: result])
            
            // Publish to cluster
            publishTaskStateToCluster(t.id, "COMPLETED")
            updateClusterGraphState(t.id, "COMPLETED")

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
            
            // Persist FAILED state
            persistTaskStateChange(t.id, PersistenceTaskState.FAILED, [
                errorMessage: error.message,
                errorStackTrace: stackTraceToString(error)
            ])
            
            // Publish to cluster
            publishTaskStateToCluster(t.id, "FAILED", error.message)
            updateClusterGraphState(t.id, "FAILED")
            
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
                    
                    // Persist SKIPPED state
                    persistTaskStateChange(tid, PersistenceTaskState.SKIPPED, [:])
                    
                    // Publish to cluster
                    publishTaskStateToCluster(tid, "SKIPPED")
                    updateClusterGraphState(tid, "SKIPPED")
                    
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
     * Convert throwable to string for persistence
     */
    private static String stackTraceToString(Throwable error) {
        if (error == null) return null
        
        StringWriter sw = new StringWriter()
        error.printStackTrace(new PrintWriter(sw))
        return sw.toString()
    }
    
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
                
                // Persist SKIPPED state
                persistTaskStateChange(taskId, PersistenceTaskState.SKIPPED, [:])
                
                // Publish to cluster
                publishTaskStateToCluster(taskId, "SKIPPED")
                updateClusterGraphState(taskId, "SKIPPED")
                
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
