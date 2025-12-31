package org.softwood.dag.task

import groovy.transform.Canonical

/**
 * Execution summary for FileTask.
 * 
 * Tracks metrics about the file processing execution:
 * - Files processed vs skipped
 * - Errors encountered
 * - Duration
 * - Memory usage
 * - Custom metrics
 */
@Canonical
class ExecutionSummary {
    int filesProcessed = 0
    int filesSkipped = 0
    int errorsEncountered = 0
    long totalDuration = 0  // milliseconds
    long peakMemoryMB = 0
    Map<String, Object> metrics = [:]
    
    /**
     * Add a custom metric.
     */
    void addMetric(String name, Object value) {
        metrics[name] = value
    }
    
    /**
     * Check if all files succeeded.
     */
    boolean allSucceeded() {
        errorsEncountered == 0 && filesSkipped == 0
    }
    
    /**
     * Check if any errors occurred.
     */
    boolean hasErrors() {
        errorsEncountered > 0
    }
    
    @Override
    String toString() {
        return "ExecutionSummary[processed=${filesProcessed}, skipped=${filesSkipped}, " +
               "errors=${errorsEncountered}, duration=${totalDuration}ms]"
    }
}
