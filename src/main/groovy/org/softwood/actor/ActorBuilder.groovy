// ═════════════════════════════════════════════════════════════
// ActorBuilder.groovy
// ═════════════════════════════════════════════════════════════
package org.softwood.actor

import groovy.transform.CompileDynamic
import org.softwood.actor.supervision.SupervisionStrategy
import org.softwood.actor.supervision.OneForOneStrategy

/**
 * Enhanced ActorBuilder with pattern-matching support.
 *
 * Supports three DSL styles:
 *  1. onMessage { msg, ctx -> ... }           - Simple handler
 *  2. react { msg, ctx -> ... }               - GPars-style
 *  3. when(Type) { msg, ctx -> ... }          - Pattern matching (DynamicDispatchActor style)
 */
@CompileDynamic
class ActorBuilder {
    final ActorSystem system

    String name
    Map initialState = [:]
    Closure handler
    SupervisionStrategy supervisionStrategy

    // For pattern matching
    private List<PatternHandler> patterns = []
    private Closure defaultHandler

    ActorBuilder(ActorSystem system) {
        this.system = system
    }

    // ─────────────────────────────────────────────────────────────
    // Basic DSL Methods
    // ─────────────────────────────────────────────────────────────

    void name(String n) {
        this.name = n
    }

    void state(Map s) {
        if (s) {
            this.initialState.putAll(s)
        }
    }
    
    /**
     * Set supervision strategy using a SupervisionStrategy object.
     * 
     * Usage:
     *   supervisionStrategy SupervisionStrategy.restartAlways()
     *   supervisionStrategy new OneForOneStrategy(...)
     */
    void supervisionStrategy(SupervisionStrategy strategy) {
        this.supervisionStrategy = strategy
    }
    
    /**
     * Set supervision strategy using a decider closure.
     * Creates a OneForOneStrategy with the provided decider.
     * 
     * Usage:
     *   supervisionStrategy { throwable ->
     *       if (throwable instanceof IOException) {
     *           return SupervisorDirective.RESTART
     *       } else {
     *           return SupervisorDirective.STOP
     *       }
     *   }
     */
    void supervisionStrategy(Closure decider) {
        this.supervisionStrategy = new OneForOneStrategy(decider)
    }
    
    /**
     * Set supervision strategy using a map configuration.
     * Creates a OneForOneStrategy with the provided options and decider.
     * 
     * Usage:
     *   supervisionStrategy(
     *       maxRestarts: 5,
     *       withinDuration: Duration.ofMinutes(1),
     *       useExponentialBackoff: true
     *   ) { throwable -> ... }
     */
    void supervisionStrategy(Map options, Closure decider) {
        def strategy = new OneForOneStrategy(decider)
        if (options.maxRestarts != null) {
            strategy.maxRestarts = options.maxRestarts as int
        }
        if (options.withinDuration != null) {
            strategy.withinDuration = options.withinDuration
        }
        if (options.useExponentialBackoff != null) {
            strategy.useExponentialBackoff = options.useExponentialBackoff as boolean
        }
        if (options.initialBackoff != null) {
            strategy.initialBackoff = options.initialBackoff
        }
        if (options.maxBackoff != null) {
            strategy.maxBackoff = options.maxBackoff
        }
        this.supervisionStrategy = strategy
    }

    // ─────────────────────────────────────────────────────────────
    // Style 1: Direct Handler
    // ─────────────────────────────────────────────────────────────

    /**
     * Define the message handler.
     * Supports both single-arg and two-arg closures.
     *
     * This handler is called for EVERY message (the mailbox loop handles repetition).
     */
    void onMessage(Closure c) {
        if (c.maximumNumberOfParameters == 1) {
            this.handler = { msg, ctx -> c.call(msg) }
        } else {
            this.handler = c
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Style 2: GPars-Compatible react
    // ─────────────────────────────────────────────────────────────

    /**
     * GPars-style "react" - alias for onMessage.
     *
     * Note: Unlike GPars continuation-based actors, this is called repeatedly
     * by the mailbox loop, so no explicit "loop" is needed.
     *
     * Usage:
     *   react { msg, ctx ->
     *       // Handle message
     *   }
     */
    void react(Closure c) {
        onMessage(c)
    }

    // ─────────────────────────────────────────────────────────────
    // Style 3: Pattern Matching (DynamicDispatchActor style)
    // ─────────────────────────────────────────────────────────────

    /**
     * Register a pattern handler for specific message type.
     *
     * Usage:
     *   when(String) { msg, ctx -> ... }
     *   when(Map) { msg, ctx -> ... }
     */
    void when(Class<?> messageType, Closure handler) {
        patterns << new PatternHandler(messageType, handler)
    }

    /**
     * Register a pattern handler with predicate.
     *
     * Usage:
     *   when({ it instanceof Map && it.type == "command" }) { msg, ctx -> ... }
     */
    void when(Closure predicate, Closure handler) {
        patterns << new PatternHandler(predicate, handler)
    }

    /**
     * Default handler for unmatched messages.
     *
     * Usage:
     *   otherwise { msg, ctx ->
     *       println "Unhandled: $msg"
     *   }
     */
    void otherwise(Closure handler) {
        this.defaultHandler = handler
    }

    // ─────────────────────────────────────────────────────────────
    // Build
    // ─────────────────────────────────────────────────────────────

    Actor build() {
        // If patterns are defined, build pattern-matching handler
        if (!patterns.isEmpty()) {
            this.handler = buildPatternHandler()
        }

        validate()
        def actor = system.createActor(name, handler, initialState)
        
        // Apply supervision strategy if set
        if (supervisionStrategy) {
            actor.setSupervisionStrategy(supervisionStrategy)
        }
        
        return actor
    }

    private Closure buildPatternHandler() {
        return { msg, ctx ->
            // Try each pattern in order
            for (PatternHandler pattern : patterns) {
                if (pattern.matches(msg)) {
                    return pattern.handle(msg, ctx)
                }
            }

            // Fall back to default handler
            if (defaultHandler) {
                return defaultHandler.call(msg, ctx)
            } else {
                println "[$name] No handler matched for message: ${msg?.class?.simpleName}"
            }
        }
    }

    private void validate() {
        assert system != null, "ActorSystem must not be null"
        assert name != null, "Actor name must be set"
        assert handler != null, "Message handler must be set via onMessage/react/when"
    }

    // ─────────────────────────────────────────────────────────────
    // Pattern Handler Helper
    // ─────────────────────────────────────────────────────────────

    private static class PatternHandler {
        private final Object matcher  // Class or Closure
        private final Closure handler

        PatternHandler(Object matcher, Closure handler) {
            this.matcher = matcher
            this.handler = handler
        }

        boolean matches(Object msg) {
            if (matcher instanceof Class) {
                return ((Class) matcher).isInstance(msg)
            } else if (matcher instanceof Closure) {
                return ((Closure) matcher).call(msg)
            }
            return false
        }

        Object handle(Object msg, ActorContext ctx) {
            if (handler.maximumNumberOfParameters == 1) {
                return handler.call(msg)
            } else {
                return handler.call(msg, ctx)
            }
        }
    }
}