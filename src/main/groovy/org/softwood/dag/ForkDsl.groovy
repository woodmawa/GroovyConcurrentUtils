package org.softwood.dag

import org.softwood.dag.task.ConditionalForkTask
import org.softwood.dag.task.DefaultTaskEventDispatcher
import org.softwood.dag.task.DynamicRouterTask
import org.softwood.dag.task.RouterTask
import org.softwood.dag.task.ServiceTask
import org.softwood.dag.task.ShardingRouterTask
import org.softwood.dag.task.Task

/**
 * DSL builder for configuring fork patterns that enable parallel task execution with routing logic.
 *
 * The fork DSL wires DAG edges and, when needed, inserts a concrete RouterTask node
 * between a source task and its targets:
 *
 *   sourceTask → routerTask → [target1, target2, target3]
 *
 * Routing strategies supported:
 *  - Static fork: fixed set of successors, no router node
 *  - Conditional fork: ConditionalForkTask with (targetId → predicate) rules
 *  - Dynamic routing: DynamicRouterTask using a custom closure
 *  - Sharding: ShardingRouterTask that computes shard IDs from a collection
 */
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
    def from(String sourceId) { routeFromId = sourceId }

    /** Statically route to one or more downstream tasks. */
    def to(String... targetIds) { staticTargets.addAll(targetIds) }

    /** Conditional routing: conditionalOn(["taskId"]) { prevValue -> boolean } */
    def conditionalOn(List<String> targets, Closure<Boolean> cond) {
        targets.each { tid -> conditionalRules[tid] = cond }
    }

    /** Dynamic routing: route { prevValue -> List<String> targetIds } */
    def route(Closure customLogic) {
        dynamicRouteLogic = customLogic
    }

    /**
     * Sharding fan-out:
     *  shard("templateId", count) { prevValue -> Collection items }
     *
     * ShardingRouterTask will compute concrete shard IDs from templateId.
     */
    def shard(String templateId, int count, Closure shardSrc) {
        shardTemplateId = templateId
        shardCount = count
        shardSource = shardSrc
    }

    // -------------------------
    // BUILD: create real tasks
    // -------------------------

    void build() {

        if (!routeFromId)
            throw new IllegalStateException("fork($id) requires from \"taskId\"")

        Task source = graph.tasks[routeFromId]
        if (!source)
            throw new IllegalStateException("Unknown source task: $routeFromId")

        boolean hasConditional = !conditionalRules.isEmpty()
        boolean hasDynamic = dynamicRouteLogic != null
        boolean hasSharding = shardSource != null

        // Choose router type (if any)
        RouterTask router

        if (hasSharding) {
            // -- SHARDING ROUTER --
            String rid = "__shardRouter_${id}_${UUID.randomUUID()}"
            router = new ShardingRouterTask(rid, rid, graph.ctx)
            router.templateTargetId = shardTemplateId
            router.shardSource = shardSource
            router.shardCount = shardCount
        }
        else if (hasDynamic) {
            // -- DYNAMIC ROUTER --
            String rid = "__dynamicRouter_${id}_${UUID.randomUUID()}"
            router = new DynamicRouterTask(rid, rid, graph.ctx)
            router.routingLogic = dynamicRouteLogic
            router.allowedTargets = (staticTargets + conditionalRules.keySet()).unique()
        }
        else if (hasConditional) {
            // -- CONDITIONAL ROUTER --
            String rid = "__conditionalRouter_${id}_${UUID.randomUUID()}"
            router = new ConditionalForkTask(rid, rid, graph.ctx)
            router.staticTargets.addAll(staticTargets)
            router.conditionalRules.putAll(conditionalRules)
        }
        else {
            // -- SIMPLE STATIC FORK: no router node needed --
            staticTargets.each { tid ->
                Task target = graph.tasks[tid]
                if (!target) throw new IllegalStateException("Unknown target: $tid")
                target.dependsOn(source.id)
                source.addSuccessor(target.id)
            }
            return
        }

        // ------------------------------------------
        // Router task selected → wire it
        // ------------------------------------------
        router.eventDispatcher = new DefaultTaskEventDispatcher(graph)
        graph.addTask(router)

        // Wire source → router
        router.dependsOn(source.id)
        source.addSuccessor(router.id)

        // ------------------------------------------
        // Wire router → downstream targets
        // ------------------------------------------

        if (!hasSharding) {
            // Static + conditional + dynamic all know explicit ids
            (staticTargets + conditionalRules.keySet()).unique().each { tid ->
                Task target = graph.tasks[tid]
                if (!target)
                    throw new IllegalStateException("Unknown target: $tid")

                target.dependsOn(router.id)
                router.addSuccessor(target.id)
            }
        } else {
            // Sharding generates its own dynamic shard IDs.
            // Shard-specific tasks are expected to be created separately.
        }
    }
}
