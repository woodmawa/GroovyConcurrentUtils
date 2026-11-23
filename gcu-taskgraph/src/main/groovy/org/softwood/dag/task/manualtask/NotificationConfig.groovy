package org.softwood.dag.task.manualtask

/**
 * Configuration for ManualTask notifications.
 * 
 * Defines when and how to send notifications for a manual task.
 */
class NotificationConfig {
    
    /** List of notification channels to use */
    List<NotificationChannel> channels = []
    
    /** Whether to notify when task is assigned */
    boolean notifyOnAssignment = true
    
    /** Whether to notify when task is escalated */
    boolean notifyOnEscalation = true
    
    /** Whether to notify when task is completed */
    boolean notifyOnCompletion = false
    
    /** Custom message template closure (optional) */
    Closure<String> messageTemplate
    
    /**
     * Add a notification channel.
     */
    void addChannel(NotificationChannel channel) {
        if (channel && channel.isEnabled()) {
            channels << channel
        }
    }
    
    /**
     * Send notification to all configured channels.
     */
    void sendNotification(NotificationMessage message) {
        channels.each { channel ->
            try {
                channel.send(message)
            } catch (Exception e) {
                // Log but don't fail the task if notification fails
                // This is handled by the channel itself
            }
        }
    }
    
    /**
     * Check if any channels are configured.
     */
    boolean hasChannels() {
        return !channels.isEmpty()
    }
    
    /**
     * Get count of enabled channels.
     */
    int getChannelCount() {
        return channels.size()
    }
}
