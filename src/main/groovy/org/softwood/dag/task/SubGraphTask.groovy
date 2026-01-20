package org.softwood.dag.task

import groovy.util.logging.Slf4j
import org.softwood.dag.TaskGraph
import org.softwood.promise.Promise

/**
 * SubGraphTask - Reusable Workflow Template Task
 *
 * Enables workflow composition by embedding complete TaskGraphs as reusable
 * components within larger workflows. Acts as a subroutine/function call
 * pattern for workflows.
 *
 * <h3>Key Features:</h3>
 * <ul>
 *   <li>Encapsulate complex workflows as single tasks</li>
 *   <li>Parameterize sub-workflows with input data</li>
 *   <li>Extract specific outputs from sub-workflow results</li>
 *   <li>Reuse workflow patterns across multiple parent workflows</li>
 *   <li>Isolate execution context (optional)</li>
 * </ul>
 *
 * <h3>Use Cases:</h3>
 * <ul>
 *   <li>Common approval workflows</li>
 *   <li>Reusable data validation pipelines</li>
 *   <li>Standard notification sequences</li>
 *   <li>Modular business process components</li>
 *   <li>Library of workflow templates</li>
 * </ul>
 *
 * <h3>DSL Example - Inline SubGraph:</h3>
 * <pre>
 * task("payment-processing", TaskType.SUBGRAPH) {
 *     // Define the sub-workflow inline
 *     subGraph {
 *         serviceTask("validate-card") {
 *             action { ctx, prev ->
 *                 def payment = prev
 *                 ctx.promiseFactory.executeAsync {
 *                     [valid: payment.amount > 0, cardNumber: payment.card]
 *                 }
 *             }
 *         }
 *         
 *         serviceTask("charge-card") {
 *             action { ctx, prev ->
 *                 ctx.promiseFactory.executeAsync {
 *                     [transactionId: "TXN-123", status: "SUCCESS"]
 *                 }
 *             }
 *         }
 *         
 *         fork("flow") {
 *             from "validate-card"
 *             to "charge-card"
 *         }
 *     }
 *     
 *     // Optional: Extract specific output
 *     outputExtractor { subGraphResult ->
 *         subGraphResult.transactionId
 *     }
 * }
 * </pre>
 *
 * <h3>DSL Example - Library Pattern (Recommended):</h3>
 * <pre>
 * // Define reusable library (once)
 * class WorkflowLibrary {
 *     static TaskGraph validateData() {
 *         TaskGraph.build {
 *             serviceTask("check-format") {
 *                 action { ctx, prev -> validateFormat(prev) }
 *             }
 *             serviceTask("check-business-rules") {
 *                 action { ctx, prev -> validateRules(prev) }
 *             }
 *             chainVia("check-format", "check-business-rules")
 *         }
 *     }
 * }
 *
 * // Reuse in multiple workflows with clean syntax
 * subGraphTask("validate") {
 *     graph WorkflowLibrary.validateData()
 *     inputMapper { prev -> [data: prev.rawInput] }
 *     outputExtractor { result -> result.validatedData }
 * }
 * </pre>
 *
 * <h3>DSL Example - Legacy Template Reference:</h3>
 * <pre>
 * // Also works with template() method
 * def emailTemplate = TaskGraph.build { ... }
 *
 * task("notify", TaskType.SUBGRAPH) {
 *     template emailTemplate
 *     inputMapper { prev -> [to: prev.email] }
 * }
 * </pre>
 *
 * <h3>Context Isolation:</h3>
 * <pre>
 * task("isolated-process", TaskType.SUBGRAPH) {
 *     subGraph { ... }
 *     isolateContext true  // Sub-workflow gets fresh context
 * }
 * </pre>
 */
@Slf4j
class SubGraphTask extends TaskBase<Object> {

    // =========================================================================
    // Configuration
    // =========================================================================
    
    /** The sub-workflow graph (can be set via template or built inline) */
    TaskGraph subWorkflow
    
    /** Closure to build sub-workflow inline */
    Closure subGraphBuilder
    
    /** Map input data to sub-workflow format */
    Closure inputMapper
    
    /** Extract specific output from sub-workflow result */
    Closure outputExtractor
    
    /** Whether to isolate context (true = new context, false = shared) */
    boolean isolateContext = false
    
    /** Parameters to pass to sub-workflow */
    Map<String, Object> parameters = [:]

    SubGraphTask(String id, String name, TaskContext ctx) {
        super(id, name, ctx)
    }

    // =========================================================================
    // DSL Methods
    // =========================================================================
    
    /**
     * Set a pre-built template graph.
     */
    void template(TaskGraph graph) {
        this.subWorkflow = graph
    }

    /**
     * Set a pre-built graph (cleaner syntax for library usage).
     * This is an alias for template() with a more intuitive name.
     *
     * Usage:
     *   subGraphTask("validate") {
     *       graph ValidationLibrary.validateAndEnrich()
     *   }
     */
    void graph(TaskGraph graph) {
        this.subWorkflow = graph
    }

    /**
     * Build sub-workflow inline using DSL.
     */
    void subGraph(@DelegatesTo(org.softwood.dag.TaskGraphDsl) Closure builder) {
        this.subGraphBuilder = builder
    }
    
