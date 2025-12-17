package org.softwood.actor.scheduling

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.softwood.actor.Actor

import java.time.Duration
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Scheduler for actor messages.
 * 
 * <p>Provides scheduling capabilities for actors to send delayed or periodic messages.
 * Uses a shared ScheduledExecutorService to efficiently manage timers across all actors.</p>
 * 
 * <h2>Features</h2>
 * <ul>
 *   <li>One-time delayed messages</li>
 *   <li>Fixed-rate periodic messages</li>
 *   <li>Fixed-delay periodic messages</li>
 *   <li>Cancellable scheduled tasks</li>
 *   <li>Automatic cleanup on actor termination</li>
 * </ul>
 * 
 * <h2>Usage</h2>
 * <pre>
 * def scheduler = new ActorScheduler()
 * 
 * // Schedule one-time message
 * def task = scheduler.scheduleOnce(actor, 'timeout', Duration.ofSeconds(5))
 * 
 * // Schedule periodic message
 * def periodic = scheduler.scheduleAtFixedRate(
 *     actor, 
 *     'health-check', 
 *     Duration.ofSeconds(10),
 *     Duration.ofSeconds(30)
 * )
 * 
 * // Cancel later
 * task.cancel()
 * </pre>
 * 
 * @since 2.0.0
 */
@Slf4j
@CompileStatic
class ActorScheduler {
    
    private final ScheduledExecutorService scheduler
    private final boolean ownsScheduler
    private final AtomicInteger taskCounter = new AtomicInteger(0)
    
    /**
     * Creates a scheduler with a default executor.
     * 
     * <p>Uses a thread pool sized to available processors / 2 (min 1, max 4).</p>
     */
    ActorScheduler() {
        int poolSize = Math.max(1, Math.min(4, Runtime.runtime.availableProcessors() / 2)) as int
        this.scheduler = createScheduler(poolSize)
        this.ownsScheduler = true
        log.debug("Created ActorScheduler with {} threads", poolSize)
    }
    
    /**
     * Creates a scheduler with a custom executor.
     * 
     * @param scheduler custom scheduled executor service
     */
    ActorScheduler(ScheduledExecutorService scheduler) {
        this.scheduler = scheduler
        this.ownsScheduler = false
        log.debug("Created ActorScheduler with custom executor")
    }
    
    /**
     * Creates a scheduler with specified thread pool size.
     * 
     * @param poolSize number of scheduler threads
     */
    ActorScheduler(int poolSize) {
        this.scheduler = createScheduler(poolSize)
        this.ownsScheduler = true
        log.debug("Created ActorScheduler with {} threads", poolSize)
    }
    
    private static ScheduledThreadPoolExecutor createScheduler(int poolSize) {
        def executor = new ScheduledThreadPoolExecutor(
            poolSize,
            { runnable ->
                def thread = new Thread(runnable)
                thread.daemon = true
                thread.name = "actor-scheduler-${thread.id}"
                return thread
            }
        )
        executor.setRemoveOnCancelPolicy(true) // Clean up cancelled tasks
        return executor
    }
    
    /**
     * Schedules a one-time message to be sent after a delay.
     * 
     * @param actor the target actor
     * @param message the message to send
     * @param delay how long to wait before sending
     * @return Cancellable task handle
     */
    Cancellable scheduleOnce(Actor actor, Object message, Duration delay) {
        int taskId = taskCounter.incrementAndGet()
        String description = "once-${actor.name}-${taskId}"
        
        log.debug("Scheduling one-time message to {} after {}: {}", 
                  actor.name, delay, message)
        
        def future = scheduler.schedule(
            {
                try {
                    if (!actor.isStopped()) {
                        actor.tell(message)
                    }
                } catch (Exception e) {
                    log.error("Failed to send scheduled message to {}: {}", 
                              actor.name, e.message, e)
                }
            } as Runnable,
            delay.toMillis(),
            TimeUnit.MILLISECONDS
        )
        
        return new ScheduledTask(future, description)
    }
    
