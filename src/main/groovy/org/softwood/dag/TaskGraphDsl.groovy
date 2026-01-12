package org.softwood.dag

import org.softwood.dag.persistence.PersistenceConfig
import org.softwood.dag.persistence.PersistenceConfigDsl
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

    /**
     * Create a script task for executing scripts in multiple languages.
     * 
     * Usage:
     *   scriptTask("transform-data") {
     *       language "javascript"
     *       script """
     *           function transform(data) {
     *               return { fullName: data.first + ' ' + data.last };
     *           }
     *           transform(input);
     *       """
     *       bindings { ctx, prev -> [input: prev] }
     *   }
     */
    ITask scriptTask(String id, @DelegatesTo(ITask) Closure config) {
        return task(id, TaskType.SCRIPT, config)
    }

    /**
     * Create a data transform task for functional data pipelines.
     * 
     * Usage:
     *   dataTransform("process-users") {
     *       transform { ctx ->
     *           ctx.input
     *               .filter { it.age > 18 }
     *               .map { [name: it.name.toUpperCase()] }
     *       }
     *   }
     */
    ITask dataTransform(String id, @DelegatesTo(ITask) Closure config) {
        return task(id, TaskType.DATA_TRANSFORM, config)
    }

    /**
     * Create a file task for processing files with rich DSL support.
     * 
     * Usage:
     *   fileTask("process-logs") {
     *       sources {
     *           directory('/var/logs') {
     *               pattern '*.log'
     *               recursive true
     *           }
     *       }
     *       
     *       filter { file -> file.size() > 1.KB }
     *       
     *       tap { files, ctx ->
     *           println "Processing ${files.size()} files"
     *       }
     *       
     *       eachFile { ctx ->
     *           // delegate is File - GDK methods available!
     *           def errorCount = 0
     *           eachLine { line ->
     *               if (line.contains('ERROR')) errorCount++
     *           }
     *           ctx.emit([file: name, errors: errorCount])
     *       }
     *       
     *       aggregate { ctx ->
     *           [totalErrors: ctx.emitted().sum { it.errors }]
     *       }
     *   }
     */
    ITask fileTask(String id, @DelegatesTo(ITask) Closure config) {
        return task(id, TaskType.FILE, config)
    }

    /**
     * Create a messaging task for Kafka/AMQP/Vert.x/in-memory messaging.
     * 
     * <h3>Send Messages:</h3>
     * <pre>
     * messagingTask("publish") {
     *     producer new VertxEventBusProducer(vertx)  // Uses existing Vert.x!
     *     destination "orders"
     *     message { prev -> [orderId: prev.id] }
     * }
     * </pre>
     * 
     * <h3>Receive Messages:</h3>
     * <pre>
     * messagingTask("consume") {
     *     subscribe "orders", "notifications"
     *     onMessage { ctx, msg -> processMessage(msg) }
     * }
     * </pre>
     */
    ITask messagingTask(String id, @DelegatesTo(ITask) Closure config) {
        return task(id, TaskType.MESSAGING, config)
    }

    /**
     * Create a SQL database task for queries and updates.
     * 
     * <h3>Query Mode:</h3>
     * <pre>
     * sqlTask("fetch-users") {
     *     dataSource {
     *         url "jdbc:h2:mem:test"
     *         username "sa"
     *         password ""
     *     }
     *     query "SELECT * FROM users WHERE age > ?"
     *     params 18
     * }
     * </pre>
     * 
     * <h3>Update Mode:</h3>
     * <pre>
     * sqlTask("create-user") {
     *     provider myProvider
     *     update "INSERT INTO users (name, age) VALUES (?, ?)"
     *     params { prev -> [prev.name, prev.age] }
     * }
     * </pre>
     */
    ITask sqlTask(String id, @DelegatesTo(ITask) Closure config) {
        return task(id, TaskType.SQL, config)
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
    // Persistence block → configure persistence settings
    // ----------------------------------------------------
    def persistence(@DelegatesTo(PersistenceConfigDsl) Closure body) {
        def dsl = new PersistenceConfigDsl()
        body.delegate = dsl
        body.resolveStrategy = Closure.DELEGATE_FIRST
        body.call()
        graph.persistenceConfig = dsl.build()
    }
    
    // ----------------------------------------------------
    // Graph timeout → set maximum graph execution time
    // ----------------------------------------------------
    
    /**
     * Set the maximum execution time for the entire graph.
     * If the graph doesn't complete within this duration, remaining tasks are cancelled.
     * 
     * Usage:
     *   graphTimeout Duration.ofMinutes(5)
     *   graphTimeout "2 minutes"  // Human-readable format
     */
    void graphTimeout(java.time.Duration timeout) {
        graph.graphTimeout = timeout
    }
    
    /**
     * Set graph timeout using human-readable string format.
     * 
     * Examples:
     *   graphTimeout "30 seconds"
     *   graphTimeout "5 minutes"
     *   graphTimeout "1 hour"
     */
    void graphTimeout(String timeoutStr) {
        graph.graphTimeout = parseDuration(timeoutStr)
    }
    
    // ----------------------------------------------------
    // Resource limits → configure resource consumption limits
    // ----------------------------------------------------
    
    /**
     * Configure resource limits for the graph.
     * 
     * Usage:
     *   resourceLimits {
     *       maxConcurrentTasks 5
     *       maxMemoryMB 512
     *       onLimitExceeded { type, current, limit ->
     *           log.warn("Limit exceeded: $type")
     *       }
     *   }
     */
    void resourceLimits(@DelegatesTo(org.softwood.dag.resilience.ResourceLimitDsl) Closure config) {
        def dsl = new org.softwood.dag.resilience.ResourceLimitDsl()
        config.delegate = dsl
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.call()
        graph.resourceLimitPolicy = dsl.build()
    }
    
    /**
     * Parse human-readable duration strings.
     */
    private static java.time.Duration parseDuration(String str) {
        def trimmed = str.trim().toLowerCase()
        
        // Match patterns like "30 seconds", "5 minutes", "2 hours"
        def matcher = (trimmed =~ /(\d+)\s*(second|minute|hour|day)s?/)
        if (matcher.matches()) {
            def value = matcher[0][1] as long
            def unit = matcher[0][2]
            
            switch (unit) {
                case 'second': return java.time.Duration.ofSeconds(value)
                case 'minute': return java.time.Duration.ofMinutes(value)
                case 'hour': return java.time.Duration.ofHours(value)
                case 'day': return java.time.Duration.ofDays(value)
            }
        }
        
        throw new IllegalArgumentException("Invalid duration format: '$str'. Use format like '30 seconds', '5 minutes', etc.")
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
