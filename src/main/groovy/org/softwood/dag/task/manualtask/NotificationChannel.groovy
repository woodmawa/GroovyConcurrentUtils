package org.softwood.dag.task.manualtask

/**
 * Interface for notification channels used by ManualTask.
 * 
 * Implementations provide different ways to notify users about manual tasks
 * (e.g., email, Slack, webhook, SMS).
 */
interface NotificationChannel {
    
    /**
     * Send a notification message.
     * 
     * @param message the notification message to send
     * @throws NotificationException if notification fails
     */
    void send(NotificationMessage message)
    
    /**
     * Get the type of this notification channel (e.g., "email", "slack").
     * 
     * @return channel type identifier
     */
    String getChannelType()
    
    /**
     * Check if this channel is enabled and ready to send notifications.
     * 
     * @return true if channel is ready, false otherwise
     */
    boolean isEnabled()
}
