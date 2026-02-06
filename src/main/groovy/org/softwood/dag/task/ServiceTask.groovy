package org.softwood.dag.task

import groovy.util.logging.Slf4j
import org.softwood.promise.Promise

/**
 * ServiceTask executes a user-defined action closure.
 * The action receives the task context and the previous task's output value.
 * 
 * Smart closure calling:
 * - 0 params: action()
 * - 1 param:  action(prevValue)     // User wants just the result
 * - 2 params: action(ctx, prevValue) // User wants context + result
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

            // Smart closure calling based on parameter count
            Promise result = callActionClosure(action, ctx, prevVal)

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
    
    /**
     * Call the action closure with appropriate parameters based on what it expects.
     * 
     * This provides user-friendly behavior:
     * - { -> ... }           gets nothing (static result)
     * - { result -> ... }    gets previous result only
     * - { ctx, prev -> ... } gets context and previous result
     * 
     * @param action The closure to call
     * @param ctx The task context
     * @param prevValue The previous task's result
     * @return The Promise returned by the closure
     */
    private Promise callActionClosure(Closure action, TaskContext ctx, Object prevValue) {
        int paramCount = action.maximumNumberOfParameters
        
        log.debug "Task ${id}: action closure has ${paramCount} parameter(s)"
        
        switch (paramCount) {
            case 0:
                // No parameters - closure doesn't need context or previous value
                return action.call()
                
            case 1:
                // One parameter - user wants just the previous result
                // (More intuitive than getting the context)
                return action.call(prevValue)
                
            case 2:
            default:
                // Two or more parameters - provide context and previous result
                return action.call(ctx, prevValue)
        }
    }
}
