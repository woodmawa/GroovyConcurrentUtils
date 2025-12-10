package org.softwood.dag

import groovy.util.logging.Slf4j
import org.softwood.dag.task.ITask
import org.softwood.dag.task.ServiceTask
import org.softwood.dag.task.TaskContext
import org.softwood.dag.task.TaskFactory
import org.softwood.dag.task.TaskType
import org.softwood.promise.Promise
import org.softwood.promise.Promises

import java.util.function.Function

/**
 * Lightweight utility for running standalone tasks without TaskGraph overhead.
 *
 * Provides a simpler API for executing independent tasks that share a common context.
 * Similar to Dataflows API but works with ITask instances.
 *
 * <h3>Usage Examples:</h3>
 * <pre>
 * // Execute multiple tasks and collect all results
 * def results = Tasks.all { ctx ->
 *     task("fetch") { "data" }
 *     task("process") { it + "!" }
 * }
 *
 * // Race - return first completed
 * def winner = Tasks.any { ctx ->
 *     task("fast") { sleep(10); "fast" }
 *     task("slow") { sleep(100); "slow" }
 * }
 *
 * // Java lambda style
 * def result = Tasks.execute(
 *     task -> task.execute { "result" }
 * )
 * </pre>
 */
@Slf4j
class Tasks {

    // =========================================================================
    // Core Execution Methods
    // =========================================================================

    /**
     * Execute multiple tasks and wait for ALL to complete.
     * Returns a list of all results in the order tasks were defined.
     *
     * FIX: Explicitly collect promises in a loop instead of using .collect {}
     * to avoid Groovy closure issues.
     */
    static <T> List<T> all(@DelegatesTo(TasksDsl) Closure dsl) {
        TaskContext ctx = new TaskContext()
        TasksDsl tasksDsl = new TasksDsl(ctx)

        dsl.delegate = tasksDsl
        dsl.resolveStrategy = Closure.DELEGATE_FIRST
        dsl.call(ctx)

        // Execute all tasks and collect promises - EXPLICIT LOOP
        List<Promise<?>> promises = []
        for (ITask task : tasksDsl.tasks) {
            log.debug "Tasks.all(): executing task ${task.id}"
            Promise<?> nullPromise = ctx.promiseFactory.createPromise()
            nullPromise.accept(null)
            Promise<?> resultPromise = task.execute(nullPromise)
            promises.add(resultPromise)
        }

        // Wait for all to complete
        log.debug "Tasks.all(): waiting for ${promises.size()} tasks to complete"
        Promise<List<T>> allPromise = Promises.all(promises)
        return allPromise.get()
    }

    /**
     * Execute multiple tasks and return the FIRST to complete.
     * Other tasks will continue executing but their results are ignored.
     *
     * <pre>
     * def winner = Tasks.any { ctx ->
     *     task("fast") { sleep(10); "fast!" }
     *     task("slow") { sleep(100); "slow" }
     * }
     * // winner = "fast!"
     * </pre>
     *
     * @param dsl closure defining tasks
     * @return result of first completed task
     */
    static <T> T any(@DelegatesTo(TasksDsl) Closure dsl) {
        TaskContext ctx = new TaskContext()
        TasksDsl tasksDsl = new TasksDsl(ctx)

        dsl.delegate = tasksDsl
        dsl.resolveStrategy = Closure.DELEGATE_FIRST
        dsl.call(ctx)

        // Execute all tasks and collect promises
        List<Promise<?>> promises = tasksDsl.tasks.collect { task ->
            log.debug "Tasks.any(): executing task ${task.id}"
            Promise<?> nullPromise = ctx.promiseFactory.createPromise()
            nullPromise.accept(null)
            task.execute(nullPromise)
        }

        // Wait for first to complete
        log.debug "Tasks.any(): racing ${promises.size()} tasks"
        Promise<T> anyPromise = Promises.any(promises)
        return anyPromise.get()
    }

