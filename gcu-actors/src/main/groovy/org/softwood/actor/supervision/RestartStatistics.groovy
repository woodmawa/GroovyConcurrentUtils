package org.softwood.actor.supervision

import groovy.transform.CompileStatic

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Tracks restart attempts for supervision strategies.
 * 
 * <p>Records restart timestamps and provides methods to check if
 * restart limits have been exceeded within a time window.</p>
 * 
 * @since 2.0.0
 */
@CompileStatic
class RestartStatistics {
    
    private final int maxRestarts
    private final Duration withinDuration
    private final ConcurrentLinkedDeque<Instant> restartTimes = new ConcurrentLinkedDeque<>()
    
    /**
     * Create restart statistics tracker.
     * 
     * @param maxRestarts maximum allowed restarts
     * @param withinDuration time window for counting restarts
     */
    RestartStatistics(int maxRestarts, Duration withinDuration) {
        this.maxRestarts = maxRestarts
        this.withinDuration = withinDuration
    }
    
    /**
     * Record a restart attempt.
     * 
     * @return true if restart is allowed, false if limit exceeded
     */
    synchronized boolean recordRestart() {
        if (maxRestarts < 0) {
            // Unlimited restarts
            return true
        }
        
        Instant now = Instant.now()
        Instant cutoff = now.minus(withinDuration)
        
        // Remove old restart times outside the window
        while (!restartTimes.isEmpty() && restartTimes.getFirst() < cutoff) {
            restartTimes.removeFirst()
        }
        
        // Check if we've exceeded the limit
        if (restartTimes.size() >= maxRestarts) {
            return false
        }
        
        // Record this restart
        restartTimes.addLast(now)
        return true
    }
    
    /**
     * Get the number of restarts within the time window.
     */
    synchronized int getRestartCount() {
        Instant now = Instant.now()
        Instant cutoff = now.minus(withinDuration)
        
        // Remove old entries
        while (!restartTimes.isEmpty() && restartTimes.getFirst() < cutoff) {
            restartTimes.removeFirst()
        }
        
        return restartTimes.size()
    }
    
    /**
     * Calculate backoff delay based on restart count.
     * 
     * @param initialBackoff initial delay
     * @param maxBackoff maximum delay
     * @return backoff duration (exponential)
     */
    Duration calculateBackoff(Duration initialBackoff, Duration maxBackoff) {
        int count = getRestartCount()
        if (count == 0) {
            return initialBackoff
        }
        
        // Exponential backoff: initialBackoff * 2^(count-1)
        long millis = initialBackoff.toMillis() * (1L << (count - 1))
        long maxMillis = maxBackoff.toMillis()
        
        return Duration.ofMillis(Math.min(millis, maxMillis))
    }
    
    /**
     * Reset restart statistics.
     */
    synchronized void reset() {
        restartTimes.clear()
    }
    
    @Override
    String toString() {
        "RestartStats[count=${getRestartCount()}, max=$maxRestarts, within=$withinDuration]"
    }
}
