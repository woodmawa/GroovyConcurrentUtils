package org.softwood.dag.task.mail

import org.softwood.dag.task.TaskContext
import org.softwood.dag.task.TaskResolver
import spock.lang.Specification

/**
 * Unit tests for EmailBuilder DSL.
 */
class EmailBuilderTest extends Specification {
    
    TaskContext ctx
    TaskResolver resolver
    
    def setup() {
        ctx = new TaskContext()
        ctx.globals = [:]
        resolver = new TaskResolver(null, ctx)
    }
    
    def "should build simple email"() {
        given:
        def builder = new EmailBuilder()
        
        when:
        builder.configure {
            from "sender@example.com", "Sender Name"
            to "recipient@example.com", "Recipient Name"
            subject "Test Subject"
            text "Plain text body"
        }
        def email = builder.build(resolver)
        
        then:
        email.from.address == "sender@example.com"
        email.from.name == "Sender Name"
        email.to.size() == 1
        email.to[0].address == "recipient@example.com"
        email.to[0].name == "Recipient Name"
        email.subject == "Test Subject"
        email.plainText == "Plain text body"
    }
    
    def "should build email with HTML"() {
        given:
        def builder = new EmailBuilder()
        
        when:
        builder.configure {
            from "sender@example.com"
            to "recipient@example.com"
            subject "HTML Email"
            html "<h1>Hello World</h1>"
        }
        def email = builder.build(resolver)
        
        then:
        email.htmlText == "<h1>Hello World</h1>"
        email.plainText == null
    }
    
    def "should build email with both text and HTML"() {
        given:
        def builder = new EmailBuilder()
        
        when:
        builder.configure {
            from "sender@example.com"
            to "recipient@example.com"
            subject "Multipart Email"
            body "Plain text", "<h1>HTML</h1>"
        }
        def email = builder.build(resolver)
        
        then:
        email.plainText == "Plain text"
        email.htmlText == "<h1>HTML</h1>"
    }
    
    def "should support multiple recipients"() {
        given:
        def builder = new EmailBuilder()
        
        when:
        builder.configure {
            from "sender@example.com"
            to "user1@example.com", "User One"
            to "user2@example.com", "User Two"
            cc "cc@example.com"
            bcc "bcc@example.com"
            subject "Multi-recipient"
            text "Body"
        }
        def email = builder.build(resolver)
        
        then:
        email.to.size() == 2
        email.to[0].address == "user1@example.com"
        email.to[1].address == "user2@example.com"
        email.cc.size() == 1
        email.cc[0].address == "cc@example.com"
        email.bcc.size() == 1
        email.bcc[0].address == "bcc@example.com"
    }
    
    def "should support bulk to addresses"() {
        given:
        def builder = new EmailBuilder()
        def addresses = ["user1@example.com", "user2@example.com", "user3@example.com"]
        
        when:
        builder.configure {
            from "sender@example.com"
            to addresses
            subject "Bulk recipients"
            text "Body"
        }
        def email = builder.build(resolver)
        
        then:
        email.to.size() == 3
        email.to*.address == addresses
    }
    
    def "should support attachments"() {
        given:
        def builder = new EmailBuilder()
        def attachmentData = "test data".bytes
        
        when:
        builder.configure {
            from "sender@example.com"
            to "recipient@example.com"
            subject "With Attachment"
            text "See attachment"
            attach "test.txt", attachmentData, "text/plain"
        }
        def email = builder.build(resolver)
        
        then:
        email.attachments.size() == 1
        email.attachments[0].filename == "test.txt"
        email.attachments[0].data == attachmentData
        email.attachments[0].mimeType == "text/plain"
    }
    
    def "should support custom headers"() {
        given:
        def builder = new EmailBuilder()
        
        when:
        builder.configure {
            from "sender@example.com"
            to "recipient@example.com"
            subject "With Headers"
            text "Body"
            header "X-Custom-Header", "custom-value"
            header "X-Priority", "1"
        }
        def email = builder.build(resolver)
        
        then:
        email.headers.size() == 2
        email.headers["X-Custom-Header"] == "custom-value"
        email.headers["X-Priority"] == "1"
    }
    
