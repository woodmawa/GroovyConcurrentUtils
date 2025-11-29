package org.softwood.dag.task

import groovy.util.logging.Slf4j

import org.softwood.promise.Promise

import java.util.function.BiFunction

@Slf4j
class ServiceTask<T> extends Task<T> {

    // (ctx, previousPromise) -> Promise<T>
    private BiFunction<TaskContext, Optional<Promise<?>>, Promise<T>> action

    ServiceTask(String id, String name = null) {
        super(id, name)
    }

    ServiceTask<T> action(Closure closure) {
        // closure: { ctx, prevOpt -> Promise<T> }
        this.action = { TaskContext ctx, Optional prev ->
            closure.call(ctx, prev)
        } as BiFunction<TaskContext, Optional<Promise<?>>, Promise<T>>
        return this
    }

    @Override
    protected Promise<T> doRun(TaskContext ctx, Optional<Promise<?>> previous) {
        if (!action) {
            throw new IllegalStateException("No action configured for ServiceTask ${id}")
        }
        return action.apply(ctx, previous)
    }
}