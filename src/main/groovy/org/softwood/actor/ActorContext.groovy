// ═════════════════════════════════════════════════════════════
// ActorContext.groovy
// ═════════════════════════════════════════════════════════════
package org.softwood.actor

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.softwood.actor.remote.security.AuthContext as SecurityAuthContext

import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * Enhanced context passed to message handlers.
 *
 * Provides:
 *  - Access to message, state, actor identity
 *  - Reply mechanism (for ask-style messages)
 *  - Forward to other actors (tell/ask)
 *  - System access for actor lookup
 *  - Sender tracking (optional)
 */
@Slf4j
@CompileStatic
class ActorContext {
    final String actorName
    final Object message
    final Map state
    final ActorSystem system

    // Optional: track sender for reply-to patterns
    final Actor sender
    
    // Optional: authentication context (for RBAC)
    final SecurityAuthContext authContext

    private final CompletableFuture<Object> replyFuture

    ActorContext(
            String actorName,
            Object message,
            Map state,
            ActorSystem system,
            CompletableFuture<Object> replyFuture,
            Actor sender = null,
            SecurityAuthContext authContext = null) {
        this.actorName = actorName
        this.message = message
        this.state = state
        this.system = system
        this.replyFuture = replyFuture
        this.sender = sender
        this.authContext = authContext ?: new SecurityAuthContext() // Unauthenticated by default
    }

    // ─────────────────────────────────────────────────────────────
    // Reply (original functionality)
    // ─────────────────────────────────────────────────────────────

    /**
     * Send a reply for ask-style messages.
     */
    void reply(Object value) {
        if (replyFuture != null && !replyFuture.isDone()) {
            replyFuture.complete(normalizeValue(value))
        } else if (replyFuture == null) {
            log.warn "[$actorName] Attempted reply on tell message (no effect)"
        }
    }

    /**
     * Check if this message expects a reply.
     */
    boolean isAskMessage() {
        replyFuture != null
    }

    // ─────────────────────────────────────────────────────────────
    // Forward to other actors
    // ─────────────────────────────────────────────────────────────

    /**
     * Forward message to another actor (fire-and-forget).
     *
     * Usage:
     *   ctx.forward("WorkerActor", processedMessage)
     */
    void forward(String targetActorName, Object msg) {
        def target = system.getActor(targetActorName)
        if (target) {
            target.tell(msg)
            log.debug "[$actorName] forwarded message to [$targetActorName]"
        } else {
            log.warn "[$actorName] Cannot forward - actor [$targetActorName] not found"
        }
    }

    /**
     * Forward to actor instance directly.
     */
    void forward(Actor target, Object msg) {
        if (target) {
            target.tell(msg)
            log.debug "[$actorName] forwarded message to [${target.name}]"
        } else {
            log.warn "[$actorName] Cannot forward - target actor is null"
        }
    }

    /**
     * Forward and wait for response, then reply with that response.
     * This creates a transparent proxy pattern.
     *
     * Usage:
     *   ctx.forwardAndReply("DatabaseActor", query)
     */
    void forwardAndReply(String targetActorName, Object msg, Duration timeout = Duration.ofSeconds(5)) {
        def target = system.getActor(targetActorName)
        if (!target) {
            throw new IllegalArgumentException("Actor [$targetActorName] not found")
        }

        if (!isAskMessage()) {
            log.warn "[$actorName] forwardAndReply called on tell message - forwarding as tell"
            target.tell(msg)
            return
        }

        try {
            def result = target.askSync(msg, timeout)
            reply(result)
        } catch (Exception e) {
            log.error "[$actorName] Error forwarding to [$targetActorName]: ${e.message}"
            replyFuture.completeExceptionally(e)
        }
    }

    /**
     * Forward to actor instance and reply with response.
     */
    void forwardAndReply(Actor target, Object msg, Duration timeout = Duration.ofSeconds(5)) {
        if (!target) {
            throw new IllegalArgumentException("Target actor is null")
        }
        forwardAndReply(target.name, msg, timeout)
    }

    // ─────────────────────────────────────────────────────────────
    // Actor Lookup Convenience
    // ─────────────────────────────────────────────────────────────

    /**
     * Get another actor by name from the system.
     *
     * Usage:
     *   def worker = ctx.actorRef("Worker")
     *   worker.tell("do work")
     */
    Actor actorRef(String name) {
        system.getActor(name)
    }

    /**
     * Check if an actor exists in the system.
     */
    boolean hasActor(String name) {
        system.hasActor(name)
    }

    // ─────────────────────────────────────────────────────────────
    // Broadcast / Multi-cast
    // ─────────────────────────────────────────────────────────────

