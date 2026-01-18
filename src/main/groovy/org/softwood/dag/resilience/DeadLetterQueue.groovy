package org.softwood.dag.resilience

import groovy.util.logging.Slf4j
import org.softwood.dag.task.ITask
import org.softwood.dag.task.TaskContext

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Dead Letter Queue - captures failed tasks for retry and analysis.
 * 
 * <p>The Dead Letter Queue (DLQ) provides a safety net for task execution failures
 * by capturing failed tasks, their context, and failure details. This enables:</p>
 * <ul>
 *   <li>Post-mortem analysis of failures</li>
 *   <li>Manual or automated retry of failed tasks</li>
 *   <li>Failure pattern detection</li>
 *   <li>Debugging and monitoring</li>
 * </ul>
 * 
 * <h3>Thread Safety:</h3>
 * <p>This class is thread-safe and can be used concurrently by multiple tasks.</p>
 * 
 * <h3>Usage Examples:</h3>
 * <pre>
 * // Create DLQ with default settings
 * def dlq = new DeadLetterQueue()
 * 
 * // Create DLQ with policy
 * def dlq = new DeadLetterQueue(
 *     maxSize: 1000,
 *     maxAge: Duration.ofHours(24),
 *     autoRetry: true,
 *     maxRetries: 3
 * )
 * 
 * // Add failed task to DLQ
 * dlq.add(task, inputValue, exception)
 * 
 * // Query DLQ
 * def recentFailures = dlq.getEntriesByTaskId("api-task")
 * def allFailures = dlq.getAllEntries()
 * 
 * // Retry a specific entry
 * def result = dlq.retry("entry-123", ctx)
 * 
 * // Retry all entries for a task
 * def results = dlq.retryAllForTask("api-task", ctx)
 * 
 * // Get statistics
 * def stats = dlq.getStats()
 * println "Total failures: ${stats.totalEntries}"
 * println "Retry success rate: ${stats.retrySuccessRate}%"
 * </pre>
 */
@Slf4j
class DeadLetterQueue {
    
    // =========================================================================
    // Configuration
    // =========================================================================
    
    /** Maximum number of entries to keep (0 = unlimited) */
    int maxSize = 1000
    
    /** Maximum age of entries before auto-cleanup (null = no auto-cleanup) */
    Duration maxAge = null
    
    /** Enable automatic retry of failed entries */
    boolean autoRetry = false
    
    /** Maximum number of retry attempts per entry */
    int maxRetries = 3
    
    /** Callback when entry is added */
    Closure onEntryAdded
    
    /** Callback when entry is retried */
    Closure onEntryRetried
    
    /** Callback when entry is removed */
    Closure onEntryRemoved
    
    /** Callback when DLQ is full */
    Closure onQueueFull
    
    // =========================================================================
    // Internal Storage (Thread-Safe)
    // =========================================================================
    
    /** Main storage: entryId -> DeadLetterQueueEntry */
    private final ConcurrentHashMap<String, DeadLetterQueueEntry> entries = new ConcurrentHashMap<>()
    
    /** Index by task ID for fast lookup: taskId -> Set<entryId> */
    private final ConcurrentHashMap<String, Set<String>> taskIdIndex = new ConcurrentHashMap<>()
    
    /** Entry counter for generating unique IDs */
    private final AtomicLong entryCounter = new AtomicLong(0)
    
    // =========================================================================
    // Statistics (Thread-Safe)
    // =========================================================================
    
    /** Total number of entries ever added */
    private final AtomicLong totalEntriesAdded = new AtomicLong(0)
    
    /** Total number of entries removed */
    private final AtomicLong totalEntriesRemoved = new AtomicLong(0)
    
    /** Total number of successful retries */
    private final AtomicLong successfulRetries = new AtomicLong(0)
    
    /** Total number of failed retries */
    private final AtomicLong failedRetries = new AtomicLong(0)
    
    /** Number of times queue was full */
    private final AtomicInteger queueFullCount = new AtomicInteger(0)
    
    // =========================================================================
    // Constructor
    // =========================================================================
    
