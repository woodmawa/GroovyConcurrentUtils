package org.softwood.dag

import groovy.util.logging.Slf4j
import org.softwood.dag.task.*

/**
 * Enhanced DSL for defining fork points in task graphs.
 */
@Slf4j
class ForkDsl {

    private final TaskGraph graph
    private final String id

    private List<String> sourceIds = []
    private List<String> targetIds = []
    private Closure mergeStrategy = null
    private ITask routerTask = null
    private Closure routerConfig = null

    ForkDsl(TaskGraph graph, String id) {
        this.graph = graph
        this.id = id
    }

    void from(String... sources) {
        if (!sources) {
            throw new IllegalArgumentException("fork($id): from() requires at least one source")
        }
        sourceIds.addAll(sources)
        log.debug("fork($id): sources set to ${sourceIds}")
    }

    void mergeWith(Closure strategy) {
        this.mergeStrategy = strategy
        log.debug("fork($id): custom merge strategy set")
    }

    void to(String... targets) {
        if (!targets) {
            throw new IllegalArgumentException("fork($id): to() requires at least one target")
        }
        targetIds.addAll(targets)
        log.debug("fork($id): targets set to ${targetIds}")
    }

    void exclusiveGateway(@DelegatesTo(ExclusiveGatewayTask) Closure config) {
        if (sourceIds.size() != 1) {
            throw new IllegalStateException("fork($id): exclusiveGateway requires exactly one source")
        }

        def gatewayId = "${id}-xor"
        routerTask = new ExclusiveGatewayTask(gatewayId, gatewayId, graph.ctx)
        routerTask.eventDispatcher = new org.softwood.dag.task.DefaultTaskEventDispatcher(graph)

        // Configure the gateway task directly using its DSL
        config.delegate = routerTask
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.call()

        // Extract targets from the configured task
        targetIds.addAll(routerTask.targetIds)

        graph.addTask(routerTask)
        log.debug("fork($id): created exclusive gateway with ${routerTask.orderedRules.size()} rules")
    }

    void switchRouter(@DelegatesTo(SwitchRouterTask) Closure config) {
        if (sourceIds.size() != 1) {
            throw new IllegalStateException("fork($id): switchRouter requires exactly one source")
        }

        def routerId = "${id}-switch"
        routerTask = new SwitchRouterTask(routerId, routerId, graph.ctx)
        routerTask.eventDispatcher = new org.softwood.dag.task.DefaultTaskEventDispatcher(graph)

        // Configure the router task directly using its DSL
        config.delegate = routerTask
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.call()

        // Extract targets from the configured task
        targetIds.addAll(routerTask.targetIds)

        graph.addTask(routerTask)
        log.debug("fork($id): created switch router")
    }

    void dynamicRouter(Closure routingLogic) {
        if (sourceIds.size() != 1) {
            throw new IllegalStateException("fork($id): dynamicRouter requires exactly one source")
        }

        def routerId = "${id}-router"
        routerTask = new DynamicRouterTask(routerId, routerId, graph.ctx)
        routerTask.eventDispatcher = new org.softwood.dag.task.DefaultTaskEventDispatcher(graph)
        routerTask.routingLogic = routingLogic
        
        // If targets were specified via to(), use them as allowed targets
        if (targetIds) {
            routerTask.allowedTargets = new ArrayList<>(targetIds)
            log.debug("fork($id): set ${targetIds.size()} allowed targets from to()")
        }

        graph.addTask(routerTask)
        log.debug("fork($id): created dynamic router")
    }

    // Alias for dynamicRouter for convenience
    void route(Closure routingLogic) {
        dynamicRouter(routingLogic)
    }

    void conditionalFork(List<String> targets, Closure condition) {
        if (sourceIds.size() != 1) {
            throw new IllegalStateException("fork($id): conditionalFork requires exactly one source")
        }

        // If no router exists yet, create one
        if (!routerTask) {
            def forkId = "${id}-conditional"
            routerTask = new ConditionalForkTask(forkId, forkId, graph.ctx)
            routerTask.eventDispatcher = new org.softwood.dag.task.DefaultTaskEventDispatcher(graph)
            graph.addTask(routerTask)
            
            // Add any existing targetIds (from previous to() calls) as static targets
            if (targetIds) {
                routerTask.staticTargets.addAll(targetIds)
                log.debug("fork($id): added ${targetIds.size()} static targets from to()")
            }
        }
        
        // Now add the conditional rules for the specified targets
        targets.each { targetId ->
            routerTask.conditionalRules[targetId] = condition
        }
        
        // Add these targets to our list if not already there
        targets.each { targetId ->
            if (!targetIds.contains(targetId)) {
                targetIds.add(targetId)
            }
        }

        log.debug("fork($id): created/updated conditional fork with ${routerTask.conditionalRules.size()} rules and ${routerTask.staticTargets.size()} static targets")
    }

