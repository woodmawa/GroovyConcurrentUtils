package org.softwood.dag.task

import groovy.util.logging.Slf4j
import org.softwood.promise.Promise
import org.softwood.promise.Promises

import java.util.concurrent.ConcurrentHashMap

/**
 * LoopTask - Iterate over a collection and execute an action for each item.
 * 
 * Supports:
 * - Sequential or parallel execution
 * - Break on condition
 * - Accumulator pattern (reduce-like)
 * - Error handling per item
 * - Progress tracking
 * 
 * <h3>Usage Examples:</h3>
 * <pre>
 * // Sequential iteration
 * loop("process-users") {
 *     over { ctx -> ctx.globals.users }
 *     action { ctx, item, index ->
 *         processUser(item)
 *     }
 * }
 * 
 * // Parallel iteration (batch processing)
 * loop("process-batch") {
 *     over { ctx -> getBatchItems() }
 *     parallel 10  // Process 10 items concurrently
 *     action { ctx, item, index ->
 *         ctx.promiseFactory.executeAsync { processItem(item) }
 *     }
 * }
 * 
 * // With accumulator (like reduce)
 * loop("calculate-total") {
 *     over { ctx -> ctx.globals.orders }
 *     accumulator([total: 0, count: 0])
 *     action { ctx, order, index, acc ->
 *         acc.total += order.amount
 *         acc.count++
 *         acc
 *     }
 * }
 * 
 * // With break condition
 * loop("find-first") {
 *     over { ctx -> ctx.globals.items }
 *     breakWhen { ctx, item, index -> item.id == targetId }
 *     action { ctx, item, index ->
 *         searchItem(item)
 *     }
 * }
 * </pre>
 */
@Slf4j
class LoopTask extends TaskBase<Object> {
    
    /** Closure that provides the collection to iterate over */
    Closure collectionProvider
    
    /** Action to execute for each item */
    Closure action
    
    /** Optional: initial accumulator value */
    Object initialAccumulator
    
    /** Optional: condition to break the loop early */
    Closure breakCondition
    
    /** Optional: error handler for individual items */
    Closure itemErrorHandler
    
    /** Parallel execution settings */
    boolean parallelExecution = false
    int parallelism = Runtime.getRuntime().availableProcessors()
    
    /** Loop control */
    boolean continueOnError = false
    
    /** Result mode */
    ResultMode resultMode = ResultMode.COLLECTION
    
    enum ResultMode {
        COLLECTION,    // Return list of results
        ACCUMULATOR,   // Return final accumulator value
        COUNT,         // Return count of processed items
        FIRST_MATCH    // Return first item matching break condition
    }
    
    LoopTask(String id, String name, TaskContext ctx) {
        super(id, name, ctx)
    }
    
    // =========================================================================
    // DSL Methods
    // =========================================================================
    
    /**
     * Specify the collection to iterate over.
     * 
     * Usage:
     *   over { ctx -> ctx.globals.items }
     *   over { ctx -> [1, 2, 3, 4, 5] }
     */
    void over(Closure provider) {
        this.collectionProvider = provider
    }
    
    /**
     * Specify the action to execute for each item.
     * 
     * Sequential signature:
     *   action { ctx, item, index -> ... }
     * 
     * With accumulator:
     *   action { ctx, item, index, acc -> ... }
     */
    void action(Closure action) {
        this.action = action
    }
    
    /**
     * Enable parallel execution with specified parallelism.
     * 
     * Usage:
     *   parallel 10  // Process 10 items at a time
     *   parallel true  // Use default parallelism (CPU cores)
     */
    void parallel(int maxParallel) {
        this.parallelExecution = true
        this.parallelism = maxParallel
    }
    
    void parallel(boolean enable) {
        this.parallelExecution = enable
    }
    
    /**
     * Specify initial accumulator value (enables reduce-like behavior).
     * 
     * Usage:
     *   accumulator([total: 0, count: 0])
     *   accumulator(0)
     */
    void accumulator(Object initial) {
        this.initialAccumulator = initial
        this.resultMode = ResultMode.ACCUMULATOR
    }
    
