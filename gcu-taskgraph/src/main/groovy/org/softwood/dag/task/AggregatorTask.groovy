package org.softwood.dag.task

import groovy.util.logging.Slf4j
import org.softwood.promise.Promise

import java.time.Duration

/**
 * AggregatorTask - Combines results from multiple parallel tasks
 * 
 * Simple aggregation of predecessor results.
 * 
 * NOTE: timeout and completionSize features are not yet supported
 * due to TaskGraph execution model limitations. These require observing
 * tasks in real-time, but TaskGraph waits for ALL predecessors before
 * starting successor tasks.
 */
@Slf4j
class AggregatorTask extends TaskBase<Object> {

    Set<String> sourceTaskIds = []
    Closure<Object> aggregationStrategy
    
    // Future features (not implemented)
    Duration timeout
    Closure<Object> onTimeoutHandler
    Integer completionSize

    AggregatorTask(String id, String name, TaskContext ctx) {
        super(id, name, ctx)
    }

    /**
     * Specify which tasks to wait for.
     */
    void waitFor(String... taskIds) {
        sourceTaskIds.addAll(taskIds)
        taskIds.each { addPredecessor(it) }
    }
    
    /**
     * Define the aggregation strategy.
     * Receives a list of results from source tasks.
     */
    void strategy(@DelegatesTo(AggregationContext) Closure strategy) {
        this.aggregationStrategy = strategy
    }
    
    /**
     * Set timeout for result collection.
     * NOTE: Not yet implemented - requires architectural changes.
     */
    void timeout(Duration duration, Map args = [:]) {
        this.timeout = duration
        this.onTimeoutHandler = args.onTimeout
        log.warn("AggregatorTask($id): timeout feature not yet implemented")
    }
    
    /**
     * Complete after N results (instead of waiting for all).
     * NOTE: Not yet implemented - requires architectural changes.
     */
    void completionSize(int size) {
        this.completionSize = size
        log.warn("AggregatorTask($id): completionSize feature not yet implemented")
    }
    
    /**
     * Called by TaskGraph after wiring (for potential future use).
     */
    void resolveSourceTasks(Map<String, ITask> allTasks) {
        log.debug("AggregatorTask($id): source tasks available")
    }

    @Override
    protected Promise<Object> runTask(TaskContext ctx, Object prevValue) {
        
        // Validate configuration
        if (sourceTaskIds.isEmpty()) {
            throw new IllegalStateException("AggregatorTask($id): requires at least one source task (use waitFor)")
        }
        if (!aggregationStrategy) {
            throw new IllegalStateException("AggregatorTask($id): requires aggregation strategy")
        }
        
        // Convert prevValue to list of results
        // When a task has multiple predecessors, TaskBase provides them as a List
        List<Object> results = normalizeResults(prevValue)
        
        log.info("AggregatorTask($id): aggregating ${results.size()} results")
        
        // Apply aggregation strategy
        return ctx.promiseFactory.executeAsync {
            try {
                def aggregated = aggregationStrategy.call(results)
                log.debug("AggregatorTask($id): aggregation complete")
                return aggregated
            } catch (Exception e) {
                log.error("AggregatorTask($id): error in aggregation strategy", e)
                throw new RuntimeException("Aggregation strategy failed", e)
            }
        }
    }
    
    /**
     * Normalize prevValue into a List of results.
     */
    private List<Object> normalizeResults(Object prevValue) {
        if (prevValue == null) {
            return []
        }
        
        if (prevValue instanceof List) {
            // Filter out nulls from failed/skipped tasks
            return ((List) prevValue).findAll { it != null }
        }
        
        // Single value - wrap in list
        return [prevValue]
    }
}

/**
 * Context object for aggregation strategy (future use).
 */
class AggregationContext {
    List<Object> results
}