    /**
     * Execute tasks sequentially, passing result of previous to next.
     * Creates a pipeline where each task receives the previous task's result.
     *
     * <pre>
     * def result = Tasks.sequence { ctx ->
     *     task("step1") { 10 }
     *     task("step2") { prev -> prev * 2 }    // receives 10
     *     task("step3") { prev -> prev + 5 }    // receives 20
     * }
     * // result = 25
     * </pre>
     *
     * @param dsl closure defining tasks
     * @return result of final task
     */
    static <T> T sequence(@DelegatesTo(TasksDsl) Closure dsl) {
        TaskContext ctx = new TaskContext()
        TasksDsl tasksDsl = new TasksDsl(ctx)

        dsl.delegate = tasksDsl
        dsl.resolveStrategy = Closure.DELEGATE_FIRST
        dsl.call(ctx)

        // Execute tasks sequentially
        Promise<?> prevPromise = ctx.promiseFactory.createPromise()
        prevPromise.accept(null)

        for (ITask task : tasksDsl.tasks) {
            log.debug "Tasks.sequence(): executing task ${task.id}"
            prevPromise = task.execute(prevPromise)
        }

        log.debug "Tasks.sequence(): waiting for final result"
        return prevPromise.get()
    }

    // =========================================================================
    // Java Lambda Style - Single Task Execution
    // =========================================================================

    /**
     * Execute a single task with Java lambda/Function style.
     * Useful for simple one-off task execution.
     *
     * <pre>
     * // Using lambda
     * def result = Tasks.execute(task -> {
     *     task.action { ctx, prev -> "Hello" }
     *     return task
     * })
     *
     * // Or method reference style
     * def result = Tasks.execute(this::configureTask)
     * </pre>
     *
     * @param configurator function to configure the task
     * @return task execution result
     */
    static <T> T execute(Function<ServiceTask, ServiceTask> configurator) {
        TaskContext ctx = new TaskContext()

        // Create a service task
        ServiceTask task = TaskFactory.createServiceTask(
                "task_${UUID.randomUUID()}",
                "anonymous",
                ctx
        )

        // Apply configuration
        configurator.apply(task)

        // Execute
        log.debug "Tasks.execute(): executing configured task"
        Promise<?> nullPromise = ctx.promiseFactory.createPromise()
        nullPromise.accept(null)
        Promise<T> result = task.execute(nullPromise)

        return result.get()
    }

    /**
     * Execute a single task with Groovy closure style.
     * The closure receives a wrapped task that automatically converts
     * non-Promise results to Promises.
     *
     * <pre>
     * def result = Tasks.execute { task ->
     *     task.action { ctx, prev -> "Hello" }  // String auto-wrapped
     * }
     * </pre>
     *
     * @param configurator closure to configure the task
     * @return task execution result
     */
    static <T> T execute(@DelegatesTo(ServiceTask) Closure configurator) {
        TaskContext ctx = new TaskContext()

        // Create a service task
        ServiceTask task = TaskFactory.createServiceTask(
                "task_${UUID.randomUUID()}",
                "anonymous",
                ctx
        )

        // Create a wrapper that intercepts action() calls and wraps results
        def wrapper = [
                action: { Closure userAction ->
                    // Wrap the user's action to handle non-promise returns
                    task.action { taskCtx, prevValue ->
                        def result = userAction.call(taskCtx, prevValue)

                        // If result is already a promise, return it; otherwise wrap it
                        if (result instanceof Promise) {
                            return result
                        } else {
                            // Wrap non-promise results in a resolved promise
                            Promise wrapped = taskCtx.promiseFactory.createPromise()
                            wrapped.accept(result)
                            return wrapped
                        }
                    }
                }
        ]

        // Apply configuration with our wrapper
        configurator.delegate = wrapper
        configurator.resolveStrategy = Closure.DELEGATE_FIRST
        configurator.call(wrapper)

        // Execute
        log.debug "Tasks.execute(): executing configured task"
        Promise<?> nullPromise = ctx.promiseFactory.createPromise()
        nullPromise.accept(null)
        Promise<T> result = task.execute(nullPromise)

        return result.get()
    }

    // =========================================================================
    // Utility Methods
    // =========================================================================