    DeadLetterQueue() {
    }
    
    DeadLetterQueue(Map config) {
        if (config.maxSize != null) this.maxSize = config.maxSize as int
        if (config.maxAge != null) this.maxAge = config.maxAge as Duration
        if (config.autoRetry != null) this.autoRetry = config.autoRetry as boolean
        if (config.maxRetries != null) this.maxRetries = config.maxRetries as int
    }
    
    // =========================================================================
    // Adding Entries
    // =========================================================================
    
    /**
     * Add a failed task to the DLQ.
     * 
     * @param task The failed task
     * @param inputValue The input value passed to the task
     * @param exception The exception that caused the failure
     * @param attemptCount Number of attempts before failure
     * @param runId Optional graph run ID
     * @param metadata Additional context metadata
     * @return The created DLQ entry ID
     */
    String add(
        ITask task,
        Object inputValue,
        Throwable exception,
        int attemptCount = 1,
        String runId = null,
        Map<String, Object> metadata = [:]) {
        
        // Check if queue is full
        if (maxSize > 0 && entries.size() >= maxSize) {
            handleQueueFull()
            // Optionally remove oldest entry to make room
            removeOldestEntry()
        }
        
        // Generate unique entry ID
        String entryId = generateEntryId()
        
        // Create entry
        def entry = new DeadLetterQueueEntry(
            entryId, task, inputValue, exception, attemptCount, runId, metadata
        )
        
        // Add to storage
        entries.put(entryId, entry)
        
        // Update task ID index
        taskIdIndex.computeIfAbsent(task.id, { k -> Collections.synchronizedSet(new HashSet<>()) })
            .add(entryId)
        
        // Update stats
        totalEntriesAdded.incrementAndGet()
        
        log.warn "DLQ: Added entry ${entryId} for task ${task.id}: ${exception.message}"
        
        // Trigger callback
        if (onEntryAdded) {
            try {
                onEntryAdded.call(entry)
            } catch (Exception e) {
                log.error "DLQ: Error in onEntryAdded callback", e
            }
        }
        
        return entryId
    }
    
    /**
     * Add entry with raw task information.
     */
    String add(
        String taskId,
        String taskName,
        String taskType,
        Object inputValue,
        Throwable exception,
        int attemptCount = 1,
        String runId = null,
        Map<String, Object> metadata = [:]) {
        
        if (maxSize > 0 && entries.size() >= maxSize) {
            handleQueueFull()
            removeOldestEntry()
        }
        
        String entryId = generateEntryId()
        
        def entry = new DeadLetterQueueEntry(
            entryId, taskId, taskName, taskType, inputValue, exception, attemptCount, runId, metadata
        )
        
        entries.put(entryId, entry)
        taskIdIndex.computeIfAbsent(taskId, { k -> Collections.synchronizedSet(new HashSet<>()) })
            .add(entryId)
        totalEntriesAdded.incrementAndGet()
        
        log.warn "DLQ: Added entry ${entryId} for task ${taskId}: ${exception.message}"
        
        if (onEntryAdded) {
            try {
                onEntryAdded.call(entry)
            } catch (Exception e) {
                log.error "DLQ: Error in onEntryAdded callback", e
            }
        }
        
        return entryId
    }
    
    // =========================================================================
    // Querying Entries
    // =========================================================================
    
    /**
     * Get entry by ID.
     */
    DeadLetterQueueEntry getEntry(String entryId) {
        return entries.get(entryId)
    }
    
    /**
     * Get all entries for a specific task.
     */
    List<DeadLetterQueueEntry> getEntriesByTaskId(String taskId) {
        def entryIds = taskIdIndex.get(taskId)
        if (!entryIds) {
            return []
        }
        return entryIds.collect { entries.get(it) }.findAll { it != null }
    }
    
    /**
     * Get all entries.
     */
    List<DeadLetterQueueEntry> getAllEntries() {
        return new ArrayList<>(entries.values())
    }
    
    /**
     * Get entries matching a filter.
     */
    List<DeadLetterQueueEntry> getEntriesWhere(Closure<Boolean> filter) {
        return entries.values().findAll(filter)
    }
    
