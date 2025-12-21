package org.softwood.dag.task

/**
 * Completion outcomes for ManualTask
 */
enum CompletionOutcome {
    /** Task completed successfully */
    SUCCESS,
    
    /** Task completed with failure */
    FAILURE,
    
    /** Task was skipped (e.g., auto-action on timeout) */
    SKIP
}
