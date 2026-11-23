package org.softwood.dag.task.mail

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

/**
 * Stub mail provider for testing without real SMTP server.
 * 
 * <p>Records all emails sent and provides inspection methods.
 * Useful for:</p>
 * <ul>
 *   <li>Unit tests</li>
 *   <li>Integration tests</li>
 *   <li>Development/debugging</li>
 *   <li>CI/CD pipelines</li>
 * </ul>
 * 
 * <h2>Usage</h2>
 * <pre>
 * def provider = new StubProvider()
 * provider.initialize()
 * 
 * mailTask("test") {
 *     provider provider
 *     email {
 *         from "test@example.com"
 *         to "user@example.com"
 *         subject "Test"
 *         text "Test body"
 *     }
 * }
 * 
 * // Verify in test
 * assert provider.getSentEmails().size() == 1
 * assert provider.getSentEmails()[0].subject == "Test"
 * </pre>
 * 
 * @since 2.1.0
 */
@Slf4j
@CompileStatic
class StubProvider implements MailProvider {
    
    /** List of all sent emails */
    private final List<Map<String, Object>> sentEmails = []
    
    /** Whether provider is initialized */
    private boolean initialized = false
    
    /** Simulated send delay (milliseconds) */
    long simulatedDelay = 0
    
    /** Whether to simulate failures */
    boolean simulateFailure = false
    
    /** Failure message for simulated failures */
    String failureMessage = "Simulated send failure"
    
    @Override
    void initialize() {
        log.info("StubProvider: initialized")
        initialized = true
    }
    
    @Override
    void close() {
        log.info("StubProvider: closed (sent ${sentEmails.size()} emails)")
        initialized = false
    }
    
    @Override
    Map<String, Object> sendEmail(Map<String, Object> emailConfig) {
        checkInitialized()
        
        // Simulate delay if configured
        if (simulatedDelay > 0) {
            log.debug("StubProvider: simulating ${simulatedDelay}ms delay")
            Thread.sleep(simulatedDelay)
        }
        
        // Simulate failure if configured
        if (simulateFailure) {
            log.debug("StubProvider: simulating failure")
            throw new RuntimeException(failureMessage)
        }
        
        // Validate email config
        validateEmailConfig(emailConfig)
        
        // Record email
        def emailCopy = new HashMap<String, Object>(emailConfig)
        emailCopy.timestamp = new Date()
        sentEmails << emailCopy
        
        log.info("StubProvider: 'sent' email to ${emailConfig.to} (total: ${sentEmails.size()})")
        
        // Return success result
        return [
            success: true,
            messageId: "stub-${UUID.randomUUID()}",
            timestamp: new Date(),
            provider: 'stub'
        ]
    }
    
    @Override
    List<Map<String, Object>> sendBulk(List<Map<String, Object>> emailConfigs) {
        checkInitialized()
        
        log.info("StubProvider: sending bulk (${emailConfigs.size()} emails)")
        
        // Send each email individually
        return emailConfigs.collect { sendEmail(it) }
    }
    
    @Override
    boolean testConnection() {
        log.debug("StubProvider: test connection")
        return initialized
    }
    
    @Override
    Object execute(Closure closure) {
        checkInitialized()
        log.debug("StubProvider: execute custom closure")
        
        // Pass stub provider itself to closure
        return closure.call(this)
    }
    
    @Override
    String getProviderName() {
        return "Stub"
    }
    
    // =========================================================================
    // Inspection Methods (for testing)
    // =========================================================================
    
    /**
     * Get all sent emails.
     * 
     * @return List of email configs (with timestamp added)
     */
    List<Map<String, Object>> getSentEmails() {
        return new ArrayList<>(sentEmails)
    }
    
    /**
     * Get emails sent to a specific recipient.
     * 
     * @param recipientEmail Email address to filter by
     * @return List of matching emails
     */
    List<Map<String, Object>> getEmailsTo(String recipientEmail) {
        return sentEmails.findAll { email ->
            def toRecipients = email.to as List<Map>
            toRecipients.any { it.address == recipientEmail }
        }
    }
    
    /**
     * Get emails with specific subject.
     * 
     * @param subject Subject to filter by
     * @return List of matching emails
     */
    List<Map<String, Object>> getEmailsWithSubject(String subject) {
        return sentEmails.findAll { it.subject == subject }
    }
    
    /**
     * Get last sent email.
     * 
     * @return Last email or null if none sent
     */
    Map<String, Object> getLastEmail() {
        return sentEmails.isEmpty() ? null : sentEmails.last()
    }
    
    /**
     * Get number of emails sent.
     * 
     * @return Count of sent emails
     */
    int getEmailCount() {
        return sentEmails.size()
    }
    
    /**
     * Clear all sent emails.
     */
    void clear() {
        log.debug("StubProvider: clearing ${sentEmails.size()} emails")
        sentEmails.clear()
    }
    
    /**
     * Reset provider to initial state.
     */
    void reset() {
        clear()
        initialized = false
        simulatedDelay = 0
        simulateFailure = false
    }
    
    // =========================================================================
    // Validation
    // =========================================================================
    
    private void checkInitialized() {
        if (!initialized) {
            throw new IllegalStateException("StubProvider not initialized - call initialize() first")
        }
    }
    
    private void validateEmailConfig(Map<String, Object> emailConfig) {
        // Check for null
        if (emailConfig == null) {
            throw new IllegalArgumentException("Email config cannot be null")
        }
        
        // Validate required fields
        if (!emailConfig.from) {
            throw new IllegalArgumentException("Email 'from' not specified")
        }
        if (!emailConfig.to || ((List) emailConfig.to).isEmpty()) {
            throw new IllegalArgumentException("Email 'to' recipients not specified")
        }
        if (!emailConfig.subject) {
            throw new IllegalArgumentException("Email 'subject' not specified")
        }
        if (!emailConfig.plainText && !emailConfig.htmlText) {
            throw new IllegalArgumentException("Email body not specified")
        }
        
        // Additional validation
        def from = emailConfig.from as Map
        if (!from.address) {
            throw new IllegalArgumentException("From address not specified")
        }
        
        def toList = emailConfig.to as List<Map>
        toList.each { recipient ->
            if (!recipient.address) {
                throw new IllegalArgumentException("Recipient address not specified")
            }
        }
    }
}
