package org.softwood.actor.remote

import groovy.util.logging.Slf4j

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * A lightweight reference to a remote actor addressed by URI.
 * Delegates to a pluggable RemotingTransport.
 */
@Slf4j
class RemoteActorRef {
    final String uri
    private final RemotingTransport transport

    RemoteActorRef(String uri, RemotingTransport transport) {
        this.uri = uri
        this.transport = transport
    }

    void tell(Object message) {
        transport.tell(uri, message)
    }

    Object askSync(Object message, Duration timeout = Duration.ofSeconds(5)) {
        CompletableFuture<Object> f = transport.ask(uri, message, timeout)
        try {
            return f.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (TimeoutException te) {
            f.cancel(true)
            throw te
        }
    }

    void sendAndContinue(Object message, Closure continuation, Duration timeout = Duration.ofSeconds(5)) {
        CompletableFuture<Object> f = transport.ask(uri, message, timeout)
        f.thenAccept { v -> continuation?.call(v) }
    }

    @Override
    String toString() { "RemoteActorRef($uri)" }
}
