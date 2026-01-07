package org.softwood.dag.task

import groovy.util.logging.Slf4j
import org.softwood.promise.Promise

import java.time.Duration
import java.util.concurrent.TimeoutException

/**
 * SubprocessTask - Subprocess/Subgraph Invocation
 *
 * Invokes a subprocess (typically a TaskGraph) and integrates its result
 * into the parent workflow. Supports input/output mapping, error handling,
 * and timeout management.
 *
 * <h3>Key Features:</h3>
 * <ul>
 *   <li>Invoke TaskGraph as subprocess</li>
 *   <li>Input data mapping to subprocess</li>
 *   <li>Output data mapping from subprocess</li>
 *   <li>Error handling and recovery</li>
 *   <li>Subprocess timeout</li>
 *   <li>Reference-based subprocess lookup</li>
 * </ul>
 *
 * <h3>DSL Example - Inline Subprocess:</h3>
 * <pre>
 * subprocess("orderWorkflow") {
 *     input { ctx ->
 *         [
 *             orderId: ctx.globals.currentOrder.id,
 *             priority: "high"
 *         ]
 *     }
 *     
 *     subProcess { ctx, input ->
 *         def graph = TaskGraph.build {
 *             serviceTask("validate") { 
 *                 action { ctx, prev -> 
 *                     validateOrder(input.orderId)
 *                 }
 *             }
 *             serviceTask("process") {
 *                 action { ctx, prev ->
 *                     processOrder(prev)
 *                 }
 *             }
 *         }
 *         return graph.run()
 *     }
 *     
 *     output { result ->
 *         ctx.globals.orderResult = result
 *         return result
 *     }
 *     
 *     onError { error ->
 *         ctx.globals.failed = true
 *         log.error("Order workflow failed", error)
 *     }
 *     
 *     timeout Duration.ofMinutes(5)
 * }
 * </pre>
 *
 * <h3>DSL Example - Reference-Based:</h3>
 * <pre>
 * subprocess("subprocess") {
 *     subProcessRef "order-fulfillment-v2"
 *     
 *     input { ctx ->
 *         ctx.globals.orderData
 *     }
 *     
 *     output { result ->
 *         result.status
 *     }
 * }
 * </pre>
 *
 * <h3>DSL Example - Simple Pass-Through:</h3>
 * <pre>
 * subprocess("validate") {
 *     subProcess { ctx, input ->
 *         ValidationGraph.build(input).run()
 *     }
 * }
 * </pre>
 */
@Slf4j
class SubprocessTask extends TaskBase<Object> {

    // =========================================================================
    // Configuration
    // =========================================================================
    
    /** Subprocess provider: (ctx, input) -> Promise<?> */
    Closure subProcessProvider
    
    /** Subprocess reference (for registry lookup) */
    String subProcessRef
    
    /** Input mapper: (ctx) -> inputData */
    Closure inputMapper
    
    /** Output mapper: (result) -> outputData */
    Closure outputMapper
    
    /** Error handler: (error) -> void */
    Closure errorHandler
    
    /** Subprocess timeout */
    Duration timeout
    
    /** Subprocess registry (static for reference lookups) */
    static Map<String, Closure> subProcessRegistry = [:]

    SubprocessTask(String id, String name, TaskContext ctx) {
        super(id, name, ctx)
    }

    // =========================================================================
    // DSL Methods
    // =========================================================================
    
    /**
     * Define subprocess inline.
     * Closure receives (ctx, inputData) and should return a Promise.
     */
    void subProcess(Closure provider) {
        this.subProcessProvider = provider
    }
    
    /**
     * Reference a subprocess by name.
     * The subprocess must be registered in the static registry.
     */
    void subProcessRef(String ref) {
        this.subProcessRef = ref
    }
    
    /**
     * Map input data from parent context to subprocess.
     * Closure receives (ctx) and returns input data.
     */
    void input(Closure mapper) {
        this.inputMapper = mapper
    }
    
    /**
     * Map output data from subprocess to parent context.
     * Closure receives (result) and returns output data.
     */
    void output(Closure mapper) {
        this.outputMapper = mapper
    }
    
    /**
     * Handle errors from subprocess.
     * Closure receives (error).
     */
    void onError(Closure handler) {
        this.errorHandler = handler
    }
    
    /**
     * Set subprocess timeout.
     */
    void timeout(Duration duration) {
        this.timeout = duration
    }

