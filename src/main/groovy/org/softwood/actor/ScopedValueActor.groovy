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
    private final Closure handler     // (msg, ctx)

    // ───────────────────────────────────────────────────────────────────────────
    //  Constructors — REQUIRED BY YOUR TESTS
    // ───────────────────────────────────────────────────────────────────────────

    ScopedValueActor(String name, Closure handler) {
        this.name = name
        this.handler = handler
        startLoop()
    }

    ScopedValueActor(String name, Map initialState, Closure handler) {
        this.name = name
        this.state.putAll(initialState ?: [:])
        this.handler = handler
        startLoop()
    }

    // ───────────────────────────────────────────────────────────────────────────

    private static class Message {
        final Object payload
        final CompletableFuture<Object> replyFuture
        Message(Object payload, CompletableFuture<Object> replyFuture) {
            this.payload = payload
            this.replyFuture = replyFuture
        }
        boolean isAsk() { replyFuture != null }
    }

    // ───────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ───────────────────────────────────────────────────────────────────────────
    private static Object normalize(Object v) {
        (v instanceof GString) ? v.toString() : v
    }

    class ActorContext {
        final Object msg
        final CompletableFuture<Object> replyFuture

        ActorContext(Object msg, CompletableFuture<Object> replyFuture) {
            this.msg = msg
            this.replyFuture = replyFuture
        }

        Map getState() { ScopedValueActor.this.state }

        void reply(Object value) {
            if (replyFuture != null) {
                replyFuture.complete(normalize(value))
            }
        }
    }

    void tell(Object msg) {
        mailbox.put(new Message(msg, null))
    }

    Object askSync(Object msg, Duration timeout = Duration.ofSeconds(3)) {
        def fut = new CompletableFuture<Object>()
        mailbox.put(new Message(msg, fut))
        return fut.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    void stop() {
        running = false
        mailbox.put(new Message("__stop__", null))
    }

    private void startLoop() {
        Thread.startVirtualThread {
            while (running) {
                def m = mailbox.take()
                if (!running || m.payload == "__stop__") break

                def ctx = new ActorContext(m.payload, m.replyFuture)

                try {
                    def result = handler(m.payload, ctx)

                    // FIXED: normalize result for ask()
                    if (m.isAsk() && !ctx.replyFuture.isDone()) {
                        ctx.reply(normalize(result))
                    }

                } catch (Throwable t) {
                    if (m.isAsk() && !ctx.replyFuture.isDone())
                        ctx.replyFuture.completeExceptionally(t)
                }
            }
        }
    }
}