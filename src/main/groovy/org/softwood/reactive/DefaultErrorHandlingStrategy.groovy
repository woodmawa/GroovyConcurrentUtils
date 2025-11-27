package org.softwood.reactive

import groovy.util.logging.Slf4j

@Slf4j
class DefaultErrorHandlingStrategy implements ErrorHandlingStrategy {
    @Override
    void onError(Throwable t, Object item, ErrorMode mode = ErrorMode.DEFAULT) {
        switch (mode) {
            case ErrorMode.RETRY:
                log.error("Retry requested but no retry handler implemented — item={}, error={}", item, t.message, t)
                break


            case ErrorMode.DROP:
                log.warn("Dropped item due to error — item={}, error={}", item, t.message)
                break


            case ErrorMode.FAIL_FAST:
                log.error("Fail-fast — item={}, error={}", item, t.message, t)
                throw t instanceof RuntimeException ? t : new RuntimeException(t)


            case ErrorMode.DEFAULT:
            default:
                log.error("Reactive error processing item {}: {}", item, t.message, t)
                break
        }
    }
}
