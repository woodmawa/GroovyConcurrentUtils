package org.softwood.dag

/**
 * Types of events that can occur during TaskGraph execution.
 *
 * Used for monitoring, metrics collection, and observability.
 * Listeners can filter on specific event types.
 */
enum GraphEventType {
    // Graph-level events
    GRAPH_STARTED,          // Graph execution started
    GRAPH_COMPLETED,        // Graph completed successfully
    GRAPH_FAILED,           // Graph failed with errors
    GRAPH_CANCELLED,        // Graph execution cancelled

    // Task-level events
    TASK_SCHEDULED,         // Task scheduled for execution
    TASK_STARTED,           // Task started executing
    TASK_COMPLETED,         // Task completed successfully
    TASK_FAILED,            // Task failed
    TASK_RETRYING,          // Task being retried after failure
    TASK_SKIPPED,           // Task skipped (conditional execution)

    // Resilience events
    CIRCUIT_OPENED,         // Circuit breaker opened
    CIRCUIT_CLOSED,         // Circuit breaker closed
    CIRCUIT_HALF_OPEN,      // Circuit breaker testing recovery
    RATE_LIMITED,           // Task rate limited
    TIMEOUT_WARNING,        // Task approaching timeout threshold
    TIMEOUT_EXCEEDED,       // Task exceeded timeout

    // Resource events
    RESOURCE_WARNING,       // Resource limit warning (memory, concurrency)
    RESOURCE_EXHAUSTED,     // Resource limit exceeded

    // Dead Letter Queue events
    DLQ_ENTRY_ADDED,        // Task failure captured to DLQ
    DLQ_ENTRY_RETRIED,      // DLQ entry being retried

    // Custom/extensible
    CUSTOM                  // For user-defined events
}
