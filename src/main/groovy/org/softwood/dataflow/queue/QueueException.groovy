package org.softwood.dataflow.queue

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

    static class PublishingException extends QueueException {
        PublishingException(String message, Throwable cause) {
            super(message, cause)
        }
    }
}