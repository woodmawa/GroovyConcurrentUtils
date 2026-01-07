package org.softwood.dag.task

import groovy.util.logging.Slf4j
import org.softwood.promise.Promise

import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * AggregatorTask - Combines results from multiple parallel tasks
 *
 * Waits for multiple predecessor tasks to complete and combines their results
 * using a user-defined aggregation strategy. Essential for fork-join patterns.
 *
 * <h3>Key Features:</h3>
 * <ul>
 *   <li>Wait for N predecessor tasks to complete</li>
 *   <li>Custom aggregation strategy</li>
 *   <li>Completion size (complete after N of M tasks)</li>
 *   <li>Timeout with partial results</li>
 *   <li>Maintains result order</li>
 * </ul>
 */
@Slf4j
class AggregatorTask extends TaskBase<Object> {

    // =========================================================================
    // Configuration
    // =========================================================================
    
    /** Task IDs to wait for */
    Set<String> sourceTaskIds = []
    
    /** Resolved source tasks (injected by graph) */
    private Map<String, ITask> sourceTasks = [:]
    
    /** Aggregation strategy: (List<results>) -> aggregatedResult */
    Closure<Object> aggregationStrategy
    
    /** Timeout for collecting results */
    Duration timeout
    
    /** Handler for timeout: (partialResults) -> result */
    Closure<Object> onTimeoutHandler
    
    /** Complete after N results (instead of waiting for all) */
    Integer completionSize
    
    // =========================================================================
    // Internal State
    // =========================================================================

    private final ConcurrentHashMap<String, Object> collectedResults = new ConcurrentHashMap<>()
    private final List<String> completionOrder = Collections.synchronizedList([])
    private CountDownLatch completionLatch
    private volatile boolean completed = false

    AggregatorTask(String id, String name, TaskContext ctx) {
        super(id, name, ctx)
    }

    // =========================================================================
    // DSL Methods
    // =========================================================================
    
    /**
     * Specify which tasks to wait for.
     * Also adds them as predecessors for graph wiring.
     */
    void waitFor(String... taskIds) {
        taskIds.each { taskId ->
            sourceTaskIds.add(taskId)
            addPredecessor(taskId)
        }
    }
    
    /**
     * Define the aggregation strategy.
     */
    void strategy(@DelegatesTo(AggregationContext) Closure strategy) {
        this.aggregationStrategy = strategy
    }
    
    /**
     * Set timeout for result collection.
     */
    void timeout(Duration duration, Map args = [:]) {
        this.timeout = duration
        this.onTimeoutHandler = args.onTimeout
    }
    
    /**
     * Complete after N results (instead of all).
     */
    void completionSize(int size) {
        this.completionSize = size
    }
    
    // =========================================================================
    // Graph Integration Hook
    // =========================================================================
    
    /**
     * Called by TaskGraph after wiring to inject source task references.
     * This allows AggregatorTask to access individual task promises.
     */
    void resolveSourceTasks(Map<String, ITask> allTasks) {
        sourceTaskIds.each { taskId ->
            def task = allTasks[taskId]
            if (!task) {
                throw new IllegalStateException("AggregatorTask($id): source task '$taskId' not found in graph")
            }
            sourceTasks[taskId] = task
        }
        log.debug("AggregatorTask($id): resolved ${sourceTasks.size()} source tasks")
    }

    // =========================================================================
    // Task Execution
    // =========================================================================

