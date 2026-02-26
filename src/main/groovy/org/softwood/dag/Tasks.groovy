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
 * <p><strong>Thread resource management:</strong> Each call to all(), any(), sequence(),
 * and parallel() creates a TaskContext (with its own ExecutorPool) that is closed
 * automatically when the call completes. For withContext(), the caller receives the
 * context and is responsible for calling ctx.close() when done.</p>
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
     * Pool is automatically closed after completion (FIX C4/M14).
     */
    static <R> List<R> all(@DelegatesTo(TasksDsl) Closure dsl) {
        TaskContext ctx = new TaskContext()
        try {
            TasksDsl tasksDsl = new TasksDsl(ctx)
            dsl.delegate = tasksDsl
            dsl.resolveStrategy = Closure.DELEGATE_FIRST
            dsl.call(ctx)

            List<Promise<?>> promises = []
            for (ITask task : tasksDsl.tasks) {
                log.debug "Tasks.all(): executing task ${task.id}"
                Promise<?> nullPromise = ctx.promiseFactory.createPromise()
                nullPromise.accept(null)
                promises.add(task.execute(nullPromise))
            }

            log.debug "Tasks.all(): waiting for ${promises.size()} tasks to complete"
            List<R> results = []
            for (Promise<?> promise : promises) {
                results.add(promise.get() as R)
            }
            log.debug "Tasks.all(): all tasks completed, returning ${results.size()} results"
            return results
        } finally {
            ctx.close()
        }
    }

    /**
     * Execute multiple tasks and return the FIRST to complete.
     * Other tasks will continue executing but their results are ignored.
     * Pool is automatically closed after the first result is obtained (FIX C4/M14).
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
    static <S> S any(@DelegatesTo(TasksDsl) Closure dsl) {
        TaskContext ctx = new TaskContext()
        try {
            TasksDsl tasksDsl = new TasksDsl(ctx)
            dsl.delegate = tasksDsl
            dsl.resolveStrategy = Closure.DELEGATE_FIRST
            dsl.call(ctx)

            List<Promise<?>> promises = []
            for (ITask task : tasksDsl.tasks) {
                log.debug "Tasks.any(): executing task ${task.id}"
                Promise<?> nullPromise = ctx.promiseFactory.createPromise()
                nullPromise.accept(null)
                promises.add(task.execute(nullPromise))
            }

            log.debug "Tasks.any(): racing ${promises.size()} tasks"
            Promise<?> anyPromise = Promises.any(promises)
            return anyPromise.get() as S
        } finally {
            ctx.close()
        }
    }

    /**
     * Execute tasks sequentially, passing result of previous to next.
     * Creates a pipeline where each task receives the previous task's result.
     * Pool is automatically closed after completion (FIX C4/M14).
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
    static <U> U sequence(@DelegatesTo(TasksDsl) Closure dsl) {
        TaskContext ctx = new TaskContext()
        try {
            TasksDsl tasksDsl = new TasksDsl(ctx)
            dsl.delegate = tasksDsl
            dsl.resolveStrategy = Closure.DELEGATE_FIRST
            dsl.call(ctx)

            Promise<?> prevPromise = ctx.promiseFactory.createPromise()
            prevPromise.accept(null)

            for (ITask task : tasksDsl.tasks) {
                log.debug "Tasks.sequence(): executing task ${task.id}"
                prevPromise = task.execute(prevPromise)
            }

            log.debug "Tasks.sequence(): waiting for final result"
            return prevPromise.get() as U
        } finally {
            ctx.close()
        }
    }

    // =========================================================================
    // Java Lambda Style - Single Task Execution
    // =========================================================================

    /**
     * Execute a single task with Java lambda/Function style.
     * Pool is automatically closed after completion (FIX C4/M14).
     *
     * <pre>
     * def result = Tasks.execute(task -> {
     *     task.action { ctx, prev -> "Hello" }
     *     return task
     * })
     * </pre>
     *
     * @param configurator function to configure the task
     * @return task execution result
     */
    static <V> V execute(Function<ServiceTask, ServiceTask> configurator) {
        TaskContext ctx = new TaskContext()
        try {
            ServiceTask task = TaskFactory.createServiceTask(
                    "task_${UUID.randomUUID()}",
                    "anonymous",
                    ctx
            )
            configurator.apply(task)

            log.debug "Tasks.execute(): executing configured task"
            Promise<?> nullPromise = ctx.promiseFactory.createPromise()
            nullPromise.accept(null)
            return task.execute(nullPromise).get() as V
        } finally {
            ctx.close()
        }
    }

    /**
     * Execute a single task with Groovy closure style.
     * Pool is automatically closed after completion (FIX C4/M14).
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
    static <W> W execute(@DelegatesTo(ServiceTask) Closure configurator) {
        TaskContext ctx = new TaskContext()
        try {
            ServiceTask task = TaskFactory.createServiceTask(
                    "task_${UUID.randomUUID()}",
                    "anonymous",
                    ctx
            )

            def wrapper = [
                    action: { Closure userAction ->
                        task.action { TaskContext taskCtx, Object prevValue ->
                            Object result = userAction.call(taskCtx, prevValue)
                            if (result instanceof Promise) {
                                return result
                            } else {
                                Promise wrapped = taskCtx.promiseFactory.createPromise()
                                wrapped.accept(result)
                                return wrapped
                            }
                        }
                    }
            ]

            configurator.delegate = wrapper
            configurator.resolveStrategy = Closure.DELEGATE_FIRST
            configurator.call(wrapper)

            log.debug "Tasks.execute(): executing configured task"
            Promise<?> nullPromise = ctx.promiseFactory.createPromise()
            nullPromise.accept(null)
            return task.execute(nullPromise).get() as W
        } finally {
            ctx.close()
        }
    }

    // =========================================================================
    // Utility Methods
    // =========================================================================

    /**
     * Execute multiple independent tasks in parallel.
     * Unlike all(), doesn't wait - returns promises immediately.
     *
     * <p><strong>Note:</strong> Because this returns live promises, the context
     * is NOT closed automatically. Caller should close it after consuming all promises:
     * <pre>
     * def (promises, ctx) = Tasks.parallelWithContext { ctx ->
     *     task("t1") { longRunningWork1() }
     *     task("t2") { longRunningWork2() }
     * }
     * // Do other work...
     * def results = promises.collect { it.get() }
     * ctx.close()
     * </pre>
     *
     * @param dsl closure defining tasks
     * @return list of promises for each task (context NOT auto-closed)
     */
    static List<Promise<?>> parallel(@DelegatesTo(TasksDsl) Closure dsl) {
        // NOTE: context deliberately not closed here since promises are still pending
        // Caller must manage lifecycle. For auto-close, use all() instead.
        TaskContext ctx = new TaskContext()
        TasksDsl tasksDsl = new TasksDsl(ctx)
        dsl.delegate = tasksDsl
        dsl.resolveStrategy = Closure.DELEGATE_FIRST
        dsl.call(ctx)

        List<Promise<?>> promises = []
        for (ITask task : tasksDsl.tasks) {
            log.debug "Tasks.parallel(): starting task ${task.id}"
            Promise<?> nullPromise = ctx.promiseFactory.createPromise()
            nullPromise.accept(null)
            promises.add(task.execute(nullPromise))
        }

        return promises
    }

    /**
     * Execute tasks with a shared context and return the context.
     * Useful when tasks need to share state via context globals.
     *
     * <p><strong>Caller is responsible for closing the returned context</strong>
     * to release pool threads.</p>
     *
     * <pre>
     * def ctx = Tasks.withContext { ctx ->
     *     task("t1") { ctx.globals.data = "shared"; "A" }
     *     task("t2") { ctx.globals.data + "B" }
     * }
     * try {
     *     println ctx.globals.data // "shared"
     * } finally {
     *     ctx.close()
     * }
     * </pre>
     *
     * @param dsl closure defining tasks
     * @return the shared TaskContext (caller must call close() when done)
     */
    static TaskContext withContext(@DelegatesTo(TasksDsl) Closure dsl) {
        // NOTE: context is returned to caller who must close() it
        TaskContext ctx = new TaskContext()
        TasksDsl tasksDsl = new TasksDsl(ctx)
        dsl.delegate = tasksDsl
        dsl.resolveStrategy = Closure.DELEGATE_FIRST
        dsl.call(ctx)

        List<Promise<?>> promises = []
        for (ITask task : tasksDsl.tasks) {
            log.debug "Tasks.withContext(): executing task ${task.id}"
            Promise<?> nullPromise = ctx.promiseFactory.createPromise()
            nullPromise.accept(null)
            promises.add(task.execute(nullPromise))
        }

        log.debug "Tasks.withContext(): waiting for ${promises.size()} tasks individually"
        for (Promise<?> promise : promises) {
            promise.get()
        }
        log.debug "Tasks.withContext(): all tasks completed"

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

        task.action { TaskContext taskCtx, Object prevValue ->
            action.delegate = task
            action.resolveStrategy = Closure.DELEGATE_FIRST

            Object result
            if (action.maximumNumberOfParameters == 0) {
                result = action.call()
            } else if (action.maximumNumberOfParameters == 1) {
                result = action.call(prevValue)
            } else {
                result = action.call(taskCtx, prevValue)
            }

            if (result instanceof Promise) {
                return result
            } else {
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
