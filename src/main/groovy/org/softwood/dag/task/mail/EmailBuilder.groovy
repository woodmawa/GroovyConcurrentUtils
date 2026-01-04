package org.softwood.dag.task.mail

import groovy.transform.CompileStatic
import org.softwood.dag.task.TaskResolver

/**
 * DSL builder for constructing email configurations.
 * 
 * <p>Works with {@link org.softwood.dag.task.TaskResolver} to enable dynamic
 * email composition based on previous task results and context globals.</p>
 * 
 * <h2>Basic Usage</h2>
 * <pre>
 * mailTask("welcome") { r ->
 *     email {
 *         from "noreply@company.com", "Company Name"
 *         to "user@example.com", "John Doe"
 *         subject "Welcome!"
 *         html "&lt;h1&gt;Welcome to our service!&lt;/h1&gt;"
 *     }
 * }
 * </pre>
 * 
 * <h2>Dynamic Content with Resolver</h2>
 * <pre>
 * mailTask("notification") { r ->
 *     email {
 *         from r.global('fromEmail')
 *         to r.prev.userEmail
 *         subject "Order #${r.prev.orderId} Confirmed"
 *         
 *         template '''
 *             &lt;h1&gt;Order Confirmed&lt;/h1&gt;
 *             &lt;p&gt;Order: ${prev.orderId}&lt;/p&gt;
 *             &lt;p&gt;Total: \$${prev.total}&lt;/p&gt;
 *         '''
 *     }
 * }
 * </pre>
 * 
 * @since 2.1.0
 */
@CompileStatic
class EmailBuilder {
    
    // Email components
    private EmailConfig config = new EmailConfig()
    
    // Configuration closure (resolved at execution time)
    private Closure configClosure
    
    /**
     * Configure email with closure that receives resolver.
     * 
     * @param closure Configuration closure
     */
    void configure(@DelegatesTo(EmailBuilderDSL) Closure closure) {
        this.configClosure = closure
    }
    
    /**
     * Build email configuration using resolver.
     * 
     * <p>Executes the configuration closure in the context of a resolver,
     * allowing access to previous results, globals, and credentials.</p>
     * 
     * @param resolver Task resolver for dynamic content
     * @return Email configuration map
     * @throws IllegalStateException if required fields missing
     */
    Map<String, Object> build(TaskResolver resolver) {
        if (!configClosure) {
            throw new IllegalStateException("Email not configured - call configure() first")
        }
        
        // Reset config for new build
        config = new EmailConfig()
        
        // Create DSL and execute configuration
        def dsl = new EmailBuilderDSL(config, resolver)
        configClosure.delegate = dsl
        configClosure.resolveStrategy = Closure.DELEGATE_FIRST
        configClosure.call(resolver)
        
        // Validate and convert to map
        return config.toMap()
    }
    
    /**
     * Internal configuration holder.
     */
    @CompileStatic
    static class EmailConfig {
        String fromAddress
        String fromName
        List<Recipient> toRecipients = []
        List<Recipient> ccRecipients = []
        List<Recipient> bccRecipients = []
        String replyToAddress
        String subject
        String plainText
        String htmlText
        List<Attachment> attachments = []
        Map<String, String> headers = [:]
        boolean requestReadReceipt = false
        boolean requestReturnReceipt = false
        
        /**
         * Convert to map format expected by MailProvider.
         */
        Map<String, Object> toMap() {
            // Validate required fields
            if (!fromAddress) {
                throw new IllegalStateException("Email 'from' address not specified")
            }
            if (toRecipients.isEmpty()) {
                throw new IllegalStateException("No 'to' recipients specified")
            }
            if (!subject) {
                throw new IllegalStateException("Email 'subject' not specified")
            }
            if (!plainText && !htmlText) {
                throw new IllegalStateException("Email body not specified (need 'text' or 'html')")
            }
            
            return [
                from: [address: fromAddress, name: fromName],
                to: toRecipients.collect { [address: it.address, name: it.name] },
                cc: ccRecipients.collect { [address: it.address, name: it.name] },
                bcc: bccRecipients.collect { [address: it.address, name: it.name] },
                replyTo: replyToAddress,
                subject: subject,
                plainText: plainText,
                htmlText: htmlText,
                attachments: attachments.collect { [
                    filename: it.filename,
                    data: it.data,
                    mimeType: it.mimeType
                ] },
                headers: headers,
                readReceipt: requestReadReceipt,
                returnReceipt: requestReturnReceipt
            ]
        }
    }
    
