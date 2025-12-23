package org.softwood.dag.task

import groovy.util.logging.Slf4j
import org.softwood.promise.Promise

import java.util.function.Function
import java.util.function.Consumer
import java.util.function.Predicate

/**
 * DataTransformTask - Functional Data Transformation Pipeline
 *
 * Transforms data through a chain of functional operations with optional
 * inspection points. Supports functional programming patterns including
 * map, filter, flatMap, reduce, and tap for side effects.
 *
 * <h3>Key Features:</h3>
 * <ul>
 *   <li>Functional transformation pipeline</li>
 *   <li>Tap points for inspection/debugging</li>
 *   <li>Support for Java functional interfaces</li>
 *   <li>Groovy closure support</li>
 *   <li>Type-safe transformations</li>
 *   <li>Error handling with recovery</li>
 * </ul>
 *
 * <h3>DSL Example - Basic Pipeline:</h3>
 * <pre>
 * task("process-data", TaskType.DATA_TRANSFORM) {
 *     // Transform: extract user data
 *     transform { data ->
 *         [
 *             userId: data.id,
 *             fullName: "${data.firstName} ${data.lastName}",
 *             email: data.email
 *         ]
 *     }
 *     
 *     // Tap: inspect intermediate result
 *     tap { data ->
 *         log.info("After extraction: $data")
 *     }
 *     
 *     // Transform: normalize email
 *     transform { data ->
 *         data.email = data.email.toLowerCase()
 *         data
 *     }
 *     
 *     // Filter: validate data
 *     filter { data ->
 *         data.email && data.fullName
 *     }
 * }
 * </pre>
 *
 * <h3>DSL Example - Collection Processing:</h3>
 * <pre>
 * task("process-users", TaskType.DATA_TRANSFORM) {
 *     // Transform each item in collection
 *     map { user ->
 *         [
 *             id: user.id,
 *             name: user.name.toUpperCase(),
 *             active: user.status == 'ACTIVE'
 *         ]
 *     }
 *     
 *     // Tap to log each item
 *     tap { users ->
 *         log.info("Processing ${users.size()} users")
 *     }
 *     
 *     // Filter active users only
 *     filter { users ->
 *         users.findAll { it.active }
 *     }
 *     
 *     // Reduce to summary
 *     reduce([total: 0, active: 0]) { acc, users ->
 *         acc.total = users.size()
 *         acc.active = users.count { it.active }
 *         acc
 *     }
 * }
 * </pre>
 *
 * <h3>DSL Example - Error Handling:</h3>
 * <pre>
 * task("safe-transform", TaskType.DATA_TRANSFORM) {
 *     transform { data ->
 *         // Might throw exception
 *         riskyOperation(data)
 *     }
 *     
 *     // Recover from errors
 *     onError { error, data ->
 *         log.error("Transform failed", error)
 *         [status: 'FAILED', error: error.message, originalData: data]
 *     }
 *     
 *     tap { result ->
 *         log.info("Final result: $result")
 *     }
 * }
 * </pre>
 *
 * <h3>Java Functional Interface Support:</h3>
 * <pre>
 * // Using Java Function interface
 * Function&lt;Map, String&gt; extractName = { data -> data.name }
 * 
 * task("java-style", TaskType.DATA_TRANSFORM) {
 *     transform(extractName)
 *     
 *     // Using Consumer for tap
 *     tap({ result -> println(result) } as Consumer)
 *     
 *     // Using Predicate for filter
 *     filter({ name -> name.length() > 3 } as Predicate)
 * }
 * </pre>
 */
@Slf4j
class DataTransformTask extends TaskBase<Object> {

    // =========================================================================
    // Transformation Pipeline
    // =========================================================================
    
    /**
     * List of transformation steps to apply in order.
     * Each step is a closure/function that transforms the data.
     */
    private final List<TransformStep> pipeline = []
    
    /**
     * Error recovery handler: (Exception, data) -> recoveredData
     */
    private Closure errorHandler
    
    /**
     * Whether to stop pipeline on filter failure (false = continue with null)
     */
    private boolean stopOnFilterFail = false

    DataTransformTask(String id, String name, TaskContext ctx) {
        super(id, name, ctx)
    }

    // =========================================================================
    // DSL Methods - Transformation
    // =========================================================================
    
    /**
     * Add a transformation step using a Groovy closure.
     * Closure receives current data and returns transformed data.
     * 
     * @param transformer (data) -> transformedData
     */
    void transform(Closure transformer) {
        pipeline << new TransformStep(
            type: StepType.TRANSFORM,
            operation: transformer,
            description: "transform"
        )
    }
    
