package org.softwood.actor

import java.time.Duration
import java.util.concurrent.CompletableFuture

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
 *   <li>Request-reply patterns via {@link #ask} (a future) and {@link #askSync} (blocking)</li>
 *   <li>Operators: {@code actor << msg} waits for the reply, {@code actor >> msg} sends without waiting
 *       (the same rule as for agents)</li>
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
     * Asynchronous request-reply. Returns at once with a future for the reply, on a local actor and
     * on a remote reference alike (2.0.0; a local ask blocked before).
     *
     * @param msg message to send
     * @param timeout how long the reply may take; after it the future completes exceptionally with a
     *        {@link java.util.concurrent.TimeoutException}
     * @return a future completed with the response from the actor's message handler
     * @throws IllegalStateException if actor is stopped
     */
    CompletableFuture<Object> ask(Object msg, Duration timeout)

    /**
     * Synchronous request-reply. Blocks until the response is received.
     *
     * @param msg message to send
     * @param timeout maximum wait time
     * @return response from actor
     * @throws java.util.concurrent.TimeoutException if timeout expires
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
     * Alias for {@link #askSync} - synchronous send-and-wait.
     *
     * @param msg message to send
     * @param timeout maximum wait time
     * @return response from actor
     */
    Object sendAndWait(Object msg, Duration timeout)
    
    /**
     * Groovy left-shift operator: send and wait for the reply, {@code actor << message}.
     *
     * <p>This is a synchronous operation that blocks until a reply is received, as {@code <<} does
     * on an agent. Equivalent to {@link #askSync(Object, Duration)} with the default five-second
     * timeout. This differs from GPars, where {@code <<} on an actor sends without waiting: the
     * GCU fire-and-forget operator is {@link #rightShift(Object) >>}.</p>
     * 
     * <h2>Usage Examples</h2>
     * <pre>
     * def result = actor << "hello"           // Send and wait for reply
     * def value = actor << [action: 'get']   // Send map message
     * </pre>
     * 
     * @param msg message to send
     * @return response from actor's message handler
     * @throws java.util.concurrent.TimeoutException if timeout expires (default: 5s)
     * @throws IllegalStateException if actor is stopped
     */
    Object leftShift(Object msg)

    /**
     * Groovy right-shift operator: send without waiting, {@code actor >> message}.
     * The same as {@link #tell(Object)}; returns this actor so sends can be chained:
     * {@code actor >> 'one' >> 'two'}. As {@code >>} on an agent, it does not wait.
     *
     * @param msg message to send
     * @return this actor
     * @throws IllegalStateException if actor is stopped
     * @since 2.0.0
     */
    Actor rightShift(Object msg)

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
