package org.softwood.actor

import java.time.Duration

/**
 * Actor – Message-driven concurrent entity with isolated state.
 *
 * <p>Actors process messages sequentially from a mailbox, ensuring thread-safe
 * state management without explicit locking. Each actor runs on its own execution
 * context (virtual thread by default).</p>
 *
 * <h2>Core Operations</h2>
 * <ul>
 *   <li>Fire-and-forget messaging via {@link #tell}</li>
 *   <li>Request-reply patterns via {@link #ask}</li>
 *   <li>Async continuations via {@link #sendAndContinue}</li>
 *   <li>State isolation with message handler context</li>
 * </ul>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Sequential message processing (one at a time)</li>
 *   <li>Context-aware message handlers with sender tracking</li>
 *   <li>Health and metrics observability</li>
 *   <li>Graceful shutdown with queue draining</li>
 * </ul>
 *
 * @since 1.0.0
 */
interface Actor {

    // ----------------------------------------------------------------------
    // Core Messaging
    // ----------------------------------------------------------------------

    /**
     * Sends a fire-and-forget message to this actor.
     * Message is queued and processed asynchronously.
     *
     * @param msg message to send
     * @throws IllegalStateException if actor is stopped
     */
    void tell(Object msg)

    /**
     * Sends a fire-and-forget message with sender tracking.
     * Allows recipient to reply back to sender via context.
     *
     * @param msg message to send
     * @param sender the sending actor (for reply-to)
     * @throws IllegalStateException if actor is stopped
     */
    void tell(Object msg, Actor sender)

    /**
     * Alias for {@link #tell(Object)} - fire-and-forget messaging.
     *
     * @param msg message to send
     */
    void send(Object msg)

    /**
     * Synchronous request-reply pattern. Blocks until response received.
     *
     * @param msg message to send
     * @param timeout maximum wait time (default: 5 seconds)
     * @return response from actor's message handler
     * @throws java.util.concurrent.TimeoutException if timeout expires
     * @throws IllegalStateException if actor is stopped
     */
    Object ask(Object msg, Duration timeout)

    /**
     * Alias for {@link #ask} with default timeout.
     *
     * @param msg message to send
     * @return response from actor
     */
    Object askSync(Object msg, Duration timeout)

    /**
     * Async request with continuation callback.
     * Sends message and invokes continuation when response arrives.
     *
     * @param msg message to send
     * @param continuation callback invoked with response
     * @param timeout maximum wait time
     */
    void sendAndContinue(Object msg, Closure continuation, Duration timeout)

    /**
     * Alias for {@link #ask} - synchronous send-and-wait.
     *
     * @param msg message to send
     * @param timeout maximum wait time
     * @return response from actor
     */
    Object sendAndWait(Object msg, Duration timeout)

    // ----------------------------------------------------------------------
    // Identification
    // ----------------------------------------------------------------------

    /**
     * Returns the actor's name.
     *
     * @return actor name
     */
    String getName()

    // ----------------------------------------------------------------------
    // State Access
    // ----------------------------------------------------------------------

    /**
     * Returns a defensive snapshot of the actor's current state.
     * Modifications to the snapshot do not affect the actor's internal state.
     *
     * @return immutable copy of state
     */
    Map getState()

    // ----------------------------------------------------------------------
    // Lifecycle Management
    // ----------------------------------------------------------------------

    /**
     * Initiates graceful shutdown. Prevents new messages and drains mailbox.
     * Blocks until all pending messages are processed.
     */
    void stop()

    /**
     * Initiates graceful shutdown with timeout.
     *
     * @param timeout maximum time to wait
     * @return true if shutdown completed within timeout
     */
    boolean stop(Duration timeout)

    /**
     * Force shutdown, discarding pending messages.
     */
    void stopNow()

    /**
     * Returns true if stop has been initiated.
     *
     * @return true if stopping or stopped
     */
    boolean isStopped()

    /**
     * Returns true if shutdown is complete (mailbox drained).
     *
     * @return true if fully terminated
     */
    boolean isTerminated()

    // ----------------------------------------------------------------------
    // Error Management
    // ----------------------------------------------------------------------

    /**
     * Sets an error handler for message processing failures.
     *
     * @param errorHandler closure accepting (Throwable) to handle errors
     * @return this Actor for chaining
     */
    Actor onError(Closure<Void> errorHandler)

    /**
     * Returns recent errors from message processing.
     *
     * @param maxCount maximum number of errors to return
     * @return list of error maps with timestamp, type, message, stack trace
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
     * Sets the maximum mailbox size. When full, new messages are rejected.
     *
     * @param max maximum queue size (0 = unbounded)
     */
    void setMaxMailboxSize(int max)

    /**
     * Returns the maximum mailbox size.
     *
     * @return maximum mailbox size (0 = unbounded)
     */
    int getMaxMailboxSize()

    // ----------------------------------------------------------------------
    // Observability
    // ----------------------------------------------------------------------

    /**
     * Returns health status of this actor.
     *
     * @return map containing health information
     */
    Map<String, Object> health()

    /**
     * Returns operational metrics for this actor.
     *
     * @return map containing metrics
     */
    Map<String, Object> metrics()
}
