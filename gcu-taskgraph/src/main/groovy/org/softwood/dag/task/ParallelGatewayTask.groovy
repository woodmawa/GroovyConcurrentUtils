package org.softwood.dag.task

import groovy.util.logging.Slf4j

/**
 * ParallelGatewayTask - Explicit AND-split for parallel execution.
 * 
 * <p>Routes execution to ALL configured targets simultaneously (AND-split).
 * The TaskGraph handles the AND-join (waiting for all to complete).</p>
 * 
 * <p>This is the BPMN Parallel Gateway equivalent - a fork pattern
 * where multiple parallel branches execute.</p>
 * 
 * <h3>Use Cases:</h3>
 * <ul>
 *   <li>Execute multiple independent tasks concurrently</li>
 *   <li>Fan-out to multiple services</li>
 *   <li>Parallel validation checks</li>
 *   <li>Concurrent processing of independent data</li>
 * </ul>
 * 
 * <h3>DSL Example:</h3>
 * <pre>
 * // In TaskGraphDsl
 * parallel("fan-out") {
 *     branches "fetch-user", "fetch-orders", "fetch-preferences"
 * }
 * 
 * // Then create a join task that depends on all branches
 * serviceTask("join") {
 *     action { ctx, prev -> ... }
 * }
 * dependsOn("join", "fetch-user", "fetch-orders", "fetch-preferences")
 * </pre>
 */
@Slf4j
class ParallelGatewayTask extends RouterTask {
    
    /** List of target task IDs to execute in parallel */
    final List<String> branches = []
    
    ParallelGatewayTask(String id, String name, TaskContext ctx) {
        super(id, name, ctx)
    }
    
    // =========================================================================
    // DSL Methods
    // =========================================================================
    
    /**
     * Specify branches to execute in parallel.
     * 
     * Usage:
     *   branches "task1", "task2", "task3"
     */
    void branches(String... targetIds) {
        branches.clear()
        branches.addAll(targetIds as List)
        this.targetIds.addAll(targetIds as List)
        log.debug("ParallelGateway($id): configured ${branches.size()} branches")
    }
    
    /**
     * Alternative name for branches() - semantically clearer.
     */
    void targets(String... targetIds) {
        branches(targetIds)
    }
    
    // =========================================================================
    // Routing Logic (AND-split)
    // =========================================================================
    
    @Override
    protected List<String> route(Object prevValue) {
        if (branches.isEmpty()) {
            log.warn("ParallelGateway($id): no branches configured")
            return []
        }
        
        log.debug("ParallelGateway($id): routing to ALL ${branches.size()} branches: $branches")
        
        // Return ALL branches for parallel execution
        return new ArrayList<>(branches)
    }
    
    @Override
    String toString() {
        return "ParallelGatewayTask[id=$id, branches=${branches.size()}]"
    }
}