    /**
     * Execute multiple independent tasks in parallel.
     * Unlike all(), doesn't wait - returns promises immediately.
     *
     * <pre>
     * def promises = Tasks.parallel { ctx ->
     *     task("t1") { longRunningWork1() }
     *     task("t2") { longRunningWork2() }
     * }
     * // Do other work...
     * def results = promises.collect { it.get() }
     * </pre>
     *
     * @param dsl closure defining tasks
     * @return list of promises for each task
     */
    static List<Promise<?>> parallel(@DelegatesTo(TasksDsl) Closure dsl) {
        TaskContext ctx = new TaskContext()
        TasksDsl tasksDsl = new TasksDsl(ctx)

        dsl.delegate = tasksDsl
        dsl.resolveStrategy = Closure.DELEGATE_FIRST
        dsl.call(ctx)

        // Execute all tasks and return promises
        return tasksDsl.tasks.collect { task ->
            log.debug "Tasks.parallel(): starting task ${task.id}"
            Promise<?> nullPromise = ctx.promiseFactory.createPromise()
            nullPromise.accept(null)
            task.execute(nullPromise)
        }
    }

    /**
     * Execute tasks with a shared context and return the context.
     * Useful when tasks need to share state via context globals.
     *
     * <pre>
     * def ctx = Tasks.withContext { ctx ->
     *     task("t1") { ctx.globals.data = "shared"; "A" }
     *     task("t2") { ctx.globals.data + "B" }
     * }
     * println ctx.globals.data // "shared"
     * </pre>
     *
     * @param dsl closure defining tasks
     * @return the shared TaskContext
     */
    static TaskContext withContext(@DelegatesTo(TasksDsl) Closure dsl) {
        TaskContext ctx = new TaskContext()
        TasksDsl tasksDsl = new TasksDsl(ctx)

        dsl.delegate = tasksDsl
        dsl.resolveStrategy = Closure.DELEGATE_FIRST
        dsl.call(ctx)

        // Execute all tasks
        List<Promise<?>> promises = tasksDsl.tasks.collect { task ->
            log.debug "Tasks.withContext(): executing task ${task.id}"
            Promise<?> nullPromise = ctx.promiseFactory.createPromise()
            nullPromise.accept(null)
            task.execute(nullPromise)
        }

        // Wait for all to complete
        Promises.all(promises).get()

        return ctx
    }
}

/**
 * DSL for defining tasks within Tasks utility methods.
 * Provides a simple task() method for creating ServiceTasks.
 */
@Slf4j
class TasksDsl {
    final TaskContext ctx
    final List<ITask> tasks = []
    private int taskCounter = 0

    TasksDsl(TaskContext ctx) {
        this.ctx = ctx
    }

    /**
     * Define a task with auto-generated ID.
     *
     * <pre>
     * task { "result" }
     * </pre>
     */
    ITask task(@DelegatesTo(ServiceTask) Closure action) {
        return task("task_${++taskCounter}", action)
    }

    /**
     * Define a named task.
     *
     * <pre>
     * task("myTask") { prev -> prev + "!" }
     * </pre>
     */
    ITask task(String id, @DelegatesTo(ServiceTask) Closure action) {
        ServiceTask task = TaskFactory.createServiceTask(id, id, ctx)

        // Configure the task's action
        task.action { taskCtx, prevValue ->
            // Execute the user's closure
            action.delegate = task
            action.resolveStrategy = Closure.DELEGATE_FIRST

            def result
            if (action.maximumNumberOfParameters == 0) {
                result = action.call()
            } else if (action.maximumNumberOfParameters == 1) {
                result = action.call(prevValue)
            } else {
                result = action.call(taskCtx, prevValue)
            }

            // If result is already a promise, return it; otherwise wrap it
            if (result instanceof Promise) {
                return result
            } else {
                // Wrap non-promise results in a resolved promise
                Promise wrapped = taskCtx.promiseFactory.createPromise()
                wrapped.accept(result)
                return wrapped
            }
        }

        tasks << task
        log.debug "TasksDsl: defined task ${task.id}"
        return task
    }

    /**
     * Define a task with explicit type.
     *
     * <pre>
     * task("myTask", TaskType.SERVICE) { "result" }
     * </pre>
     */
    ITask task(String id, TaskType type, @DelegatesTo(ITask) Closure config) {
        ITask task = TaskFactory.createTask(type, id, id, ctx)

        config.delegate = task
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.call()

        tasks << task
        log.debug "TasksDsl: defined task ${task.id} of type ${type}"
        return task
    }
}