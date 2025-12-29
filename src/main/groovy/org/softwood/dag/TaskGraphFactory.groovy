package org.softwood.dag

import org.softwood.dag.task.TaskContext

/**
 * Factory for creating multiple isolated TaskGraph instances from a single definition.
 * 
 * <h3>Purpose:</h3>
 * TaskGraph instances are single-use by design. When you need to execute the same 
 * graph structure multiple times with complete isolation, use TaskGraphFactory to 
 * define the structure once and create fresh instances on demand.
 * 
 * <h3>Thread Safety:</h3>
 * <ul>
 *   <li>The factory itself is immutable and thread-safe</li>
 *   <li>Each created graph is fully isolated with its own TaskContext, tasks, and state</li>
 *   <li>Multiple threads can safely call create() concurrently</li>
 *   <li>Each created graph can be executed independently without interference</li>
 * </ul>
 * 
 * <h3>Example 1 - Concurrent Processing:</h3>
 * <pre>
 * // Define the graph structure once
 * def factory = TaskGraphFactory.define {
 *     httpTask("fetch") {
 *         url { prev -> "https://api.example.com/item/${prev}" }
 *     }
 *     httpTask("process") {
 *         dependsOn "fetch"
 *         action { ctx, prev -> processData(prev) }
 *     }
 * }
 * 
 * // Execute concurrently for different items - fully isolated
 * def futures = (1..100).collect { itemId ->
 *     CompletableFuture.supplyAsync {
 *         def graph = factory.create()
 *         graph.start().get()
 *     }
 * }
 * CompletableFuture.allOf(futures as CompletableFuture[]).join()
 * </pre>
 * 
 * <h3>Example 2 - Retry with Fresh State:</h3>
 * <pre>
 * def factory = TaskGraphFactory.define {
 *     httpTask("login") {
 *         url "https://api.example.com/auth"
 *         cookieJar true  // Each execution gets fresh cookie jar
 *         formData { 
 *             username "alice"
 *             password "secret" 
 *         }
 *     }
 *     httpTask("getData") {
 *         dependsOn "login"
 *         url "https://api.example.com/data"
 *         cookieJar true  // Uses same fresh jar as login
 *     }
 * }
 * 
 * // Retry logic - each attempt gets completely fresh state
 * int maxRetries = 3
 * for (int attempt = 1; attempt <= maxRetries; attempt++) {
 *     try {
 *         def result = factory.create().start().get()
 *         println "Success on attempt $attempt: $result"
 *         break
 *     } catch (Exception e) {
 *         println "Attempt $attempt failed: ${e.message}"
 *         if (attempt == maxRetries) throw e
 *     }
 * }
 * </pre>
 * 
 * <h3>When NOT to use:</h3>
 * <ul>
 *   <li>Single execution: Just use {@code TaskGraph.build { }} directly</li>
 *   <li>Graphs that differ per execution: Build graphs individually instead</li>
 *   <li>Shared state required: Use a single graph instance (but only execute once)</li>
 * </ul>
 */
class TaskGraphFactory {
    
    private final Closure definition
    
    private TaskGraphFactory(Closure definition) {
        this.definition = definition
    }
    
    /**
     * Define a reusable graph structure.
     * 
     * <p>The provided closure will be used to build fresh TaskGraph instances
     * each time {@link #create()} is called. The closure is cloned for each
     * invocation to ensure complete isolation.</p>
     * 
     * @param definition DSL closure defining the graph structure
     * @return Factory that can create isolated graph instances
     */
    static TaskGraphFactory define(@DelegatesTo(TaskGraphDsl) Closure definition) {
        return new TaskGraphFactory(definition)
    }
    
    /**
     * Create a fresh TaskGraph instance with completely isolated state.
     * 
     * <p>Each created graph has:</p>
     * <ul>
     *   <li>Fresh TaskContext with empty globals map</li>
     *   <li>Fresh ExecutorPool for virtual threads</li>
     *   <li>Fresh PromiseFactory</li>
     *   <li>Fresh task instances</li>
     *   <li>Fresh cookie jars (if using HttpTask with cookieJar)</li>
     *   <li>No shared state with other graphs</li>
     * </ul>
     * 
     * <p>This method is thread-safe and can be called concurrently.</p>
     * 
     * @return New TaskGraph ready for execution via {@code start()}
     */
    TaskGraph create() {
        // Clone the closure to ensure complete isolation
        // Each graph gets its own closure instance
        return TaskGraph.build(definition.clone() as Closure)
    }
    
    /**
     * Create a fresh TaskGraph instance with a custom TaskContext.
     * 
     * <p>Useful when you need to provide specific configuration such as:</p>
     * <ul>
     *   <li>Custom ExecutorPool (e.g., for testing with FakePool)</li>
     *   <li>Custom PromiseFactory</li>
     *   <li>Pre-configured settings in context.config</li>
     * </ul>
     * 
     * <p><b>Warning:</b> If you share the same TaskContext instance across
     * multiple created graphs, they will share globals including cookie jars.
     * For full isolation, create a fresh TaskContext for each graph.</p>
     * 
     * @param customContext TaskContext to use for this graph instance
     * @return New TaskGraph with the provided context
     */
    TaskGraph create(TaskContext customContext) {
        def graph = new TaskGraph()
        graph.ctx = customContext
        
        TaskGraphDsl dsl = new TaskGraphDsl(graph)
        def cloned = definition.clone() as Closure
        cloned.delegate = dsl
        cloned.resolveStrategy = Closure.DELEGATE_FIRST
        cloned.call()
        
        dsl.wireDeferred()
        
        return graph
    }
}
