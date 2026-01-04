package org.softwood.dag.task

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.softwood.dag.task.mail.EmailBuilder
import org.softwood.dag.task.mail.MailProvider
import org.softwood.dag.task.mail.SmtpProvider
import org.softwood.promise.Promise

/**
 * DAG task for sending emails using pluggable mail providers.
 *
 * <p>Supports:</p>
 * <ul>
 *   <li>Single email sending</li>
 *   <li>Bulk email campaigns</li>
 *   <li>Template-based emails</li>
 *   <li>Dynamic content from previous tasks</li>
 *   <li>Secure credential management</li>
 *   <li>SMTP, SendGrid, AWS SES (via providers)</li>
 * </ul>
 *
 * <h2>Basic Example</h2>
 * <pre>
 * import org.softwood.dag.task.mail.SmtpProvider
 *
 * graph {
 *     mailTask("welcome-email") {
 *         smtp {
 *             host = "smtp.gmail.com"
 *             port = 587
 *             username = "app@company.com"
 *             password = "app-password"
 *             transportStrategy = SmtpProvider.TransportStrategy.SMTP_TLS
 *         }
 *
 *         email {
 *             from "noreply@company.com", "Company Name"
 *             to "user@example.com", "John Doe"
 *             subject "Welcome to Our Service!"
 *             html "&lt;h1&gt;Welcome!&lt;/h1&gt;&lt;p&gt;Thanks for signing up.&lt;/p&gt;"
 *         }
 *     }
 * }
 * </pre>
 *
 * <h2>Dynamic Content from Previous Task</h2>
 * <pre>
 * graph {
 *     task("create-order") {
 *         action { ctx, prev ->
 *             [orderId: "12345", userEmail: "user@example.com", total: 99.99]
 *         }
 *     }
 *
 *     mailTask("confirmation") {
 *         dependsOn "create-order"
 *         smtp mySmtpProvider
 *
 *         email { r ->
 *             from r.global('fromEmail', 'noreply@company.com')
 *             to r.prev.userEmail
 *             subject "Order #${r.prev.orderId} Confirmed"
 *
 *             template '''
 *                 &lt;h1&gt;Order Confirmed!&lt;/h1&gt;
 *                 &lt;p&gt;Order ID: ${prev.orderId}&lt;/p&gt;
 *                 &lt;p&gt;Total: \$${prev.total}&lt;/p&gt;
 *             '''
 *         }
 *     }
 * }
 * </pre>
 *
 * <h2>Secure Credentials</h2>
 * <pre>
 * import org.softwood.security.SecretsResolver
 *
 * mailTask("secure-send") {
 *     smtp { r ->
 *         host = "smtp.gmail.com"
 *         username = r.credential('smtp.username')
 *         password = r.credential('smtp.password')
 *     }
 *
 *     email {
 *         from "app@company.com"
 *         to "user@example.com"
 *         subject "Secure Email"
 *         text "This email uses secure credential management"
 *     }
 * }
 * </pre>
 *
 * <h2>Bulk Email</h2>
 * <pre>
 * mailTask("campaign") {
 *     dependsOn "fetch-users"
 *     smtp myProvider
 *
 *     bulk { r ->
 *         r.prev.users.collect { user ->
 *             [
 *                 from: [address: "marketing@company.com"],
 *                 to: [[address: user.email, name: user.name]],
 *                 subject: "Special Offer Just for You!",
 *                 htmlText: renderTemplate("campaign", user)
 *             ]
 *         }
 *     }
 * }
 * </pre>
 *
 * @since 2.1.0
 */
@Slf4j
@CompileStatic
class MailTask extends TaskBase<Object> {

    // =========================================================================
    // Configuration
    // =========================================================================

    /** Mail provider (SMTP, SendGrid, etc.) */
    private MailProvider provider

    /** Task mode (SEND, SEND_BULK, EXECUTE) */
    private MailMode mode = MailMode.SEND

    /** Email builder (for SEND mode) */
    private EmailBuilder emailBuilder

    /** Bulk email configurations (for SEND_BULK mode) */
    private Closure bulkEmailsClosure

    /** Execute closure (for EXECUTE mode) */
    private Closure executeClosure

    /** Result mapper */
    private Closure resultMapper

    // =========================================================================
    // Constructors
    // =========================================================================

