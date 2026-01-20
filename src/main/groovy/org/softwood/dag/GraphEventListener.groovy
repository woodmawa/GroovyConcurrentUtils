package org.softwood.dag

/**
 * Listener interface for TaskGraph execution events.
 *
 * Implementations receive notifications about graph and task state changes,
 * enabling monitoring, metrics collection, logging, and alerting.
 *
 * <h3>Thread Safety:</h3>
 * Listeners are invoked synchronously on the graph execution thread.
 * Implementations should be thread-safe and avoid blocking operations.
 * For expensive operations (I/O, network calls), use async processing.
 *
 * <h3>Error Handling:</h3>
 * Exceptions thrown by listeners are caught and logged, but do not
 * interrupt graph execution. Listeners should handle their own errors.
 *
 * <h3>Usage Example:</h3>
 * <pre>
 * class MyListener implements GraphEventListener {
 *     &#64;Override
 *     void onEvent(GraphEvent event) {
 *         if (event.type == GraphEventType.TASK_FAILED) {
 *             log.error("Task failed: ${event.taskEvent.taskId}")
 *         }
 *     }
 *
 *     &#64;Override
 *     boolean accepts(GraphEvent event) {
 *         // Only listen to failure events
 *         return event.isFailure()
 *     }
 * }
 *
 * workflow.addListener(new MyListener())
 * </pre>
 *
 * @see GraphEvent
 * @see GraphEventType
 */
interface GraphEventListener {

    /**
     * Handle a graph event.
     *
     * Called for each event that passes the {@link #accepts(GraphEvent)} filter.
     *
     * <h3>Best Practices:</h3>
     * - Keep processing fast (< 1ms if possible)
     * - Use async processing for I/O operations
     * - Handle all exceptions internally
     * - Avoid blocking operations
     *
     * @param event the graph event
     */
    void onEvent(GraphEvent event)

    /**
     * Filter events before processing.
     *
     * Return true to receive this event in {@link #onEvent(GraphEvent)}.
     * Return false to skip this event.
     *
     * Default implementation accepts all events.
     *
     * <h3>Use Cases:</h3>
     * - Filter by event type (only failures, only completions, etc.)
     * - Filter by graph ID (only specific workflows)
     * - Filter by task ID pattern (only critical tasks)
     * - Filter by metadata (only production runs)
     *
     * @param event the event to filter
     * @return true to process this event, false to skip
     */
    default boolean accepts(GraphEvent event) {
        return true
    }

    /**
     * Get listener name for debugging/logging.
     *
     * Default implementation returns the class simple name.
     *
     * @return listener name
     */
    default String getName() {
        return this.class.simpleName
    }
}
