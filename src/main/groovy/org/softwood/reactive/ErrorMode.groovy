package org.softwood.reactive

/**
 * Strategy classifier enum.
 */
enum ErrorMode {
    DEFAULT, // basic logging
    RETRY, // future: retry item
    DROP, // silently drop
    FAIL_FAST // propagate exception immediately
}