    /**
     * Add a transformation step using a Java Function.
     * 
     * @param transformer Function that transforms the data
     */
    void transform(Function transformer) {
        pipeline << new TransformStep(
            type: StepType.TRANSFORM,
            operation: { data -> transformer.apply(data) },
            description: "transform (Function)"
        )
    }
    
    /**
     * Map over a collection - transforms each item.
     * If data is a collection, applies transformation to each element.
     * 
     * @param mapper (item) -> transformedItem
     */
    void map(Closure mapper) {
        pipeline << new TransformStep(
            type: StepType.MAP,
            operation: mapper,
            description: "map"
        )
    }
    
    /**
     * Map using Java Function.
     */
    void map(Function mapper) {
        pipeline << new TransformStep(
            type: StepType.MAP,
            operation: { item -> mapper.apply(item) },
            description: "map (Function)"
        )
    }
    
    /**
     * FlatMap - transforms and flattens nested collections.
     * 
     * @param mapper (item) -> List/Collection
     */
    void flatMap(Closure mapper) {
        pipeline << new TransformStep(
            type: StepType.FLATMAP,
            operation: mapper,
            description: "flatMap"
        )
    }
    
    /**
     * Filter data based on a predicate.
     * If predicate returns false, data is filtered out.
     * 
     * @param predicate (data) -> boolean
     */
    void filter(Closure predicate) {
        pipeline << new TransformStep(
            type: StepType.FILTER,
            operation: predicate,
            description: "filter"
        )
    }
    
    /**
     * Filter using Java Predicate.
     */
    void filter(Predicate predicate) {
        pipeline << new TransformStep(
            type: StepType.FILTER,
            operation: { data -> predicate.test(data) },
            description: "filter (Predicate)"
        )
    }
    
    /**
     * Reduce/fold data to a single value.
     * 
     * @param initialValue starting accumulator value
     * @param reducer (accumulator, data) -> newAccumulator
     */
    void reduce(Object initialValue, Closure reducer) {
        pipeline << new TransformStep(
            type: StepType.REDUCE,
            operation: reducer,
            initialValue: initialValue,
            description: "reduce"
        )
    }

    // =========================================================================
    // DSL Methods - Inspection (Tap)
    // =========================================================================
    
    /**
     * Tap into the pipeline to inspect data without modifying it.
     * Useful for logging, debugging, or side effects.
     * 
     * @param inspector (data) -> void
     */
    void tap(Closure inspector) {
        pipeline << new TransformStep(
            type: StepType.TAP,
            operation: inspector,
            description: "tap"
        )
    }
    
    /**
     * Tap using Java Consumer.
     */
    void tap(Consumer consumer) {
        pipeline << new TransformStep(
            type: StepType.TAP,
            operation: { data -> consumer.accept(data); data },
            description: "tap (Consumer)"
        )
    }
    
    /**
     * Named tap for easier identification in logs.
     * 
     * @param name descriptive name for this tap point
     * @param inspector (data) -> void
     */
    void tap(String name, Closure inspector) {
        pipeline << new TransformStep(
            type: StepType.TAP,
            operation: inspector,
            description: "tap: $name"
        )
    }

    // =========================================================================
    // DSL Methods - Error Handling
    // =========================================================================
    
    /**
     * Define error recovery strategy.
     * If any transformation fails, this handler is called.
     * 
     * @param handler (Exception, currentData) -> recoveredData
     */
    void onError(Closure handler) {
        this.errorHandler = handler
    }
    
    /**
     * Set whether to stop pipeline when filter returns false.
     * Default: false (continue with null)
     */
    void stopOnFilterFail(boolean stop) {
        this.stopOnFilterFail = stop
    }

    // =========================================================================
    // Task Execution
    // =========================================================================

