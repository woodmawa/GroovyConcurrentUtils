package org.softwood.actor.remote

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.softwood.actor.ActorSystem

/**
 * GCU 1.3.0 (decision D10): the rules every server transport applies before it listens and before it
 * delivers a message.
 *
 * <ul>
 *   <li>A server transport listens on the loopback address unless given another.</li>
 *   <li>On any other address it refuses to start with authentication off, unless the caller has set
 *       {@code insecure = true} and so said that an open listener is intended.</li>
 *   <li>On any other address without TLS it starts, and warns.</li>
 *   <li>Only an actor marked remote ({@code remote true} in its builder, or
 *       {@code system.markRemote(name)}) can be reached from outside. Any other name is answered
 *       exactly as a name that does not exist.</li>
 * </ul>
 */
@Slf4j
@CompileStatic
class RemotingGuard {

    /** Where a server transport listens when no address is given. */
    static final String DEFAULT_BIND_ADDRESS = '127.0.0.1'

    static boolean isLoopback(String address) {
        if (!address) return false
        if (address.equalsIgnoreCase('localhost')) return true
        try {
            return InetAddress.getByName(address).isLoopbackAddress()
        } catch (Exception ignored) {
            return false
        }
    }

    /**
     * Called by a transport before it opens its server socket.
     *
     * @throws IllegalStateException if the address is not loopback, authentication is off and
     *                               {@code insecure} was not set
     */
    static void checkBind(String scheme, String bindAddress, int port,
                          boolean authEnabled, boolean tlsEnabled, boolean insecure) {
        if (isLoopback(bindAddress)) {
            return
        }
        if (!authEnabled && !insecure) {
            throw new IllegalStateException(
                    "The ${scheme} transport will not listen on ${bindAddress}:${port} with authentication off: " +
                    "any caller that can reach that address could send messages to this system's remote actors. " +
                    "Either pass an AuthConfig with enabled = true, or set insecure = true on the transport " +
                    "to say that an open listener is what you intend.")
        }
        if (!authEnabled) {
            log.warn("{} transport listening on {}:{} with authentication OFF (insecure = true)", scheme, bindAddress, port)
        }
        if (!tlsEnabled) {
            log.warn("{} transport listening on {}:{} without TLS: messages and tokens travel in clear text", scheme, bindAddress, port)
        }
    }

    /** True if a message arriving from outside may be delivered to this actor. */
    static boolean addressable(ActorSystem system, String actorName) {
        return system != null && actorName != null && system.hasActor(actorName) && system.isRemote(actorName)
    }
}
