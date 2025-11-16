// ═════════════════════════════════════════════════════════════
// MessageEnvelope.groovy
// ═════════════════════════════════════════════════════════════
package org.softwood.actor

import groovy.transform.CompileStatic
import java.util.concurrent.CompletableFuture

/**
 * Internal message envelope wrapping payload and optional reply future.
 * Used by the mailbox to distinguish tell vs ask.
 */
@CompileStatic
class MessageEnvelope {
    final Object payload
    final CompletableFuture<Object> replyFuture
    final ScopedValueActor sender  // Optional: for reply-to patterns

    // Main constructor
    MessageEnvelope(Object payload, CompletableFuture<Object> replyFuture, ScopedValueActor sender) {
        this.payload = payload
        this.replyFuture = replyFuture
        this.sender = sender
    }

    // Convenience constructors for common cases
    MessageEnvelope(Object payload) {
        this(payload, null, null)
    }

    MessageEnvelope(Object payload, CompletableFuture<Object> replyFuture) {
        this(payload, replyFuture, null)
    }

    boolean isAsk() {
        replyFuture != null
    }

    boolean isTell() {
        replyFuture == null
    }

    boolean hasSender() {
        sender != null
    }
}