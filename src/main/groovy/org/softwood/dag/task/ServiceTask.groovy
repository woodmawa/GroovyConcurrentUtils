package org.softwood.dag.task

import groovy.util.logging.Slf4j
import org.softwood.promise.Promise

/**
 * ServiceTask executes a user-defined action closure.
 * The action receives the task context and the previous task's output value.
 */
@Slf4j
class ServiceTask extends TaskBase<Object> {

    Closure action

    ServiceTask(String id, String name, TaskContext ctx) {
        super(id, name, ctx)
    }

    /**
     * DSL method to set the task's action.
     */
    void action(Closure action) {
        this.action = action
    }

    @Override
    protected Promise<Object> runTask(TaskContext ctx, Object prevValue) {

        log.debug "Task ${id}: calling runTask() with prevValue"

        if (!action) {
            throw new IllegalStateException("ServiceTask ${id}: no action defined")
        }

        return ctx.promiseFactory.executeAsync {

            // prevOpt already contains the unwrapped VALUE, not a Promise
            Object prevVal = prevValue

            println "ServiceTask.runTask: about to call serviceAction for task $id"
            println "ServiceTask.runTask: about to call serviceAction for task $id"
            println "ServiceTask.runTask: prevVal = $prevVal"

            // Call the user's action with context and previous value
            Promise result = action.call(ctx, prevVal)

            println "ServiceTask.runTask: serviceAction returned: $result"

            log.debug "Task ${id}: runTask() returned promise, waiting for result"

            // The action should return a Promise, so we wait for it
            // Handle DataflowPromise race condition where error state is set before error value
            try {
                return result.get()
            } catch (java.lang.IllegalStateException raceErr) {
                // DataflowPromise race condition: error state detected but error value not yet set
                if (raceErr.message?.contains("Error state detected but error value is null")) {
                    Thread.sleep(10)  // Brief delay to let error propagate
                    return result.get()  // Retry to get actual error
                } else {
                    throw raceErr
                }
            }
        }
    }
}