    /**
     * Broadcast message to multiple actors (fire-and-forget).
     *
     * Usage:
     *   ctx.broadcast(["Worker1", "Worker2", "Worker3"], task)
     */
    void broadcast(Collection<String> actorNames, Object msg) {
        actorNames.each { name ->
            forward(name, msg)
        }
        log.debug "[$actorName] broadcast to ${actorNames.size()} actors"
    }

    /**
     * Broadcast to all actors matching a pattern.
     *
     * Usage:
     *   ctx.broadcastPattern("Worker.*", task)
     */
    void broadcastPattern(String pattern, Object msg) {
        def matchingActors = system.actorNames.findAll { it.matches(pattern) }
        broadcast(matchingActors, msg)
    }

    // ─────────────────────────────────────────────────────────────
    // Reply-To Pattern (if sender is tracked)
    // ─────────────────────────────────────────────────────────────

    /**
     * Reply to the original sender actor (if tracked).
     * Useful for actor-to-actor conversations.
     */
    void replyToSender(Object msg) {
        if (sender) {
            sender.tell(msg)
            log.debug "[$actorName] replied to sender [${sender.name}]"
        } else {
            log.warn "[$actorName] No sender to reply to"
        }
    }

    boolean hasSender() {
        sender != null
    }

    // ─────────────────────────────────────────────────────────────
    // Self-Reference
    // ─────────────────────────────────────────────────────────────

    /**
     * Get reference to self (useful for passing to other actors).
     */
    Actor self() {
        system.getActor(actorName)
    }

    /**
     * Schedule a message to self (useful for timeouts, retries).
     * Note: This is synchronous for now; true scheduling requires a scheduler.
     */
    void tellSelf(Object msg) {
        self()?.tell(msg)
    }

    // ─────────────────────────────────────────────────────────────
    // Authorization & Security
    // ─────────────────────────────────────────────────────────────

    /**
     * Check if request is authenticated.
     * 
     * @return true if authenticated
     */
    boolean isAuthenticated() {
        return authContext.authenticated
    }
    
    /**
     * Get authenticated subject (username/service ID).
     * 
     * @return subject or null if not authenticated
     */
    String getSubject() {
        return authContext.subject
    }
    
    /**
     * Get authenticated user's roles.
     * 
     * @return list of roles or empty if not authenticated
     */
    List<String> getRoles() {
        return authContext.roles
    }
    
    /**
     * Check if authenticated user has a specific role.
     * 
     * @param role role name
     * @return true if user has role
     */
    boolean hasRole(String role) {
        return authContext.hasRole(role)
    }
    
    /**
     * Check if authenticated user has any of the specified roles.
     * 
     * @param roles list of role names
     * @return true if user has at least one role
     */
    boolean hasAnyRole(List<String> roles) {
        return authContext.hasAnyRole(roles)
    }
    
    /**
     * Check if authenticated user has all of the specified roles.
     * 
     * @param roles list of role names
     * @return true if user has all roles
     */
    boolean hasAllRoles(List<String> roles) {
        return authContext.hasAllRoles(roles)
    }
    
    /**
     * Require authentication - throws if not authenticated.
     * 
     * @throws SecurityAuthContext.AuthenticationException if not authenticated
     */
    void requireAuthenticated() {
        authContext.requireAuthenticated()
    }
    
    /**
     * Require specific role - throws if not present.
     * 
     * @param role required role
     * @throws SecurityAuthContext.AuthorizationException if role not present
     */
    void requireRole(String role) {
        authContext.requireRole(role)
    }
    
    /**
     * Require any of the specified roles - throws if none present.
     * 
     * @param roles required roles
     * @throws SecurityAuthContext.AuthorizationException if no roles present
     */
    void requireAnyRole(List<String> roles) {
        authContext.requireAnyRole(roles)
    }
    
    /**
     * Require all of the specified roles - throws if not all present.
     * 
     * @param roles required roles
     * @throws SecurityAuthContext.AuthorizationException if not all roles present
     */
    void requireAllRoles(List<String> roles) {
        authContext.requireAllRoles(roles)
    }

    // ─────────────────────────────────────────────────────────────
    // Helper
    // ─────────────────────────────────────────────────────────────

    private static Object normalizeValue(Object v) {
        (v instanceof GString) ? v.toString() : v
    }

    // ─────────────────────────────────────────────────────────────
    // Debugging
    // ─────────────────────────────────────────────────────────────

    @Override
    String toString() {
        "ActorContext[actor=$actorName, msg=${message?.class?.simpleName}, hasReply=${isAskMessage()}, sender=${sender?.name}]"
    }
}