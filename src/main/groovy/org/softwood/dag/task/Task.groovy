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

    Promise<?> completionPromise = null
    private Optional<?> injectedInput = Optional.empty()

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

    boolean getHasStarted() { state != TaskState.SCHEDULED }

    boolean isCompleted() { state == TaskState.COMPLETED }
    boolean isSkipped() { state == TaskState.SKIPPED }
    boolean isFailed() { state == TaskState.FAILED }

    void markScheduled() {
        state = TaskState.SCHEDULED
        emitEvent(TaskState.SCHEDULED)
    }

    void markCompleted() {
        state = TaskState.COMPLETED
        emitEvent(TaskState.COMPLETED)
    }

    void markFailed(Throwable error) {
        lastError = error
        state = TaskState.FAILED
        emitErrorEvent(error)
    }

    void markSkipped() {
        state = TaskState.SKIPPED
        emitEvent(TaskState.SKIPPED)
    }

    void setInjectedInput(Object data) {
        this.injectedInput = Optional.ofNullable(data)
    }


    // ------------
    // helper method
    // -------------
    Promise<?> buildPrevPromise(Map<String, Task> tasks) {
        if (injectedInput.isPresent()) {
            return ctx.promiseFactory.createPromise(injectedInput.get())
        }

        if (predecessors.isEmpty()) {
            return ctx.promiseFactory.createPromise (null)
        }

        if (predecessors.size() == 1) {
            String predId = predecessors.first()
            Task pred = tasks[predId]
            return pred?.completionPromise ?: ctx.promiseFactory.createPromise(null)
        }

        List<Promise<?>> predPromises = predecessors.collect {
            tasks[it]?.completionPromise
        }.findAll { it != null }

        return Promise.allOf(predPromises)
    }

    // ----------------------------------------------------
    // Subclasses implement the core promise-returning action
    // This receives the UNWRAPPED value (Optional<?>), not the promise
    // ----------------------------------------------------
    protected abstract Promise<T> runTask(TaskContext ctx, Object prevValue)

    // ----------------------------------------------------
    // Main entrypoint called by TaskGraph
    // Receives Optional<Promise<?>> and unwraps it for runTask
    // ----------------------------------------------------
    Promise execute(Promise<?> previousPromise) {

        state = TaskState.RUNNING
        emitEvent(TaskState.RUNNING)

        log.debug "Task ${id}: execute() called with prevPromise present: $previousPromise"

        Promise<T> attemptPromise = runAttempt(previousPromise)
        completionPromise = attemptPromise

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
    private Promise<T> runAttempt(Promise<?> previousPromise) {

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
                    Object prevValue = null
                    if (previousPromise != null) {
                        log.debug "Task ${id}: unwrapping predecessor promise"
                        prevValue = previousPromise.get()  // Block and get the value

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

                        log.debug "Task ${id}: final unwrapped prevValue: $prevValue"
                    }

                    log.debug "Task ${id}: calling runTask() with prevValue"
                    Promise<T> promise = runTask(ctx, prevValue)  // Pass raw value (may be null)

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
