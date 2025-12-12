package org.softwood.agent

import java.util.concurrent.TimeUnit

/**
 * Agent<T> – single-threaded, message-driven access to mutable state.
 *
 * <p>This interface defines the contract for agents that wrap mutable objects
 * and guarantee all updates and reads occur sequentially on a dedicated worker thread.
 * Multiple threads may submit work concurrently; the Agent ensures serialized execution.</p>
 *
 * <h2>Core Operations</h2>
 * <ul>
 *   <li>Fire-and-forget updates via {@link #send}</li>
 *   <li>Synchronous operations via {@link #sendAndGet}</li>
 *   <li>Immutable snapshot access via {@link #getValue}</li>
 *   <li>Graceful shutdown via {@link #shutdown}</li>
 * </ul>
 *
 * <h2>Operator Support</h2>
 * <ul>
 *   <li>{@code >>} - Async send (right shift)</li>
 *   <li>{@code <<} - Sync send (left shift)</li>
 * </ul>
 *
 * @param <T> type of underlying state
 */
interface Agent<T> {

    // ----------------------------------------------------------------------
    // Core Operations
    // ----------------------------------------------------------------------

    /**
     * Submits a fire-and-forget update task.
     *
     * @param action closure to execute on underlying state
     * @return this Agent for chaining
     * @throws IllegalStateException if agent is shutting down
     * @throws java.util.concurrent.RejectedExecutionException if queue is full
     */
    Agent<T> send(Closure action)

    /**
     * Submits a closure and blocks until it completes, returning the result.
     *
     * @param action closure returning a result
     * @param timeoutSeconds optional timeout in seconds (0 = no timeout)
     * @return closure return value
     * @throws RuntimeException if timeout expires or closure throws
     * @throws IllegalStateException if agent is shutting down
     */
    def <R> R sendAndGet(Closure<R> action, long timeoutSeconds)

    /**
     * Returns an immutable defensive snapshot of the current state.
     * Modifications to the snapshot do not affect the agent's internal state.
     *
     * @return immutable copy of wrapped object
     */
    T getValue()

    // ----------------------------------------------------------------------
    // Lifecycle Management
    // ----------------------------------------------------------------------

    /**
     * Initiates graceful shutdown: prevents new tasks, drains queue, and waits until finished.
     * Blocks indefinitely until all pending tasks complete.
     */
    void shutdown()

    /**
     * Initiates graceful shutdown with timeout.
     * Prevents new tasks, drains queue, and waits up to the specified timeout.
     *
     * @param timeout maximum time to wait
     * @param unit time unit
     * @return true if shutdown completed within timeout, false otherwise
     */
    boolean shutdown(long timeout, TimeUnit unit)

    /**
     * Forcefully shuts down the agent, discarding pending tasks.
     * Does not wait for currently executing task to complete.
     */
    void shutdownNow()

    /**
     * Returns true if shutdown has been initiated.
     *
     * @return true if shutdown() or shutdownNow() has been called
     */
    boolean isShutdown()

    /**
     * Returns true if shutdown is complete and all tasks have finished.
     *
     * @return true if terminated
     */
    boolean isTerminated()

    // ----------------------------------------------------------------------
    // Aliases
    // ----------------------------------------------------------------------

    /**
     * Alias for {@link #send} - fire-and-forget async operation.
     */
    Agent<T> async(Closure c)

    /**
     * Alias for {@link #sendAndGet} - synchronous operation with optional timeout.
     *
     * @param c closure to execute
     * @param timeoutSeconds timeout in seconds (default 0 = no timeout)
     */
    def <R> R sync(Closure<R> c, long timeoutSeconds)

    // ----------------------------------------------------------------------
    // Operators
    // ----------------------------------------------------------------------

    /**
     * Operator: {@code agent >> closure}
     * Async send operation.
     */
    def rightShift(Closure c)

    /**
     * Operator: {@code agent << closure}
     * Sync send operation, returns result.
     */
    def <R> R leftShift(Closure<R> c)

    // ----------------------------------------------------------------------
    // Error Management
    // ----------------------------------------------------------------------

    /**
     * Sets an error handler to be called when tasks throw exceptions.
     *
     * @param errorHandler closure accepting (Throwable) to handle errors
     * @return this Agent for chaining
     */
    Agent<T> onError(Closure<Void> errorHandler)

    /**
     * Returns recent errors that occurred during task execution.
     *
     * @param maxCount maximum number of errors to return (default: all available)
     * @return list of error maps with timestamp, error type, message, and stack trace
     */
    List<Map<String, Object>> getErrors(int maxCount)

    /**
     * Clears the error history.
     */
    void clearErrors()

    // ----------------------------------------------------------------------
    // Configuration
    // ----------------------------------------------------------------------

    /**
     * Returns the maximum queue size (0 = unbounded).
     *
     * @return maximum queue size
     */
    int getMaxQueueSize()

    /**
     * Sets the maximum queue size (0 = unbounded).
     * If set, send() will throw RejectedExecutionException when queue is full.
     *
     * @param max maximum queue size (0 = unbounded)
     */
    void setMaxQueueSize(int max)

    // ----------------------------------------------------------------------
    // Health & Metrics
    // ----------------------------------------------------------------------

    /**
     * Returns health status of this agent.
     * Includes information about shutdown state and queue status.
     *
     * @return map containing health information
     */
    Map<String, Object> health()

    /**
     * Returns operational metrics for this agent.
     * Includes queue depth, task counts, throughput, and processing state.
     *
     * @return map containing metrics
     */
    Map<String, Object> metrics()
}
