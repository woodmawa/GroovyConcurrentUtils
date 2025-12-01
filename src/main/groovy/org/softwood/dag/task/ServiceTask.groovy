package org.softwood.dag.task


import groovy.util.logging.Slf4j
import org.softwood.promise.Promise
import org.softwood.promise.Promises

import java.util.function.BiFunction

@Slf4j
class ServiceTask<T> extends Task<T> {

    // (ctx, previousPromiseOpt) -> Promise<T>
    private BiFunction<TaskContext, Optional<Promise<?>>, Promise<T>> action

    ServiceTask(String id, String name = null) {
        super(id, name)
    }

    /**
     * Configure the task's work as a closure.
     *
     * The closure may return:
     *   - a Promise<T>
     *   - a plain value T
     *   - a DataflowVariable, etc.
     * It will always be normalized to Promise<T> via Promises.ensurePromise.
     */
    ServiceTask<T> action(Closure closure) {
        this.action = { TaskContext ctx, Optional<Promise<?>> prev ->
            def result = closure.call(ctx, prev)
            // Normalize into a Promise<T> safely
            (Promise<T>) Promises.ensurePromise(result)
        } as BiFunction<TaskContext, Optional<Promise<?>>, Promise<T>>

        return this
    }

    /**
     * REQUIRED implementation (matches abstract Task.runTask)
     */
    @Override
    Promise<T> runTask(TaskContext ctx, Optional<Promise<?>> prevOpt) {
        if (!action) {
            throw new IllegalStateException("No action configured for ServiceTask '${id}'")
        }
        return action.apply(ctx, prevOpt)
    }
}