    /**
     * Specify condition to break loop early.
     * 
     * Usage:
     *   breakWhen { ctx, item, index -> item.status == 'FOUND' }
     */
    void breakWhen(Closure condition) {
        this.breakCondition = condition
        if (resultMode == ResultMode.COLLECTION) {
            this.resultMode = ResultMode.FIRST_MATCH
        }
    }
    
    /**
     * Specify error handler for individual items.
     * 
     * Usage:
     *   onItemError { ctx, item, index, error -> 
     *       log.error("Failed on item $index", error)
     *       return null  // Or recovery value
     *   }
     */
    void onItemError(Closure handler) {
        this.itemErrorHandler = handler
        this.continueOnError = true
    }
    
    /**
     * Continue processing even if individual items fail.
     */
    void continueOnError(boolean continue_) {
        this.continueOnError = continue_
    }
    
    /**
     * Specify result mode.
     */
    void returns(ResultMode mode) {
        this.resultMode = mode
    }
    
    // =========================================================================
    // Execution
    // =========================================================================
    
    @Override
    protected Promise<Object> runTask(TaskContext ctx, Object prevValue) {
        log.debug "LoopTask ${id}: starting iteration"
        
        if (!collectionProvider) {
            return ctx.promiseFactory.executeAsync {
                throw new IllegalStateException("LoopTask ${id}: no collection provider specified")
            }
        }
        
        if (!action) {
            return ctx.promiseFactory.executeAsync {
                throw new IllegalStateException("LoopTask ${id}: no action specified")
            }
        }
        
        return ctx.promiseFactory.executeAsync {
            try {
                // Get the collection to iterate over
                def collection = collectionProvider.call(ctx)
                
                if (collection == null) {
                    log.warn "LoopTask ${id}: collection provider returned null"
                    return handleEmptyResult()
                }
                
                // Convert to list if needed
                def items = collection instanceof Collection ? collection as List : [collection]
                
                if (items.isEmpty()) {
                    log.debug "LoopTask ${id}: empty collection"
                    return handleEmptyResult()
                }
                
                log.debug "LoopTask ${id}: processing ${items.size()} items (parallel=${parallelExecution})"
                
                // Execute based on mode
                if (parallelExecution) {
                    return executeParallel(ctx, items)
                } else {
                    return executeSequential(ctx, items)
                }
                
            } catch (Exception e) {
                log.error "LoopTask ${id}: error getting collection", e
                throw e
            }
        }
    }
    
    /**
     * Execute items sequentially.
     */
    private Object executeSequential(TaskContext ctx, List items) {
        def results = []
        def accumulator = initialAccumulator
        boolean broken = false
        
        for (int i = 0; i < items.size(); i++) {
            def item = items[i]
            
            // Check break condition
            if (breakCondition) {
                try {
                    if (breakCondition.call(ctx, item, i)) {
                        log.debug "LoopTask ${id}: break condition met at index ${i}"
                        broken = true
                        if (resultMode == ResultMode.FIRST_MATCH) {
                            return item
                        }
                        break
                    }
                } catch (Exception e) {
                    log.warn "LoopTask ${id}: error evaluating break condition at index ${i}", e
                }
            }
            
            // Execute action for this item
            try {
                def result
                if (initialAccumulator != null) {
                    // Call with accumulator
                    result = action.call(ctx, item, i, accumulator)
                    accumulator = result
                } else {
                    // Call without accumulator
                    result = action.call(ctx, item, i)
                }
                
                // Handle if result is a Promise
                if (result instanceof Promise) {
                    result = result.get()
                }
                
                // Always add to results for counting, but only store if COLLECTION mode
                if (resultMode == ResultMode.COLLECTION || resultMode == ResultMode.COUNT) {
                    results << result
                }
                
            } catch (Exception e) {
                log.error "LoopTask ${id}: error processing item at index ${i}", e
                
                if (itemErrorHandler) {
                    try {
                        def recoveryValue = itemErrorHandler.call(ctx, item, i, e)
                        if ((resultMode == ResultMode.COLLECTION || resultMode == ResultMode.COUNT)) {
                            results << recoveryValue
                        }
                    } catch (Exception handlerError) {
                        log.error "LoopTask ${id}: error in item error handler", handlerError
                        if (!continueOnError) {
                            throw e
                        } else {
                            // Still count as processed even if recovery failed
                            if (resultMode == ResultMode.COUNT) {
                                results << null
                            }
                        }
                    }
                } else if (!continueOnError) {
                    throw e
                } else {
                    // Continue on error - add null to results for COLLECTION and COUNT modes
                    if (resultMode == ResultMode.COLLECTION || resultMode == ResultMode.COUNT) {
                        results << null
                    }
                }
            }
        }
        
        // Return based on result mode
        return formatResult(results, accumulator, items.size(), broken)
    }
    
