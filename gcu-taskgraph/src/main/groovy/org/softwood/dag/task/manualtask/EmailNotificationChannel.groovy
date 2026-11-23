package org.softwood.dag.task.manualtask

import groovy.util.logging.Slf4j

/**
 * Email notification channel for ManualTask.
 * 
 * This is a simple implementation that logs email notifications.
 * In production, you would integrate with an actual email service (SMTP, SendGrid, etc.).
 */
@Slf4j
class EmailNotificationChannel implements NotificationChannel {
    
    /** Email server configuration */
    String smtpHost
    int smtpPort = 587
    String username
    String password
    String fromAddress
    
    /** Flag to control if channel is enabled */
    boolean enabled = true
    
    EmailNotificationChannel(Map config = [:]) {
        this.smtpHost = config.smtpHost
        this.smtpPort = config.smtpPort ?: 587
        this.username = config.username
        this.password = config.password
        this.fromAddress = config.fromAddress ?: "noreply@example.com"
        this.enabled = config.enabled != null ? config.enabled : true
    }
    
    @Override
    void send(NotificationMessage message) {
        if (!enabled) {
            log.debug("Email channel disabled - skipping notification to ${message.recipient}")
            return
        }
        
        // TODO: Implement actual email sending
        // For now, just log the notification
        log.info("EMAIL NOTIFICATION:")
        log.info("  To: ${message.recipient}")
        log.info("  From: ${fromAddress}")
        log.info("  Subject: ${message.subject}")
        log.info("  Type: ${message.type}")
        log.info("  Body: ${message.body}")
        
        // In production, integrate with email service:
        // sendViaSmtp(message) or sendViaApi(message)
    }
    
    @Override
    String getChannelType() {
        return "email"
    }
    
    @Override
    boolean isEnabled() {
        return enabled
    }
    
    /**
     * Create a simple email channel for testing/development.
     */
    static EmailNotificationChannel createSimple(String fromAddress = "noreply@example.com") {
        return new EmailNotificationChannel(fromAddress: fromAddress, enabled: true)
    }
}
