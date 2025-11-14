package org.softwood.actor

import groovy.transform.CompileDynamic

import java.time.Duration
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Fully dynamic actor built on virtual threads and a simple mailbox.
 *
 * Public API:
 *   - tell(msg)             : fire-and-forget send
 *   - askSync(msg, timeout) : request/response with timeout
 */
@CompileDynamic
class ScopedValueActor {

    final String name
    final Closure handler
    Map<String, Object> state = [:]

    private final BlockingQueue<Message> mailbox = new LinkedBlockingQueue<>()
    private volatile boolean running = true
    private Thread loopThread
    private long messageCounter = 0L

    ScopedValueActor(String name) {
        this(name, null)
    }

    ScopedValueActor(String name, Closure handler) {
        this.name = name
        this.handler = handler
        startLoop()
    }

    /**
     * Allow Groovy syntax: new ScopedValueActor("X") { msg, ctx -> ... }
     * Since handler is final, we return a NEW instance with that handler.
     */
    ScopedValueActor call(Closure handlerClosure) {
        return new ScopedValueActor(this.name, handlerClosure)
    }

    // ------------------------------------------------------------
    // Helper to normalize values coming out of Groovy
    // ------------------------------------------------------------
    static Object normalize(Object v) {
        (v instanceof GString) ? v.toString() : v
    }

    // ------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------

    void tell(Object msg) {
        mailbox.offer(new Message(msg, null))
    }

    Object askSync(Object msg, Duration timeout = Duration.ofSeconds(5)) {
        def future = new CompletableFuture<Object>()
        mailbox.offer(new Message(msg, future))
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (TimeoutException te) {
            future.cancel(true)
            throw te
        }
    }

    void stop() {
        running = false
        loopThread?.interrupt()
    }

    // ------------------------------------------------------------
    // Internal message loop
    // ------------------------------------------------------------

    private void startLoop() {
        loopThread = Thread.ofVirtual().name("actor-" + name).start {
            try {
                while (running) {
                    Message env
                    try {
                        env = mailbox.take()
                    } catch (InterruptedException ie) {
                        if (!running) break
                        continue
                    }
                    process(env)
                }
            } finally {
                running = false
            }
        }
    }

    private void process(Message env) {
        messageCounter++
        def ctx = new ActorContext(this, messageCounter, env)

        try {
            def result = handler.call(env.payload, ctx)
            if (env.replyFuture && !env.replyFuture.isDone()) {
                env.replyFuture.complete(normalize(result))
            }
        } catch (Throwable t) {
            if (env.replyFuture && !env.replyFuture.isDone()) {
                env.replyFuture.completeExceptionally(t)
            }
        }
    }

    // ------------------------------------------------------------
    // Helper types
    // ------------------------------------------------------------

    static class Message {
        final Object payload
        final CompletableFuture<Object> replyFuture

        Message(Object payload, CompletableFuture<Object> replyFuture) {
            this.payload = payload
            this.replyFuture = replyFuture
        }
    }

    @CompileDynamic
    static class ActorContext {
        final ScopedValueActor actor
        Object msg
        final long messageNumber
        final Message env
        final Map<String, Object> state = [:]

        ActorContext(ScopedValueActor actor, long messageNumber, Message env) {
            this.actor = actor
            this.messageNumber = messageNumber
            this.env = env
        }

        Map getState() {
            return actor.state
        }

        Object get(String key) { state[key] }
        void set(String key, Object value) { state[key] = value }

        void reply(Object value) {
            if (env?.replyFuture && !env.replyFuture.isDone()) {
                env.replyFuture.complete(ScopedValueActor.normalize(value))
            }
        }
    }
}
