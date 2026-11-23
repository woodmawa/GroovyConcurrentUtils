package org.softwood.dag.task

import groovy.transform.Canonical

/**
 * Result wrapper for FileTask that includes both data and execution summary.
 * 
 * This allows downstream tasks to access:
 * - The actual result data
 * - Execution metrics (files processed, duration, errors, etc.)
 */
@Canonical
class FileTaskResult {
    /** The actual result data */
    Object data
    
    /** Execution summary */
    ExecutionSummary summary
    
    /**
     * Check if execution was successful.
     */
    boolean isSuccess() {
        summary.allSucceeded()
    }
    
    /**
     * Check if there were any errors.
     */
    boolean hasErrors() {
        summary.hasErrors()
    }
    
    /**
     * Get the result data (convenience method).
     */
    Object getResult() {
        data
    }
    
    @Override
    String toString() {
        return "FileTaskResult[data=${data?.toString()?.take(100)}, ${summary}]"
    }
}
