package org.softwood.dag.task.mail

import org.softwood.dag.task.MailTask
import org.softwood.dag.task.TaskContext
import org.softwood.promise.core.dataflow.DataflowPromiseFactory
import spock.lang.Specification

/**
 * Integration tests for MailTask.
 */
class MailTaskIntegrationTest extends Specification {

    TaskContext ctx
    StubProvider stubProvider

    def setup() {
        ctx = new TaskContext()
        ctx.globals = [:]
        // TaskContext already has promiseFactory initialized

        stubProvider = new StubProvider()
        stubProvider.initialize()
    }

    def cleanup() {
        stubProvider?.close()
    }

    // =========================================================================
    // Basic Email Sending
    // =========================================================================

    def "should send simple email"() {
        given:
        def task = new MailTask("test-mail", "Test Mail", ctx)

        when:
        task.provider(stubProvider)
        task.email {
            from "sender@example.com", "Sender Name"
            to "recipient@example.com", "Recipient Name"
            subject "Test Email"
            text "This is a test email"
        }

        def promise = task.runTask(ctx, null)
        def result = promise.get()

        then:
        result != null
        result.success == true
        result.messageId != null

        and: "email was actually sent"
        stubProvider.getSentEmails().size() == 1
        def sent = stubProvider.getSentEmails()[0]
        sent.from.address == "sender@example.com"
        sent.to[0].address == "recipient@example.com"
        sent.subject == "Test Email"
        sent.plainText == "This is a test email"
    }

    def "should send HTML email"() {
        given:
        def task = new MailTask("html-mail", "HTML Mail", ctx)

        when:
        task.provider(stubProvider)
        task.email {
            from "sender@example.com"
            to "recipient@example.com"
            subject "HTML Email"
            html "<h1>Hello World</h1><p>This is HTML content</p>"
        }

        def result = task.runTask(ctx, null).get()

        then:
        result.success == true

        and:
        def sent = stubProvider.getSentEmails()[0]
        sent.htmlText == "<h1>Hello World</h1><p>This is HTML content</p>"
        sent.plainText == null
    }

    def "should send multipart email"() {
        given:
        def task = new MailTask("multipart-mail", "Multipart Mail", ctx)

        when:
        task.provider(stubProvider)
        task.email {
            from "sender@example.com"
            to "recipient@example.com"
            subject "Multipart Email"
            body "Plain text version", "<h1>HTML version</h1>"
        }

        def result = task.runTask(ctx, null).get()

        then:
        result.success == true

        and:
        def sent = stubProvider.getSentEmails()[0]
        sent.plainText == "Plain text version"
        sent.htmlText == "<h1>HTML version</h1>"
    }

    // =========================================================================
    // Dynamic Content from Previous Task
    // =========================================================================

    def "should use previous task result in email"() {
        given:
        def task = new MailTask("dynamic-mail", "Dynamic Mail", ctx)
        def prevResult = [
                userEmail: "john@example.com",
                userName: "John Doe",
                orderId: "ORD-12345",
                total: 99.99,
                dollar: '$'
        ]

        when:
        task.provider(stubProvider)
        task.email { r ->
            from "orders@company.com"
            to r.prev.userEmail
            subject "Order #${r.prev.orderId} Confirmed"
            text "Thank you ${r.prev.userName}! Your order total is ${r.prev.dollar}${r.prev.total}"
        }

        def result = task.runTask(ctx, prevResult).get()

        then:
        result.success == true

        and:
        def sent = stubProvider.getSentEmails()[0]
        sent.to[0].address == "john@example.com"
        sent.subject == "Order #ORD-12345 Confirmed"
        sent.plainText.contains("John Doe")
        sent.plainText.contains("\$99.99")
    }

