package org.softwood.dag.task


import groovy.util.logging.Slf4j
import org.softwood.promise.Promise
import org.softwood.promise.Promises

import java.util.function.BiFunction

@Slf4j
class ServiceTask<T> extends Task<T> {

    private Closure serviceAction

    ServiceTask(String id, String name, TaskContext ctx) {
        super(id, name, ctx)
    }

    /**
     * DSL: action { ctx, prev -> result }
     */
    void action(@DelegatesTo(ServiceTask) Closure c) {
        serviceAction = c.clone() as Closure

        //ensure serviceAction closure resoles to this serviceTask instance first
        serviceAction.delegate = this
        serviceAction.resolveStrategy = Closure.DELEGATE_FIRST
    }

    @Override
    protected Promise<T> runTask(TaskContext ctx, Optional<Promise<?>> prevOpt) {

        if (serviceAction == null)
            throw new IllegalStateException("ServiceTask '$id' requires an action")

        return Promises.async {
            //def prevVal = prevOpt.isPresent() ? prevOpt.get().get() : null
            //return (T) serviceAction.call(ctx, prevVal)
            println "ServiceTask.runTask: about to call serviceAction for task $id"
            def prevVal = prevOpt.isPresent() ? prevOpt.get().get() : null

            /*orig ---
            println "ServiceTask.runTask: prevVal = $prevVal"
            def result = serviceAction.call(ctx, prevVal)
            println "ServiceTask.runTask: serviceAction returned: $result"
            return (T) result
            */

            // --- FIX START ---
            // We need to run the service action in a separate async block and wait for it.
            // This ensures that the promise returned by runTask is backed by an
            // execution thread that can be timed out externally by Task.runAttempt.
            // The task action's sleep will now be running on a separate thread,
            // and the get() call here will block the main task thread until it either
            // completes or the external Task.runAttempt timeout hits.

            // The inner promise runs the long-running action
            return Promises.async {
                println "ServiceTask.runTask: about to call serviceAction for task $id"
                println "ServiceTask.runTask: prevVal = $prevVal"
                def result = serviceAction.call(ctx, prevVal)
                println "ServiceTask.runTask: serviceAction returned: $result"
                return (T) result
            }.get() // Block the outer promise's thread until the inner action completes/fails

            // --- FIX END ---
        }
    }
}
