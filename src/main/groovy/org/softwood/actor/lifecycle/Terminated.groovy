package org.softwood.actor.lifecycle

import groovy.transform.CompileStatic
import org.softwood.actor.Actor

/**
 * Message sent when a watched actor terminates.
 * 
 * <p>Actors can watch other actors and will receive a Terminated message
 * when the watched actor stops, either normally or due to failure.</p>
 * 
 * <h2>Usage</h2>
 * <pre>
 * actor.onMessage { msg, ctx ->
 *     if (msg == 'start-worker') {
 *         def worker = ctx.system.actor('worker') { msg2, ctx2 -> ... }
 *         ctx.watch(worker)
 *     } else if (msg instanceof Terminated) {
 *         println "Worker ${msg.actor.name} terminated!"
 *         // Spawn new worker or take other action
 *     }
 * }
 * </pre>
 * 
 * @since 2.0.0
 */
@CompileStatic
class Terminated {
    
    /**
     * The actor that terminated.
     */
    final Actor actor
    
    /**
     * Whether the termination was due to a failure.
     */
    final boolean failed
    
    /**
     * The cause of failure (if failed == true).
     */
    final Throwable cause
    
    /**
     * Creates a Terminated message for normal termination.
     * 
     * @param actor the actor that terminated
     */
    Terminated(Actor actor) {
        this(actor, false, null)
    }
    
    /**
     * Creates a Terminated message for failure termination.
     * 
     * @param actor the actor that terminated
     * @param cause the failure cause
     */
    Terminated(Actor actor, Throwable cause) {
        this(actor, true, cause)
    }
    
    private Terminated(Actor actor, boolean failed, Throwable cause) {
        this.actor = actor
        this.failed = failed
        this.cause = cause
    }
    
    @Override
    String toString() {
        if (failed && cause) {
            return "Terminated(${actor.name}, failed: ${cause.class.simpleName}: ${cause.message})"
        } else {
            return "Terminated(${actor.name})"
        }
    }
    
    @Override
    boolean equals(Object obj) {
        if (!(obj instanceof Terminated)) return false
        Terminated other = (Terminated) obj
        return actor == other.actor && failed == other.failed
    }
    
    @Override
    int hashCode() {
        return actor.hashCode() * 31 + (failed ? 1 : 0)
    }
}