    def "should use context globals in email"() {
        given:
        def task = new MailTask("globals-mail", "Globals Mail", ctx)
        ctx.globals.fromEmail = "noreply@company.com"
        ctx.globals.companyName = "ACME Corp"
        ctx.globals.supportEmail = "support@company.com"

        when:
        task.provider(stubProvider)
        task.email { r ->
            from r.global('fromEmail')
            to "customer@example.com"
            replyTo r.global('supportEmail')
            subject "Welcome to ${r.global('companyName')}"
            text "Thank you for choosing ${r.global('companyName')}"
        }

        def result = task.runTask(ctx, null).get()

        then:
        result.success == true

        and:
        def sent = stubProvider.getSentEmails()[0]
        sent.from.address == "noreply@company.com"
        sent.replyTo == "support@company.com"
        sent.subject == "Welcome to ACME Corp"
        sent.plainText.contains("ACME Corp")
    }

    def "should render template with resolver"() {
        given:
        def task = new MailTask("template-mail", "Template Mail", ctx)
        def prevResult = [
                userName: "Alice",
                orderId: "XYZ-789",
                total: 149.99,
                items: ["Item 1", "Item 2", "Item 3"],
                dollar: '$'
        ]
        ctx.globals.companyName = "ShopCo"

        when:
        task.provider(stubProvider)
        task.email { r ->
            from "orders@shopco.com"
            to "alice@example.com"
            subject "Order Confirmation"

            template '''
                <html>
                <body>
                    <h1>Order Confirmed</h1>
                    <p>Hello ${prev.userName},</p>
                    <p>Your order #${prev.orderId} has been confirmed.</p>
                    <p>Total: ${prev.dollar}${prev.total}</p>
                    <ul>
                    <% prev.items.each { item -> %>
                        <li>${item}</li>
                    <% } %>
                    </ul>
                    <hr>
                    <p>Thank you for shopping with ${globals.companyName}</p>
                </body>
                </html>
            '''
        }

        def result = task.runTask(ctx, prevResult).get()

        then:
        result.success == true

        and:
        def sent = stubProvider.getSentEmails()[0]
        sent.htmlText.contains("Hello Alice")
        sent.htmlText.contains("order #XYZ-789")
        sent.htmlText.contains("\$149.99")
        sent.htmlText.contains("Item 1")
        sent.htmlText.contains("Item 2")
        sent.htmlText.contains("ShopCo")
    }

    // =========================================================================
    // Multiple Recipients
    // =========================================================================

    def "should send email to multiple recipients"() {
        given:
        def task = new MailTask("multi-recipient-mail", "Multi Recipient", ctx)
        def prevResult = [
                recipients: ["user1@example.com", "user2@example.com", "user3@example.com"]
        ]

        when:
        task.provider(stubProvider)
        task.email { r ->
            from "sender@example.com"
            to r.prev.recipients
            cc "manager@example.com"
            bcc "audit@example.com"
            subject "Team Notification"
            text "This email goes to the whole team"
        }

        def result = task.runTask(ctx, prevResult).get()

        then:
        result.success == true

        and:
        def sent = stubProvider.getSentEmails()[0]
        sent.to.size() == 3
        sent.to*.address == ["user1@example.com", "user2@example.com", "user3@example.com"]
        sent.cc.size() == 1
        sent.bcc.size() == 1
    }

    // =========================================================================
    // Attachments
    // =========================================================================

    def "should send email with attachments"() {
        given:
        def task = new MailTask("attachment-mail", "Attachment Mail", ctx)
        def reportData = "Report Content".bytes
        def invoiceData = "Invoice Content".bytes

        when:
        task.provider(stubProvider)
        task.email {
            from "reports@company.com"
            to "manager@example.com"
            subject "Monthly Report"
            html "<h1>Monthly Report</h1><p>Please find attached.</p>"
            attach "report.txt", reportData, "text/plain"
            attach "invoice.pdf", invoiceData, "application/pdf"
        }

        def result = task.runTask(ctx, null).get()

        then:
        result.success == true

        and:
        def sent = stubProvider.getSentEmails()[0]
        sent.attachments.size() == 2
        sent.attachments[0].filename == "report.txt"
        sent.attachments[1].filename == "invoice.pdf"
    }

    // =========================================================================
    // Bulk Email
    // =========================================================================

