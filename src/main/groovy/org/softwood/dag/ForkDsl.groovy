package org.softwood.dag

import groovy.util.logging.Slf4j
import org.softwood.dag.task.ConditionalForkTask
import org.softwood.dag.task.DefaultTaskEventDispatcher
import org.softwood.dag.task.DynamicRouterTask
import org.softwood.dag.task.RouterTask
import org.softwood.dag.task.ShardingRouterTask
import org.softwood.dag.task.Task

/**
 * DSL builder for configuring fork patterns that enable parallel task execution with routing logic.
 *
 * Compatible with TaskGraph that has:
 *   - Map<String, Task> tasks
 *   - List<RouterTask> routers
 *   - void addTask(Task)
 *   - void registerRouter(RouterTask)
 *   - finalizeWiring() that infers predecessors from successors.
 */
@Slf4j
class ForkDsl {

    private final TaskGraph graph
    private final String id

    private String routeFromId
    private final Set<String> staticTargets = [] as Set
    private final Map<String, Closure<Boolean>> conditionalRules = [:]
    private Closure dynamicRouteLogic = null
    private Closure shardSource = null
    private String shardTemplateId = null
    private Integer shardCount = null

    ForkDsl(TaskGraph graph, String id) {
        this.graph = graph
        this.id = id
    }

    // -------------------------
    // DSL Methods
    // -------------------------

    /** Select the upstream source whose output drives the fork. */
    def from(String sourceId) {
        routeFromId = sourceId
    }

    /** Statically route to one or more downstream tasks. */
    def to(String... targetIds) {
        staticTargets.addAll(targetIds)
    }

    /** Conditional routing: conditionalOn(["taskId"]) { prevValue -> boolean } */
    def conditionalOn(List<String> targets, Closure<Boolean> cond) {
        // Create a delegate that exposes ctx as a property
        def delegateObj = new Object() {
            def getCtx() { return graph.ctx }
        }
        cond.delegate = delegateObj
        cond.resolveStrategy = Closure.DELEGATE_FIRST
        targets.each { tid -> conditionalRules[tid] = cond }
    }

    /** Dynamic routing: route { prevValue -> List<String> targetIds } */
    def route(Closure customLogic) {
        // Create a delegate that exposes ctx as a property
        def delegateObj = new Object() {
            def getCtx() { return graph.ctx }
        }
        customLogic.delegate = delegateObj
        customLogic.resolveStrategy = Closure.DELEGATE_FIRST
        dynamicRouteLogic = customLogic
    }

    /**
     * For dynamic routes, explicitly declare all possible target task IDs.
     * This is needed so the router knows which tasks might be activated.
     * Usage: targets("fast", "standard")
     */
    def targets(String... targetIds) {
        staticTargets.addAll(targetIds)
    }

    /**
     * Sharding fan-out:
     *  shard("templateId", count) { prevValue -> Collection items }
     */
    def shard(String templateId, int count, Closure shardSrc) {
        // Create a delegate that exposes ctx as a property
        def delegateObj = new Object() {
            def getCtx() { return graph.ctx }
        }
        shardSrc.delegate = delegateObj
        shardSrc.resolveStrategy = Closure.DELEGATE_FIRST
        shardTemplateId = templateId
        shardCount = count
        shardSource = shardSrc
    }

    // -------------------------
    // BUILD: create real tasks + wire into TaskGraph
    // -------------------------