    /**
     * Email recipient.
     */
    @CompileStatic
    static class Recipient {
        String address
        String name
        
        Recipient(String address, String name = null) {
            this.address = address
            this.name = name
        }
    }
    
    /**
     * Email attachment.
     */
    @CompileStatic
    static class Attachment {
        String filename
        byte[] data
        String mimeType
        
        Attachment(String filename, byte[] data, String mimeType = null) {
            this.filename = filename
            this.data = data
            this.mimeType = mimeType
        }
    }
}

/**
 * DSL for email building with resolver access.
 * 
 * <p>Provides methods for setting email fields with support for:
 * - Static values
 * - GString interpolation
 * - Closure evaluation with resolver
 * - Template rendering
 * </p>
 */
@CompileStatic
class EmailBuilderDSL {
    
    private final EmailBuilder.EmailConfig config
    private final TaskResolver resolver
    
    EmailBuilderDSL(EmailBuilder.EmailConfig config, TaskResolver resolver) {
        this.config = config
        this.resolver = resolver
    }
    
    /**
     * Expose resolver for direct access in closures.
     */
    TaskResolver getR() { resolver }
    TaskResolver getResolver() { resolver }
    
    // =========================================================================
    // Sender
    // =========================================================================
    
    /**
     * Set sender address and optional name.
     * 
     * @param address Email address
     * @param name Display name (optional)
     */
    void from(String address, String name = null) {
        config.fromAddress = resolveValue(address)
        config.fromName = name != null ? resolveValue(name) : null
    }
    
    /**
     * Set sender using closure.
     * 
     * @param addressClosure Closure returning email address
     */
    void from(Closure<String> addressClosure) {
        config.fromAddress = addressClosure.call(resolver)
    }
    
    // =========================================================================
    // Recipients
    // =========================================================================
    
    /**
     * Add TO recipient.
     * 
     * @param address Email address
     * @param name Display name (optional)
     */
    void to(String address, String name = null) {
        config.toRecipients << new EmailBuilder.Recipient(
            resolveValue(address),
            name != null ? resolveValue(name) : null
        )
    }
    
    /**
     * Add multiple TO recipients.
     * 
     * @param addresses List of email addresses or recipient maps
     */
    void to(List addresses) {
        addresses.each { 
            if (it instanceof Map) {
                to(it.address as String, it.name as String)
            } else {
                to(it.toString())
            }
        }
    }
    
    /**
     * Add TO recipient(s) using closure.
     * 
     * @param closure Closure returning address, list, or map
     */
    void to(Closure closure) {
        def result = closure.call(resolver)
        if (result instanceof List) {
            to(result as List)
        } else if (result instanceof Map) {
            to(result.address as String, result.name as String)
        } else {
            to(result.toString())
        }
    }
    
    /**
     * Add CC recipient.
     * 
     * @param address Email address
     * @param name Display name (optional)
     */
    void cc(String address, String name = null) {
        config.ccRecipients << new EmailBuilder.Recipient(
            resolveValue(address),
            name != null ? resolveValue(name) : null
        )
    }
    
    /**
     * Add multiple CC recipients.
     * 
     * @param addresses List of email addresses
     */
    void cc(List addresses) {
        addresses.each { cc(it.toString()) }
    }
    
    /**
     * Add BCC recipient.
     * 
     * @param address Email address
     * @param name Display name (optional)
     */
    void bcc(String address, String name = null) {
        config.bccRecipients << new EmailBuilder.Recipient(
            resolveValue(address),
            name != null ? resolveValue(name) : null
        )
    }
    
    /**
     * Add multiple BCC recipients.
     * 
     * @param addresses List of email addresses
     */
    void bcc(List addresses) {
        addresses.each { bcc(it.toString()) }
    }
    
