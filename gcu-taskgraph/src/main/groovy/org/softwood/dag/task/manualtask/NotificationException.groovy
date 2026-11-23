package org.softwood.dag.task.manualtask

/**
 * Exception thrown when notification fails.
 */
class NotificationException extends Exception {
    
    /** The channel that failed */
    String channelType
    
    /** The message that failed to send */
    NotificationMessage failedMessage
    
    NotificationException(String message, String channelType, NotificationMessage failedMessage) {
        super(message)
        this.channelType = channelType
        this.failedMessage = failedMessage
    }
    
    NotificationException(String message, String channelType, NotificationMessage failedMessage, Throwable cause) {
        super(message, cause)
        this.channelType = channelType
        this.failedMessage = failedMessage
    }
}
