package org.softwood.reactive

/**
 * Custom exceptions for queue-specific error scenarios
 */
class QueueException extends RuntimeException {
    QueueException(String message) {
        super(message)
    }

    QueueException(String message, Throwable cause) {
        super(message, cause)
    }

    static class QueueFullException extends QueueException {
        QueueFullException(String message) {
            super(message)
        }
    }

    /**
     * Thrown when publishing to the sink fails after retries.
     */
    static class PublishingException extends QueueException {

        /**
         * Construct with message only.
         */
        PublishingException(String message) {
            super(message)
        }

        /**
         * Construct with message + cause.
         */
        PublishingException(String message, Throwable cause) {
            super(message, cause)
        }
    }

}