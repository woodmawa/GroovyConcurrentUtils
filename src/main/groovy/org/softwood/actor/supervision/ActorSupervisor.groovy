package org.softwood.actor.supervision

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.softwood.actor.ActorContext
import org.softwood.actor.ActorSystem

// ═════════════════════════════════════════════════════════════
// Approach 3: Full ActorSupervisor (Advanced)
// ═════════════════════════════════════════════════════════════

@Slf4j
@CompileStatic
class ActorSupervisor {
    final String name
    final ActorSystem system
    final SupervisionStrategy defaultStrategy

    // Track supervised actors
    private final Map<String, SupervisionConfig> supervised = [:]
    private final Map<String, List<Long>> restartHistory = [:]

    ActorSupervisor(String name, ActorSystem system, SupervisionStrategy defaultStrategy = SupervisionStrategy.RESTART) {
        this.name = name
        this.system = system
        this.defaultStrategy = defaultStrategy

        // Create supervisor actor
        createSupervisorActor()
    }

    /**
     * Add actor to supervision.
     */
    void supervise(String actorName, SupervisionStrategy strategy = null, int maxRestarts = 3) {
        supervised[actorName] = new SupervisionConfig(
                strategy: strategy ?: defaultStrategy,
                maxRestarts: maxRestarts,
                restartWindow: 60_000
        )
        restartHistory[actorName] = []
    }

    /**
     * Create the supervisor actor that handles failures.
     */
    private void createSupervisorActor() {
        system.actor {
            name this.name

            onMessage { msg, ActorContext ctx ->
                if (msg instanceof Map && msg.actor && msg.error) {
                    handleFailure(msg as Map, ctx )
                } else {
                    log.warn "[$name] Unknown supervision message: $msg"
                }
            }
        }
    }

    private void handleFailure(Map failureReport, ActorContext ctx) {
        def actorName = failureReport.actor as String
        def config = supervised[actorName]

        if (!config) {
            log.warn "[$name] Actor $actorName not supervised - ignoring failure"
            return
        }

        log.error "[$name] Actor $actorName failed: ${failureReport.error}"

        switch (config.strategy) {
            case SupervisionStrategy.RESTART:
                restartActor(actorName, config, ctx)
                break
            case SupervisionStrategy.RESUME:
                log.info "[$name] Resuming $actorName"
                break
            case SupervisionStrategy.STOP:
                log.info "[$name] Stopping $actorName"
                system.removeActor(actorName)
                break
            case SupervisionStrategy.ESCALATE:
                log.info "[$name] Escalating $actorName failure"
                // Could forward to higher-level supervisor
                break
        }
    }

    private void restartActor(String actorName, SupervisionConfig config, ActorContext ctx) {
        def history = restartHistory[actorName]
        def now = System.currentTimeMillis()

        // Add restart timestamp
        history << now

        // Clean old timestamps
        history.removeAll { now - it > config.restartWindow }

        if (history.size() > config.maxRestarts) {
            log.error "[$name] Actor $actorName exceeded max restarts (${config.maxRestarts}) - stopping"
            system.removeActor(actorName)
        } else {
            log.info "[$name] Restarting $actorName (attempt ${history.size()}/${config.maxRestarts})"

            // Get current actor
            def actor = system.getActor(actorName)
            if (actor) {
                // Clear state
                actor.state.clear()
                log.info "[$name] Actor $actorName state reset"
            }
        }
    }

    /**
     * Get supervision statistics.
     */
    Map getStats() {
        def stats = [:]
        supervised.each { actorName, config ->
            def history = restartHistory[actorName]
            stats[actorName] = [
                    strategy: config.strategy,
                    restarts: history.size(),
                    lastRestart: history.isEmpty() ? null : new Date(history.last())
            ]
        }
        return stats
    }
}