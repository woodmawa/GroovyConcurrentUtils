package org.softwood.dag.task

import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType
import groovy.util.logging.Slf4j
import org.softwood.promise.Promise
import org.softwood.promise.Promises

import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * Base abstract Task that participates in a DAG-based TaskGraph.
 *
 * Enhanced with @DelegatesTo annotations for better IDE support.
 */
@Slf4j
abstract class Task<T> {

    final String id
    String name

    LocalDateTime startTime
    LocalDateTime finishTime
    LocalDateTime scheduleAt

    final Set<String> predecessors = [] as Set
    final Set<String> successors = [] as Set

    TaskState state = TaskState.PENDING
    RetryPolicy retryPolicy = new RetryPolicy()

    protected Task(String id, String name = null) {
        this.id = id
        this.name = name ?: id
    }

    // -------------------------------------------------------------------------
    // DSL SUPPORT (with type hints for IDE)
    // -------------------------------------------------------------------------

    Task<T> maxRetries(int n) {
        retryPolicy.maxAttempts = n
        return this
    }

    Task<T> maxRetries(Number n) {
        return maxRetries(n.intValue())
    }

    Task<T> retryDelay(Duration d) {
        retryPolicy.initialDelay = d
        return this
    }

    Task<T> retryDelay(Number millis) {
        return retryDelay(Duration.ofMillis(millis.longValue()))
    }

    Task<T> backoffMultiplier(double m) {
        retryPolicy.backoffMultiplier = m
        return this
    }

    Task<T> backoffMultiplier(Number n) {
        return backoffMultiplier(n.doubleValue())
    }

    Task<T> circuitOpenFor(Duration d) {
        retryPolicy.circuitOpenDuration = d
        return this
    }

    Task<T> scheduledAt(LocalDateTime time) {
        this.scheduleAt = time
        return this
    }

    Task<T> scheduleAt(LocalDateTime time) {
        return scheduledAt(time)
    }

    Task<T> dependsOn(String... ids) {
        predecessors.addAll(ids)
        return this
    }

    // -------------------------------------------------------------------------
    // ABSTRACT WORK METHOD
    // -------------------------------------------------------------------------

    protected abstract Promise<T> doRun(TaskContext ctx, Optional<Promise<?>> previous)

    // -------------------------------------------------------------------------
    // EXECUTION ENGINE
    // -------------------------------------------------------------------------

    Optional<Promise<T>> execute(TaskContext ctx,
                                 Optional<Promise<?>> previous,
                                 Closure<TaskEvent> eventEmitter) {

        if (retryPolicy.isCircuitOpen()) {
            state = TaskState.CIRCUIT_OPEN
            log.warn("Circuit open for task ${id}, skipping execution")
            eventEmitter.call(new TaskEvent(taskId: id, type: TaskEventType.SKIP))
            return Optional.empty()
        }

        state = TaskState.SCHEDULED

        Promise<T> resultPromise = Promises.newPromise()

        Runnable attempt = null
        attempt = {

            state = TaskState.RUNNING
            startTime = LocalDateTime.now()

            eventEmitter.call(new TaskEvent(taskId: id, type: TaskEventType.START))

            try {
                Promise<T> work = doRun(ctx, previous)

                work.onComplete { T value ->
                    finishTime = LocalDateTime.now()
                    retryPolicy.recordSuccess()
                    state = TaskState.COMPLETED

                    resultPromise.accept(value)
                    eventEmitter.call(new TaskEvent(
                            taskId: id,
                            type: TaskEventType.SUCCESS,
                            result: value
                    ))
                }.onError { Throwable e ->
                    handleFailure(e, ctx, previous, resultPromise, eventEmitter)
                }
            }
            catch (Throwable t) {
                handleFailure(t, ctx, previous, resultPromise, eventEmitter)
            }
        }

        if (scheduleAt != null && scheduleAt.isAfter(LocalDateTime.now())) {
            long delayMillis = Duration.between(LocalDateTime.now(), scheduleAt).toMillis()
            ctx.pool.scheduleExecution(delayMillis as int, TimeUnit.MILLISECONDS, attempt)
        } else {
            ctx.pool.execute(attempt)
        }

        return Optional.of(resultPromise)
    }

    private void handleFailure(Throwable e,
                               TaskContext ctx,
                               Optional<Promise<?>> previous,
                               Promise<T> promise,
                               Closure<TaskEvent> events) {

        retryPolicy.recordFailure()

        if (retryPolicy.attemptCount <= retryPolicy.maxAttempts) {
            def delay = retryPolicy.nextDelay()
            log.warn("Task ${id} failed: ${e.message} – retrying in ${delay.toMillis()}ms " +
                    "(${retryPolicy.attemptCount}/${retryPolicy.maxAttempts})")

            ctx.pool.scheduleExecution(delay.toMillis() as int, TimeUnit.MILLISECONDS, {
                execute(ctx, previous, events)
            })
        }
        else {
            finishTime = LocalDateTime.now()
            state = TaskState.FAILED
            promise.fail(e)

            events.call(new TaskEvent(
                    taskId: id,
                    type: TaskEventType.ERROR,
                    error: e
            ))
        }
    }
}