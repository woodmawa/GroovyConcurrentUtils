package org.softwood.dag.task

/**
 * Enumeration of task types supported by the TaskFactory.
 *
 * <p>Task types are divided into two main categories:</p>
 * <ul>
 *   <li><b>Service Tasks</b> - Regular computational tasks that execute work</li>
 *   <li><b>Decision Tasks</b> - Control flow tasks that route execution</li>
 * </ul>
 */
enum TaskType {

    // =========================================================================
    // Service Tasks (ITask)
    // =========================================================================

    /**
     * Standard service task that executes business logic.
     * Returns a single result.
     */
    SERVICE(ServiceTask, false),

    // =========================================================================
    // Decision Tasks (IDecisionTask)
    // =========================================================================

    /**
     * Conditional fork that branches execution based on a predicate.
     * Routes to different downstream tasks based on condition evaluation.
     */
    CONDITIONAL_FORK(ConditionalForkTask, true),

    /**
     * Dynamic router that selects downstream tasks at runtime.
     * Routing decision is made based on task result or context.
     */
    DYNAMIC_ROUTER(DynamicRouterTask, true),

    /**
     * Sharding router that distributes work across multiple parallel paths.
     * Useful for parallel processing and load distribution.
     */
    SHARDING_ROUTER(ShardingRouterTask, true)

    // =========================================================================
    // Enum Properties
    // =========================================================================

    /** The concrete class for this task type */
    final Class<? extends ITask> taskClass

    /** Whether this is a decision/routing task (private field) */
    private final boolean _isDecisionTask

    /**
     * Constructor
     */
    TaskType(Class<? extends ITask> taskClass, boolean isDecisionTask) {
        this.taskClass = taskClass
        this._isDecisionTask = isDecisionTask
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    /**
     * Get the simple class name for this task type.
     * @return class name without package
     */
    String getClassName() {
        return taskClass.simpleName
    }

    /**
     * Check if this is a service task (not a decision task).
     * @return true if service task
     */
    boolean isServiceTask() {
        return !_isDecisionTask
    }

    /**
     * Check if this is a decision task (routing/branching).
     * @return true if decision task
     */
    boolean isDecisionTask() {
        return _isDecisionTask
    }

    /**
     * Parse a task type from a string (case-insensitive).
     * Supports both enum names and friendly names.
     *
     * @param type string representation
     * @return TaskType enum value
     * @throws IllegalArgumentException if type not found
     */
    static TaskType fromString(String type) {
        if (!type) {
            throw new IllegalArgumentException("Task type cannot be null or empty")
        }

        // Try exact enum name match first
        try {
            return TaskType.valueOf(type.toUpperCase().replace('-', '_'))
        } catch (IllegalArgumentException e) {
            // Fall through to friendly name matching
        }

        // Try friendly name matching
        switch (type.toLowerCase().replace('_', '').replace('-', '')) {
            case 'service':
                return SERVICE
            case 'conditional':
            case 'conditionalfork':
            case 'fork':
                return CONDITIONAL_FORK
            case 'dynamicrouter':
            case 'dynamic':
                return DYNAMIC_ROUTER
            case 'sharding':
            case 'shardingrouter':
            case 'shard':
                return SHARDING_ROUTER
            default:
                throw new IllegalArgumentException(
                        "Unknown task type: '$type'. Valid types: ${values()*.name().join(', ')}"
                )
        }
    }

    /**
     * Get all service task types.
     * @return list of service task types
     */
    static List<TaskType> getServiceTasks() {
        return values().findAll { it.isServiceTask() }
    }

    /**
     * Get all decision task types.
     * @return list of decision task types
     */
    static List<TaskType> getDecisionTasks() {
        return values().findAll { it.isDecisionTask() }
    }

    @Override
    String toString() {
        return name().toLowerCase().replace('_', '-')
    }
}