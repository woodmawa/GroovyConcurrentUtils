package org.softwood.dag.resilience

/**
 * Exception thrown when a resource limit is exceeded during graph execution.
 * 
 * Resource limits include:
 * - Maximum concurrent tasks
 * - Maximum memory usage
 * - Maximum CPU usage
 * - Task queue limits
 */
class ResourceLimitExceededException extends RuntimeException {
    
    final String resourceType
    final long currentValue
    final long limitValue
    final String graphId
    
    /**
     * Create a resource limit exception with detailed information.
     * 
     * @param resourceType Type of resource (e.g., "concurrent_tasks", "memory_mb")
     * @param currentValue Current resource usage
     * @param limitValue Configured limit
     * @param graphId Graph identifier
     */
    ResourceLimitExceededException(String resourceType, long currentValue, long limitValue, String graphId = null) {
        super("Resource limit exceeded: ${resourceType} (current: ${currentValue}, limit: ${limitValue})")
        this.resourceType = resourceType
        this.currentValue = currentValue
        this.limitValue = limitValue
        this.graphId = graphId
    }
    
    /**
     * Simple constructor for custom messages.
     */
    ResourceLimitExceededException(String message) {
        super(message)
        this.resourceType = null
        this.currentValue = 0
        this.limitValue = 0
        this.graphId = null
    }
    
    /**
     * Constructor with cause.
     */
    ResourceLimitExceededException(String message, Throwable cause) {
        super(message, cause)
        this.resourceType = null
        this.currentValue = 0
        this.limitValue = 0
        this.graphId = null
    }
}
