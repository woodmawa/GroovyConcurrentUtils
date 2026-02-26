package org.softwood.dag.task

import org.softwood.promise.Promise

/**
 * User-facing contract for executable tasks in the DAG.
 *
 * <p>This interface contains only the methods and properties that are
 * meaningful to users building and querying task graphs. Graph-internal
 * plumbing (wiring successors/predecessors, completion promise management,
 * injected inputs) is in {@link IGraphNode}.</p>
 *
 * <p><b>L15 note:</b> The type parameter {@code <T>} represents the task's result type.
 * Currently most usage sites use the raw {@code ITask} type. Future work should
 * parameterise call sites ({@code ITask<String>}, {@code ITask<MyResult>} etc)
 * for compile-time type safety on {@link #execute} return values.</p>
 */
interface ITask<T> {

    // --- Identity ---
    String getId()
    String getName()

    // --- State (read-only) ---
    TaskState getState()
    boolean getHasStarted()
    boolean isCompleted()
    boolean isSkipped()
    boolean isFailed()
    boolean isCancelled()
    Throwable getError()

    // --- Topology (user-facing: declare what this task depends on) ---
    Set<String> getPredecessors()
    Set<String> getSuccessors()

    /**
     * Declare that this task depends on the task with the given ID.
     * This adds taskId as a predecessor of this task.
     */
    void dependsOn(String taskId)

    // --- Execution configuration ---
    Integer getMaxRetries()
    void setMaxRetries(Integer v)
    Long getTimeoutMillis()
    void setTimeoutMillis(Long v)

    // --- State transitions (called by graph infrastructure) ---
    void markScheduled()
    void markCompleted()
    void markFailed(Throwable error)
    void markSkipped()
    void markCancelled()

    // --- Execution (called by graph) ---
    Promise<?> execute(Promise<?> previousPromise)

    // --- Event dispatch ---
    TaskEventDispatch getEventDispatcher()
    void setEventDispatcher(TaskEventDispatch dispatcher)

    // --- Context (L16 fix: typed as TaskContext, not def) ---
    TaskContext getCtx()
    void setCtx(TaskContext ctx)
}
