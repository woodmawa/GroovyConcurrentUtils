package org.softwood.actor.remote

import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * Pluggable remoting transport SPI.
 * Implementations provide a scheme (e.g. "microstream", "http", "tcp")
 * and know how to deliver tell/ask to a remote actor identified by a URI.
 *
 * Example URI: microstream://host:port/system/actorName
 */
interface RemotingTransport extends Closeable {
    /** Unique lowercase scheme name for this transport, used in URIs. */
    String scheme()

    /** Start or initialize transport resources if required. */
    void start()

    /**
     * Fire-and-forget delivery to a remote actor.
     * @param actorUri full actor URI including scheme
     * @param message payload to send
     */
    void tell(String actorUri, Object message)

    /**
     * Request/response delivery to a remote actor with timeout support.
     * Should complete the future with reply or exceptionally on error/timeout.
     */
    CompletableFuture<Object> ask(String actorUri, Object message, Duration timeout)

    @Override
    void close()
}