    def "should send bulk emails"() {
        given:
        def task = new MailTask("bulk-mail", "Bulk Mail", ctx)
        def prevResult = [
                users: [
                        [email: "user1@example.com", name: "User One"],
                        [email: "user2@example.com", name: "User Two"],
                        [email: "user3@example.com", name: "User Three"]
                ]
        ]

        when:
        task.provider(stubProvider)
        task.bulk { r ->
            r.prev.users.collect { user ->
                [
                        from: [address: "newsletter@company.com"],
                        to: [[address: user.email, name: user.name]],
                        subject: "Newsletter for ${user.name}",
                        plainText: "Hello ${user.name}!",
                        htmlText: "<h1>Hello ${user.name}!</h1>",
                        attachments: [],
                        headers: [:],
                        readReceipt: false,
                        returnReceipt: false
                ]
            }
        }

        def results = task.runTask(ctx, prevResult).get()

        then:
        results.size() == 3
        results.every { it.success == true }

        and:
        stubProvider.getSentEmails().size() == 3
        stubProvider.getSentEmails()[0].to[0].address == "user1@example.com"
        stubProvider.getSentEmails()[1].to[0].address == "user2@example.com"
        stubProvider.getSentEmails()[2].to[0].address == "user3@example.com"
    }

    // =========================================================================
    // Custom Execution
    // =========================================================================

    def "should execute custom mailer code"() {
        given:
        def task = new MailTask("custom-mail", "Custom Mail", ctx)
        def customExecuted = false
        def customResult = null

        when:
        task.provider(stubProvider)
        task.execute { r, mailer ->
            customExecuted = true
            customResult = [
                    custom: "result",
                    globalValue: r.global('testValue', 'default')
            ]
            return customResult
        }

        ctx.globals.testValue = "from-globals"
        def result = task.runTask(ctx, null).get()

        then:
        customExecuted == true
        result.custom == "result"
        result.globalValue == "from-globals"
    }

    // =========================================================================
    // Result Mapper
    // =========================================================================

    def "should transform result with resultMapper"() {
        given:
        def task = new MailTask("mapped-mail", "Mapped Mail", ctx)

        when:
        task.provider(stubProvider)
        task.email {
            from "sender@example.com"
            to "recipient@example.com"
            subject "Test"
            text "Body"
        }
        task.resultMapper { result ->
            [
                    sent: result.success,
                    id: result.messageId,
                    timestamp: new Date(),
                    custom: "transformed"
            ]
        }

        def result = task.runTask(ctx, null).get()

        then:
        result.sent == true
        result.id != null
        result.timestamp != null
        result.custom == "transformed"
    }

    // =========================================================================
    // Error Cases
    // =========================================================================

    def "should fail when provider not configured"() {
        given:
        def task = new MailTask("no-provider-mail", "No Provider", ctx)

        when:
        task.email {
            from "sender@example.com"
            to "recipient@example.com"
            subject "Test"
            text "Body"
        }

        task.runTask(ctx, null).get()

        then:
        thrown(IllegalStateException)
    }

    def "should fail when email not configured"() {
        given:
        def task = new MailTask("no-email-mail", "No Email", ctx)

        when:
        task.provider(stubProvider)
        // No email configuration

        task.runTask(ctx, null).get()

        then:
        thrown(IllegalStateException)
    }

    def "should propagate provider send failures"() {
        given:
        stubProvider.simulateFailure = true
        def task = new MailTask("fail-mail", "Failing Mail", ctx)

        when:
        task.provider(stubProvider)
        task.email {
            from "sender@example.com"
            to "recipient@example.com"
            subject "Should Fail"
            text "Body"
        }

        task.runTask(ctx, null).get()

        then:
        thrown(RuntimeException)
    }

    // =========================================================================
    // SMTP Provider Configuration
    // =========================================================================

    def "should configure SMTP provider inline"() {
        given:
        def task = new MailTask("smtp-inline", "SMTP Inline", ctx)

        when:
        task.smtp {
            host = "smtp.example.com"
            port = 587
            username = "test@example.com"
            password = "password"
            transportStrategy = SmtpProvider.TransportStrategy.SMTP_TLS
            stubMode = true  // Don't actually connect to SMTP
        }
        task.email {
            from "sender@example.com"
            to "recipient@example.com"
            subject "SMTP Test"
            text "Body"
        }

        def result = task.runTask(ctx, null).get()

        then:
        result != null
        // Note: With stub mode, this won't actually connect to SMTP
        // Real SMTP tests would need a test SMTP server
    }

