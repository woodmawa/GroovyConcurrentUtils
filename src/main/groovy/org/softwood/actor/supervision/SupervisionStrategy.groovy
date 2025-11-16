package org.softwood.actor.supervision

enum SupervisionStrategy {
    RESTART,  // Reset state and continue
    RESUME,   // Keep state and continue
    STOP,     // Stop the actor
    ESCALATE  // Forward error to supervisor
}
