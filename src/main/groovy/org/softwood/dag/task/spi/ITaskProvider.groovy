package org.softwood.dag.task.spi

import org.softwood.dag.task.ITask
import org.softwood.dag.task.TaskContext

/**
 * Service Provider Interface (SPI) for custom task types.
 */
interface ITaskProvider {
    
    /**
     * Get the primary type name for tasks created by this provider.
     */
    String getTaskTypeName()
    
    /**
     * Get the concrete task class this provider creates.
     */
    Class<? extends ITask> getTaskClass()
    
    /**
     * Create a new task instance.
     */
    ITask createTask(String id, String name, TaskContext ctx)
    
    /**
     * Check if this provider supports the given type string.
     */
    boolean supports(String typeString)
    
    /**
     * Get priority for this provider (higher wins in case of conflicts).
     */
    default int getPriority() {
        return 0
    }
    
    /**
     * Get optional metadata about this provider.
     */
    default Map<String, Object> getMetadata() {
        return [:]
    }
}