    MailTask(String id, String name, ctx) {
        super(id, name, ctx)
    }

    // =========================================================================
    // DSL Configuration Methods
    // =========================================================================

    /**
     * Set mail provider directly.
     *
     * <p>Use this when you have a pre-configured provider instance.</p>
     *
     * @param provider Mail provider instance
     */
    void provider(MailProvider provider) {
        this.provider = provider
    }

    /**
     * Configure SMTP provider.
     *
     * <p>Creates and configures an SmtpProvider instance.</p>
     *
     * <h3>Static Configuration:</h3>
     * <pre>
     * smtp {
     *     host = "smtp.gmail.com"
     *     port = 587
     *     username = "app@company.com"
     *     password = "password"
     *     transportStrategy = SmtpProvider.TransportStrategy.SMTP_TLS
     * }
     * </pre>
     *
     * <h3>Dynamic Configuration (with resolver):</h3>
     * <pre>
     * smtp { r ->
     *     host = "smtp.gmail.com"
     *     username = r.credential('smtp.username')
     *     password = r.credential('smtp.password')
     * }
     * </pre>
     *
     * @param config SMTP configuration closure
     */
    void smtp(@DelegatesTo(SmtpProvider) Closure config) {
        // Check if closure expects a resolver parameter
        if (config.maximumNumberOfParameters > 0) {
            // Defer configuration until execution time (when resolver is available)
            this.executeClosure = config
            this.mode = MailMode.CONFIGURE_PROVIDER
        } else {
            // Configure immediately (no resolver needed)
            def smtpProvider = new SmtpProvider()
            config.delegate = smtpProvider
            config.resolveStrategy = Closure.DELEGATE_FIRST
            config.call()
            smtpProvider.initialize()
            this.provider = smtpProvider
        }
    }

    /**
     * Configure email for single send.
     *
     * <h3>Static Example:</h3>
     * <pre>
     * email {
     *     from "noreply@company.com"
     *     to "user@example.com"
     *     subject "Hello"
     *     text "Hello, World!"
     * }
     * </pre>
     *
     * <h3>Dynamic Example:</h3>
     * <pre>
     * email { r ->
     *     from r.global('fromEmail')
     *     to r.prev.userEmail
     *     subject "Order #${r.prev.orderId}"
     *     template '''
     *         &lt;h1&gt;Order Confirmed&lt;/h1&gt;
     *         &lt;p&gt;ID: ${prev.orderId}&lt;/p&gt;
     *     '''
     * }
     * </pre>
     *
     * @param config Email configuration closure
     */
    void email(Closure config) {
        this.emailBuilder = new EmailBuilder()
        this.emailBuilder.configure(config)
        // Only set mode to SEND if not deferred provider configuration
        if (this.mode != MailMode.CONFIGURE_PROVIDER) {
            this.mode = MailMode.SEND
        }
    }

    /**
     * Configure bulk email sending.
     *
     * <p>The closure should return a list of email configuration maps.</p>
     *
     * <h3>Example:</h3>
     * <pre>
     * bulk { r ->
     *     r.prev.users.collect { user ->
     *         [
     *             from: [address: "marketing@company.com"],
     *             to: [[address: user.email, name: user.name]],
     *             subject: "Special Offer",
     *             htmlText: "&lt;h1&gt;Hello ${user.name}!&lt;/h1&gt;"
     *         ]
     *     }
     * }
     * </pre>
     *
     * @param closure Closure returning list of email configs
     */
    void bulk(Closure closure) {
        this.bulkEmailsClosure = closure
        // Only set mode to SEND_BULK if not deferred provider configuration
        if (this.mode != MailMode.CONFIGURE_PROVIDER) {
            this.mode = MailMode.SEND_BULK
        }
    }

    /**
     * Execute custom code with native mailer.
     *
     * <p>Provides access to underlying mail library for advanced use cases.</p>
     *
     * <h3>Example (Simple Java Mail):</h3>
     * <pre>
     * execute { r, mailer ->
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
     * @param closure Closure receiving resolver and native mailer
     */
    void execute(Closure closure) {
        this.executeClosure = closure
        this.mode = MailMode.EXECUTE
    }

