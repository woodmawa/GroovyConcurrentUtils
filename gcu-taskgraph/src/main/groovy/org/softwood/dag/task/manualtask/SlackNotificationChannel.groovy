package org.softwood.dag.task.manualtask

import groovy.util.logging.Slf4j

/**
 * Slack notification channel for ManualTask.
 * 
 * This is a simple implementation that logs Slack notifications.
 * In production, you would integrate with Slack API (webhook or API).
 */
@Slf4j
class SlackNotificationChannel implements NotificationChannel {
    
    /** Slack webhook URL or API token */
    String webhookUrl
    String apiToken
    
    /** Default channel to post to */
    String defaultChannel
    
    /** Flag to control if channel is enabled */
    boolean enabled = true
    
    SlackNotificationChannel(Map config = [:]) {
        this.webhookUrl = config.webhookUrl
        this.apiToken = config.apiToken
        this.defaultChannel = config.defaultChannel ?: "#general"
        this.enabled = config.enabled != null ? config.enabled : true
    }
    
    @Override
    void send(NotificationMessage message) {
        if (!enabled) {
            log.debug("Slack channel disabled - skipping notification to ${message.recipient}")
            return
        }
        
        // TODO: Implement actual Slack API integration
        // For now, just log the notification
        log.info("SLACK NOTIFICATION:")
        log.info("  Channel: ${message.recipient ?: defaultChannel}")
        log.info("  Type: ${message.type}")
        log.info("  Task: ${message.taskTitle}")
        log.info("  Message: ${message.body}")
        
        // In production, integrate with Slack:
        // postToSlack(message) via webhook or API
    }
    
    @Override
    String getChannelType() {
        return "slack"
    }
    
    @Override
    boolean isEnabled() {
        return enabled
    }
    
    /**
     * Create a simple Slack channel for testing/development.
     */
    static SlackNotificationChannel createSimple(String defaultChannel = "#notifications") {
        return new SlackNotificationChannel(defaultChannel: defaultChannel, enabled: true)
    }
}
