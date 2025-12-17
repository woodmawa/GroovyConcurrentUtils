// ═════════════════════════════════════════════════════════════
// ActorDSL.groovy
// ═════════════════════════════════════════════════════════════
package org.softwood.actor

/**
 * @deprecated Use ActorFactory.actor() or ActorSystem.actor() instead.
 * 
 * <p>This class has been merged into ActorFactory for simpler imports.
 * 
 * <p>Migration:</p>
 * <pre>
 * // Old:
 * import org.softwood.actor.ActorDSL
 * def actor = ActorDSL.actor(system) { ... }
 * 
 * // New (Option 1 - static import):
 * import static org.softwood.actor.ActorFactory.actor
 * def actor = actor(system) { ... }
 * 
 * // New (Option 2 - system method):
 * def actor = system.actor { ... }
 * 
 * // New (Option 3 - shortest syntax):
 * def actor = system.actor("name") { msg, ctx -> ... }
 * </pre>
 */
@Deprecated
class ActorDSL {

    /**
     * Create an actor using builder DSL.
     * 
     * @deprecated Use ActorFactory.actor() or ActorSystem.actor() instead.
     */
    @Deprecated
    static Actor actor(
            ActorSystem system,
            @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = ActorBuilder)
                    Closure<?> spec) {
        // Delegate to ActorFactory
        return ActorFactory.actor(system, spec)
    }
}