    /**
     * Transform send result.
     *
     * <p>Allows mapping the provider's result to a different format.</p>
     *
     * <h3>Example:</h3>
     * <pre>
     * resultMapper { result ->
     *     [
     *         sent: result.success,
     *         messageId: result.messageId,
     *         timestamp: new Date()
     *     ]
     * }
     * </pre>
     *
     * @param mapper Result transformation closure
     */
    void resultMapper(Closure mapper) {
        this.resultMapper = mapper
    }

    // =========================================================================
    // Task Execution
    // =========================================================================

    @Override
    protected Promise<Object> runTask(TaskContext ctx, Object prevValue) {
        return ctx.promiseFactory.executeAsync {
            // Validate configuration
            if (!provider && mode != MailMode.CONFIGURE_PROVIDER) {
                throw new IllegalStateException(
                        "MailTask '${id}': provider not configured - use smtp{} or provider()"
                )
            }

            // Create resolver for dynamic content
            def resolver = createResolver(prevValue, ctx)

            // Execute based on mode
            switch (mode) {
                case MailMode.CONFIGURE_PROVIDER:
                    return configureProviderMode(resolver)

                case MailMode.SEND:
                    return executeSend(resolver)

                case MailMode.SEND_BULK:
                    return executeBulk(resolver)

                case MailMode.EXECUTE:
                    return executeCustom(resolver)

                default:
                    throw new IllegalStateException("MailTask '${id}': unknown mode ${mode}")
            }
        } as Promise<Object>
    }

    /**
     * Configure provider at execution time (with resolver support).
     */
    private Object configureProviderMode(TaskResolver resolver) {
        def smtpProvider = new SmtpProvider()
        executeClosure.delegate = smtpProvider
        executeClosure.resolveStrategy = Closure.DELEGATE_FIRST
        executeClosure.call(resolver)
        smtpProvider.initialize()
        this.provider = smtpProvider

        // Switch to SEND mode and execute
        this.mode = MailMode.SEND
        return executeSend(resolver)
    }

    /**
     * Send single email.
     */
    private Object executeSend(TaskResolver resolver) {
        if (!emailBuilder) {
            throw new IllegalStateException("MailTask '${id}': email not configured")
        }

        // Build email configuration with resolver
        def emailConfig = emailBuilder.build(resolver)

        log.info("MailTask '${id}': sending email to ${emailConfig.to}")

        // Send via provider
        def result = provider.sendEmail(emailConfig)

        log.info("MailTask '${id}': email sent successfully (messageId: ${result.messageId})")

        return applyMapper(result)
    }

    /**
     * Send bulk emails.
     */
    private Object executeBulk(TaskResolver resolver) {
        if (!bulkEmailsClosure) {
            throw new IllegalStateException("MailTask '${id}': bulk emails not configured")
        }

        // Get email configurations from closure
        def emailConfigs = bulkEmailsClosure.call(resolver) as List<Map<String, Object>>

        if (!emailConfigs || emailConfigs.isEmpty()) {
            log.warn("MailTask '${id}': no emails to send in bulk")
            return []
        }

        log.info("MailTask '${id}': sending ${emailConfigs.size()} emails")

        // Send via provider
        def results = provider.sendBulk(emailConfigs)

        log.info("MailTask '${id}': ${results.size()} emails sent")

        return applyMapper(results)
    }

    /**
     * Execute custom mailer code.
     */
    private Object executeCustom(TaskResolver resolver) {
        if (!executeClosure) {
            throw new IllegalStateException("MailTask '${id}': execute closure not configured")
        }

        log.info("MailTask '${id}': executing custom mailer code")

        // Execute with resolver and native mailer
        def result = provider.execute { nativeMailer ->
            executeClosure.call(resolver, nativeMailer)
        }

        log.info("MailTask '${id}': custom execution complete")

        return applyMapper(result)
    }

    /**
     * Apply result mapper if configured.
     */
    private Object applyMapper(Object results) {
        if (resultMapper) {
            return resultMapper.call(results)
        }
        return results
    }

    /**
     * Mail task modes.
     */
    static enum MailMode {
        /** Configure provider at execution time */
        CONFIGURE_PROVIDER,

        /** Send single email */
        SEND,

        /** Send bulk emails */
        SEND_BULK,

        /** Execute custom mailer code */
        EXECUTE
    }
}
