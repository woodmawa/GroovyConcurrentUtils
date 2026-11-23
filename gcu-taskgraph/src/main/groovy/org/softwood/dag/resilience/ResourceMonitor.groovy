package org.softwood.dag.resilience

import groovy.util.logging.Slf4j
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Monitors and enforces resource limits during graph execution.
 * 
 * Thread-safe implementation using semaphores and atomic counters.
 * 
 * Usage:
 * <pre>
 * def monitor = new ResourceMonitor(policy)
 * monitor.acquireTaskSlot()  // Blocks until slot available
 * try {
 *     // Execute task
 * } finally {
 *     monitor.releaseTaskSlot()
 * }
 * </pre>
 */
@Slf4j
class ResourceMonitor {
    
    private final ResourceLimitPolicy policy
    private final Semaphore concurrencySemaphore
    private final AtomicInteger runningTasks = new AtomicInteger(0)
    private final AtomicInteger queuedTasks = new AtomicInteger(0)
    
    private volatile boolean memoryWarningIssued = false
    private volatile long lastMemoryCheck = 0
    
    /**
     * Create a resource monitor with the given policy.
     */
    ResourceMonitor(ResourceLimitPolicy policy) {
        this.policy = policy
        
        // Initialize concurrency semaphore if limit is set
        if (policy.hasConcurrencyLimit()) {
            this.concurrencySemaphore = new Semaphore(policy.maxConcurrentTasks, true)
            log.debug "ResourceMonitor: concurrency limit set to ${policy.maxConcurrentTasks}"
        } else {
            this.concurrencySemaphore = null
        }
    }
    
    /**
     * Acquire a slot for task execution.
     * Blocks until a slot is available (unless failFast is enabled).
     * 
     * @throws ResourceLimitExceededException if limits are exceeded and failFast is enabled
     */
    void acquireTaskSlot() {
        // Check queue limit first
        if (policy.hasQueueLimit()) {
            int queued = queuedTasks.incrementAndGet()
            if (queued > policy.maxQueuedTasks) {
                queuedTasks.decrementAndGet()
                policy.notifyLimitExceeded("queued_tasks", queued, policy.maxQueuedTasks)
                throw new ResourceLimitExceededException(
                    "queued_tasks",
                    queued,
                    policy.maxQueuedTasks
                )
            }
        }
        
        // Try to acquire concurrency slot
        if (concurrencySemaphore != null) {
            if (policy.failFastOnConcurrency) {
                // Non-blocking: fail immediately if no slots available
                if (!concurrencySemaphore.tryAcquire()) {
                    queuedTasks.decrementAndGet()
                    int running = runningTasks.get()
                    policy.notifyLimitExceeded("concurrent_tasks", running, policy.maxConcurrentTasks)
                    throw new ResourceLimitExceededException(
                        "concurrent_tasks",
                        running,
                        policy.maxConcurrentTasks
                    )
                }
            } else {
                // Blocking: wait for a slot to become available
                try {
                    concurrencySemaphore.acquire()
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt()
                    queuedTasks.decrementAndGet()
                    throw new RuntimeException("Interrupted while waiting for task slot", e)
                }
            }
            
            int running = runningTasks.incrementAndGet()
            queuedTasks.decrementAndGet()
            
            log.debug "Task slot acquired (running: $running/${policy.maxConcurrentTasks})"
        }
        
        // Check memory limit
        checkMemoryLimit()
    }
    
    /**
     * Release a task slot after execution completes.
     */
    void releaseTaskSlot() {
        if (concurrencySemaphore != null) {
            int running = runningTasks.decrementAndGet()
            concurrencySemaphore.release()
            log.debug "Task slot released (running: $running/${policy.maxConcurrentTasks})"
        }
    }
    
    /**
     * Check if memory limit is exceeded.
     * Uses throttled checking to avoid performance overhead.
     */
    private void checkMemoryLimit() {
        if (!policy.hasMemoryLimit()) return
        
        long now = System.currentTimeMillis()
        long interval = policy.memoryCheckInterval.toMillis()
        
        // Throttle memory checks
        if (now - lastMemoryCheck < interval) {
            return
        }
        
        lastMemoryCheck = now
        
        Runtime runtime = Runtime.getRuntime()
        long usedMemoryMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        
        if (usedMemoryMB > policy.maxMemoryMB) {
            policy.notifyLimitExceeded("memory_mb", usedMemoryMB, policy.maxMemoryMB)
            
            if (policy.failFastOnMemory) {
                throw new ResourceLimitExceededException(
                    "memory_mb",
                    usedMemoryMB,
                    policy.maxMemoryMB
                )
            } else if (!memoryWarningIssued) {
                log.warn "Memory limit exceeded: ${usedMemoryMB}MB / ${policy.maxMemoryMB}MB (continuing)"
                memoryWarningIssued = true
            }
        }
    }
    
    /**
     * Get current number of running tasks.
     */
    int getRunningTaskCount() {
        return runningTasks.get()
    }
    
    /**
     * Get current number of queued tasks.
     */
    int getQueuedTaskCount() {
        return queuedTasks.get()
    }
    
    /**
     * Get current memory usage in MB.
     */
    long getCurrentMemoryMB() {
        Runtime runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    }
    
    /**
     * Get resource usage statistics.
     */
    Map<String, Object> getStats() {
        return [
            runningTasks: runningTasks.get(),
            queuedTasks: queuedTasks.get(),
            memoryMB: getCurrentMemoryMB(),
            maxConcurrent: policy.maxConcurrentTasks,
            maxMemory: policy.maxMemoryMB,
            maxQueue: policy.maxQueuedTasks
        ]
    }
    
    @Override
    String toString() {
        return "ResourceMonitor(running=${runningTasks.get()}, queued=${queuedTasks.get()}, memory=${getCurrentMemoryMB()}MB)"
    }
}
