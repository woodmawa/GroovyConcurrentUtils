package org.softwood.dag.task

import groovy.transform.ToString
import groovy.util.logging.Slf4j
import org.softwood.promise.Promise
import org.softwood.promise.Promises

import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Abstract base class for all DAG tasks.
 *
 * Implements:
 *  - retryPolicy (maxRetries, backoff, delay)
 *  - timeoutMillis
 *  - state handling
 *  - promise-based async execution
 */
@Slf4j
@ToString
abstract class Task<T> {

    final String id
    final String name
    //Closure action
    TaskState state = TaskState.SCHEDULED
    TaskEventDispatch eventDispatcher

    //enable duck typing on context
    def  ctx

    /**
     * Canonical DAG topology:
     * All DSLs and tests use only these.
     */
    final Set<String> predecessors = [] as Set
    final Set<String> successors   = [] as Set

    /** -------------------------------
     * Task runtime behaviour
     */
    protected RetryPolicy retryPolicy = new RetryPolicy()
    private Long taskTimeoutMillis = null
    protected Throwable lastError = null


    // ----------------------------------------------------
    // constructor - expects id and a name and a graph ctx
    // ----------------------------------------------------
    Task(String id, String name, ctx) {
        this.id = id
        this.name = name
        this.ctx = ctx
    }

    // ----------------------------------------------------
    // Canonical graph wiring, methods for Task DSL classes to call
    // ----------------------------------------------------

    void dependsOn(String taskId) {
        predecessors << taskId
    }

    void addSuccessor(String taskId) {
        successors << taskId
    }

     // ----------------------------------------------------
    // Synthetic properties
    // ----------------------------------------------------
    Integer getMaxRetries() { retryPolicy.maxAttempts }
    void setMaxRetries(Integer v) { retryPolicy.maxAttempts = v }

    Long getTimeoutMillis() { taskTimeoutMillis }
    void setTimeoutMillis(Long v) { taskTimeoutMillis = v }

    Throwable getError () {
        lastError
    }

    // ----------------------------------------------------
    // Subclasses implement the core promise-returning action
    // ----------------------------------------------------
    protected abstract Promise<T> runTask(TaskContext ctx, Optional<Promise<?>> prev)

    // ----------------------------------------------------
    // Main entrypoint called by TaskGraph
    // ----------------------------------------------------
    Promise execute(Optional<Object> previousResult) {

        state = TaskState.RUNNING
        emitEvent(TaskState.RUNNING)

        println "Task execute : call runAttempt with prevResult : $previousResult "
        Promise<T> attemptPromise = runAttempt(previousResult as Optional<Promise<?>>)

        println "Task execute just got back attemptPromise $attemptPromise"

        return attemptPromise
                .then { T result ->
                    println "Task.execute then(): received result $result"
                    state = TaskState.COMPLETED
                    emitEvent(TaskState.COMPLETED)
                    result  // <-- Don't use "return", just the expression
                }
                .recover { Throwable err ->
                    println "Task.execute recover(): received error $err"
                    lastError = err
                    state = TaskState.FAILED
                    emitErrorEvent(err)
                    throw err  // Re-throw to keep the promise failed
                }
    }

    // ----------------------------------------------------
    // Retry + timeout wrapper
    // ----------------------------------------------------
    private Promise<T> runAttempt(Optional<Promise<?>> prevOpt) {

        println "Task starting runAttempt and calling promises.async() with local closure "

        //use ctx.pools threads to run the closure.  handles for injected mocks etc
        def factory = ctx.promiseFactory
        if (!factory) {
            throw new IllegalStateException("TaskContext.promiseFactory must not be null")
        }

        println "----> Task starting runAttempt via ctx.promiseFactory.executeAsync()"

        return factory.executeAsync {

            int attempt = 1
            long delay  = retryPolicy.initialDelay.toMillis()

            while (true) {
                try {
                    state = TaskState.RUNNING
                    emitEvent(TaskState.RUNNING)

                    println "Task runAttempt: run task $this with runTask calling concrete implementation in serviceTask!"
                    Promise<T> promise = runTask(ctx, prevOpt)

                    println "Task runAttempt: run task returned promise $promise "
                    T result
                    // Let Promises.timeout throw TimeoutException naturally.
                    if (taskTimeoutMillis != null) {
                        result = promise.get(taskTimeoutMillis, TimeUnit.MILLISECONDS)
                        println "Task runAttempt: prmoise.get(timeout ) got result from promise: $result"  // ADD THIS
                    } else {
                        // If this throws, catch block handles it.
                        result = promise.get()
                        println "Task runAttempt: prmoise.get() got result from promise: $result"  // ADD THIS

                    }

                    // SUCCESS
                    state = TaskState.COMPLETED
                    emitEvent(TaskState.COMPLETED)
                    return result

                } catch (Throwable err) {

                    lastError = err

                    // TIMEOUT — fail immediately, no retry
                    if (err instanceof TimeoutException) {
                        state = TaskState.FAILED
                        emitErrorEvent(err)
                        throw err          // <-- important! propagate failure
                    }

                    // NO MORE RETRIES
                    if (attempt >= retryPolicy.maxAttempts) {
                        state = TaskState.FAILED
                        //todo  maybe throw a new runtime error with max attempts exceeded ?
                        def exceededAttemptsErr = new RuntimeException("exceeded retry attempts ", lastError)
                        emitErrorEvent(exceededAttemptsErr)
                        throw exceededAttemptsErr
                    }

                    log.warn("Task $id failed on attempt $attempt: ${err.message} → retrying")

                    Thread.sleep(delay)
                    delay = (long)(delay * retryPolicy.backoffMultiplier)
                    attempt++
                }
            }
        }
    }

    // ----------------------------------------------------
    // Task Event helpers
    // ----------------------------------------------------

    private void emitEvent(TaskState newState) {
        state = newState
        eventDispatcher.emit (new TaskEvent(id, newState))
    }

    private void emitErrorEvent(Throwable err) {
        state = TaskState.FAILED
        eventDispatcher.emit  (new TaskEvent(id, TaskState.FAILED, err))
    }
}