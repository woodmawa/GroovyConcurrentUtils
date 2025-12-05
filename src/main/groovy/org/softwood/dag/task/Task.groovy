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
@ToString(includeNames = true, includeFields = true, excludes = ['ctx', 'eventDispatcher'])
abstract class Task<T> {

    final String id
    final String name
    TaskState state = TaskState.SCHEDULED
    TaskEventDispatch eventDispatcher

    // enable duck typing on context
    def ctx

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
    // Canonical graph wiring
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

    Throwable getError() { lastError }

    // ----------------------------------------------------
    // Subclasses implement the core promise-returning action
    // This receives the UNWRAPPED value (Optional<?>), not the promise
    // ----------------------------------------------------
    protected abstract Promise<T> runTask(TaskContext ctx, Optional<?> prevValue)

    // ----------------------------------------------------
    // Main entrypoint called by TaskGraph
    // Receives Optional<Promise<?>> and unwraps it for runTask
    // ----------------------------------------------------
    Promise execute(Optional<Promise<?>> previousPromiseOpt) {

        state = TaskState.RUNNING
        emitEvent(TaskState.RUNNING)

        log.debug "Task ${id}: execute() called with prevPromise present: ${previousPromiseOpt.isPresent()}"

        Promise<T> attemptPromise = runAttempt(previousPromiseOpt)

        log.debug "Task ${id}: execute() got attemptPromise"

        return attemptPromise.then { T result ->
            log.debug "Task ${id}: completed with result: $result"
            state = TaskState.COMPLETED
            emitEvent(TaskState.COMPLETED)
            result
        }.recover { Throwable err ->
            log.error "Task ${id}: failed with error: ${err.message}"
            lastError = err
            state = TaskState.FAILED
            emitErrorEvent(err)
            throw err
        }
    }

    // ----------------------------------------------------
    // Retry + timeout wrapper
    // Unwraps Optional<Promise<?>> to Optional<?> for runTask
    // ----------------------------------------------------
    private Promise<T> runAttempt(Optional<Promise<?>> prevPromiseOpt) {

        log.debug "Task ${id}: runAttempt() starting"
        def factory = ctx.promiseFactory

        return factory.executeAsync {

            int attempt = 1
            long delay = retryPolicy.initialDelay.toMillis()

            while (true) {
                try {
                    state = TaskState.RUNNING
                    emitEvent(TaskState.RUNNING)

                    // Unwrap the promise to get the actual value
                    Optional<?> prevValueOpt = Optional.empty()
                    if (prevPromiseOpt.isPresent()) {
                        Promise<?> prevPromise = prevPromiseOpt.get()

                        log.debug "Task ${id}: unwrapping predecessor promise"
                        Object prevValue = prevPromise.get()  // Block and get the value

                        log.debug "Task ${id}: raw unwrapped value: $prevValue (${prevValue?.getClass()?.simpleName})"

                        // If the value is itself a Promise, unwrap it again (this can happen with nested promises)
                        while (prevValue instanceof Promise) {
                            log.debug "Task ${id}: value is still a Promise, unwrapping again"
                            prevValue = ((Promise) prevValue).get()
                        }

                        // Handle List results from multiple predecessors
                        if (prevValue instanceof List && ((List) prevValue).size() == 1) {
                            prevValue = ((List) prevValue)[0]
                            log.debug "Task ${id}: unwrapped single-element list to: $prevValue"
                        }

                        // IMPORTANT: don't call Optional.of(null)
                        if (prevValue != null) {
                            prevValueOpt = Optional.of(prevValue)
                        } else {
                            prevValueOpt = Optional.empty()
                        }

                        log.debug "Task ${id}: final unwrapped prevValue: $prevValue"
                    }

                    log.debug "Task ${id}: calling runTask() with prevValue"
                    Promise<T> promise = runTask(ctx, prevValueOpt)

                    log.debug "Task ${id}: runTask() returned promise, waiting for result"
                    T result = (taskTimeoutMillis != null)
                            ? promise.get(taskTimeoutMillis, TimeUnit.MILLISECONDS)
                            : promise.get()

                    log.debug "Task ${id}: got result: $result"

                    state = TaskState.COMPLETED
                    emitEvent(TaskState.COMPLETED)
                    return result

                } catch (Throwable err) {
                    lastError = err
                    log.error "Task ${id}: attempt $attempt failed: ${err.message}"

                    if (err instanceof TimeoutException) {
                        state = TaskState.FAILED
                        emitErrorEvent(err)
                        throw err
                    }

                    if (attempt >= retryPolicy.maxAttempts) {
                        state = TaskState.FAILED
                        def exceeded = new RuntimeException("Task ${id}: exceeded retry attempts", err)
                        emitErrorEvent(exceeded)
                        throw exceeded
                    }

                    log.debug "Task ${id}: retrying after ${delay}ms (attempt $attempt)"
                    Thread.sleep(delay)
                    delay = (long) (delay * retryPolicy.backoffMultiplier)
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
        if (eventDispatcher) {
            eventDispatcher.emit(new TaskEvent(id, newState))
        }
    }

    private void emitErrorEvent(Throwable err) {
        state = TaskState.FAILED
        if (eventDispatcher) {
            eventDispatcher.emit(new TaskEvent(id, TaskState.FAILED, err))
        }
    }
}