    @Override
    protected Promise<Object> runTask(TaskContext ctx, Object prevValue) {
        
        log.debug("DataTransformTask($id): starting pipeline with ${pipeline.size()} steps")
        
        if (pipeline.isEmpty()) {
            log.warn("DataTransformTask($id): no transformations defined, returning input")
            return ctx.promiseFactory.createPromise(prevValue)
        }
        
        try {
            // Execute the transformation pipeline
            def result = executePipeline(ctx, prevValue)
            
            log.info("DataTransformTask($id): pipeline completed successfully")
            
            return ctx.promiseFactory.createPromise(result)
            
        } catch (Exception e) {
            log.error("DataTransformTask($id): pipeline failed", e)
            
            // Try error recovery if handler is defined
            if (errorHandler) {
                try {
                    log.info("DataTransformTask($id): attempting error recovery")
                    def recovered = errorHandler.call(e, prevValue)
                    return ctx.promiseFactory.createPromise(recovered)
                } catch (Exception recoveryError) {
                    log.error("DataTransformTask($id): error recovery failed", recoveryError)
                    return ctx.promiseFactory.createFailedPromise(recoveryError)
                }
            } else {
                return ctx.promiseFactory.createFailedPromise(e)
            }
        }
    }

    // =========================================================================
    // Pipeline Execution
    // =========================================================================
    
    private Object executePipeline(TaskContext ctx, Object initialData) {
        def currentData = initialData
        def stepNumber = 0
        
        for (TransformStep step : pipeline) {
            stepNumber++
            
            log.debug("DataTransformTask($id): executing step $stepNumber: ${step.description}")
            
            try {
                currentData = executeStep(step, currentData, ctx)
                
                // Check if filter failed and we should stop
                if (currentData == null && stopOnFilterFail && step.type == StepType.FILTER) {
                    log.info("DataTransformTask($id): filter failed at step $stepNumber, stopping pipeline")
                    break
                }
                
            } catch (Exception e) {
                log.error("DataTransformTask($id): step $stepNumber failed: ${step.description}", e)
                throw new TransformException(
                    "Transform step $stepNumber failed: ${step.description}",
                    e,
                    stepNumber,
                    step.description
                )
            }
        }
        
        return currentData
    }
    
    private Object executeStep(TransformStep step, Object data, TaskContext ctx) {
        switch (step.type) {
            case StepType.TRANSFORM:
                return step.operation.call(data)
                
            case StepType.MAP:
                if (data instanceof Collection) {
                    return data.collect { item -> step.operation.call(item) }
                } else if (data instanceof Map) {
                    return data.collectEntries { k, v -> 
                        [k, step.operation.call(v)]
                    }
                } else {
                    // Single item
                    return step.operation.call(data)
                }
                
            case StepType.FLATMAP:
                if (data instanceof Collection) {
                    return data.collectMany { item -> 
                        def result = step.operation.call(item)
                        result instanceof Collection ? result : [result]
                    }
                } else {
                    def result = step.operation.call(data)
                    return result instanceof Collection ? result : [result]
                }
                
            case StepType.FILTER:
                def passes = step.operation.call(data)
                return passes ? data : null
                
            case StepType.REDUCE:
                if (data instanceof Collection) {
                    return data.inject(step.initialValue) { acc, item ->
                        step.operation.call(acc, item)
                    }
                } else {
                    // For single item, just call with initial value
                    return step.operation.call(step.initialValue, data)
                }
                
            case StepType.TAP:
                // Execute tap for side effect, return data unchanged
                step.operation.call(data)
                return data
                
            default:
                throw new IllegalStateException("Unknown step type: ${step.type}")
        }
    }

    // =========================================================================
    // Pipeline Introspection
    // =========================================================================
    
    /**
     * Get the number of steps in the pipeline.
     */
    int getPipelineSize() {
        return pipeline.size()
    }
    
    /**
     * Get descriptions of all pipeline steps.
     */
    List<String> getPipelineSteps() {
        return pipeline.collect { "${it.type}: ${it.description}" }
    }
    
    /**
     * Clear the pipeline (useful for testing/rebuilding).
     */
    void clearPipeline() {
        pipeline.clear()
    }

    // =========================================================================
    // Internal Classes
    // =========================================================================
    
    private static class TransformStep {
        StepType type
        Closure operation
        Object initialValue  // For reduce
        String description
    }
    
    private enum StepType {
        TRANSFORM,    // Basic transformation
        MAP,          // Map over collection
        FLATMAP,      // FlatMap over collection
        FILTER,       // Filter/predicate
        REDUCE,       // Reduce/fold
        TAP           // Inspect without modifying
    }
    
    /**
     * Exception thrown when a transformation step fails.
     * Includes step number and description for debugging.
     */
    static class TransformException extends RuntimeException {
        final int stepNumber
        final String stepDescription
        
        TransformException(String message, Throwable cause, int stepNumber, String stepDescription) {
            super(message, cause)
            this.stepNumber = stepNumber
            this.stepDescription = stepDescription
        }
    }
}
