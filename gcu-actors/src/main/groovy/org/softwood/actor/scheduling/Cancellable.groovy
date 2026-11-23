package org.softwood.actor.scheduling

/**
 * Represents a scheduled task that can be cancelled.
 * 
 * <p>Returned by scheduling methods to allow cancellation of pending tasks.</p>
 * 
 * @since 2.0.0
 */
interface Cancellable {
    
    /**
     * Cancels this scheduled task.
     * 
     * <p>If the task has already executed or been cancelled, this has no effect.
     * If the task is currently executing, it will complete but not reschedule.</p>
     * 
     * @return true if the task was successfully cancelled, false if already executed or cancelled
     */
    boolean cancel()
    
    /**
     * Checks if this task has been cancelled.
     * 
     * @return true if cancelled, false otherwise
     */
    boolean isCancelled()
}