    def "should support reply-to"() {
        given:
        def builder = new EmailBuilder()
        
        when:
        builder.configure {
            from "noreply@example.com"
            to "recipient@example.com"
            replyTo "support@example.com"
            subject "With Reply-To"
            text "Body"
        }
        def email = builder.build(resolver)
        
        then:
        email.replyTo == "support@example.com"
    }
    
    def "should support read and return receipts"() {
        given:
        def builder = new EmailBuilder()
        
        when:
        builder.configure {
            from "sender@example.com"
            to "recipient@example.com"
            subject "With Receipts"
            text "Body"
            requestReadReceipt()
            requestReturnReceipt()
        }
        def email = builder.build(resolver)
        
        then:
        email.readReceipt == true
        email.returnReceipt == true
    }
    
    // =========================================================================
    // Dynamic Content with Resolver
    // =========================================================================
    
    def "should resolve dynamic content from resolver"() {
        given:
        def builder = new EmailBuilder()
        ctx.globals.fromEmail = "configured@example.com"
        def prevResult = [userEmail: "user@example.com", orderId: "12345"]
        resolver = new TaskResolver(prevResult, ctx)
        
        when:
        builder.configure { r ->
            from r.global('fromEmail')
            to r.prev.userEmail
            subject "Order #${r.prev.orderId}"
            text "Your order has been confirmed"
        }
        def email = builder.build(resolver)
        
        then:
        email.from.address == "configured@example.com"
        email.to[0].address == "user@example.com"
        email.subject == "Order #12345"
    }
    
    def "should render templates with resolver"() {
        given:
        def builder = new EmailBuilder()
        def prevResult = [orderId: "12345", total: 99.99, userName: "John"]
        ctx.globals.companyName = "ACME Corp"
        resolver = new TaskResolver(prevResult, ctx)
        
        when:
        builder.configure { r ->
            from "noreply@example.com"
            to "user@example.com"
            subject "Order Confirmation"
            
            template '''
                <h1>Order Confirmed!</h1>
                <p>Hello ${prev.userName},</p>
                <p>Order ID: ${prev.orderId}</p>
                <p>Total: ${dollar}${prev.total}</p>
                <hr>
                <p>Thank you from ${globals.companyName}</p>
            ''', [dollar: '$']
        }
        def email = builder.build(resolver)
        
        then:
        email.htmlText.contains("Hello John")
        email.htmlText.contains("Order ID: 12345")
        email.htmlText.contains("Total: \$99.99")
        email.htmlText.contains("Thank you from ACME Corp")
    }
    
    def "should support template files"() {
        given:
        def builder = new EmailBuilder()
        def templateFileObj = File.createTempFile("email-template-", ".html")
        templateFileObj.text = "<h1>Hello \${prev.userName}!</h1><p>Order: \${prev.orderId}</p>"
        
        def prevResult = [userName: "Alice", orderId: "XYZ-789"]
        resolver = new TaskResolver(prevResult, ctx)
        
        when:
        builder.configure { r ->
            from "noreply@example.com"
            to "user@example.com"
            subject "Template Test"
            templateFile templateFileObj.absolutePath
        }
        def email = builder.build(resolver)
        
        then:
        email.htmlText.contains("Hello Alice!")
        email.htmlText.contains("Order: XYZ-789")
        
        cleanup:
        templateFileObj?.delete()
    }
    
    def "should support closure-based recipients"() {
        given:
        def builder = new EmailBuilder()
        def prevResult = [recipients: ["user1@example.com", "user2@example.com"]]
        resolver = new TaskResolver(prevResult, ctx)
        
        when:
        builder.configure { r ->
            from "sender@example.com"
            to { r.prev.recipients }
            subject "Multiple recipients from closure"
            text "Body"
        }
        def email = builder.build(resolver)
        
        then:
        email.to.size() == 2
        email.to*.address == ["user1@example.com", "user2@example.com"]
    }
    
