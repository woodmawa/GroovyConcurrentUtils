package org.softwood.dag.resilience

import java.time.Duration

/**
 * Exception thrown when a task execution exceeds its timeout.
 * This exception is non-retriable as timeouts indicate the task took too long.
 */
class TaskTimeoutException extends RuntimeException {
    
    final String taskId
    final Duration timeout
    final Duration elapsed
    
    TaskTimeoutException(String taskId, Duration timeout, Duration elapsed) {
        super("Task '${taskId}' timed out after ${elapsed} (timeout: ${timeout})")
        this.taskId = taskId
        this.timeout = timeout
        this.elapsed = elapsed
    }
    
    TaskTimeoutException(String message) {
        super(message)
        this.taskId = null
        this.timeout = null
        this.elapsed = null
    }
    
    TaskTimeoutException(String message, Throwable cause) {
        super(message, cause)
        this.taskId = null
        this.timeout = null
        this.elapsed = null
    }
}

/**
 * Exception thrown when a task graph execution exceeds its timeout.
 */
class GraphTimeoutException extends RuntimeException {
    
    final String graphId
    final Duration timeout
    final Duration elapsed
    final int completedTasks
    final int totalTasks
    
    GraphTimeoutException(String graphId, Duration timeout, Duration elapsed, 
                         int completedTasks, int totalTasks) {
        super("Graph '${graphId}' timed out after ${elapsed} (timeout: ${timeout}, completed: ${completedTasks}/${totalTasks})")
        this.graphId = graphId
        this.timeout = timeout
        this.elapsed = elapsed
        this.completedTasks = completedTasks
        this.totalTasks = totalTasks
    }
    
    GraphTimeoutException(String message) {
        super(message)
        this.graphId = null
        this.timeout = null
        this.elapsed = null
        this.completedTasks = 0
        this.totalTasks = 0
    }
    
    GraphTimeoutException(String message, Throwable cause) {
        super(message, cause)
        this.graphId = null
        this.timeout = null
        this.elapsed = null
        this.completedTasks = 0
        this.totalTasks = 0
    }
}
