package org.softwood.dag

import groovy.util.logging.Slf4j
import org.softwood.dag.task.*

import java.util.concurrent.ConcurrentHashMap

/**
 * TasksCollection - Registry and Coordinator for Standalone Tasks
 *
 * Provides a lightweight collection for managing independent tasks that
 * coordinate via signals rather than explicit dependencies. Similar in concept
 * to Dataflows but for task execution rather than data flow.
 *
 * <h3>Key Features:</h3>
 * <ul>
 *   <li>Shared TaskContext across all tasks</li>
 *   <li>Named registry for task discovery</li>
 *   <li>Lifecycle management (start/stop)</li>
 *   <li>Signal-based coordination</li>
 *   <li>Metrics and monitoring</li>
 * </ul>
 *
 * <h3>Usage Example - Event-Driven System:</h3>
 * <pre>
 * def tasks = new TasksCollection()
 *
 * // Timer that sends periodic signals
 * tasks.timer("heartbeat") {
 *     interval Duration.ofSeconds(10)
 *     action { ctx ->
 *         SignalTask.sendSignalGlobal("heartbeat", [timestamp: System.currentTimeMillis()])
 *     }
 * }
 *
 * // Business rule that reacts to signals
 * tasks.businessRule("monitor") {
 *     when { signal "heartbeat" }
 *     evaluate { ctx, data ->
 *         System.currentTimeMillis() - data.timestamp < 15000
 *     }
 *     onFalse { ctx, data ->
 *         tasks.find("alert").execute(tasks.ctx.promiseFactory.createPromise(data))
 *     }
 * }
 *
 * // Subprocess for handling alerts
 * tasks.callActivity("alert") {
 *     subProcess { ctx, input ->
 *         // Handle alert
 *     }
 * }
 *
 * // Start all managed tasks
 * tasks.start()
 * </pre>
 *
 * <h3>Comparison with TaskGraph:</h3>
 * <ul>
 *   <li><b>TaskGraph</b>: Structured dependencies, orchestrated execution, graph completion</li>
 *   <li><b>TasksCollection</b>: Loose coupling, event-driven, long-running tasks</li>
 * </ul>
 */
@Slf4j
class TasksCollection {

    // =========================================================================
    // Core Components
    // =========================================================================
    
    /** Shared context across all tasks */
    final TaskContext ctx = new TaskContext()
    
    /** Named registry for task discovery */
    private final Map<String, ITask> registry = new ConcurrentHashMap<>()
    
    /** Tasks that auto-start (timers, business rules) */
    private final Set<String> autoStartTasks = Collections.synchronizedSet(new HashSet<>())
    
    /** Running state */
    volatile boolean running = false

    // =========================================================================
    // Task Registration - Convenience Methods
    // =========================================================================
    
    /**
     * Register and configure a timer task.
     */
    TimerTask timer(String id, @DelegatesTo(TimerTask) Closure config) {
        def task = TaskFactory.createTask(TaskType.TIMER, id, id, ctx) as TimerTask
        config.delegate = task
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.call()
        
        registry[id] = task
        autoStartTasks.add(id)
        
        log.debug("TasksCollection: registered timer '${id}'")
        return task
    }
    
    /**
     * Register and configure a business rule task.
     */
    BusinessRuleTask businessRule(String id, @DelegatesTo(BusinessRuleTask) Closure config) {
        def task = TaskFactory.createTask(TaskType.BUSINESS_RULE, id, id, ctx) as BusinessRuleTask
        config.delegate = task
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.call()
        
        registry[id] = task
        autoStartTasks.add(id)
        
        log.debug("TasksCollection: registered business rule '${id}'")
        return task
    }
    
    /**
     * Register and configure a call activity task.
     */
    CallActivityTask callActivity(String id, @DelegatesTo(CallActivityTask) Closure config) {
        def task = TaskFactory.createTask(TaskType.CALL_ACTIVITY, id, id, ctx) as CallActivityTask
        config.delegate = task
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.call()
        
        registry[id] = task
        // Call activities don't auto-start
        
        log.debug("TasksCollection: registered call activity '${id}'")
        return task
    }
    
    /**
     * Register and configure a service task.
     */
    ServiceTask serviceTask(String id, @DelegatesTo(ServiceTask) Closure config) {
        def task = TaskFactory.createServiceTask(id, id, ctx)
        config.delegate = task
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.call()
        
        registry[id] = task
        // Service tasks don't auto-start
        
        log.debug("TasksCollection: registered service task '${id}'")
        return task
    }
    
    /**
     * Register and configure any task type.
     */
    ITask task(String id, TaskType type, @DelegatesTo(ITask) Closure config) {
        def task = TaskFactory.createTask(type, id, id, ctx)
        config.delegate = task
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.call()
        
        registry[id] = task
        
        // Auto-start timers and business rules
        if (type in [TaskType.TIMER, TaskType.BUSINESS_RULE]) {
            autoStartTasks.add(id)
        }
        
        log.debug("TasksCollection: registered task '${id}' of type ${type}")
        return task
    }

