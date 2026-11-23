package org.softwood.dag.task.manualtask

import java.time.LocalDateTime

/**
 * Message to be sent via notification channels.
 */
class NotificationMessage {
    
    /** Recipient identifier (email address, Slack user, etc.) */
    String recipient
    
    /** Notification subject/title */
    String subject
    
    /** Notification body/content */
    String body
    
    /** Task ID this notification is about */
    String taskId
    
    /** Task title */
    String taskTitle
    
    /** Priority level */
    String priority
    
    /** Due date (if any) */
    LocalDateTime dueDate
    
    /** Notification type (ASSIGNMENT, ESCALATION, COMPLETION, REMINDER) */
    NotificationType type
    
    /** Additional metadata */
    Map<String, Object> metadata = [:]
    
    /** URL to access the task (if applicable) */
    String taskUrl
    
    /**
     * Create a notification message.
     */
    static NotificationMessage create(Map params) {
        def message = new NotificationMessage()
        message.recipient = params.recipient
        message.subject = params.subject
        message.body = params.body
        message.taskId = params.taskId
        message.taskTitle = params.taskTitle
        message.priority = params.priority
        message.dueDate = params.dueDate
        message.type = params.type as NotificationType
        message.metadata = params.metadata ?: [:]
        message.taskUrl = params.taskUrl
        return message
    }
}

/**
 * Types of notifications.
 */
enum NotificationType {
    /** Task has been assigned */
    ASSIGNMENT,
    
    /** Task has been escalated */
    ESCALATION,
    
    /** Task has been completed */
    COMPLETION,
    
    /** Reminder about pending task */
    REMINDER
}
