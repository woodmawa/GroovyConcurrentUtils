package org.softwood.actor

import groovy.transform.CompileDynamic

import java.time.Duration
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Minimal dynamic actor with:
 *  - tell
 *  - ask
 *  - persistent state
 *  - handler closure (msg, ctx)
 *  - virtual thread mailbox loop
 */
class ScopedValueActor {

    final String name
    final Map state = [:]

    private final BlockingQueue<Message> mailbox = new LinkedBlockingQueue<>()
    private volatile boolean running = true
    private final Closure handler   // (msg, ctx) -> result

    // ─────────────────────────────────────────────────────────────
    // Constructors
    // ─────────────────────────────────────────────────────────────

    /**
     * Main constructor used by tests and DSL:
     *
     *   new ScopedValueActor("Echo", { msg, ctx -> ... })
     */
    ScopedValueActor(String name, Closure handler) {
        this.name = name
        this.handler = handler
        startLoop()
    }

    /**
     * Optional ctor with initial state map.
     */
    ScopedValueActor(String name, Map initialState, Closure handler) {
        this.name = name
        if (initialState != null) {
            this.state.putAll(initialState)
        }
        this.handler = handler
        startLoop()
    }

    // ─────────────────────────────────────────────────────────────
    // Internal message envelope
    // ─────────────────────────────────────────────────────────────

    private static class Message {
        final Object payload
        final CompletableFuture<Object> replyFuture   // null for fire-and-forget

        Message(Object payload, CompletableFuture<Object> replyFuture) {
            this.payload = payload
            this.replyFuture = replyFuture
        }

        boolean isAsk() { replyFuture != null }
    }

    // ─────────────────────────────────────────────────────────────
    // Context passed into handlers
    // ─────────────────────────────────────────────────────────────

    class ActorContext {
        final Object msg
        final CompletableFuture<Object> replyFuture

        ActorContext(Object msg, CompletableFuture<Object> replyFuture) {
            this.msg = msg
            this.replyFuture = replyFuture
        }

        /**
         * Actor-local persistent state.
         */
        Map getState() { ScopedValueActor.this.state }

        /**
         * Explicit reply (mainly for sendAndContinue or manual control).
         */
        void reply(Object value) {
            if (replyFuture != null && !replyFuture.isDone()) {
                replyFuture.complete(normalize(value))
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Helper: String/GString normalization
    // ─────────────────────────────────────────────────────────────

    private static Object normalize(Object v) {
        (v instanceof GString) ? v.toString() : v
    }

    // ─────────────────────────────────────────────────────────────
    // Core API: tell / ask
    // ─────────────────────────────────────────────────────────────

    /**
     * Fire-and-forget. GPars equivalent: send().
     */
    void tell(Object msg) {
        mailbox.put(new Message(msg, null))
    }

    /**
     * Alias for tell(msg) to match GPars semantics.
     */
    void send(Object msg) {
        tell(msg)
    }

    /**
     * Synchronous request/response.
     */
    Object askSync(Object msg, Duration timeout = Duration.ofSeconds(5)) {
        def fut = new CompletableFuture<Object>()
        mailbox.put(new Message(msg, fut))

        try {
            def result = fut.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
            return normalize(result)
        } catch (TimeoutException te) {
            fut.cancel(true)
            throw te
        }
    }

    /**
     * GPars-style name for askSync.
     */
    Object sendAndWait(Object msg, Duration timeout = Duration.ofSeconds(5)) {
        return askSync(msg, timeout)
    }

    /**
     * GPars-style continuation: sendAndContinue(msg) { reply -> ... }
     *
     * This does an async ask under the hood and invokes the callback
     * when the reply arrives.
     */
    void sendAndContinue(Object msg, Closure continuation, Duration timeout = Duration.ofSeconds(5)) {
        def fut = new CompletableFuture<Object>()
        mailbox.put(new Message(msg, fut))

        // Complete normally
        fut.thenAccept { value ->
            continuation.call(normalize(value))
        }

        // Optional: handle timeout separately if you want; or rely on caller.
        // If you want hard timeouts, you can schedule a timeout task somewhere
        // and call fut.completeExceptionally(...) when elapsed.
    }

    /**
     * Stop this actor gracefully.
     */
    void stop() {
        running = false
        // sentinel to unblock mailbox.take()
        mailbox.put(new Message("__STOP__", null))
    }

    // ─────────────────────────────────────────────────────────────
    // Message loop
    // ─────────────────────────────────────────────────────────────

    private void startLoop() {
        Thread.startVirtualThread {
            while (running) {
                def m = mailbox.take()
                if (!running || m.payload == "__STOP__") break

                def ctx = new ActorContext(m.payload, m.replyFuture)

                try {
                    def result = handler(m.payload, ctx)

                    // If it's an ask and the handler didn’t explicitly reply,
                    // we auto-complete with the return value.
                    if (m.isAsk() && !ctx.replyFuture.isDone()) {
                        ctx.reply(result)
                    }

                } catch (Throwable t) {
                    if (m.isAsk() && !ctx.replyFuture.isDone()) {
                        ctx.replyFuture.completeExceptionally(t)
                    }
                }
            }
        }
    }
}