    /**
     * Set reply-to address.
     * 
     * @param address Email address for replies
     */
    void replyTo(String address) {
        config.replyToAddress = resolveValue(address)
    }
    
    // =========================================================================
    // Content
    // =========================================================================
    
    /**
     * Set email subject.
     * 
     * @param subject Subject line
     */
    void subject(String subject) {
        config.subject = resolveValue(subject)
    }
    
    /**
     * Set subject using closure.
     * 
     * @param closure Closure returning subject
     */
    void subject(Closure<String> closure) {
        config.subject = closure.call(resolver)
    }
    
    /**
     * Set plain text body.
     * 
     * @param plainText Text content
     */
    void text(String plainText) {
        config.plainText = resolveValue(plainText)
    }
    
    /**
     * Set HTML body.
     * 
     * @param htmlText HTML content
     */
    void html(String htmlText) {
        config.htmlText = resolveValue(htmlText)
    }
    
    /**
     * Set both text and HTML (multipart).
     * 
     * @param text Plain text version
     * @param html HTML version
     */
    void body(String text, String html) {
        config.plainText = resolveValue(text)
        config.htmlText = resolveValue(html)
    }
    
    /**
     * Render Groovy template for HTML body.
     * 
     * <p>Template has access to prev, ctx, globals, r (resolver).</p>
     * 
     * @param templateText Template source
     * @param binding Additional variables (optional)
     */
    void template(String templateText, Map binding = [:]) {
        config.htmlText = resolver.template(templateText, binding)
    }
    
    /**
     * Render template from file.
     * 
     * @param filepath Path to template file
     * @param binding Additional variables (optional)
     */
    void templateFile(String filepath, Map binding = [:]) {
        config.htmlText = resolver.templateFile(filepath, binding)
    }
    
    /**
     * Render template from classpath resource.
     * 
     * @param resourcePath Classpath resource path
     * @param binding Additional variables (optional)
     */
    void templateResource(String resourcePath, Map binding = [:]) {
        config.htmlText = resolver.templateResource(resourcePath, binding)
    }
    
    // =========================================================================
    // Attachments
    // =========================================================================
    
    /**
     * Attach file data.
     * 
     * @param filename Attachment filename
     * @param data File bytes
     * @param mimeType MIME type (optional, auto-detected if not provided)
     */
    void attach(String filename, byte[] data, String mimeType = null) {
        config.attachments << new EmailBuilder.Attachment(filename, data, mimeType)
    }
    
    /**
     * Attach file from filesystem.
     * 
     * @param filepath Path to file
     * @throws FileNotFoundException if file not found
     */
    void attachFile(String filepath) {
        def file = new File(filepath)
        if (!file.exists()) {
            throw new FileNotFoundException("Attachment not found: ${filepath}")
        }
        attach(file.name, file.bytes)
    }
    
    /**
     * Attach files from list of paths.
     * 
     * @param filepaths List of file paths
     */
    void attachFiles(List<String> filepaths) {
        filepaths.each { attachFile(it) }
    }
    
    // =========================================================================
    // Headers & Options
    // =========================================================================
    
    /**
     * Add custom email header.
     * 
     * @param name Header name
     * @param value Header value
     */
    void header(String name, String value) {
        config.headers[name] = resolveValue(value)
    }
    
    /**
     * Add multiple headers.
     * 
     * @param headers Map of header name -> value
     */
    void headers(Map<String, String> headers) {
        headers.each { name, value ->
            header(name, value)
        }
    }
    
    /**
     * Request read receipt.
     * 
     * @param request true to request receipt (default: true)
     */
    void requestReadReceipt(boolean request = true) {
        config.requestReadReceipt = request
    }
    
    /**
     * Request return receipt.
     * 
     * @param request true to request receipt (default: true)
     */
    void requestReturnReceipt(boolean request = true) {
        config.requestReturnReceipt = request
    }
    
    // =========================================================================
    // Helpers
    // =========================================================================
    
    /**
     * Resolve value (handles GStrings automatically).
     */
    private String resolveValue(String value) {
        return value  // GStrings are auto-resolved in Groovy context
    }
}
