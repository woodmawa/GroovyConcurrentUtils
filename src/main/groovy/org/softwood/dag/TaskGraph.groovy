package org.softwood.dag

import groovy.util.logging.Slf4j

import org.softwood.dag.task.TaskContext
import org.softwood.dag.task.TaskEvent
import org.softwood.dag.task.Task
import org.softwood.dag.task.TaskListener
import org.softwood.dag.task.TaskState
import org.softwood.promise.Promise
import org.softwood.promise.Promises

/**
 * TaskGraph represents a Directed Acyclic Graph (DAG) of executable tasks with promise-based
 * asynchronous execution capabilities.
 *
 * <p>The TaskGraph provides a declarative DSL for defining complex task workflows with dependencies,
 * fork/join patterns, and conditional routing. Tasks are executed asynchronously using a thread pool,
 * with automatic dependency resolution and state management.</p>
 *
 * <h3>Architecture Overview</h3>
 * <ul>
 *   <li><b>Task Management:</b> Maintains a registry of tasks and their relationships</li>
 *   <li><b>Execution Engine:</b> Promise-based asynchronous execution with dependency tracking</li>
 *   <li><b>Event System:</b> Pluggable listener architecture for monitoring task lifecycle</li>
 *   <li><b>Context Propagation:</b> Shared context for passing data between tasks</li>
 * </ul>
 *
 * <h3>Usage Pattern</h3>
 * <pre>
 * def graph = TaskGraph.build {
 *     globals {
 *         apiKey = "secret"
 *     }
 *
 *     serviceTask("fetchData") {
 *         action { ctx, prev ->
 *             // fetch data
 *             return dataPromise
 *         }
 *     }
 *
 *     serviceTask("processData") {
 *         dependsOn("fetchData")
 *         action { ctx, prev ->
 *             def data = prev.get().get()
 *             // process data
 *         }
 *     }
 * }
 *
 * graph.run().get() // Execute and wait for completion
 * </pre>
 *
 * @author Softwood
 * @see TaskGraphDsl
 * @see Task
 * @see Promise
 */
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

    // ---------------------------------------------------------------------
    // DSL entry point
    // ---------------------------------------------------------------------

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

    // ---------------------------------------------------------------------
    // Execution
    // ---------------------------------------------------------------------

    Promise<?> run() {

        Promise<Object> completion = Promises.newPromise()

        // taskId -> Optional<Promise<?>>
        Map<String, Optional<Promise<?>>> results =
                new LinkedHashMap<String, Optional<Promise<?>>>().withDefault {
                    Optional.<Promise<?>>empty()
                }

        // Expose to JoinDsl and general diagnostics
        ctx.globals.__taskResults = results

        // Event emitter wrapper
        Closure<Void> emit = { TaskEvent ev ->
            ev.graphId = this.id
            listeners.each { TaskListener l -> l.onEvent(ev) }
            null
        }

        // "Ready" tasks: PENDING/SCHEDULED whose predecessors are all terminal
        Closure<List<Task>> ready = {
            tasks.values().findAll { Task t ->
                t.state in [TaskState.PENDING, TaskState.SCHEDULED] &&
                        t.predecessors.every { pid ->
                            tasks[pid].state in [
                                    TaskState.COMPLETED,
                                    TaskState.FAILED,
                                    TaskState.SKIPPED
                            ]
                        }
            }
        }

        // Run scheduler on the pool
        ctx.pool.execute {

            try {
                while (true) {
                    def toRun = ready()

                    // Schedule all ready tasks
                    toRun.each { Task t ->
                        Optional<Promise<?>> prevOpt = Optional.empty()

                        boolean isJoinTask =
                                t.metaClass.hasProperty(t, 'isJoinTask') &&
                                        t.metaClass.isJoinTask

                        // 1-predecessor passthrough (non-join)
                        if (!isJoinTask && t.predecessors.size() == 1) {
                            prevOpt = results[t.predecessors.first()]
                        }

                        Optional<Promise<?>> optPromise = t.execute(ctx, prevOpt, emit)

                        if (optPromise.isPresent()) {
                            Promise<?> p = optPromise.get()
                            results[t.id] = Optional.of(p)
                        } else {
                            // Represent "no promise" as a completed-null promise
                            Promise<Object> p = Promises.newPromise()
                            p.accept(null)
                            results[t.id] = Optional.of(p)
                        }
                    }

                    if (toRun.isEmpty()) {
                        boolean allDone = tasks.values().every { Task t ->
                            t.state in [
                                    TaskState.COMPLETED,
                                    TaskState.FAILED,
                                    TaskState.SKIPPED
                            ]
                        }

                        if (allDone) {
                            break
                        }

                        Thread.sleep(10)
                    }
                }

                // Choose "last" task as graph result
                def lastTask = tasks.values().last()
                Optional<Promise<?>> opt = results[lastTask.id]
                Promise<?> lastPromise = opt?.orElse(null)

                if (lastPromise == null) {
                    lastPromise = Promises.newPromise()
                    lastPromise.accept(null)
                }

                lastPromise.onComplete { value ->
                    completion.accept(value)
                }.onError { err ->
                    completion.fail(err)
                }

            } catch (Throwable e) {
                completion.fail(e)
            }
        }

        return completion
    }
}