    /**
     * Schedules a message to be sent repeatedly at fixed rate.
     * 
     * <p>Messages are sent at fixed intervals regardless of processing time.
     * If processing takes longer than the interval, messages may queue up.</p>
     * 
     * @param actor the target actor
     * @param message the message to send repeatedly
     * @param initialDelay delay before first message
     * @param period interval between messages
     * @return Cancellable task handle
     */
    Cancellable scheduleAtFixedRate(
            Actor actor, 
            Object message, 
            Duration initialDelay, 
            Duration period
    ) {
        int taskId = taskCounter.incrementAndGet()
        String description = "fixed-rate-${actor.name}-${taskId}"
        
        log.debug("Scheduling fixed-rate message to {} every {} (initial delay {}): {}", 
                  actor.name, period, initialDelay, message)
        
        def future = scheduler.scheduleAtFixedRate(
            {
                try {
                    if (!actor.isStopped()) {
                        actor.tell(message)
                    } else {
                        // Actor stopped - cancel this task
                        throw new IllegalStateException("Actor stopped")
                    }
                } catch (Exception e) {
                    if (e instanceof IllegalStateException) {
                        log.debug("Stopping periodic task for stopped actor: {}", actor.name)
                        throw e // Propagate to cancel the task
                    }
                    log.error("Failed to send scheduled message to {}: {}", 
                              actor.name, e.message, e)
                }
            } as Runnable,
            initialDelay.toMillis(),
            period.toMillis(),
            TimeUnit.MILLISECONDS
        )
        
        return new ScheduledTask(future, description)
    }
    
    /**
     * Schedules a message to be sent repeatedly with fixed delay.
     * 
     * <p>Waits for the specified delay AFTER each message completes processing
     * before sending the next. Prevents queue buildup if processing is slow.</p>
     * 
     * @param actor the target actor
     * @param message the message to send repeatedly
     * @param initialDelay delay before first message
     * @param delay delay between completion of one send and start of next
     * @return Cancellable task handle
     */
    Cancellable scheduleWithFixedDelay(
            Actor actor, 
            Object message, 
            Duration initialDelay, 
            Duration delay
    ) {
        int taskId = taskCounter.incrementAndGet()
        String description = "fixed-delay-${actor.name}-${taskId}"
        
        log.debug("Scheduling fixed-delay message to {} every {} (initial delay {}): {}", 
                  actor.name, delay, initialDelay, message)
        
        def future = scheduler.scheduleWithFixedDelay(
            {
                try {
                    if (!actor.isStopped()) {
                        actor.tell(message)
                    } else {
                        throw new IllegalStateException("Actor stopped")
                    }
                } catch (Exception e) {
                    if (e instanceof IllegalStateException) {
                        log.debug("Stopping periodic task for stopped actor: {}", actor.name)
                        throw e
                    }
                    log.error("Failed to send scheduled message to {}: {}", 
                              actor.name, e.message, e)
                }
            } as Runnable,
            initialDelay.toMillis(),
            delay.toMillis(),
            TimeUnit.MILLISECONDS
        )
        
        return new ScheduledTask(future, description)
    }
    
    /**
     * Shuts down the scheduler.
     * 
     * <p>Only shuts down if this scheduler owns the executor (not a custom one).
     * Cancels all pending tasks.</p>
     */
    void shutdown() {
        if (ownsScheduler && scheduler != null) {
            log.debug("Shutting down ActorScheduler")
            scheduler.shutdown()
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow()
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow()
                Thread.currentThread().interrupt()
            }
        }
    }
    
    /**
     * Returns the number of active scheduled tasks.
     * 
     * @return number of pending tasks
     */
    int getActiveTaskCount() {
        if (scheduler instanceof ScheduledThreadPoolExecutor) {
            return ((ScheduledThreadPoolExecutor) scheduler).queue.size()
        }
        return 0
    }
}
