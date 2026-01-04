package org.softwood.dag.task.mail

import groovy.transform.CompileStatic

/**
 * Provider interface for email sending in MailTask.
 * 
 * <p>Supports pluggable email implementations:</p>
 * <ul>
 *   <li>{@link SmtpProvider} - SMTP/SMTPS using Simple Java Mail</li>
 *   <li>{@link StubProvider} - Testing without real mail server</li>
 *   <li>Future: SendGridProvider, AwsSesProvider, etc.</li>
 * </ul>
 * 
 * <p>All providers must implement AutoCloseable for proper resource cleanup.</p>
 * 
 * @since 2.1.0
 */
@CompileStatic
interface MailProvider extends AutoCloseable {
    
    /**
     * Initialize the provider.
     * 
     * <p>Called once before any send operations.
     * Use this to establish connections, validate configuration, etc.</p>
     * 
     * @throws IllegalStateException if configuration is invalid
     */
    void initialize()
    
    /**
     * Clean up provider resources.
     * 
     * <p>Called when provider is no longer needed.
     * Must be idempotent (safe to call multiple times).</p>
     */
    @Override
    void close()
    
    /**
     * Send a single email.
     * 
     * <p>Email configuration map must contain:</p>
     * <ul>
     *   <li><code>from</code> - Map with 'address' and optional 'name'</li>
     *   <li><code>to</code> - List of recipient maps with 'address' and optional 'name'</li>
     *   <li><code>subject</code> - Email subject line</li>
     *   <li><code>plainText</code> or <code>htmlText</code> - Email body</li>
     * </ul>
     * 
     * <p>Optional fields:</p>
     * <ul>
     *   <li><code>cc</code> - CC recipients (same format as 'to')</li>
     *   <li><code>bcc</code> - BCC recipients (same format as 'to')</li>
     *   <li><code>replyTo</code> - Reply-to address (string)</li>
     *   <li><code>attachments</code> - List of attachment maps</li>
     *   <li><code>headers</code> - Map of custom headers</li>
     *   <li><code>readReceipt</code> - Request read receipt (boolean)</li>
     *   <li><code>returnReceipt</code> - Request return receipt (boolean)</li>
     * </ul>
     * 
     * @param emailConfig Email configuration map
     * @return Result map with status and message ID
     * @throws IllegalArgumentException if configuration is invalid
     * @throws RuntimeException if send fails
     */
    Map<String, Object> sendEmail(Map<String, Object> emailConfig)
    
    /**
     * Send multiple emails in bulk.
     * 
     * <p>More efficient than multiple sendEmail() calls when supported
     * by the underlying provider (e.g., connection reuse, batch API).</p>
     * 
     * @param emailConfigs List of email configurations
     * @return List of result maps (one per email)
     */
    List<Map<String, Object>> sendBulk(List<Map<String, Object>> emailConfigs)
    
    /**
     * Test connection to mail server.
     * 
     * <p>Validates configuration and connectivity without sending email.
     * Useful for health checks and troubleshooting.</p>
     * 
     * @return true if connection successful, false otherwise
     */
    boolean testConnection()
    
    /**
     * Execute custom code with native provider API.
     * 
     * <p>Provides access to underlying mail library for advanced use cases
     * not covered by the standard interface.</p>
     * 
     * <p><strong>Example (Simple Java Mail):</strong></p>
     * <pre>
     * provider.execute { mailer ->
     *     def email = EmailBuilder.startingBlank()
     *         .from("test@example.com")
     *         .to("user@example.com")
     *         .withSubject("Test")
     *         .signWithDomainKey(privateKey, "example.com", "selector")
     *         .buildEmail()
     *     
     *     mailer.sendMail(email, true)  // async
     *     return [success: true]
     * }
     * </pre>
     * 
     * @param closure Closure receiving native provider object
     * @return Result from closure
     */
    Object execute(Closure closure)
    
    /**
     * Get provider name for logging and debugging.
     * 
     * @return Human-readable provider name (e.g., "SMTP", "SendGrid", "Stub")
     */
    String getProviderName()
}