    // =========================================================================
    // Validation
    // =========================================================================
    
    def "should require from address"() {
        given:
        def builder = new EmailBuilder()
        
        when:
        builder.configure {
            to "recipient@example.com"
            subject "No Sender"
            text "Body"
        }
        builder.build(resolver)
        
        then:
        thrown(IllegalStateException)
    }
    
    def "should require at least one recipient"() {
        given:
        def builder = new EmailBuilder()
        
        when:
        builder.configure {
            from "sender@example.com"
            subject "No Recipients"
            text "Body"
        }
        builder.build(resolver)
        
        then:
        thrown(IllegalStateException)
    }
    
    def "should require subject"() {
        given:
        def builder = new EmailBuilder()
        
        when:
        builder.configure {
            from "sender@example.com"
            to "recipient@example.com"
            text "Body"
        }
        builder.build(resolver)
        
        then:
        thrown(IllegalStateException)
    }
    
    def "should require body (text or HTML)"() {
        given:
        def builder = new EmailBuilder()
        
        when:
        builder.configure {
            from "sender@example.com"
            to "recipient@example.com"
            subject "No Body"
        }
        builder.build(resolver)
        
        then:
        thrown(IllegalStateException)
    }
    
    def "should handle missing template file gracefully"() {
        given:
        def builder = new EmailBuilder()
        
        when:
        builder.configure { r ->
            from "sender@example.com"
            to "recipient@example.com"
            subject "Missing Template"
            templateFile "/nonexistent/template.html"
        }
        builder.build(resolver)
        
        then:
        thrown(FileNotFoundException)
    }
    
    // =========================================================================
    // Edge Cases
    // =========================================================================
    
    def "should handle empty recipient names"() {
        given:
        def builder = new EmailBuilder()
        
        when:
        builder.configure {
            from "sender@example.com", ""
            to "recipient@example.com", null
            subject "Empty Names"
            text "Body"
        }
        def email = builder.build(resolver)
        
        then:
        email.from.name == ""
        email.to[0].name == null
    }
    
    def "should handle null resolver prev value"() {
        given:
        def builder = new EmailBuilder()
        resolver = new TaskResolver(null, ctx)
        
        when:
        builder.configure { r ->
            from "sender@example.com"
            to "recipient@example.com"
            subject r.prev ? "Has prev" : "No prev"
            text "Body"
        }
        def email = builder.build(resolver)
        
        then:
        email.subject == "No prev"
    }
    
    def "should handle template with additional binding"() {
        given:
        def builder = new EmailBuilder()
        def prevResult = [orderId: "123"]
        resolver = new TaskResolver(prevResult, ctx)
        
        when:
        builder.configure { r ->
            from "sender@example.com"
            to "recipient@example.com"
            subject "Template with binding"
            
            template '<p>Order: ${prev.orderId}, Custom: ${customVar}</p>', 
                     [customVar: "custom value"]
        }
        def email = builder.build(resolver)
        
        then:
        email.htmlText.contains("Order: 123")
        email.htmlText.contains("Custom: custom value")
    }
    
    def "should reuse builder for multiple emails"() {
        given:
        def builder = new EmailBuilder()
        
        when:
        builder.configure {
            from "sender@example.com"
            to "user1@example.com"
            subject "Email 1"
            text "Body 1"
        }
        def email1 = builder.build(resolver)
        
        // Reconfigure for second email
        builder.configure {
            from "sender@example.com"
            to "user2@example.com"
            subject "Email 2"
            text "Body 2"
        }
        def email2 = builder.build(resolver)
        
        then:
        email1.to[0].address == "user1@example.com"
        email1.subject == "Email 1"
        email1.plainText == "Body 1"
        
        email2.to[0].address == "user2@example.com"
        email2.subject == "Email 2"
        email2.plainText == "Body 2"
    }
}