    // =========================================================================
    // Static Registry Methods
    // =========================================================================
    
    /**
     * Register a subprocess for reference-based lookup.
     * 
     * @param name subprocess reference name
     * @param provider subprocess provider closure
     */
    static void registerSubProcess(String name, Closure provider) {
        subProcessRegistry[name] = provider
        log.debug("Registered subprocess: $name")
    }
    
    /**
     * Unregister a subprocess.
     */
    static void unregisterSubProcess(String name) {
        subProcessRegistry.remove(name)
        log.debug("Unregistered subprocess: $name")
    }
    
    /**
     * Clear all registered subprocesses.
     */
    static void clearRegistry() {
        subProcessRegistry.clear()
        log.debug("Cleared subprocess registry")
    }

    // =========================================================================
    // Task Execution
    // =========================================================================

    @Override
    protected Promise<Object> runTask(TaskContext ctx, Object prevValue) {
        
        log.debug("SubprocessTask($id): starting subprocess invocation")
        
        // Validate configuration
        validateConfiguration()
        
        // Resolve subprocess provider
        Closure provider = resolveSubProcessProvider()
        
        // Map input data
        Object inputData = mapInput(ctx, prevValue)
        
        log.info("SubprocessTask($id): invoking subprocess with input: ${inputData?.getClass()?.simpleName}")
        
        // Invoke subprocess
        return ctx.promiseFactory.executeAsync {
            try {
                // Call subprocess provider
                def subProcessResult = provider.call(ctx, inputData)
                
                // Handle result based on type
                Promise<?> resultPromise
                if (subProcessResult instanceof Promise) {
                    resultPromise = subProcessResult
                } else {
                    // Wrap non-promise result
                    resultPromise = ctx.promiseFactory.createPromise()
                    resultPromise.accept(subProcessResult)
                }
                
                // Wait for subprocess with timeout
                Object result
                if (timeout) {
                    log.debug("SubprocessTask($id): waiting for subprocess with timeout ${timeout.toMillis()}ms")
                    result = resultPromise.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                } else {
                    log.debug("SubprocessTask($id): waiting for subprocess (no timeout)")
                    result = resultPromise.get()
                }
                
                log.info("SubprocessTask($id): subprocess completed successfully")
                
                // Map output
                return mapOutput(result)
                
            } catch (TimeoutException e) {
                log.error("SubprocessTask($id): subprocess timeout after ${timeout}")
                handleError(e)
                throw e
            } catch (Exception e) {
                log.error("SubprocessTask($id): subprocess failed", e)
                handleError(e)
                throw e
            }
        }
    }
    
    private void validateConfiguration() {
        if (!subProcessProvider && !subProcessRef) {
            throw new IllegalStateException(
                "SubprocessTask($id): requires either subProcess or subProcessRef"
            )
        }
        
        if (subProcessProvider && subProcessRef) {
            throw new IllegalStateException(
                "SubprocessTask($id): cannot specify both subProcess and subProcessRef"
            )
        }
        
        if (subProcessRef && !subProcessRegistry.containsKey(subProcessRef)) {
            throw new IllegalStateException(
                "SubprocessTask($id): subprocess reference '$subProcessRef' not found in registry"
            )
        }
    }
    
    private Closure resolveSubProcessProvider() {
        if (subProcessProvider) {
            return subProcessProvider
        } else {
            return subProcessRegistry[subProcessRef]
        }
    }
    
    private Object mapInput(TaskContext ctx, Object prevValue) {
        if (inputMapper) {
            try {
                return inputMapper.call(ctx)
            } catch (Exception e) {
                log.error("SubprocessTask($id): error mapping input", e)
                throw new IllegalStateException("Failed to map input data", e)
            }
        } else {
            // Default: pass through previous value
            return prevValue
        }
    }
    
    private Object mapOutput(Object result) {
        if (outputMapper) {
            try {
                return outputMapper.call(result)
            } catch (Exception e) {
                log.error("SubprocessTask($id): error mapping output", e)
                throw new IllegalStateException("Failed to map output data", e)
            }
        } else {
            // Default: pass through result
            return result
        }
    }
    
    private void handleError(Throwable error) {
        if (errorHandler) {
            try {
                errorHandler.call(error)
            } catch (Exception e) {
                log.error("SubprocessTask($id): error in error handler", e)
            }
        }
    }
}
