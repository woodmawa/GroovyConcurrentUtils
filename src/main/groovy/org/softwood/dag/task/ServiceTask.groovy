package org.softwood.dag.task

import groovy.util.logging.Slf4j
import org.softwood.promise.Promise
import org.softwood.promise.Promises

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

        // Wrap the action in executeAsync so that:
        // 1. The action runs on its own thread (enabling timeouts in executeTaskWithRetry)
        // 2. Long-running action closures (e.g. with Thread.sleep) don't block runAttempt's thread
        //
        // The action closure typically returns a Promise (from executeAsync). We unwrap it
        // here via result.get() so that the OUTER promise (returned from this method)
        // represents the final resolved value. This is a single executeAsync — NOT double-wrapping.
        return ctx.promiseFactory.executeAsync {

            Object prevVal = prevValue

            log.debug "ServiceTask ${id}: calling action closure with prevVal=$prevVal"

            // Smart closure calling based on parameter count
            def result = callActionClosure(action, ctx, prevVal)

            log.debug "ServiceTask ${id}: action returned: $result"

            // If the action returned a Promise, unwrap it to get the actual value
            if (result instanceof Promise) {
                try {
                    return ((Promise) result).get()
                } catch (java.lang.IllegalStateException raceErr) {
                    // DataflowPromise race condition: error state detected but error value not yet set
                    if (raceErr.message?.contains("Error state detected but error value is null")) {
                        Thread.sleep(10)
                        return ((Promise) result).get()
                    } else {
                        throw raceErr
                    }
                }
            }

            // Action returned a plain value
            return result
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
    private Object callActionClosure(Closure action, TaskContext ctx, Object prevValue) {
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
