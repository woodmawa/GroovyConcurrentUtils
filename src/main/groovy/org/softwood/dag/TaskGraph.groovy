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

        Map<String, Optional<Promise<?>>> results = [:].withDefault { Optional.empty() }

        // Store results map in context so join tasks can access it
        ctx.globals.__taskResults = results

        Closure<TaskEvent> emit = { TaskEvent ev ->
            ev.graphId = this.id
            listeners.each { it.onEvent(ev) }
        }

        def ready = { ->
            tasks.values().findAll { t ->
                t.state in [TaskState.PENDING, TaskState.SCHEDULED] &&
                        t.predecessors.every { pid ->
                            tasks[pid].state in [TaskState.COMPLETED, TaskState.FAILED, TaskState.SKIPPED]
                        }
            }
        }

        ctx.pool.execute {
            def activePromises = [] as List<Promise<?>>

            while (true) {
                def toRun = ready()
                if (!toRun && activePromises.isEmpty()) break

                toRun.each { Task t ->
                    Optional<Promise<?>> prevOpt = Optional.empty()

                    // For non-join tasks with single predecessor, pass that promise
                    boolean isJoinTask = t.metaClass.hasProperty(t, 'isJoinTask') && t.metaClass.isJoinTask

                    if (!isJoinTask && t.predecessors.size() == 1) {
                        prevOpt = results[t.predecessors.first()]
                    }
                    // Join tasks will access results via ctx.globals.__taskResults

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
                    Thread.sleep(10)
                }
            }

            def last = tasks.values().last()
            def lastRes = results[last.id]?.orElse(null)
            completion.accept(lastRes)
        }

        return completion
    }
}