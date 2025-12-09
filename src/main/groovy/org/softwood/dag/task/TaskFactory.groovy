package org.softwood.dag.task

import groovy.util.logging.Slf4j

/**
 * Factory for creating task instances.
 * Provides static factory methods for creating different types of tasks.
 * This enables easier mocking and testing.
 */
@Slf4j
class TaskFactory {

    /**
     * Create a ServiceTask
     */
    static ServiceTask createServiceTask(String id, String name, TaskContext ctx) {
        return new ServiceTask(id, name, ctx)
    }

    /**
     * Create a ConditionalForkTask
     */
    static ConditionalForkTask createConditionalForkTask(String id, String name, TaskContext ctx) {
        return new ConditionalForkTask(id, name, ctx)
    }

    /**
     * Create a DynamicRouterTask
     */
    static DynamicRouterTask createDynamicRouterTask(String id, String name, TaskContext ctx) {
        return new DynamicRouterTask(id, name, ctx)
    }

    /**
     * Create a ShardingRouterTask
     */
    static ShardingRouterTask createShardingRouterTask(String id, String name, TaskContext ctx) {
        return new ShardingRouterTask(id, name, ctx)
    }

    /**
     * Generic factory method - creates task based on type
     */
    static ITask createTask(String type, String id, String name, TaskContext ctx) {
        switch (type?.toLowerCase()) {
            case 'service':
                return createServiceTask(id, name, ctx)
            case 'conditionalfork':
            case 'conditional':
                return createConditionalForkTask(id, name, ctx)
            case 'dynamicrouter':
            case 'router':
                return createDynamicRouterTask(id, name, ctx)
            case 'shardingrouter':
            case 'sharding':
                return createShardingRouterTask(id, name, ctx)
            default:
                throw new IllegalArgumentException("Unknown task type: $type")
        }
    }
}
