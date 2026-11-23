package org.softwood.actor.remote.microstream

import groovy.util.logging.Slf4j
import org.softwood.actor.ActorSystem
import org.softwood.actor.remote.RemotingTransport

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * MicroStream-based transport (skeleton).
 *
 * This minimal implementation demonstrates the pluggable transport API without
 * taking a hard dependency on MicroStream libraries. It parses URIs of the form:
 *   microstream://host:port/system/actorName
 * and simulates delivery. You can later replace internals to use real
 * MicroStream channels or storage mechanisms.
 */
@Slf4j
class MicroStreamTransport implements RemotingTransport {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "microstream-transport")
        t.setDaemon(true)
        return t
    })

    // Optional hook for local loopback demo when URI host matches local marker
    private final ActorSystem localSystem

    MicroStreamTransport(ActorSystem localSystem = null) {
        this.localSystem = localSystem
    }

    @Override
    String scheme() { 'microstream' }

    @Override
    void start() {
        log.info "MicroStream transport started"
    }

    @Override
    void tell(String actorUri, Object message) {
        log.debug "[microstream] tell -> $actorUri : $message"
        // Minimal loopback: if pointing at local system via special host 'local', deliver directly
        def target = parse(actorUri)
        if (localSystem && target.host == 'local' && localSystem.hasActor(target.actor)) {
            localSystem.getActor(target.actor).tell(message)
        }
    }

    @Override
    CompletableFuture<Object> ask(String actorUri, Object message, Duration timeout) {
        log.debug "[microstream] ask -> $actorUri : $message (timeout=${timeout?.toMillis()}ms)"
        def fut = new CompletableFuture<Object>()

        def target = parse(actorUri)
        if (localSystem && target.host == 'local' && localSystem.hasActor(target.actor)) {
            scheduler.execute({
                try {
                    def reply = localSystem.getActor(target.actor).askSync(message, timeout)
                    fut.complete(reply)
                } catch (Throwable t) {
                    fut.completeExceptionally(t)
                }
            })
        } else {
            // Simulate remote latency and echo
            scheduler.schedule({
                fut.complete([status: 'SENT', uri: actorUri, message: message])
            }, Math.min(50, timeout?.toMillis() ?: 50), TimeUnit.MILLISECONDS)
        }

        return fut
    }

    @Override
    void close() {
        scheduler.shutdownNow()
        log.info "MicroStream transport stopped"
    }

    private static ParsedUri parse(String uri) {
        // microstream://host:port/system/actor
        def u = new URI(uri)
        def parts = (u.path ?: '/').split('/')
        def system = parts.length > 1 ? parts[1] : ''
        def actor = parts.length > 2 ? parts[2] : ''
        return new ParsedUri(u.scheme, u.host ?: 'local', u.port, system, actor)
    }

    private static class ParsedUri {
        final String scheme
        final String host
        final int port
        final String system
        final String actor
        ParsedUri(String scheme, String host, int port, String system, String actor) {
            this.scheme = scheme
            this.host = host
            this.port = port
            this.system = system
            this.actor = actor
        }
    }
}
