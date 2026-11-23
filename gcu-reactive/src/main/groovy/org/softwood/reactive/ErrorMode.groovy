package org.softwood.reactive

/**
 * Strategy classifier enum.
 */
enum ErrorMode {
    DEFAULT, // basic logging
    RETRY, // retry a publish no subscriber could take (DataflowTopic: maxRetries, retryBackoff), then log
    DROP, // silently drop
    FAIL_FAST // propagate exception immediately
}
