package org.softwood.actor.scheduling

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Implementation of Cancellable backed by ScheduledFuture.
 * 
 * <p>Wraps a ScheduledFuture and provides a cleaner cancellation API.</p>
 * 
 * @since 2.0.0
 */
@Slf4j
@CompileStatic
class ScheduledTask implements Cancellable {
    
    private final ScheduledFuture<?> future
    private final AtomicBoolean cancelled = new AtomicBoolean(false)
    private final String description
    
    ScheduledTask(ScheduledFuture<?> future, String description = "task") {
        this.future = future
        this.description = description
    }
    
    @Override
    boolean cancel() {
        if (cancelled.compareAndSet(false, true)) {
            boolean result = future.cancel(false) // Don't interrupt if running
            if (result) {
                log.debug("Cancelled scheduled task: {}", description)
            }
            return result
        }
        return false
    }
    
    @Override
    boolean isCancelled() {
        return cancelled.get() || future.isCancelled()
    }
    
    /**
     * Checks if the task has completed (successfully or otherwise).
     * 
     * @return true if done, false if still pending
     */
    boolean isDone() {
        return future.isDone()
    }
}
