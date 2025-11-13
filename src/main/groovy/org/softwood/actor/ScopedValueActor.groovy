package org.softwood.actor

import groovy.transform.CompileStatic

import java.time.Duration
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight Actor implementation using
 * - Virtual Threads
 * - ScopedValue for per-message context
 * Compatible with Java 21–25 without preview or incubator APIs.
 */
@CompileStatic
class ScopedValueActor<M> implements AutoCloseable {

    private static final ScopedValue<ActorContext> ACTOR_CONTEXT =
            ScopedValue.newInstance()

    private final String name
    private final BlockingQueue<Message<M>> mailbox
    private volatile boolean running = true
    private final ExecutorService executor
    private Future<?> loopFuture

    private final Map<String, Object> state = [:].asSynchronized()
    private final AtomicLong messageCount = new AtomicLong(0)

    private MessageHandler<M> handler

    // =========================================================================
    //  Message + Context classes
    // =========================================================================

    static class Message<M> {
        final M payload
        final String sender
        final long timestamp
        final CompletableFuture<Object> replyTo

        Message(M payload,
                String sender = "anonymous",
                CompletableFuture<Object> replyTo = null) {
            this.payload = payload
            this.sender = sender
            this.timestamp = System.currentTimeMillis()
            this.replyTo = replyTo
        }
    }

    static class ActorContext {
        final ScopedValueActor<?> actor
        final String actorName
        final Map<String, Object> state
        final long messageNumber

        ActorContext(ScopedValueActor<?> actor,
                     String actorName,
                     Map<String, Object> state,
                     long messageNumber) {
            this.actor = actor
            this.actorName = actorName
            this.state = state
            this.messageNumber = messageNumber
        }

        Object get(String key) { state.get(key) }
        void   set(String key, Object value) { state.put(key, value) }
        boolean has(String key) { state.containsKey(key) }
        void   remove(String key) { state.remove(key) }

        /**
         * Switch behavior for subsequent messages.
         */
        void become(ScopedValueActor.MessageHandler<?> newHandler) {
            ((ScopedValueActor) actor).becomeInternal(newHandler)
        }

        /**
         * Convenience closure-based become.
         */
        void become(Closure<?> c) {
            ScopedValueActor.MessageHandler<Object> mh =
                    new ScopedValueActor.MessageHandler<Object>() {
                        @Override
                        Object handle(Object m, ActorContext ctx2) {
                            return c.call(m, ctx2)
                        }
                    }
            ((ScopedValueActor) actor).becomeInternal(mh)
        }

        String toString() {
            "ActorContext(name=$actorName, msg=$messageNumber, stateKeys=${state.keySet()})"
        }
    }

    @FunctionalInterface
    interface MessageHandler<M> {
        Object handle(M message, ActorContext ctx)
    }

    // =========================================================================
    //  Construction
    // =========================================================================

    ScopedValueActor(String name,
                     MessageHandler<M> handler,
                     int mailboxSize = 1000) {

        this.name = name
        this.handler = handler
        this.mailbox = new LinkedBlockingQueue<>(mailboxSize)
        this.executor = Executors.newVirtualThreadPerTaskExecutor()
        startLoop()
    }

    private void startLoop() {
        loopFuture = executor.submit(() -> {
            println "Actor '$name' started on ${Thread.currentThread()}"
            try {
                while (running || !mailbox.isEmpty()) {
                    Message<M> msg = mailbox.poll(100, TimeUnit.MILLISECONDS)
                    if (msg != null) processMessage(msg)
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt()
            } catch (Throwable t) {
                println "Actor '$name' crashed: ${t.message}"
                t.printStackTrace()
            } finally {
                println "Actor '$name' stopped"
            }
        })
    }

    // =========================================================================
    //  Message Processing
    // =========================================================================

    private void processMessage(Message<M> msg) {
        long msgNum = messageCount.incrementAndGet()
        ActorContext ctx = new ActorContext(this, name, state, msgNum)

        try {
            Object result = ScopedValue.where(ACTOR_CONTEXT, ctx)
                    .call(() -> handler.handle(msg.payload, ctx))

            if (msg.replyTo != null) {
                msg.replyTo.complete(result)
            }
        } catch (Throwable t) {
            if (msg.replyTo != null) msg.replyTo.completeExceptionally(t)
        }
    }

    // =========================================================================
    //  Behavior Switching
    // =========================================================================

    @SuppressWarnings("unchecked")
    private void becomeInternal(MessageHandler<?> newHandler) {
        this.handler = (MessageHandler<M>) newHandler
    }

    void become(MessageHandler<M> newHandler) {
        this.handler = newHandler
    }

    // =========================================================================
    //  Messaging API
    // =========================================================================

    /**
     * Core low-level send with sender metadata.
     */
    void send(M message, String sender) {
        if (!running)
            throw new IllegalStateException("Actor '$name' is not running")

        if (!mailbox.offer(new Message<>(message, sender))) {
            throw new IllegalStateException("Mailbox for '$name' is full")
        }
    }

    /**
     * Fire-and-forget sugar.
     * Equivalent to Akka/GPars `tell()`.
     */
    ScopedValueActor<M> tell(M message) {
        send(message, "anonymous")
        return this
    }

    /**
     * Asynchronous request/reply.
     */
    CompletableFuture<Object> ask(M message, String sender = "anonymous") {
        if (!running)
            throw new IllegalStateException("Actor '$name' is not running")

        CompletableFuture<Object> reply = new CompletableFuture<>()
        if (!mailbox.offer(new Message<>(message, sender, reply))) {
            reply.completeExceptionally(
                    new IllegalStateException("Mailbox for '$name' is full")
            )
        }
        return reply
    }

    /**
     * Blocking request/reply.
     */
    Object askSync(M message,
                   String sender = "anonymous",
                   Duration timeout = Duration.ofSeconds(5)) {

        return ask(message, sender)
                .get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    // =========================================================================
    //  Lifecycle API
    // =========================================================================

    void stop() { running = false }

    void stopAndWait(Duration timeout = Duration.ofSeconds(10)) {
        running = false
        try {
            if (loopFuture != null) {
                loopFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
            }
        } catch (TimeoutException te) {
            loopFuture?.cancel(true)
        } finally {
            executor.shutdownNow()
        }
    }

    @Override
    void close() {
        stopAndWait()
    }

    // =========================================================================
    //  ScopedValue helpers
    // =========================================================================

    static ActorContext getCurrentContext() {
        if (!ACTOR_CONTEXT.isBound())
            throw new IllegalStateException("Not in actor context")
        return ACTOR_CONTEXT.get()
    }

    static boolean isInActorContext() {
        ACTOR_CONTEXT.isBound()
    }

    // -------------------------------------------------------------------------
    //  Accessors
    // -------------------------------------------------------------------------

    String getName() {
        return name
    }

    // =========================================================================
    //  Debug
    // =========================================================================

    @Override
    String toString() {
        "Actor[$name](messages=${messageCount.get()}, mailbox=${mailbox.size()})"
    }
}