    @Override
    protected Promise<Object> runTask(TaskContext ctx, Object prevValue) {
        
        log.debug("AggregatorTask($id): starting aggregation")
        
        // Validate configuration
        validateConfiguration()
        
        // If source tasks weren't resolved, try simple mode
        if (sourceTasks.isEmpty()) {
            log.warn("AggregatorTask($id): source tasks not resolved, falling back to simple mode")
            return runSimpleMode(ctx, prevValue)
        }
        
        // Determine required count
        int requiredCount = completionSize ?: sourceTaskIds.size()
        completionLatch = new CountDownLatch(requiredCount)
        
        log.info("AggregatorTask($id): waiting for $requiredCount of ${sourceTaskIds.size()} results")
        
        // Register listeners on source tasks
        registerSourceListeners()
        
        // Wait for completion in async context
        return ctx.promiseFactory.executeAsync {
            try {
                boolean completedInTime
                if (timeout) {
                    log.debug("AggregatorTask($id): waiting with timeout ${timeout.toMillis()}ms")
                    completedInTime = completionLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)
                } else {
                    log.debug("AggregatorTask($id): waiting without timeout")
                    completionLatch.await()
                    completedInTime = true
                }
                
                completed = true
                
                if (!completedInTime) {
                    log.warn("AggregatorTask($id): timeout reached")
                    return handleTimeout()
                }
                
                // Apply aggregation strategy
                return applyAggregation()
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt()
                throw new RuntimeException("AggregatorTask($id): interrupted while waiting", e)
            }
        }
    }
    
    /**
     * Simple mode: Just aggregate prevValue list (no timeout/completionSize)
     */
    private Promise<Object> runSimpleMode(TaskContext ctx, Object prevValue) {
        List<Object> results = normalizeResults(prevValue)
        log.info("AggregatorTask($id): simple mode - aggregating ${results.size()} results")
        
        return ctx.promiseFactory.executeAsync {
            try {
                return aggregationStrategy.call(results)
            } catch (Exception e) {
                log.error("AggregatorTask($id): error in aggregation strategy", e)
                throw new RuntimeException("Aggregation strategy failed", e)
            }
        }
    }
    
    private void validateConfiguration() {
        if (sourceTaskIds.isEmpty()) {
            throw new IllegalStateException("AggregatorTask($id): requires at least one source task (use waitFor)")
        }
        
        if (!aggregationStrategy) {
            throw new IllegalStateException("AggregatorTask($id): requires aggregation strategy")
        }
        
        if (completionSize != null) {
            if (completionSize < 1) {
                throw new IllegalStateException("AggregatorTask($id): completionSize must be >= 1")
            }
            if (completionSize > sourceTaskIds.size()) {
                throw new IllegalStateException(
                    "AggregatorTask($id): completionSize ($completionSize) > sourceTaskIds (${sourceTaskIds.size()})"
                )
            }
        }
    }
    
    private void registerSourceListeners() {
        sourceTasks.each { taskId, task ->
            // Wait for the task's completion promise
            task.completionPromise.onComplete { result ->
                handleSourceCompletion(taskId, result)
            }
            
            // Also handle failures
            task.completionPromise.onError { error ->
                handleSourceFailure(taskId, error)
            }
        }
    }
    
    private synchronized void handleSourceCompletion(String taskId, Object result) {
        if (completed) {
            return  // Already completed/timed out
        }

        log.debug("AggregatorTask($id): received result from $taskId")
        collectedResults.put(taskId, result)
        completionOrder.add(taskId)  // Track completion order
        completionLatch.countDown()

        long remaining = completionLatch.getCount()
        log.debug("AggregatorTask($id): $remaining results remaining")
    }
    
    private synchronized void handleSourceFailure(String taskId, Throwable error) {
        if (completed) {
            return
        }
        
        log.warn("AggregatorTask($id): source task $taskId failed: ${error.message}")
        // Store null for failed task
        collectedResults.put(taskId, null)
        completionLatch.countDown()
    }
    
    private Object handleTimeout() {
        log.warn("AggregatorTask($id): timeout - got ${collectedResults.size()} of ${sourceTaskIds.size()} results")
        
        if (onTimeoutHandler) {
            def partialResults = buildResultsList()
            return onTimeoutHandler.call(partialResults)
        } else {
            // Default: return partial results
            return buildResultsList()
        }
    }
    
    private Object applyAggregation() {
        def results = buildResultsList()
        
        log.info("AggregatorTask($id): aggregating ${results.size()} results")
        
        try {
            def aggregated = aggregationStrategy.call(results)
            log.debug("AggregatorTask($id): aggregation complete")
            return aggregated
        } catch (Exception e) {
            log.error("AggregatorTask($id): error in aggregation strategy", e)
            throw new RuntimeException("Aggregation strategy failed", e)
        }
    }
    
    /**
     * Build list of results in completion order.
     * When using completionSize, only returns the first N completed results.
     * Filters out null values (from failed or incomplete tasks).
     */
    private List<Object> buildResultsList() {
        // Determine how many results to include
        int maxResults = completionSize ?: sourceTaskIds.size()

        // Use completion order, limited to maxResults
        def orderedTaskIds = completionOrder.take(maxResults)

        // Build list of results in completion order
        return orderedTaskIds.collect { taskId ->
            collectedResults.get(taskId)
        }.findAll { it != null }
    }
    
    /**
     * Normalize prevValue into a List (for simple mode fallback).
     */
    private List<Object> normalizeResults(Object prevValue) {
        if (prevValue == null) {
            return []
        }
        
        if (prevValue instanceof List) {
            return ((List) prevValue).findAll { it != null }
        }
        
        return [prevValue]
    }
}

/**
 * Context object passed to aggregation strategy.
 */
class AggregationContext {
    List<Object> results
}