    /**
     * Get entries newer than a timestamp.
     */
    List<DeadLetterQueueEntry> getEntriesNewerThan(Instant timestamp) {
        return entries.values().findAll { it.failureTimestamp.isAfter(timestamp) }
    }
    
    /**
     * Get entries by exception type.
     */
    List<DeadLetterQueueEntry> getEntriesByExceptionType(Class<? extends Throwable> exceptionType) {
        String typeName = exceptionType.name
        return entries.values().findAll { it.exceptionType == typeName }
    }
    
    /**
     * Check if queue contains an entry.
     */
    boolean contains(String entryId) {
        return entries.containsKey(entryId)
    }
    
    /**
     * Get current queue size.
     */
    int size() {
        return entries.size()
    }
    
    /**
     * Check if queue is empty.
     */
    boolean isEmpty() {
        return entries.isEmpty()
    }
    
    // =========================================================================
    // Retrying Entries
    // =========================================================================
    
    /**
     * Retry a specific entry.
     * 
     * @param entryId The entry ID to retry
     * @param ctx TaskContext for execution
     * @return RetryResult indicating success or failure
     */
    DeadLetterQueueEntry.RetryResult retry(String entryId, TaskContext ctx) {
        def entry = entries.get(entryId)
        if (!entry) {
            log.warn "DLQ: Cannot retry - entry ${entryId} not found"
            return DeadLetterQueueEntry.RetryResult.NOT_ATTEMPTED
        }
        
        if (!entry.canRetry(maxRetries)) {
            log.warn "DLQ: Cannot retry - entry ${entryId} has exceeded max retries (${maxRetries})"
            return DeadLetterQueueEntry.RetryResult.NOT_ATTEMPTED
        }
        
        log.info "DLQ: Retrying entry ${entryId} (attempt ${entry.retryCount + 1}/${maxRetries})"
        
        // Note: Actual retry logic would require reconstructing and executing the task
        // This is a placeholder that marks the retry attempt
        // In a real implementation, you'd need access to the task factory or task instance
        
        def result = DeadLetterQueueEntry.RetryResult.NOT_ATTEMPTED
        entry.recordRetry(result)
        
        if (onEntryRetried) {
            try {
                onEntryRetried.call(entry, result)
            } catch (Exception e) {
                log.error "DLQ: Error in onEntryRetried callback", e
            }
        }
        
        return result
    }
    
    /**
     * Retry all entries for a specific task.
     */
    Map<String, DeadLetterQueueEntry.RetryResult> retryAllForTask(String taskId, TaskContext ctx) {
        def results = [:]
        def entryIds = taskIdIndex.get(taskId)
        
        if (!entryIds) {
            log.info "DLQ: No entries found for task ${taskId}"
            return results
        }
        
        entryIds.each { entryId ->
            results[entryId] = retry(entryId, ctx)
        }
        
        return results
    }
    
    /**
     * Retry all entries in the queue.
     */
    Map<String, DeadLetterQueueEntry.RetryResult> retryAll(TaskContext ctx) {
        def results = [:]
        entries.keySet().each { entryId ->
            results[entryId] = retry(entryId, ctx)
        }
        return results
    }
    
    // =========================================================================
    // Removing Entries
    // =========================================================================
    
    /**
     * Remove an entry by ID.
     */
    boolean remove(String entryId) {
        def entry = entries.remove(entryId)
        if (entry) {
            // Update task ID index
            def entryIds = taskIdIndex.get(entry.taskId)
            if (entryIds) {
                entryIds.remove(entryId)
                if (entryIds.isEmpty()) {
                    taskIdIndex.remove(entry.taskId)
                }
            }
            
            totalEntriesRemoved.incrementAndGet()
            
            log.debug "DLQ: Removed entry ${entryId}"
            
            if (onEntryRemoved) {
                try {
                    onEntryRemoved.call(entry)
                } catch (Exception e) {
                    log.error "DLQ: Error in onEntryRemoved callback", e
                }
            }
            
            return true
        }
        return false
    }
    
