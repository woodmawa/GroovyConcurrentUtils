package org.softwood.dag.task

import groovy.util.logging.Slf4j

/**
 * Factory for creating task instances.
 *
 * <p>Provides static factory methods for creating different types of tasks
 * with a clean, type-safe API. This enables easier mocking, testing, and
 * consistent task creation across the application.</p>
 *
 * <h3>Usage Examples:</h3>
 * <pre>
 * // Using TaskType enum (recommended)
 * def task = TaskFactory.createTask(TaskType.SERVICE, "task1", "My Task", ctx)
 *
 * // Using specific factory methods
 * def service = TaskFactory.createServiceTask("task1", "My Task", ctx)
 * def router = TaskFactory.createDynamicRouter("router1", "Route Logic", ctx)
 *
 * // Using string type (for DSL/config)
 * def task = TaskFactory.createTask("service", "task1", "My Task", ctx)
 * </pre>
 */
@Slf4j
class TaskFactory {

    // =========================================================================
    // Service Task Creation
    // =========================================================================

    /**
     * Create a ServiceTask - standard work execution task.
     *
     * @param id unique task identifier
     * @param name human-readable task name
     * @param ctx task execution context
     * @return new ServiceTask instance
     */
    static ServiceTask createServiceTask(String id, String name, TaskContext ctx) {
        log.debug("Creating ServiceTask: id=$id, name=$name")
        return new ServiceTask(id, name, ctx)
    }

    /**
     * Create a ManualTask - human interaction task.
     *
     * @param id unique task identifier
     * @param name human-readable task name
     * @param ctx task execution context
     * @return new ManualTask instance
     */
    static ManualTask createManualTask(String id, String name, TaskContext ctx) {
        log.debug("Creating ManualTask: id=$id, name=$name")
        return new ManualTask(id, name, ctx)
    }

    /**
     * Create a SignalTask - event coordination task.
     *
     * @param id unique task identifier
     * @param name human-readable task name
     * @param ctx task execution context
     * @return new SignalTask instance
     */
    static SignalTask createSignalTask(String id, String name, TaskContext ctx) {
        log.debug("Creating SignalTask: id=$id, name=$name")
        return new SignalTask(id, name, ctx)
    }

    /**
     * Create a SubGraphTask - reusable workflow template task.
     *
     * @param id unique task identifier
     * @param name human-readable task name
     * @param ctx task execution context
     * @return new SubGraphTask instance
     */
    static SubGraphTask createSubGraphTask(String id, String name, TaskContext ctx) {
        log.debug("Creating SubGraphTask: id=$id, name=$name")
        return new SubGraphTask(id, name, ctx)
    }

    // =========================================================================
    // Decision Task Creation
    // =========================================================================

    /**
     * Create a ConditionalForkTask - branches execution based on condition.
     *
     * @param id unique task identifier
     * @param name human-readable task name
     * @param ctx task execution context
     * @return new ConditionalForkTask instance
     */
    static ConditionalForkTask createConditionalFork(String id, String name, TaskContext ctx) {
        log.debug("Creating ConditionalForkTask: id=$id, name=$name")
        return new ConditionalForkTask(id, name, ctx)
    }

    /**
     * Create a DynamicRouterTask - routes to different paths at runtime.
     *
     * @param id unique task identifier
     * @param name human-readable task name
     * @param ctx task execution context
     * @return new DynamicRouterTask instance
     */
    static DynamicRouterTask createDynamicRouter(String id, String name, TaskContext ctx) {
        log.debug("Creating DynamicRouterTask: id=$id, name=$name")
        return new DynamicRouterTask(id, name, ctx)
    }

    /**
     * Create a ShardingRouterTask - distributes work across parallel paths.
     *
     * @param id unique task identifier
     * @param name human-readable task name
     * @param ctx task execution context
     * @return new ShardingRouterTask instance
     */
    static ShardingRouterTask createShardingRouter(String id, String name, TaskContext ctx) {
        log.debug("Creating ShardingRouterTask: id=$id, name=$name")
        return new ShardingRouterTask(id, name, ctx)
    }

    /**
     * Create an ExclusiveGatewayTask - XOR routing (first match wins).
     *
     * @param id unique task identifier
     * @param name human-readable task name
     * @param ctx task execution context
     * @return new ExclusiveGatewayTask instance
     */
    static ExclusiveGatewayTask createExclusiveGateway(String id, String name, TaskContext ctx) {
        log.debug("Creating ExclusiveGatewayTask: id=$id, name=$name")
        return new ExclusiveGatewayTask(id, name, ctx)
    }

    /**
     * Create a SwitchRouterTask - switch/case style routing.
     *
     * @param id unique task identifier
     * @param name human-readable task name
     * @param ctx task execution context
     * @return new SwitchRouterTask instance
     */
    static SwitchRouterTask createSwitchRouter(String id, String name, TaskContext ctx) {
        log.debug("Creating SwitchRouterTask: id=$id, name=$name")
        return new SwitchRouterTask(id, name, ctx)
    }

    // =========================================================================
    // Type-Safe Factory Methods (Using Enum)
    // =========================================================================