    /**
     * Execute items in parallel batches.
     */
    private Object executeParallel(TaskContext ctx, List items) {
        def results = new ConcurrentHashMap<Integer, Object>()
        def errors = new ConcurrentHashMap<Integer, Exception>()
        def broken = false
        
        // Can't use accumulator in parallel mode
        if (initialAccumulator != null) {
            log.warn "LoopTask ${id}: accumulator not supported in parallel mode, using sequential"
            return executeSequential(ctx, items)
        }
        
        // Process in batches
        def batches = items.collate(parallelism)
        
        for (def batch : batches) {
            if (broken) break
            
            def promises = []
            
            batch.eachWithIndex { item, batchIndex ->
                def globalIndex = items.indexOf(item)
                
                // Check break condition (not fully reliable in parallel)
                if (breakCondition && !broken) {
                    try {
                        if (breakCondition.call(ctx, item, globalIndex)) {
                            log.debug "LoopTask ${id}: break condition met at index ${globalIndex}"
                            broken = true
                            if (resultMode == ResultMode.FIRST_MATCH) {
                                return item
                            }
                        }
                    } catch (Exception e) {
                        log.warn "LoopTask ${id}: error evaluating break condition", e
                    }
                }
                
                if (!broken) {
                    def promise = ctx.promiseFactory.executeAsync {
                        try {
                            def result = action.call(ctx, item, globalIndex)
                            
                            // Handle if result is a Promise
                            if (result instanceof Promise) {
                                result = result.get()
                            }
                            
                            results[globalIndex] = result
                            return result
                            
                        } catch (Exception e) {
                            log.error "LoopTask ${id}: error processing item at index ${globalIndex}", e
                            errors[globalIndex] = e
                            
                            if (itemErrorHandler) {
                                try {
                                    def recoveryValue = itemErrorHandler.call(ctx, item, globalIndex, e)
                                    if (recoveryValue != null) {
                                        results[globalIndex] = recoveryValue
                                    }
                                } catch (Exception handlerError) {
                                    log.error "LoopTask ${id}: error in item error handler", handlerError
                                    if (!continueOnError) {
                                        throw e
                                    }
                                }
                            } else if (!continueOnError) {
                                throw e
                            }
                        }
                    }
                    
                    promises << promise
                }
            }
            
            // Wait for batch to complete
            Promises.all(promises).get()
        }
        
        // Check if we had errors
        if (!continueOnError && !errors.isEmpty()) {
            def firstError = errors.values().first()
            throw new RuntimeException("LoopTask ${id}: ${errors.size()} items failed", firstError)
        }
        
        // Convert results map to ordered list
        def orderedResults = (0..<items.size()).collect { results[it] }
        
        return formatResult(orderedResults, null, items.size(), broken)
    }
    
    /**
     * Handle empty collection based on result mode.
     */
    private Object handleEmptyResult() {
        switch (resultMode) {
            case ResultMode.COLLECTION:
                return []
            case ResultMode.ACCUMULATOR:
                return initialAccumulator
            case ResultMode.COUNT:
                return 0
            case ResultMode.FIRST_MATCH:
                return null
            default:
                return []
        }
    }
    
    /**
     * Format final result based on result mode.
     */
    private Object formatResult(List results, Object accumulator, int totalCount, boolean broken) {
        switch (resultMode) {
            case ResultMode.COLLECTION:
                return results
            case ResultMode.ACCUMULATOR:
                return accumulator
            case ResultMode.COUNT:
                return results.size()
            case ResultMode.FIRST_MATCH:
                return broken ? results.last() : null
            default:
                return results
        }
    }
}
