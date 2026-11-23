package org.softwood.dag.task

/**
 * Signal operation modes for SignalTask.
 */
enum SignalMode {
    /** Wait for a single signal */
    WAIT,
    
    /** Send a signal */
    SEND,
    
    /** Wait for all specified signals (barrier pattern) */
    WAIT_ALL,
    
    /** Wait for any one of specified signals (race pattern) */
    WAIT_ANY
}
