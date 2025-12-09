package org.softwood.dag.task

import groovy.util.logging.Slf4j
import org.softwood.promise.Promise

/**
 * ServiceTask executes a user-defined action closure.
 * The action receives the task context and the previous task's output value.
 */
@Slf4j
class ServiceTask extends TaskBase<Object> {

    Closure serviceAction

    ServiceTask(String id, String name, TaskContext ctx) {
        super(id, name, ctx)
    }

    /**
     * DSL method to set the task's action.
     */
    void action(Closure action) {
        this.serviceAction = action
    }

    @Override
    protected Promise<Object> runTask(TaskContext ctx, Object prevValue) {

        log.debug "Task ${id}: calling runTask() with prevValue"

        if (!serviceAction) {
            throw new IllegalStateException("ServiceTask ${id}: no action defined")
        }

        return ctx.promiseFactory.executeAsync {

            // prevOpt already contains the unwrapped VALUE, not a Promise
            Object prevVal = prevValue

            println "ServiceTask.runTask: about to call serviceAction for task $id"
            println "ServiceTask.runTask: about to call serviceAction for task $id"
            println "ServiceTask.runTask: prevVal = $prevVal"

            // Call the user's action with context and previous value
            Promise result = serviceAction.call(ctx, prevVal)

            println "ServiceTask.runTask: serviceAction returned: $result"

            log.debug "Task ${id}: runTask() returned promise, waiting for result"

            // The action should return a Promise, so we wait for it
            return result.get()
        }
    }
}