    // Alias for conditionalFork
    void conditionalOn(List<String> targets, Closure condition) {
        conditionalFork(targets, condition)
    }

    void shardingRouter(String baseTaskName, int shardCount, Closure shardLogic) {
        if (sourceIds.size() != 1) {
            throw new IllegalStateException("fork($id): shardingRouter requires exactly one source")
        }

        def routerId = "${id}-sharding"
        routerTask = new ShardingRouterTask(routerId, routerId, graph.ctx)
        routerTask.eventDispatcher = new org.softwood.dag.task.DefaultTaskEventDispatcher(graph)
        routerTask.shardSource = shardLogic
        routerTask.shardCount = shardCount
        routerTask.templateTargetId = baseTaskName
        
        // The shard targets will be determined dynamically
        // but we need to add them to targetIds for the build process
        (0..<shardCount).each { i ->
            targetIds.add("${baseTaskName}_shard_${i}")
        }

        graph.addTask(routerTask)
        log.debug("fork($id): created sharding router with ${shardCount} shards")
    }

    // Alias for shardingRouter
    void shard(String baseTaskName, int shardCount, Closure shardLogic) {
        shardingRouter(baseTaskName, shardCount, shardLogic)
    }

    void build() {
        if (sourceIds.isEmpty()) {
            throw new IllegalStateException("fork($id): no source tasks specified")
        }

        log.debug("fork($id): building with ${sourceIds.size()} source(s)")

        if (sourceIds.size() > 1) {
            buildWithAutoJoin()
        } else {
            buildStandard()
        }
    }

    private void buildWithAutoJoin() {
        log.info("fork($id): auto-creating join for ${sourceIds.size()} sources")

        def joinId = "${id}-autojoin"
        def joinTask = graph.tasks[joinId]

        if (!joinTask) {
            try {
                joinTask = new ServiceTask(joinId, joinId, graph.ctx)
                joinTask.eventDispatcher = new org.softwood.dag.task.DefaultTaskEventDispatcher(graph)

                if (mergeStrategy) {
                    joinTask.action(mergeStrategy)
                } else {
                    joinTask.action({ ctx, prevValue ->
                        ctx.promiseFactory.executeAsync { prevValue }
                    })
                }

                graph.addTask(joinTask)
            } catch (MissingPropertyException e) {
                if (e.property == 'action') {
                    throw new IllegalStateException(
                        "fork($id): Cannot set 'action' as a property. Use the action() method instead:\n" +
                        "  Correct: joinTask.action { ctx, prev -> ... }\n" +
                        "  Wrong:   joinTask.action = { ctx, prev -> ... }",
                        e
                    )
                }
                throw e
            }
        }

        sourceIds.each { sourceId ->
            def source = graph.tasks[sourceId]
            if (!source) {
                throw new IllegalStateException("fork($id): unknown source: $sourceId")
            }
            source.addSuccessor(joinTask)
            joinTask.addPredecessor(source)
            log.debug("fork($id): ${sourceId} -> ${joinId}")
        }

        if (targetIds) {
            targetIds.each { targetId ->
                def target = graph.tasks[targetId]
                if (!target) {
                    throw new IllegalStateException("fork($id): unknown target: $targetId")
                }
                joinTask.addSuccessor(target)
                target.addPredecessor(joinTask)
                log.debug("fork($id): ${joinId} -> ${targetId}")
            }
        }
    }

    private void buildStandard() {
        def sourceId = sourceIds[0]
        def source = graph.tasks[sourceId]

        if (!source) {
            throw new IllegalStateException("fork($id): unknown source: $sourceId")
        }

        if (routerTask) {
            buildRouterFork(source)
        } else if (targetIds) {
            buildFanOutFork(source)
        } else {
            throw new IllegalStateException("fork($id): no targets specified")
        }
    }

    private void buildRouterFork(ITask source) {
        source.addSuccessor(routerTask)
        routerTask.addPredecessor(source)
        log.debug("fork($id): ${source.id} -> ${routerTask.id}")

        // CRITICAL: Populate the router's targetIds set so TaskGraph knows which tasks to mark as SKIPPED
        if (routerTask instanceof RouterTask) {
            routerTask.targetIds.addAll(targetIds)
            log.debug("fork($id): set router targetIds to ${targetIds}")
        }

        targetIds.each { targetId ->
            def target = graph.tasks[targetId]
            if (!target) {
                throw new IllegalStateException("fork($id): unknown target: $targetId")
            }
            routerTask.addSuccessor(target)
            target.addPredecessor(routerTask)
            log.debug("fork($id): ${routerTask.id} -> ${targetId}")
        }
    }

    private void buildFanOutFork(ITask source) {
        targetIds.each { targetId ->
            def target = graph.tasks[targetId]
            if (!target) {
                throw new IllegalStateException("fork($id): unknown target: $targetId")
            }
            source.addSuccessor(target)
            target.addPredecessor(source)
            log.debug("fork($id): ${source.id} -> ${targetId}")
        }
    }
}