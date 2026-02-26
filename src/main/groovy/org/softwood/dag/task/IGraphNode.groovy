package org.softwood.dag.task

import org.softwood.promise.Promise

/**
 * Graph-internal contract for task nodes — NOT part of the public API.
 *
 * <p>Contains wiring and lifecycle methods used by {@code TaskGraph} to
 * assemble the execution graph and track completion. End-users should only
 * interact with {@link ITask}; this interface is an internal detail.</p>
 *
 * <p>All implementations ({@code TaskBase} and its subclasses) implement both
 * {@link ITask} and this interface. {@code TaskGraph} casts to {@code IGraphNode}
 * where internal operations are needed.</p>
 */
interface IGraphNode<T> extends ITask<T> {

    // --- Topology wiring (called by TaskGraph during graph construction) ---

    /**
     * Add a successor task by ID. Called by the graph, not by user code.
     */
    void addSuccessor(String taskId)

    /**
     * Add a predecessor task by ID. Called by the graph, not by user code.
     */
    void addPredecessor(String taskId)

    // --- Execution plumbing ---

    /**
     * Build the upstream promise to pass as input to this task's execute().
     * If the task has a single predecessor, returns its completionPromise.
     * If it has multiple predecessors, returns an all-of joined promise.
     */
    Promise<?> buildPrevPromise(Map<String, ? extends ITask> tasks)

    /**
     * Get the promise that completes when this task finishes.
     * Set before execute() is called so successor tasks can wire themselves.
     */
    Promise<?> getCompletionPromise()

    /**
     * Set the completion promise. Called by execute() before runAttempt fires.
     */
    void setCompletionPromise(Promise<?> promise)

    // --- Data injection (used by sharding router) ---

    /**
     * Inject input data directly into this task (bypasses predecessor result).
     */
    void setInjectedInput(Object data)

    // --- Guarded transition ---

    /**
     * Attempt a guarded state transition using CAS semantics.
     * Returns true if the transition succeeded, false if rejected.
     */
    boolean transitionState(TaskState next)
}
