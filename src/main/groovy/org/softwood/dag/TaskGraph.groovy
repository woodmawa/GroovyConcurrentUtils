package org.softwood.dag

import groovy.util.logging.Slf4j
import org.softwood.dag.task.RouterTask
import org.softwood.dag.task.TaskContext
import org.softwood.dag.task.TaskEvent
import org.softwood.dag.task.Task
import org.softwood.dag.task.TaskListener
import org.softwood.dag.task.TaskState
import org.softwood.pool.ExecutorPool
import org.softwood.promise.Promise
import org.softwood.promise.Promises
import org.softwood.promise.core.PromisePoolContext

/**
 * TaskGraph represents a Directed Acyclic Graph (DAG) of executable tasks with promise-based
 * asynchronous execution capabilities.
 *
 * <p>The TaskGraph provides a declarative DSL for defining complex task workflows with dependencies,
 * fork/join patterns, and conditional/dynamic routing. Tasks are executed asynchronously using a
 * thread pool, with automatic dependency resolution and state management.</p>
 *
 * <h3>Execution model</h3>
 * <ul>
 *   <li>Each node is a concrete {@link Task} (ServiceTask, RouterTask, etc.)</li>
 *   <li>Edges are fixed after DSL build time (no runtime mutation of the DAG)</li>
 *   <li>RouterTask subclasses decide which successors are logically "active"</li>
 *   <li>Unselected successors are marked {@link TaskState#SKIPPED}</li>
 * </ul>
 */
@Slf4j
class TaskGraph {

    final String id
    final TaskContext ctx

    final Map<String, Task> tasks = [:]
    final List<Closure> eventListeners = []

    // Store pool reference if provided
    private final ExecutorPool pool

    TaskGraph(String id = UUID.randomUUID().toString(),
              TaskContext ctx = new TaskContext(), ExecutorPool pool = null) {
        this.id = id
        this.ctx = ctx
        this.pool = pool ?: PromisePoolContext.getCurrentPool()
    }

    static TaskGraph build(@DelegatesTo(TaskGraphDsl) Closure buildSpec) {
        // ensures that the spec "this" is the current TaskGraph instance
        Closure spec = buildSpec.clone() as Closure

        def g = new TaskGraph()
        def taskGraphDsl = new TaskGraphDsl(g)
        spec.delegate = taskGraphDsl
        spec.resolveStrategy = Closure.DELEGATE_FIRST

        spec.call()
        return g
    }

    void addTask(Task t) {
        tasks[t.id] = t
    }

    void onEvent(Closure listener) {
        eventListeners << listener
    }

    void notifyEvent(TaskEvent evt) {
        eventListeners.each { it(evt) }
    }

    // --------------------------------------------------------------------
    // Run whole DAG
    // --------------------------------------------------------------------
    Promise run() {

        // If we have a pool, use it for all promise operations
        if (pool != null) {
            return Promises.withPool(pool) {
                executeTaskGraph()
            }
        } else {
            return executeTaskGraph()
        }
    }

    private Promise executeTaskGraph() {
        return Promises.async {
            List<Task> roots = tasks.values()
                    .findAll { it.predecessors.isEmpty() }
                    .toList()

            assert roots.size() > 0

            Map<String, Promise> results = [:]

            roots.each { root ->
                def taskPromise = schedule(root, Optional.empty(), results)
                results[root.id] = taskPromise
            }

            ctx.globals.__taskResults = results

            Promise last = results[roots.last().id]
            return last.get()
        }
    }

    // --------------------------------------------------------------------
    // Schedule a task
    // --------------------------------------------------------------------
    private Promise schedule(Task task,
                             Optional<Promise<?>> prevOpt,
                             Map<String, Promise> results) {

        println "schedule(): received task $task, prevOpt=$prevOpt"

        // 1. Already scheduled?
        if (results.containsKey(task.id)) {
            return results[task.id]
        }

        // 2. Collect predecessor promises
        List<Promise> predPromises = task.predecessors.collect { pid ->
            results[pid] ?: schedule(tasks[pid], Optional.empty(), results)
        }

        // If no predecessors, prev = null
        Promise prevPromise =
                predPromises.isEmpty()
                        ? Promises.async { null }
                        : Promises.all(predPromises)

        println "schedule(): prevPromise = $prevPromise"

        // 3. Execute task (flatMap so we don't wrap promises)
        Promise exec = prevPromise.flatMap { prevVal ->

            println "schedule(): flatMap(prevVal=$prevVal) on task=${task.id}"

            Optional optPrev = Optional.ofNullable(prevVal)
            Promise execPromise = task.execute(optPrev)

            // 4. If NOT a RouterTask → normal task, return execPromise
            if (!(task instanceof RouterTask)) {
                return execPromise
            }

            // 5. RouterTask logic: execPromise resolves to List<String>
            return execPromise.then { List<String> selectedTargets ->

                log.info "schedule(): RouterTask ${task.id} selected = $selectedTargets"

                Set<String> allTargets = task.successors as Set

                // 6. Mark unselected successors as SKIPPED
                allTargets.each { tid ->
                    if (!selectedTargets.contains(tid)) {

                        log.info "schedule(): marking SKIPPED for task $tid"

                        Promise skippedPromise = Promises.async {
                            Task t = tasks[tid]
                            t.state = TaskState.SKIPPED
                            TaskState.SKIPPED
                        }

                        // store SKIPPED result
                        results[tid] = skippedPromise
                    }
                }

                // 7. Return router decision downstream (if anyone cares)
                return selectedTargets
            }
        }

        // 8. Store execution promise
        results[task.id] = exec

        return exec
    }
}
