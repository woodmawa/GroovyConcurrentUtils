// ═════════════════════════════════════════════════════════════
// ScopedValueActor.groovy
// ═════════════════════════════════════════════════════════════
package org.softwood.actor

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import java.time.Duration
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Core actor implementation with enhanced context support.
 *
 * Now provides system reference to context, enabling:
 *  - forward() to other actors
 *  - actorRef() lookups
 *  - broadcast() capabilities
 */
@Slf4j
@CompileStatic
class ScopedValueActor {
    final String name
    final Map state = [:]

    private final Closure handler
    private final BlockingQueue<MessageEnvelope> mailbox = new LinkedBlockingQueue<>()
    private volatile boolean running = true

    // Reference to the actor system for context
    private ActorSystem system

    // ─────────────────────────────────────────────────────────────
    // Construction
    // ─────────────────────────────────────────────────────────────

    ScopedValueActor(String name, Map initialState = [:], Closure handler) {
        this.name = name
        this.handler = handler
        if (initialState) {
            this.state.putAll(initialState)
        }
        startMailboxLoop()
    }

    /**
     * Set the actor system reference (called by ActorSystem after creation).
     * Package-private, only ActorSystem should call this.
     */
    void setSystem(ActorSystem system) {
        this.system = system
    }

    // ─────────────────────────────────────────────────────────────
    // Messaging API (tell/ask patterns)
    // ─────────────────────────────────────────────────────────────

    /**
     * Fire-and-forget (GPars: send).
     * Can optionally track sender for reply-to patterns.
     */
    void tell(Object msg, ScopedValueActor sender = null) {
        enqueueMessage(msg, null, sender)
    }

    void send(Object msg) {
        tell(msg)
    }

    /**
     * Synchronous request-reply with timeout.
     */
    Object askSync(Object msg, Duration timeout = Duration.ofSeconds(5)) {
        def future = new CompletableFuture<Object>()
        enqueueMessage(msg, future, null)

        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (TimeoutException te) {
            future.cancel(true)
            throw te
        }
    }

    Object sendAndWait(Object msg, Duration timeout = Duration.ofSeconds(5)) {
        askSync(msg, timeout)
    }

    /**
     * Async request with continuation callback (GPars: sendAndContinue).
     */
    void sendAndContinue(Object msg, Closure continuation, Duration timeout = Duration.ofSeconds(5)) {
        def future = new CompletableFuture<Object>()
        enqueueMessage(msg, future, null)

        future.thenAccept { value ->
            continuation.call(value)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────

    void stop() {
        running = false
        mailbox.put(new MessageEnvelope("__STOP__"))
    }

    // ─────────────────────────────────────────────────────────────
    // Internal: Mailbox loop
    // ─────────────────────────────────────────────────────────────

    private void startMailboxLoop() {
        Thread.startVirtualThread {
            log.debug "Actor [$name] mailbox loop started"

            while (running) {
                def envelope = mailbox.take()

                if (!running || envelope.payload == "__STOP__") {
                    log.debug "Actor [$name] stopping"
                    break
                }

                processMessage(envelope)
            }

            log.debug "Actor [$name] mailbox loop terminated"
        }
    }

    private void processMessage(MessageEnvelope envelope) {
        def ctx = new ActorContext(
                name,
                envelope.payload,
                state,
                system,
                envelope.replyFuture,
                envelope.sender
        )

        try {
            def result = handler.call(envelope.payload, ctx)

            // Auto-reply if ask and handler didn't explicitly reply
            if (envelope.isAsk() && !envelope.replyFuture.isDone()) {
                ctx.reply(result)
            }
        } catch (Throwable t) {
            log.error "Error in actor [$name] processing message: ${envelope.payload}", t

            if (envelope.isAsk() && !envelope.replyFuture.isDone()) {
                envelope.replyFuture.completeExceptionally(t)
            }
        }
    }

    private void enqueueMessage(Object msg, CompletableFuture<Object> future, ScopedValueActor sender) {
        if (!running) {
            throw new IllegalStateException("Actor [$name] is not running")
        }
        mailbox.put(new MessageEnvelope(msg, future, sender))
    }
}