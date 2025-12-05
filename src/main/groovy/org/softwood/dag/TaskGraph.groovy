package org.softwood.dag

import groovy.util.logging.Slf4j
import org.softwood.dag.task.*
import org.softwood.promise.Promise

@Slf4j
class TaskGraph {

    Map<String, Task> tasks = [:]

    /** Fork → RouterTask mapping produced by ForkDsl */
    List<RouterTask> routers = []

    /** True once finalizeWiring() has run */
    boolean wired = false

    // --------------------------------------------------------------------
    // BUILD / WIRING
    // --------------------------------------------------------------------

    void addTask(Task t) {
        tasks[t.id] = t
    }

    void registerRouter(RouterTask router) {
        routers << router
    }

    /**
     * After ForkDSL has attached successors to router.targetIds, finalize DAG
     */
    void finalizeWiring() {
        if (wired) return
        wired = true

        log.debug "finalizeWiring: processing ${routers.size()} forks"

        tasks.values().each { t ->
            t.successors.each { succId ->
                tasks[succId]?.predecessors << t.id
            }
        }

        log.debug "Final graph structure:"
        tasks.values().each { t ->
            log.debug "  Task ${t.id}: predecessors=${t.predecessors} successors=${t.successors}"
        }
    }

    // --------------------------------------------------------------------
    // EXECUTION
    // --------------------------------------------------------------------

    /**
     * Start graph execution by scheduling all root tasks
     */
    Promise<?> start() {
        finalizeWiring()

        List<Task> roots = tasks.values().findAll { it.predecessors.isEmpty() }
        log.debug "Root tasks: ${roots*.id}"

        roots.each { schedule(it) }

        // Return combined promise that completes when all terminal tasks complete
        List<Promise> terminals = tasks.values()
                .findAll { it.successors.isEmpty() }
                .collect { it.completionPromise }

        return Promise.allOf(terminals)
    }

    // --------------------------------------------------------------------
    // Scheduler
    // --------------------------------------------------------------------

    private void schedule(Task t) {

        if (t.hasStarted) {
            log.debug "schedule(): ${t.id} already scheduled"
            return
        }

        t.markScheduled()

        log.debug "schedule(): execute ${t.id}"

        Promise<?> prevPromise = t.buildPrevPromise(tasks)
        Promise<?> execPromise = t.execute(prevPromise)

        // -------------------------
        // Completion callback
        // -------------------------
        execPromise.onComplete { result, error ->

            if (error) {
                log.error "Task ${t.id} failed: $error"
                t.markFailed(error)
                return
            }

            t.markCompleted()

            if (!(t instanceof RouterTask)) {
                scheduleNormalSuccessors(t)
            } else {
                scheduleRouterSuccessors((RouterTask)t)
            }
        }
    }

    // --------------------------------------------------------------------
    // NORMAL SUCCESSOR SCHEDULING
    // --------------------------------------------------------------------

    private void scheduleNormalSuccessors(Task t) {
        t.successors.each { succId ->
            Task succ = tasks[succId]
            scheduleIfReady(succ)
        }
    }

    // --------------------------------------------------------------------
    // ROUTER SUCCESSOR SCHEDULING
    // --------------------------------------------------------------------

    private void scheduleRouterSuccessors(RouterTask router) {

        if (!router.alreadyRouted) {
            log.error "Router ${router.id} completed but has no routing result!"
            return
        }

        List<String> chosen = router.lastSelectedTargets
        Set<String> allTargets = router.targetIds

        log.debug "router ${router.id} selected targets: $chosen"
        log.debug "router ${router.id} all possible targets: $allTargets"

        // Mark unselected targets as SKIPPED
        allTargets.each { tid ->
            if (!chosen.contains(tid)) {
                tasks[tid]?.markSkipped()
            }
        }

        // Handle sharding router → ensure join successors scheduled only ONCE
        if (router instanceof ShardingRouterTask) {

            if (!router.scheduledShardSuccessors) {
                router.scheduledShardSuccessors = true

                // First schedule shard tasks
                chosen.each { sid ->
                    Task shardTask = tasks[sid]

                    // Inject shard data into shard task
                    List shardData = router.getShardData(sid)
                    shardTask.setInjectedInput(shardData)

                    schedule(shardTask)
                }

                // AFTER scheduling shards → schedule successors of router
                router.successors.each { joinId ->
                    scheduleIfReady(tasks[joinId])
                }
            }

            return
        }

        // Non-sharding router: schedule only chosen targets
        chosen.each { tid ->
            scheduleIfReady(tasks[tid])
        }
    }

    // --------------------------------------------------------------------
    // READY CHECK
    // --------------------------------------------------------------------

    private void scheduleIfReady(Task t) {
        if (t == null || t.hasStarted) return

        boolean ready = t.predecessors.every { pid ->
            def pt = tasks[pid]
            pt.isCompleted() || pt.isSkipped()
        }

        if (ready) {
            schedule(t)
        }
    }
}
