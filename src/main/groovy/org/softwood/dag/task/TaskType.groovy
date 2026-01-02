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

    /**
     * Manual task that pauses workflow for human intervention.
     * Requires external completion via complete() method.
     */
    MANUAL(ManualTask, false),

    /**
     * Signal task for event coordination and synchronization.
     * Can wait for or send signals to coordinate parallel branches.
     */
    SIGNAL(SignalTask, false),

    /**
     * Sub-graph task for embedding reusable workflow templates.
     * Enables workflow composition and modular design.
     */
    SUBGRAPH(SubGraphTask, false),

    /**
     * Script task for executing scripts in multiple languages.
     * Supports JavaScript, Groovy, Python, and other JSR 223 engines.
     */
    SCRIPT(ScriptTask, false),

    /**
     * Send task for sending messages/events to external systems.
     * Supports HTTP, webhooks, message queues, and other protocols.
     */
    SEND(SendTask, false),

    /**
     * Receive task for waiting for external messages/events.
     * Supports webhooks, message queues, and async responses.
     */
    RECEIVE(ReceiveTask, false),

    /**
     * Timer task for periodic or scheduled execution.
     * Supports fixed interval, fixed rate, and multiple stop conditions.
     */
    TIMER(TimerTask, false),

    /**
     * Business rule task for condition-based reactive execution.
     * Executes when business rules are satisfied via signals or polling.
     */
    BUSINESS_RULE(BusinessRuleTask, false),

    /**
     * Data transform task for functional data transformation pipelines.
     * Supports map, filter, reduce, flatMap, and tap inspection points.
     */
    DATA_TRANSFORM(DataTransformTask, false),

    /**
     * Call activity task for invoking subprocesses/subgraphs.
     * Supports input/output mapping and subprocess composition.
     */
    CALL_ACTIVITY(CallActivityTask, false),

    /**
     * Loop task for iteration over collections.
     * Supports sequential/parallel execution, break conditions, and accumulators.
     */
    LOOP(LoopTask, false),

    /**
     * File task for processing files with rich DSL support.
     * Supports file discovery, filtering, per-file processing with GDK delegation,
     * pipeline visibility via tap(), and execution summaries.
     */
    FILE(FileTask, false),

    /**
     * Messaging task for sending/receiving messages via Kafka, AMQP, Vert.x, or in-memory queues.
     * ZERO DEPENDENCIES: Uses InMemoryProducer/Consumer by default.
     * Also supports VertxEventBusProducer (existing Vert.x dependency).
     */
    MESSAGING(MessagingTask, false),

    /**
     * SQL database task for executing queries and updates.
     * ZERO DEPENDENCIES: Uses JDBC (built into JDK) by default.
     * Add groovy-sql, H2, or HikariCP for enhanced features.
     */
    SQL(SqlTask, false),

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
    SHARDING_ROUTER(ShardingRouterTask, true),

    /**
     * Exclusive gateway (XOR) that routes to exactly ONE path.
     * First matching condition wins (priority-ordered evaluation).
     * BPMN Exclusive Gateway equivalent.
     */
    EXCLUSIVE_GATEWAY(ExclusiveGatewayTask, true),

    /**
     * Switch-case style router that routes based on discrete value matching.
     * Extracts a value and matches it against registered cases.
     * Exactly one path is selected based on value equality.
     */
    SWITCH_ROUTER(SwitchRouterTask, true),

    /**
     * Parallel gateway (AND-split/AND-join) that routes to ALL configured targets.
     * Waits for all parallel branches to complete before continuing.
     * BPMN Parallel Gateway equivalent.
     */
    PARALLEL_GATEWAY(ParallelGatewayTask, true),

    /**
     * HTTP task for making REST API calls and HTTP requests.
     * Supports all HTTP methods, headers, authentication, and request bodies.
     */
    HTTP(HttpTask, false)

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
            case 'manual':
            case 'manualtask':
            case 'human':
                return MANUAL
            case 'signal':
            case 'signaltask':
            case 'event':
                return SIGNAL
            case 'subgraph':
            case 'subgraphtask':
            case 'subprocess':
            case 'template':
                return SUBGRAPH
            case 'script':
            case 'scripttask':
            case 'code':
            case 'eval':
                return SCRIPT
            case 'send':
            case 'sendtask':
            case 'publish':
            case 'emit':
                return SEND
            case 'receive':
            case 'receivetask':
            case 'wait':
            case 'listen':
                return RECEIVE
            case 'timer':
            case 'timertask':
            case 'scheduled':
            case 'periodic':
                return TIMER
            case 'businessrule':
            case 'businessruletask':
            case 'rule':
                return BUSINESS_RULE
            case 'datatransform':
            case 'datatransformtask':
            case 'transform':
            case 'pipeline':
                return DATA_TRANSFORM
            case 'callactivity':
            case 'callactivitytask':
            case 'subprocess':
            case 'call':
                return CALL_ACTIVITY
            case 'loop':
            case 'looptask':
            case 'iterate':
            case 'foreach':
            case 'each':
                return LOOP
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
            case 'exclusive':
            case 'exclusivegateway':
            case 'xor':
            case 'xorgateway':
                return EXCLUSIVE_GATEWAY
            case 'switch':
            case 'switchrouter':
            case 'case':
                return SWITCH_ROUTER
            case 'parallel':
            case 'parallelgateway':
            case 'and':
            case 'andgateway':
            case 'fanout':
                return PARALLEL_GATEWAY
            case 'http':
            case 'httptask':
            case 'rest':
            case 'api':
                return HTTP
            case 'file':
            case 'filetask':
            case 'files':
            case 'fileprocessing':
                return FILE
            case 'messaging':
            case 'messagingtask':
            case 'kafka':
            case 'amqp':
            case 'queue':
            case 'topic':
            case 'eventbus':
            case 'vertx':
                return MESSAGING
            case 'sql':
            case 'sqltask':
            case 'database':
            case 'db':
            case 'jdbc':
            case 'query':
                return SQL
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