    /**
     * Set input mapper closure.
     * Maps parent workflow data to sub-workflow input format.
     */
    void inputMapper(Closure mapper) {
        this.inputMapper = mapper
    }
    
    /**
     * Set output extractor closure.
     * Extracts specific data from sub-workflow result.
     */
    void outputExtractor(Closure extractor) {
        this.outputExtractor = extractor
    }
    
    /**
     * Set context isolation.
     */
    void isolateContext(boolean value) {
        this.isolateContext = value
    }
    
    /**
     * Set parameters for sub-workflow.
     */
    void parameters(Map<String, Object> params) {
        this.parameters.putAll(params)
    }
    
    /**
     * Add a single parameter.
     */
    void parameter(String key, Object value) {
        this.parameters[key] = value
    }

    // =========================================================================
    // Task Execution
    // =========================================================================

    @Override
    protected Promise<Object> runTask(TaskContext ctx, Object prevValue) {
        
        log.debug("SubGraphTask($id): executing sub-workflow")
        
        return ctx.promiseFactory.executeAsync {
            
            // Build sub-workflow if not already set
            if (!subWorkflow) {
                if (!subGraphBuilder) {
                    throw new IllegalStateException(
                        "SubGraphTask($id): must set either template or subGraph builder"
                    )
                }
                
                log.debug("SubGraphTask($id): building sub-workflow from DSL")
                subWorkflow = buildSubWorkflow()
            }
            
            // Prepare input for sub-workflow
            def subInput = prepareInput(prevValue)
            
            // Determine context for sub-workflow
            TaskContext subContext = isolateContext ? createIsolatedContext() : ctx
            
            // If isolated, copy parameters to sub-context globals
            if (isolateContext && parameters) {
                subContext.globals.putAll(parameters)
            }
            
            // Create input promise for sub-workflow
            def inputPromise = subContext.promiseFactory.createPromise(subInput)
            
            // Execute sub-workflow
            log.info("SubGraphTask($id): starting sub-workflow execution")
            def subPromise = executeSubWorkflow(subWorkflow, inputPromise)
            
            // Wait for sub-workflow completion
            def subResult = subPromise.get()
            
            log.info("SubGraphTask($id): sub-workflow completed")
            
            // Extract output if extractor is defined
            def finalResult = extractOutput(subResult)
            
            return ctx.promiseFactory.createPromise(finalResult)
            
        }.flatMap { it }  // Flatten the nested promise
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================
    
    private TaskGraph buildSubWorkflow() {
        // Create a new graph and apply the builder
        def graph = new TaskGraph()
        graph.ctx = isolateContext ? createIsolatedContext() : this.ctx
        
        def dsl = new org.softwood.dag.TaskGraphDsl(graph)
        subGraphBuilder.delegate = dsl
        subGraphBuilder.resolveStrategy = Closure.DELEGATE_FIRST
        subGraphBuilder.call()
        
        // Wire the graph
        dsl.wireDeferred()
        
        return graph
    }
    
    private Object prepareInput(Object prevValue) {
        if (inputMapper) {
            log.debug("SubGraphTask($id): mapping input")
            return inputMapper.call(prevValue)
        }
        return prevValue
    }
    
    private TaskContext createIsolatedContext() {
        log.debug("SubGraphTask($id): creating isolated context")
        def newContext = new TaskContext()
        
        // Copy parameters to new context globals
        if (parameters) {
            newContext.globals.putAll(parameters)
        }
        
        return newContext
    }
    
    private Promise executeSubWorkflow(TaskGraph graph, Promise inputPromise) {
        // Inject input into root tasks
        def rootTasks = graph.tasks.values().findAll { it.predecessors.isEmpty() }
        
        if (rootTasks.size() == 1) {
            // Single root - inject input directly
            rootTasks[0].setInjectedInput(inputPromise.get())
        } else if (rootTasks.size() > 1) {
            // Multiple roots - each gets the same input
            def input = inputPromise.get()
            rootTasks.each { it.setInjectedInput(input) }
        }
        
        // Run the sub-workflow
        return graph.run()
    }
    
    private Object extractOutput(Object subResult) {
        if (outputExtractor) {
            log.debug("SubGraphTask($id): extracting output")
            return outputExtractor.call(subResult)
        }
        return subResult
    }
    
    // =========================================================================
    // Template Management
    // =========================================================================
    
    /**
     * Create a reusable template from a graph builder closure.
     * This is a static factory method for creating template instances.
     */
    static TaskGraph createTemplate(@DelegatesTo(org.softwood.dag.TaskGraphDsl) Closure builder) {
        return TaskGraph.build(builder)
    }
    
    /**
     * Clone a template graph for reuse.
     * This creates a fresh instance of the template that can be executed independently.
     * 
     * Note: This is a shallow copy - tasks are recreated but referenced objects
     * (like closures) are shared.
     */
    static TaskGraph cloneTemplate(TaskGraph template) {
        // For now, templates should be built fresh each time
        // A true deep clone would require serialization or manual reconstruction
        log.warn("Template cloning not yet implemented - using template directly")
        return template
    }
}