    /**
     * Create a task using TaskType enum (RECOMMENDED).
     * Type-safe factory method that uses the TaskType enum.
     *
     * @param type task type from TaskType enum
     * @param id unique task identifier
     * @param name human-readable task name
     * @param ctx task execution context
     * @return new task instance of the specified type
     */
    static ITask createTask(TaskType type, String id, String name, TaskContext ctx) {
        log.debug("Creating task: type=${type.name()}, id=$id, name=$name")

        switch (type) {
            case TaskType.SERVICE:
                return createServiceTask(id, name, ctx)

            case TaskType.MANUAL:
                return createManualTask(id, name, ctx)

            case TaskType.SIGNAL:
                return createSignalTask(id, name, ctx)

            case TaskType.SUBGRAPH:
                return createSubGraphTask(id, name, ctx)

            case TaskType.CONDITIONAL_FORK:
                return createConditionalFork(id, name, ctx)

            case TaskType.DYNAMIC_ROUTER:
                return createDynamicRouter(id, name, ctx)

            case TaskType.SHARDING_ROUTER:
                return createShardingRouter(id, name, ctx)

            case TaskType.EXCLUSIVE_GATEWAY:
                return createExclusiveGateway(id, name, ctx)

            case TaskType.SWITCH_ROUTER:
                return createSwitchRouter(id, name, ctx)

            default:
                throw new IllegalArgumentException("Unsupported task type: $type")
        }
    }

    /**
     * Create a task using string type (for DSL/configuration).
     * Less type-safe but useful for DSLs and configuration files.
     *
     * @param typeString task type as string (case-insensitive)
     * @param id unique task identifier
     * @param name human-readable task name
     * @param ctx task execution context
     * @return new task instance of the specified type
     * @throws IllegalArgumentException if type string is invalid
     */
    static ITask createTask(String typeString, String id, String name, TaskContext ctx) {
        TaskType type = TaskType.fromString(typeString)
        return createTask(type, id, name, ctx)
    }

    // =========================================================================
    // Builder-Style Creation (Fluent API)
    // =========================================================================

    /**
     * Start building a task with fluent API.
     *
     * <pre>
     * def task = TaskFactory.builder()
     *     .type(TaskType.SERVICE)
     *     .id("task1")
     *     .name("My Task")
     *     .context(ctx)
     *     .build()
     * </pre>
     *
     * @return new TaskBuilder instance
     */
    static TaskBuilder builder() {
        return new TaskBuilder()
    }

    /**
     * Builder for fluent task creation.
     */
    static class TaskBuilder {
        private TaskType type
        private String id
        private String name
        private TaskContext context

        TaskBuilder type(TaskType type) {
            this.type = type
            return this
        }

        TaskBuilder type(String typeString) {
            this.type = TaskType.fromString(typeString)
            return this
        }

        TaskBuilder id(String id) {
            this.id = id
            return this
        }

        TaskBuilder name(String name) {
            this.name = name
            return this
        }

        TaskBuilder context(TaskContext context) {
            this.context = context
            return this
        }

        ITask build() {
            if (!type) throw new IllegalStateException("Task type not set")
            if (!id) throw new IllegalStateException("Task id not set")
            if (!name) throw new IllegalStateException("Task name not set")
            if (!context) throw new IllegalStateException("Task context not set")

            return createTask(type, id, name, context)
        }
    }

    // =========================================================================
    // Batch Creation Helpers
    // =========================================================================

    /**
     * Create multiple tasks of the same type.
     * Useful for parallel task creation.
     *
     * @param type task type
     * @param ids list of task IDs
     * @param namePrefix prefix for task names (id will be appended)
     * @param ctx shared task context
     * @return list of created tasks
     */
    static List<ITask> createTasks(TaskType type, List<String> ids, String namePrefix, TaskContext ctx) {
        return ids.collect { id ->
            createTask(type, id, "${namePrefix}_${id}", ctx)
        }
    }

    /**
     * Create a map of tasks indexed by ID.
     * Convenient for quick task lookup.
     *
     * @param type task type
     * @param ids list of task IDs
     * @param namePrefix prefix for task names
     * @param ctx shared task context
     * @return map of id -> task
     */
    static Map<String, ITask> createTaskMap(TaskType type, List<String> ids, String namePrefix, TaskContext ctx) {
        return ids.collectEntries { id ->
            [(id): createTask(type, id, "${namePrefix}_${id}", ctx)]
        }
    }

    // =========================================================================
    // Validation & Introspection
    // =========================================================================

    /**
     * Check if a task type string is valid.
     *
     * @param typeString type to validate
     * @return true if valid, false otherwise
     */
    static boolean isValidTaskType(String typeString) {
        try {
            TaskType.fromString(typeString)
            return true
        } catch (IllegalArgumentException e) {
            return false
        }
    }

    /**
     * Get all available task types.
     *
     * @return list of all TaskType values
     */
    static List<TaskType> getAvailableTypes() {
        return TaskType.values() as List
    }

    /**
     * Get task types filtered by category.
     *
     * @param decisionTasks if true, return only decision tasks; if false, return only service tasks
     * @return filtered list of task types
     */
    static List<TaskType> getTaskTypes(boolean decisionTasks) {
        return decisionTasks ? TaskType.getDecisionTasks() : TaskType.getServiceTasks()
    }
}