package org.softwood.dag.task.manualtask

import groovy.util.logging.Slf4j

/**
 * Webhook notification channel for ManualTask.
 * 
 * Sends HTTP POST requests to configured webhook URL with notification data.
 * This is a simple implementation that logs webhook calls.
 * In production, you would make actual HTTP requests.
 */
@Slf4j
class WebhookNotificationChannel implements NotificationChannel {
    
    /** Webhook URL to POST to */
    String webhookUrl
    
    /** HTTP headers to include */
    Map<String, String> headers = [:]
    
    /** Flag to control if channel is enabled */
    boolean enabled = true
    
    WebhookNotificationChannel(Map config = [:]) {
        this.webhookUrl = config.webhookUrl
        this.headers = config.headers ?: [:]
        this.enabled = config.enabled != null ? config.enabled : true
        
        // Default content-type
        if (!this.headers['Content-Type']) {
            this.headers['Content-Type'] = 'application/json'
        }
    }
    
    @Override
    void send(NotificationMessage message) {
        if (!enabled) {
            log.debug("Webhook channel disabled - skipping notification")
            return
        }
        
        if (!webhookUrl) {
            log.warn("Webhook URL not configured - skipping notification")
            return
        }
        
        // Build webhook payload
        def payload = [
            taskId: message.taskId,
            taskTitle: message.taskTitle,
            type: message.type.toString(),
            recipient: message.recipient,
            subject: message.subject,
            body: message.body,
            priority: message.priority,
            dueDate: message.dueDate?.toString(),
            taskUrl: message.taskUrl,
            metadata: message.metadata
        ]
        
        // TODO: Implement actual HTTP POST
        // For now, just log the webhook call
        log.info("WEBHOOK NOTIFICATION:")
        log.info("  URL: ${webhookUrl}")
        log.info("  Type: ${message.type}")
        log.info("  Payload: ${payload}")
        
        // In production, make HTTP POST:
        // httpPost(webhookUrl, payload, headers)
    }
    
    @Override
    String getChannelType() {
        return "webhook"
    }
    
    @Override
    boolean isEnabled() {
        return enabled
    }
    
    /**
     * Create a simple webhook channel for testing/development.
     */
    static WebhookNotificationChannel createSimple(String webhookUrl) {
        return new WebhookNotificationChannel(webhookUrl: webhookUrl, enabled: true)
    }
}