    // =========================================================================
    // Generic Registration
    // =========================================================================
    
    /**
     * Register an already-created task.
     */
    ITask register(ITask task) {
        if (!task) {
            throw new IllegalArgumentException("Cannot register null task")
        }
        
        // Share context
        task.ctx = this.ctx
        
        registry[task.id] = task
        
        // Auto-start if appropriate
        if (task instanceof TimerTask || task instanceof BusinessRuleTask) {
            autoStartTasks.add(task.id)
        }
        
        log.debug("TasksCollection: registered existing task '${task.id}'")
        return task
    }
    
    /**
     * Unregister a task.
     */
    void unregister(String id) {
        def task = registry.remove(id)
        autoStartTasks.remove(id)
        
        if (task) {
            log.debug("TasksCollection: unregistered task '${id}'")
        }
    }

    // =========================================================================
    // Discovery
    // =========================================================================
    
    /**
     * Find a task by ID.
     */
    ITask find(String id) {
        return registry[id]
    }
    
    /**
     * Find all tasks matching a filter.
     */
    List<ITask> findAll(Closure filter) {
        return registry.values().findAll(filter)
    }
    
    /**
     * Get all registered task IDs.
     */
    Set<String> getTaskIds() {
        return new HashSet<>(registry.keySet())
    }
    
    /**
     * Check if a task exists.
     */
    boolean contains(String id) {
        return registry.containsKey(id)
    }

    // =========================================================================
    // Lifecycle Management
    // =========================================================================
    
    /**
     * Start all auto-start tasks (timers, business rules).
     */
    void start() {
        if (running) {
            log.warn("TasksCollection: already running")
            return
        }
        
        running = true
        
        log.info("TasksCollection: starting ${autoStartTasks.size()} auto-start tasks")
        
        autoStartTasks.each { taskId ->
            def task = registry[taskId]
            if (task) {
                try {
                    def nullPromise = ctx.promiseFactory.createPromise(null)
                    task.execute(nullPromise)
                    log.debug("TasksCollection: started task '${taskId}'")
                } catch (Exception e) {
                    log.error("TasksCollection: failed to start task '${taskId}'", e)
                }
            }
        }
        
        log.info("TasksCollection: started")
    }
    
    /**
     * Stop all running tasks.
     */
    void stop() {
        if (!running) {
            log.warn("TasksCollection: not running")
            return
        }
        
        running = false
        
        log.info("TasksCollection: stopping all tasks")
        
        registry.values().each { task ->
            try {
                if (task instanceof TimerTask) {
                    task.stop()
                }
                // Business rules and call activities complete on their own
            } catch (Exception e) {
                log.error("TasksCollection: error stopping task '${task.id}'", e)
            }
        }
        
        log.info("TasksCollection: stopped")
    }
    
    /**
     * Clear all tasks and reset.
     */
    void clear() {
        stop()
        registry.clear()
        autoStartTasks.clear()
        ctx.globals.clear()
        
        log.info("TasksCollection: cleared")
    }

    // =========================================================================
    // Metrics and Monitoring
    // =========================================================================
    
    /**
     * Get count of registered tasks.
     */
    int getTaskCount() {
        return registry.size()
    }
    
    /**
     * Get count of auto-start tasks.
     */
    int getAutoStartCount() {
        return autoStartTasks.size()
    }
    
    /**
     * Get count of tasks by state.
     */
    int getTaskCountByState(TaskState state) {
        return registry.values().count { it.state == state }
    }
    
    /**
     * Get count of active (running) tasks.
     */
    int getActiveCount() {
        return getTaskCountByState(TaskState.RUNNING)
    }
    
    /**
     * Get count of completed tasks.
     */
    int getCompletedCount() {
        return getTaskCountByState(TaskState.COMPLETED)
    }
    
    /**
     * Get count of failed tasks.
     */
    int getFailedCount() {
        return getTaskCountByState(TaskState.FAILED)
    }
    
    /**
     * Get list of active tasks.
     */
    List<ITask> getActiveTasks() {
        return registry.values().findAll { it.state == TaskState.RUNNING }
    }
    
    /**
     * Get tasks by type.
     */
    List<ITask> getTasksByType(Class<? extends ITask> type) {
        return registry.values().findAll { type.isInstance(it) }
    }
    
    /**
     * Get summary statistics.
     */
    Map getStats() {
        return [
            total: taskCount,
            autoStart: autoStartCount,
            running: activeCount,
            completed: completedCount,
            failed: failedCount,
            scheduled: getTaskCountByState(TaskState.SCHEDULED),
            skipped: getTaskCountByState(TaskState.SKIPPED)
        ]
    }

    @Override
    String toString() {
        return "TasksCollection[tasks=${taskCount}, running=${running}, active=${activeCount}]"
    }
}
