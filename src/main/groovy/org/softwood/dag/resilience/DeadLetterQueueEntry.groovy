package org.softwood.dag.resilience

import org.softwood.dag.task.ITask
import org.softwood.dag.task.TaskContext

import java.time.Instant

/**
 * Represents a failed task entry in the Dead Letter Queue.
 * 
 * <p>Captures comprehensive information about task failure including:</p>
 * <ul>
 *   <li>Task details (id, name, type)</li>
 *   <li>Failure information (exception, stacktrace, timestamp)</li>
 *   <li>Execution context (input value, attempt count)</li>
 *   <li>Retry tracking (retry count, last retry time)</li>
 * </ul>
 */
class DeadLetterQueueEntry implements Serializable {
    private static final long serialVersionUID = 1L
    
    // =========================================================================
    // Task Information
    // =========================================================================
    
    /** Unique identifier for this DLQ entry */
    final String entryId
    
    /** ID of the failed task */
    final String taskId
    
    /** Name of the failed task */
    final String taskName
    
    /** Class name of the task */
    final String taskType
    
    // =========================================================================
    // Failure Information
    // =========================================================================
    
    /** The exception that caused the failure */
    final Throwable exception
    
    /** Exception class name (for serialization safety) */
    final String exceptionType
    
    /** Exception message */
    final String exceptionMessage
    
    /** Full stack trace */
    final String stackTrace
    
    /** Timestamp when failure occurred */
    final Instant failureTimestamp
    
    // =========================================================================
    // Execution Context
    // =========================================================================
    
    /** Input value that was passed to the task */
    final Object inputValue
    
    /** Number of execution attempts before failure */
    final int attemptCount
    
    /** Graph run ID (if available) */
    final String runId
    
    /** Additional context metadata */
    final Map<String, Object> metadata
    
    // =========================================================================
    // Retry Tracking
    // =========================================================================
    
    /** Number of times this entry has been retried */
    int retryCount = 0
    
    /** Timestamp of last retry attempt */
    Instant lastRetryTimestamp
    
    /** Result of last retry (if any) */
    RetryResult lastRetryResult
    
    // =========================================================================
    // Constructor
    // =========================================================================
    
    DeadLetterQueueEntry(
        String entryId,
        ITask task,
        Object inputValue,
        Throwable exception,
        int attemptCount = 1,
        String runId = null,
        Map<String, Object> metadata = [:]) {
        
        this.entryId = entryId
        this.taskId = task.id
        this.taskName = task.name
        this.taskType = task.class.simpleName
        
        this.exception = exception
        this.exceptionType = exception.class.name
        this.exceptionMessage = exception.message ?: exception.class.simpleName
        this.stackTrace = getStackTraceAsString(exception)
        this.failureTimestamp = Instant.now()
        
        this.inputValue = inputValue
        this.attemptCount = attemptCount
        this.runId = runId
        this.metadata = metadata ? new LinkedHashMap<>(metadata) : [:]
    }
    
    /**
     * Create entry from raw task information (when ITask not available).
     */
    DeadLetterQueueEntry(
        String entryId,
        String taskId,
        String taskName,
        String taskType,
        Object inputValue,
        Throwable exception,
        int attemptCount = 1,
        String runId = null,
        Map<String, Object> metadata = [:]) {
        
        this.entryId = entryId
        this.taskId = taskId
        this.taskName = taskName
        this.taskType = taskType
        
        this.exception = exception
        this.exceptionType = exception.class.name
        this.exceptionMessage = exception.message ?: exception.class.simpleName
        this.stackTrace = getStackTraceAsString(exception)
        this.failureTimestamp = Instant.now()
        
        this.inputValue = inputValue
        this.attemptCount = attemptCount
        this.runId = runId
        this.metadata = metadata ? new LinkedHashMap<>(metadata) : [:]
    }
    
    // =========================================================================
    // Retry Tracking
    // =========================================================================
    
    /**
     * Record a retry attempt.
     */
    void recordRetry(RetryResult result) {
        this.retryCount++
        this.lastRetryTimestamp = Instant.now()
        this.lastRetryResult = result
    }
    
    /**
     * Check if this entry can be retried.
     */
    boolean canRetry(int maxRetries) {
        return retryCount < maxRetries
    }
    
    // =========================================================================
    // Utility Methods
    // =========================================================================
    
    /**
     * Get stack trace as string.
     */
    private static String getStackTraceAsString(Throwable t) {
        def sw = new StringWriter()
        t.printStackTrace(new PrintWriter(sw))
        return sw.toString()
    }
    
    /**
     * Get a summary of this entry.
     */
    String getSummary() {
        return "[${entryId}] Task ${taskId} (${taskType}) failed at ${failureTimestamp}: ${exceptionMessage}"
    }
    
    /**
     * Get detailed information.
     */
    Map<String, Object> getDetails() {
        return [
            entryId: entryId,
            taskId: taskId,
            taskName: taskName,
            taskType: taskType,
            exceptionType: exceptionType,
            exceptionMessage: exceptionMessage,
            failureTimestamp: failureTimestamp,
            attemptCount: attemptCount,
            retryCount: retryCount,
            lastRetryTimestamp: lastRetryTimestamp,
            lastRetryResult: lastRetryResult,
            runId: runId,
            hasInput: inputValue != null,
            metadata: metadata
        ]
    }
    
    @Override
    String toString() {
        return getSummary()
    }
    
    // =========================================================================
    // Retry Result Enum
    // =========================================================================
    
    enum RetryResult {
        /** Retry succeeded */
        SUCCESS,
        
        /** Retry failed with same error */
        FAILED_SAME_ERROR,
        
        /** Retry failed with different error */
        FAILED_DIFFERENT_ERROR,
        
        /** Retry not attempted */
        NOT_ATTEMPTED
    }
}
