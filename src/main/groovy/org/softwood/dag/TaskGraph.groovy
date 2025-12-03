package org.softwood.dag

import groovy.util.logging.Slf4j

import org.softwood.dag.task.TaskContext
import org.softwood.dag.task.TaskEvent
import org.softwood.dag.task.Task
import org.softwood.dag.task.TaskListener
import org.softwood.dag.task.TaskState
import org.softwood.pool.ConcurrentPool
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

    final Map<String, Task> tasks = [:]
    final List<Closure> eventListeners = []

    // conditionalForks[sourceTaskId] = [ targetId → condition ]
    final Map<String, Map<String, Closure<Boolean>>> conditionalForks = [:]

    TaskGraph(String id = UUID.randomUUID().toString(),
              TaskContext ctx = new TaskContext()) {
        this.id = id
        this.ctx = ctx
        //ensure we set the taskGraph into the context
        this.ctx.graph = this
    }

    static TaskGraph build(@DelegatesTo(TaskGraphDsl) Closure buildSpec) {
        //ensures that the spec this is current taskGraph instance
        Closure spec = buildSpec.clone () as Closure

        def g = new TaskGraph()
        def taskGraphDsl = new TaskGraphDsl(g)
        spec.delegate = taskGraphDsl
        spec.resolveStrategy = Closure.DELEGATE_FIRST

        //run the closure spec attached to the build method, resolving on the dsl first
        spec.call()
        return g
    }

    void addTask(Task t) {
        tasks[t.id] = t
    }

    void registerConditionalFork(String sourceId, String targetId, Closure<Boolean> cond) {
        conditionalForks
                .computeIfAbsent(sourceId) { [:] }[targetId] = cond
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

        return Promises.async {

            println "taskGraph run: async closure delegate is  $delegate with tasks : $tasks"
            List<Task> roots = tasks.values()
                    .findAll { it.predecessors.isEmpty() }
                    .toList()

            assert roots.size() >0
            println "taskGraph run: has tasks schedule $roots"

            Map<String, Promise> results = [:]

            roots.each { root ->
                assert root
                println "taskGraph run : scheduling task $root for its promise "
                def taskPromise = schedule(root, Optional.empty(), results)
                assert taskPromise
                println "root task $root returned with promise $taskPromise "
                results[root.id] = taskPromise
            }

            // Store results in context globals for test access
            ctx.globals.__taskResults = results

            // return last root result (what tests expect)
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

        println "taskGraph schedule():  received task $task to process, prevOpt : $prevOpt, results : $results "


        // already scheduled?
        if (results.containsKey(task.id))
            return results[task.id]

        // collect predecessor promises
        List<Promise> predPromises = task.predecessors.collect { pid ->
            results[pid] ?: schedule(tasks[pid], Optional.empty(), results)
        }

        // PROMISE for previous results
        Promise prevPromise =
                predPromises.isEmpty()
                        ? Promises.async { null }
                        : Promises.all(predPromises)  //todo problem here as we dont have an all()

        println "taskGraph schedule(): prevPromise is $prevPromise"

        // ----------------------------------------------------------------
        // CRITICAL FIX: use flatMap instead of map to avoid wrapping promise
        // ----------------------------------------------------------------
        Promise exec = prevPromise.flatMap { prevVal ->

            // conditional routing
            if (conditionalForks.containsKey(task.id)) {
                def conds = conditionalForks[task.id]
                conds.each { tid, cond ->
                    if (!cond(prevVal)) {
                        task.successors.remove(tid)
                    }
                }
            }

            println  "TaskGraph schedule() : flatmap delegate is $delegate, prevVal is $prevVal "
            Optional optPrevValue = Optional.ofNullable(prevVal)
            println  "TaskGraph schedule() : : run  task.execute($optPrevValue) "
            Promise taskExecPromise = task.execute(optPrevValue)
            println  "TaskGraph schedule() :: just executed task.execute($optPrevValue) and got promise $taskExecPromise "

            return taskExecPromise
        }

        results[task.id] = exec
        return exec
    }
}