    def "should configure SMTP with credentials from resolver"() {
        given:
        def task = new MailTask("smtp-creds", "SMTP Credentials", ctx)
        ctx.globals.'smtp.username' = "user@example.com"
        ctx.globals.'smtp.password' = "secret"

        when:
        task.smtp { r ->
            host = "smtp.example.com"
            username = r.credential('smtp.username')
            password = r.credential('smtp.password')
            stubMode = true  // Don't actually connect to SMTP
        }
        task.email {
            from "sender@example.com"
            to "recipient@example.com"
            subject "Secure SMTP"
            text "Body"
        }

        def result = task.runTask(ctx, null).get()

        then:
        result != null
    }

    // =========================================================================
    // Integration Scenarios
    // =========================================================================

    def "should support complex workflow scenario"() {
        given: "A task that processes an order and sends confirmation"
        def task = new MailTask("order-confirmation", "Order Confirmation", ctx)

        and: "Context has company configuration"
        ctx.globals.companyName = "TechStore"
        ctx.globals.supportEmail = "support@techstore.com"
        ctx.globals.fromEmail = "orders@techstore.com"

        and: "Previous task returned order details"
        def prevResult = [
                orderId: "ORD-2024-001",
                customerName: "Jane Doe",
                customerEmail: "jane@example.com",
                items: [
                        [name: "Laptop", price: 999.99],
                        [name: "Mouse", price: 29.99],
                        [name: "Keyboard", price: 79.99]
                ],
                total: 1109.97,
                shippingAddress: "123 Main St, City, ST 12345",
                dollar: '$'
        ]

        when: "Email is sent with all the details"
        task.provider(stubProvider)
        task.email { r ->
            from r.global('fromEmail'), r.global('companyName')
            to r.prev.customerEmail, r.prev.customerName
            replyTo r.global('supportEmail')
            subject "Order Confirmation - #${r.prev.orderId}"

            template '''
                <html>
                <body style="font-family: Arial, sans-serif;">
                    <h1>Order Confirmation</h1>
                    <p>Dear ${prev.customerName},</p>
                    <p>Thank you for your order! Your order #${prev.orderId} has been confirmed.</p>
                    
                    <h2>Order Details</h2>
                    <table border="1" cellpadding="5">
                        <tr><th>Item</th><th>Price</th></tr>
                        <% prev.items.each { item -> %>
                        <tr>
                            <td>${item.name}</td>
                            <td>${prev.dollar}${item.price}</td>
                        </tr>
                        <% } %>
                        <tr>
                            <td><strong>Total</strong></td>
                            <td><strong>${prev.dollar}${prev.total}</strong></td>
                        </tr>
                    </table>
                    
                    <h2>Shipping Address</h2>
                    <p>${prev.shippingAddress}</p>
                    
                    <hr>
                    <p>Questions? Reply to this email or contact us at ${globals.supportEmail}</p>
                    <p>- ${globals.companyName} Team</p>
                </body>
                </html>
            '''
        }
        task.resultMapper { result ->
            return [
                    success: result.success,
                    orderId: prevResult.orderId,
                    messageId: result.messageId
            ]
        }

        def result = task.runTask(ctx, prevResult).get()

        then: "Email is sent successfully"
        result.success == true
        result.orderId == "ORD-2024-001"

        and: "Email content is correct"
        def sent = stubProvider.getSentEmails()[0]
        sent.from.address == "orders@techstore.com"
        sent.from.name == "TechStore"
        sent.to[0].address == "jane@example.com"
        sent.to[0].name == "Jane Doe"
        sent.replyTo == "support@techstore.com"
        sent.subject == "Order Confirmation - #ORD-2024-001"
        sent.htmlText.contains("Jane Doe")
        sent.htmlText.contains("Laptop")
        sent.htmlText.contains("\$999.99")
        sent.htmlText.contains("\$1109.97")
        sent.htmlText.contains("123 Main St")
    }
}