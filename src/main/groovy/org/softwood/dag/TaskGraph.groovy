package org.softwood.dag

import groovy.util.logging.Slf4j

import org.softwood.dag.task.TaskContext
import org.softwood.dag.task.TaskEvent
import org.softwood.dag.task.Task
import org.softwood.dag.task.TaskListener
import org.softwood.dag.task.TaskState
import org.softwood.promise.Promise
import org.softwood.promise.Promises

@Slf4j
class TaskGraph {

    final String id
    final TaskContext ctx
    final Map<String, Task<?>> tasks = [:]
    final List<TaskListener> listeners = []

    private TaskGraph(String id = UUID.randomUUID().toString(),
                      TaskContext ctx = new TaskContext()) {
        this.id = id
        this.ctx = ctx
    }

    // ───────────────────────────────
    // DSL entry point
    // ───────────────────────────────

    static TaskGraph build(@DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = TaskGraphDsl) Closure cl) {
        def graph = new TaskGraph()
        def dsl = new TaskGraphDsl(graph)
        cl.delegate = dsl
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl.call()
        return graph
    }

    void addTask(Task<?> task) {
        tasks[task.id] = task
    }

    void addListener(TaskListener l) {
        listeners << l
    }

    // ───────────────────────────────
    // Execution
    // ───────────────────────────────

    Promise<?> run() {
        Promise<Object> completion = Promises.newPromise()

        // simple topological scheduling loop – can be improved
        Map<String, Optional<Promise<?>>> results = [:].withDefault { Optional.empty() }

        Closure<TaskEvent> emit = { TaskEvent ev ->
            ev.graphId = this.id
            listeners.each { it.onEvent(ev) }
        }

        // queue of tasks whose predecessors are satisfied
        def ready = { ->
            tasks.values().findAll { t ->
                t.state in [TaskState.PENDING, TaskState.SCHEDULED] &&
                        t.predecessors.every { pid ->
                            tasks[pid].state in [TaskState.COMPLETED, TaskState.FAILED, TaskState.SKIPPED]
                        }
            }
        }

        // Kick off scheduling on pool
        ctx.pool.execute {
            def activePromises = [] as List<Promise<?>>

            while (true) {
                def toRun = ready()
                if (!toRun && activePromises.isEmpty()) break

                toRun.each { Task t ->
                    // compute previous aggregated result for join
                    Optional<Promise<?>> prevOpt = Optional.empty()
                    if (t.predecessors.size() == 1) {
                        prevOpt = Optional.ofNullable(results[t.predecessors.first()]?.orElse(null))
                    }
                    // for multi-predecessor join, you could pass a Promise<List<?>> etc.

                    def optPromise = t.execute(ctx, prevOpt, emit)
                    if (optPromise.isPresent()) {
                        Promise<?> p = optPromise.get()
                        results[t.id] = Optional.of(p)
                        activePromises << p
                        p.onComplete { activePromises.remove(p) }
                        p.onError    { activePromises.remove(p) }
                    } else {
                        results[t.id] = Optional.empty()
                    }
                }

                if (toRun.isEmpty()) {
                    Thread.sleep(10) // crude wait; in reality better coordination
                }
            }

            // for now: graph result = last task’s result or null
            def last = tasks.values().last()
            def lastRes = results[last.id]?.orElse(null)
            completion.accept(lastRes)
        }

        return completion
    }
}