    /**
     * Remove all entries for a specific task.
     */
    int removeAllForTask(String taskId) {
        def entryIds = taskIdIndex.get(taskId)
        if (!entryIds) {
            return 0
        }
        
        int count = 0
        // Create copy to avoid concurrent modification
        new ArrayList<>(entryIds).each { entryId ->
            if (remove(entryId)) {
                count++
            }
        }
        
        log.info "DLQ: Removed ${count} entries for task ${taskId}"
        return count
    }
    
    /**
     * Remove entries older than max age.
     */
    int removeExpiredEntries() {
        if (!maxAge) {
            return 0
        }
        
        Instant cutoff = Instant.now().minus(maxAge)
        def expiredIds = entries.values()
            .findAll { it.failureTimestamp.isBefore(cutoff) }
            .collect { it.entryId }
        
        int count = 0
        expiredIds.each { entryId ->
            if (remove(entryId)) {
                count++
            }
        }
        
        if (count > 0) {
            log.info "DLQ: Removed ${count} expired entries"
        }
        
        return count
    }
    
    /**
     * Clear all entries.
     */
    void clear() {
        int count = entries.size()
        entries.clear()
        taskIdIndex.clear()
        log.info "DLQ: Cleared ${count} entries"
    }
    
    // =========================================================================
    // Private Helpers
    // =========================================================================
    
    /**
     * Generate unique entry ID.
     */
    private String generateEntryId() {
        long counter = entryCounter.incrementAndGet()
        long timestamp = System.currentTimeMillis()
        return "dlq-${timestamp}-${counter}"
    }
    
    /**
     * Handle queue full condition.
     */
    private void handleQueueFull() {
        queueFullCount.incrementAndGet()
        log.warn "DLQ: Queue is full (size: ${entries.size()}, max: ${maxSize})"
        
        if (onQueueFull) {
            try {
                onQueueFull.call(entries.size(), maxSize)
            } catch (Exception e) {
                log.error "DLQ: Error in onQueueFull callback", e
            }
        }
    }
    
    /**
     * Remove oldest entry to make room.
     */
    private void removeOldestEntry() {
        def oldest = entries.values()
            .min { it.failureTimestamp }
        
        if (oldest) {
            remove(oldest.entryId)
            log.debug "DLQ: Removed oldest entry ${oldest.entryId} to make room"
        }
    }
    
    // =========================================================================
    // Statistics
    // =========================================================================
    
    /**
     * Get DLQ statistics.
     */
    Map<String, Object> getStats() {
        def currentSize = entries.size()
        def totalAdded = totalEntriesAdded.get()
        def totalRemoved = totalEntriesRemoved.get()
        def successful = successfulRetries.get()
        def failed = failedRetries.get()
        def totalRetries = successful + failed
        
        return [
            currentSize: currentSize,
            maxSize: maxSize,
            utilizationPercent: maxSize > 0 ? (currentSize / (double) maxSize) * 100.0 : 0.0,
            totalEntriesAdded: totalAdded,
            totalEntriesRemoved: totalRemoved,
            successfulRetries: successful,
            failedRetries: failed,
            totalRetries: totalRetries,
            retrySuccessRate: totalRetries > 0 ? (successful / (double) totalRetries) * 100.0 : 0.0,
            queueFullCount: queueFullCount.get(),
            uniqueTaskIds: taskIdIndex.size(),
            hasMaxAge: maxAge != null,
            autoRetryEnabled: autoRetry
        ]
    }
    
    /**
     * Get summary by task ID.
     */
    Map<String, Integer> getTaskSummary() {
        def summary = [:]
        taskIdIndex.each { taskId, entryIds ->
            summary[taskId] = entryIds.size()
        }
        return summary
    }
    
    /**
     * Get summary by exception type.
     */
    Map<String, Integer> getExceptionSummary() {
        def summary = [:]
        entries.values().each { entry ->
            summary[entry.exceptionType] = (summary[entry.exceptionType] ?: 0) + 1
        }
        return summary
    }
}
