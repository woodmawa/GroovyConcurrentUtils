package org.softwood.reactive

import java.time.Duration

/**
 * Common error-handling strategy used by reactive components such as
 * DataflowTopic and DataflowWorkQueue.
 * <p>
 * Implementations decide what to do when an item-processing error occurs.
 */
interface ErrorHandlingStrategy {
/**
 * Called when a reactive processing component encounters an exception.
 *
 * @param t the thrown exception
 * @param item the item that triggered the failure
 */
    void onError(Throwable t, Object item, ErrorMode mode)
    void onError(Throwable t, Object item)
    boolean getLogErrors ()

    Duration getPublishTimeout ()
}