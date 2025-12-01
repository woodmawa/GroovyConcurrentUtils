package org.softwood.dag.task

import groovy.util.logging.Slf4j
import org.softwood.promise.Promise
import org.softwood.promise.Promises

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
abstract class Task<T> {

    final String id

    /** predecessor → successor wiring */
    final List<String> predecessors = []
    final List<String> successors   = []

    /** Current state */
    TaskState state = TaskState.PENDING

    /** Retry support */
    final RetryPolicy retryPolicy = new RetryPolicy()

    /** Timeout support (null → no timeout) */
    Long timeoutMillis = null
    void setTimeoutMillis(Number n) { timeoutMillis = n?.longValue() }

    /** Execution hook implemented in subclasses */
    abstract Promise<T> runTask(TaskContext ctx, Optional<Promise<?>> prevOpt)

    Task(String id) {
        this.id = id
    }

    // -----------------------------------------------------------
    // Retry + backoff setters (DSL-friendly)
    // -----------------------------------------------------------

    Task<T> maxRetries(int n)               { retryPolicy.maxAttempts = n; return this }
    void setMaxRetries(Number n)            { retryPolicy.maxAttempts = n?.intValue() }

    Task<T> retryDelay(long millis)         { retryPolicy.initialDelayMillis = millis; return this }
    void setRetryDelay(Number n)            { retryPolicy.initialDelayMillis = n?.longValue() }

    Task<T> retryBackoff(double factor)     { retryPolicy.backoffMultiplier = factor; return this }
    void setRetryBackoff(Number n)          { retryPolicy.backoffMultiplier = n?.doubleValue() }

    // -----------------------------------------------------------
    // Execution entry point
    // -----------------------------------------------------------

    Optional<Promise<?>> execute(TaskContext ctx,
                                 Optional<Promise<?>> prevOpt,
                                 Closure emitEvent) {

        this.state = TaskState.SCHEDULED
        emitEvent(new TaskEvent(id, state))

        // If predecessors failed → skip
        boolean anyFailed = predecessors.any { pid ->
            ctx.graph.tasks[pid].state == TaskState.FAILED
        }
        if (anyFailed) {
            this.state = TaskState.SKIPPED
            emitEvent(new TaskEvent(id, state))
            return Optional.empty()
        }

        // Submit async execution
        Promise<T> p = runAttempt(ctx, prevOpt, emitEvent)

        return Optional.of(p)
    }

    // -----------------------------------------------------------
    // Internal run + retry loop
    // -----------------------------------------------------------

    Promise<T> runAttempt(TaskContext ctx,
                          Optional<Promise<?>> prevOpt,
                          Closure emitEvent) {

        return Promises.future(ctx.pool.executor, {
            int attempt = 1
            long delay  = retryPolicy.initialDelayMillis

            while (true) {
                try {
                    this.state = TaskState.RUNNING
                    emitEvent(new TaskEvent(id, state))

                    // Run user action (implemented in subclass)
                    Promise<T> result = runTask(ctx, prevOpt)

                    // Apply timeout if configured
                    if (timeoutMillis != null) {
                        result = result.orTimeout(timeoutMillis)
                    }

                    // Success path
                    this.state = TaskState.COMPLETED
                    emitEvent(new TaskEvent(id, state))
                    return result.get()

                } catch (Throwable err) {

                    // Out of retries? → fail task
                    if (attempt >= retryPolicy.maxAttempts) {
                        this.state = TaskState.FAILED
                        emitEvent(new TaskEvent(id, state, err))
                        throw err
                    }

                    // Retry allowed → wait + retry
                    log.warn("Task ${id} failed on attempt ${attempt}: ${err.message} → retrying")

                    Thread.sleep(delay)
                    delay = (long)(delay * retryPolicy.backoffMultiplier)
                    attempt++
                }
            }
        })
    }

}