    void build() {

        if (!routeFromId) {
            throw new IllegalStateException("fork($id) requires from \"taskId\"")
        }

        Task source = graph.tasks[routeFromId]
        if (!source) {
            throw new IllegalStateException("Unknown source task: $routeFromId")
        }

        // We assume all tasks in the graph share a TaskContext; reuse the source's ctx
        def ctx = source.ctx

        boolean hasConditional = !conditionalRules.isEmpty()
        boolean hasDynamic = dynamicRouteLogic != null
        boolean hasSharding = shardSource != null

        RouterTask router = null

        // --------------------------------------------------------------------
        // SHARDING FORK
        // --------------------------------------------------------------------
        if (hasSharding) {
            String rid = "__shardRouter_${id}_${UUID.randomUUID()}"
            router = new ShardingRouterTask(rid, rid, ctx)
            router.templateTargetId = shardTemplateId
            router.shardSource = shardSource
            router.shardCount = shardCount

            router.eventDispatcher = new DefaultTaskEventDispatcher(graph)
            graph.addTask(router)
            graph.registerRouter(router)

            log.debug "ForkDsl: created sharding router ${rid}"

            if (shardCount == null || shardCount <= 0) {
                throw new IllegalStateException(
                        "fork($id) sharding requires positive shardCount; got $shardCount"
                )
            }

            // Compute shard task IDs based on the template
            List<String> shardTargetIds = (0..<shardCount).collect { "${shardTemplateId}_shard_${it}" }
            router.targetIds.addAll(shardTargetIds)

            log.debug "ForkDsl: sharding router targets: $shardTargetIds"

            // Wire: source -> router
            source.addSuccessor(router.id)

            // Wire: router -> each shard task
            shardTargetIds.each { String tid ->
                Task shardTask = graph.tasks[tid]
                if (!shardTask) {
                    throw new IllegalStateException(
                            "fork($id) sharding refers to unknown shard task '$tid'. " +
                                    "Define shard tasks before declaring the fork."
                    )
                }
                router.addSuccessor(tid)
                shardTask.dependsOn(router.id)
            }

            return
        }

        // --------------------------------------------------------------------
        // NON-SHARDING FORKS: static / conditional / dynamic
        // --------------------------------------------------------------------
        boolean needsRouter = hasConditional || hasDynamic

        if (needsRouter) {
            // ----------- router creation -----------
            if (hasDynamic) {
                String rid = "__dynamicRouter_${id}_${UUID.randomUUID()}"
                router = new DynamicRouterTask(rid, rid, ctx)
                router.routingLogic = dynamicRouteLogic

                List<String> allTargets = (staticTargets + conditionalRules.keySet()).unique().toList()
                router.allowedTargets = allTargets
                router.targetIds.addAll(allTargets)

                log.debug "ForkDsl: created dynamic router ${rid} with targets: $allTargets"

            } else { // conditional only
                String rid = "__conditionalRouter_${id}_${UUID.randomUUID()}"
                router = new ConditionalForkTask(rid, rid, ctx)
                router.staticTargets.addAll(staticTargets)
                router.conditionalRules.putAll(conditionalRules)

                List<String> allTargets = (staticTargets + conditionalRules.keySet()).unique().toList()
                router.targetIds.addAll(allTargets)

                log.debug "ForkDsl: created conditional router ${rid} with targets: $allTargets"
            }

            router.eventDispatcher = new DefaultTaskEventDispatcher(graph)
            graph.addTask(router)
            graph.registerRouter(router)

            // Wire source → router
            source.addSuccessor(router.id)

            // Wire router → each possible target
            List<String> allTargets = router.targetIds.toList()
            if (allTargets.isEmpty()) {
                log.warn "ForkDsl: fork $id created router ${router.id} but has no targets"
            }

            allTargets.each { String tid ->
                Task targetTask = graph.tasks[tid]
                if (!targetTask) {
                    throw new IllegalStateException(
                            "fork($id) router ${router.id} refers to unknown target task '$tid'."
                    )
                }
                router.addSuccessor(tid)
                targetTask.dependsOn(router.id)
            }

        } else {
            // ----------------------------------------------------------------
            // PURE STATIC FAN-OUT (no router)
            // ----------------------------------------------------------------
            List<String> allTargets = staticTargets.unique().toList()
            if (allTargets.isEmpty()) {
                log.warn "ForkDsl: fork ${id} has no targets to wire"
                return
            }

            allTargets.each { String tid ->
                Task targetTask = graph.tasks[tid]
                if (!targetTask) {
                    throw new IllegalStateException(
                            "fork($id) static fork refers to unknown target task '$tid'."
                    )
                }
                source.addSuccessor(tid)
                targetTask.dependsOn(source.id)
            }
        }
    }
}
