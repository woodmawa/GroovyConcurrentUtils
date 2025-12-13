package org.softwood.actor.remote

import groovy.util.logging.Slf4j
import org.softwood.actor.Actor

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * A lightweight reference to a remote actor addressed by URI.
 * Implements the Actor interface to provide seamless local/remote transparency.
 * Delegates to a pluggable RemotingTransport.
 * 
 * <h2>Usage</h2>
 * <pre>
 * // Create remote reference
 * def remote = new RemoteActorRef('rsocket://host:7000/sys/actor', transport)
 * 
 * // Use like any other actor
 * remote.tell("Hello")
 * def reply = remote.askSync("World", Duration.ofSeconds(5))
 * </pre>
 * 
 * @since 2.0.0
 */
@Slf4j
class RemoteActorRef implements Actor {
    final String uri
    private final RemotingTransport transport
    private final String actorName

    RemoteActorRef(String uri, RemotingTransport transport) {
        this.uri = uri
        this.transport = transport
        this.actorName = extractActorName(uri)
    }
    
    // ═════════════════════════════════════════════════════════════
    // Actor Interface Implementation
    // ═════════════════════════════════════════════════════════════

    @Override
    void tell(Object message) {
        transport.tell(uri, message)
    }
    
    @Override
    void tell(Object message, Actor sender) {
        // Remote actors don't support sender references yet
        // Just send the message
        transport.tell(uri, message)
    }
    
    @Override
    void send(Object message) {
        // Alias for tell
        transport.tell(uri, message)
    }

    @Override
    CompletableFuture<Object> ask(Object message, Duration timeout) {
        return transport.ask(uri, message, timeout ?: Duration.ofSeconds(5))
    }

    @Override
    Object askSync(Object message, Duration timeout = Duration.ofSeconds(5)) {
        CompletableFuture<Object> f = transport.ask(uri, message, timeout)
        try {
            return f.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (TimeoutException te) {
            f.cancel(true)
            throw te
        }
    }

    @Override
    void sendAndContinue(Object message, Closure continuation, Duration timeout = Duration.ofSeconds(5)) {
        CompletableFuture<Object> f = transport.ask(uri, message, timeout)
        f.thenAccept { v -> continuation?.call(v) }
    }
    
    @Override
    Object sendAndWait(Object message, Duration timeout = Duration.ofSeconds(5)) {
        return askSync(message, timeout)
    }
    
    @Override
    String getName() {
        return actorName
    }
    
    @Override
    Map getState() {
        // Remote actors don't expose state
        return [:]
    }
    
    // ═════════════════════════════════════════════════════════════
    // Lifecycle Methods (Remote actors managed remotely)
    // ═════════════════════════════════════════════════════════════
    
    @Override
    void stop() {
        log.warn("Cannot stop remote actor ${uri} from client side")
    }
    
    @Override
    boolean stop(Duration timeout) {
        log.warn("Cannot stop remote actor ${uri} from client side")
        return false
    }
    
    @Override
    void stopNow() {
        log.warn("Cannot stop remote actor ${uri} from client side")
    }
    
    @Override
    boolean isStopped() {
        // Remote actors are assumed running
        return false
    }
    
    @Override
    boolean isTerminated() {
        // Remote actors are assumed running
        return false
    }
    
    // ═════════════════════════════════════════════════════════════
    // Error Handling (Not applicable for remote refs)
    // ═════════════════════════════════════════════════════════════
    
    @Override
    Actor onError(Closure handler) {
        log.warn("Error handlers not supported on remote actor references")
        return this
    }
    
    @Override
    List getErrors(int maxCount = 10) {
        // Remote actors don't expose their error state
        return []
    }
    
    @Override
    void clearErrors() {
        // No-op for remote actors
    }
    
    // ═════════════════════════════════════════════════════════════
    // Mailbox Management (Not applicable for remote refs)
    // ═════════════════════════════════════════════════════════════
    
    @Override
    void setMaxMailboxSize(int max) {
        log.warn("Cannot set mailbox size on remote actor ${uri}")
    }
    
    @Override
    int getMaxMailboxSize() {
        return -1 // Unknown
    }
    
    // ═════════════════════════════════════════════════════════════
    // Health & Metrics (Limited for remote refs)
    // ═════════════════════════════════════════════════════════════
    
    @Override
    Map health() {
        return [
            uri: uri,
            type: 'remote',
            transport: transport.scheme(),
            reachable: true  // Assume reachable (could add ping)
        ]
    }
    
    @Override
    Map metrics() {
        return [
            uri: uri,
            type: 'remote',
            note: 'Remote metrics not available from client side'
        ]
    }
    
    // ═════════════════════════════════════════════════════════════
    // Helper Methods
    // ═════════════════════════════════════════════════════════════
    
    /**
     * Extracts actor name from URI.
     * Format: scheme://host:port/system/actorName
     */
    private static String extractActorName(String uri) {
        def parts = uri.split('/')
        return parts.length > 0 ? parts[parts.length - 1] : 'unknown'
    }

    @Override
    String toString() { "RemoteActorRef($uri)" }
}
