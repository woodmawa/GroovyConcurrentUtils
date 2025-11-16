package org.softwood.actor.supervision

import groovy.util.logging.Slf4j
import org.softwood.actor.ActorContext
import org.softwood.actor.supervision.SupervisionStrategy

// ═════════════════════════════════════════════════════════════
// Approach 2: Supervision Trait (Optional Enhancement)
// ═════════════════════════════════════════════════════════════

@Slf4j
trait Supervised {
    // Supervision strategy
    SupervisionStrategy strategy = SupervisionStrategy.RESTART
    int maxRestarts = 3
    long restartWindow = 60_000 // 1 minute

    // State tracking
    private List<Long> restartTimestamps = []

    /**
     * Called when supervised actor fails.
     * Override to customize behavior.
     */
    void onFailure(Exception error, Object failedMessage, ActorContext ctx) {
        log.error "[$ctx.actorName] Failed: ${error.message}"

        switch (strategy) {
            case SupervisionStrategy.RESTART:
                handleRestart(ctx)
                break
            case SupervisionStrategy.RESUME:
                log.info "[$ctx.actorName] Resuming after error"
                break
            case SupervisionStrategy.STOP:
                log.info "[$ctx.actorName] Stopping due to error"
                ctx.self()?.stop()
                break
            case SupervisionStrategy.ESCALATE:
                // Forward to parent/system supervisor
                if (ctx.hasActor("SystemSupervisor")) {
                    ctx.forward("SystemSupervisor", [
                            actor: ctx.actorName,
                            error: error.message,
                            strategy: "escalate"
                    ])
                }
                break
        }
    }

    private void handleRestart(ActorContext ctx) {
        def now = System.currentTimeMillis()
        restartTimestamps << now

        // Remove old timestamps outside window
        restartTimestamps.removeAll { now - it > restartWindow }

        if (restartTimestamps.size() > maxRestarts) {
            log.error "[$ctx.actorName] Too many restarts (${restartTimestamps.size()}) - stopping"
            ctx.self()?.stop()
        } else {
            log.info "[$ctx.actorName] Restarting (attempt ${restartTimestamps.size()})"
            ctx.state.clear() // Reset state on restart
        }
    }
}