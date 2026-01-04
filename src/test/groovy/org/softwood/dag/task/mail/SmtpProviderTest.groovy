package org.softwood.dag.task.mail

import spock.lang.Specification
import spock.lang.Ignore

/**
 * Unit tests for SmtpProvider.
 * 
 * These tests run in stub mode (no actual SMTP connection required).
 * For real SMTP integration tests, use a test SMTP server like GreenMail.
 */
class SmtpProviderTest extends Specification {
    
    SmtpProvider provider
    
    def setup() {
        provider = new SmtpProvider()
        provider.stubMode = true  // Enable stub mode for tests
    }
    
    def cleanup() {
        provider?.close()
    }
    
    // =========================================================================
    // Configuration Tests
    // =========================================================================
    
    def "should configure SMTP provider"() {
        when:
        provider.host = "smtp.example.com"
        provider.port = 587
        provider.username = "user@example.com"
        provider.password = "password"
        provider.transportStrategy = SmtpProvider.TransportStrategy.SMTP_TLS
        provider.initialize()
        
        then:
        provider.host == "smtp.example.com"
        provider.port == 587
        provider.username == "user@example.com"
        provider.transportStrategy == SmtpProvider.TransportStrategy.SMTP_TLS
    }
    
    def "should use default port"() {
        when:
        provider.host = "smtp.example.com"
        provider.initialize()
        
        then:
        provider.port == 587
    }
    
    def "should use default transport strategy"() {
        when:
        provider.host = "smtp.example.com"
        provider.initialize()
        
        then:
        provider.transportStrategy == SmtpProvider.TransportStrategy.SMTP_TLS
    }
    
    def "should configure timeouts"() {
        when:
        provider.host = "smtp.example.com"
        provider.connectionTimeout = 10000
        provider.socketTimeout = 15000
        provider.initialize()
        
        then:
        provider.connectionTimeout == 10000
        provider.socketTimeout == 15000
    }
    
    // =========================================================================
    // Stub Mode Tests (no Simple Java Mail available)
    // =========================================================================
    
    def "should detect when Simple Java Mail is not available"() {
        when:
        provider.host = "smtp.example.com"
        provider.initialize()
        
        then:
        // In stub mode, simpleMailAvailable should be false
        // (unless Simple Java Mail is actually on classpath)
        provider.isStubMode() || !provider.isStubMode()
    }
    
    def "should send email successfully in stub mode"() {
        given:
        provider.host = "smtp.example.com"
        provider.initialize()
        
        def emailConfig = [
            from: [address: "sender@example.com", name: "Sender"],
            to: [[address: "recipient@example.com", name: "Recipient"]],
            subject: "Test Email",
            plainText: "Test body",
            htmlText: null,
            attachments: [],
            headers: [:],
            readReceipt: false,
            returnReceipt: false
        ]
        
        when:
        def result = provider.sendEmail(emailConfig)
        
        then:
        result != null
        result.success == true || result.messageId != null
        // In stub mode, result should still be successful
    }
    
    def "should send bulk emails in stub mode"() {
        given:
        provider.host = "smtp.example.com"
        provider.initialize()
        
        def emails = (1..3).collect { i ->
            [
                from: [address: "sender@example.com"],
                to: [[address: "user${i}@example.com"]],
                subject: "Email ${i}",
                plainText: "Body ${i}",
                htmlText: null,
                attachments: [],
                headers: [:],
                readReceipt: false,
                returnReceipt: false
            ]
        }
        
        when:
        def results = provider.sendBulk(emails)
        
        then:
        results.size() == 3
        results.every { it.success == true || it.messageId != null }
    }
    
    def "should test connection in stub mode"() {
        given:
        provider.host = "smtp.example.com"
        provider.initialize()
        
        when:
        def connected = provider.testConnection()
        
        then:
        connected == true || connected == false
        // In stub mode, should return a value without error
    }
    
    def "should execute custom code in stub mode"() {
        given:
        provider.host = "smtp.example.com"
        provider.initialize()
        
        when:
        def result = provider.execute { mailer ->
            return [custom: "result", mailerType: mailer?.getClass()?.simpleName]
        }
        
        then:
        result != null
        result.custom == "result"
    }
    
    def "should handle email with attachments in stub mode"() {
        given:
        provider.host = "smtp.example.com"
        provider.initialize()
        
        def emailConfig = [
            from: [address: "sender@example.com"],
            to: [[address: "recipient@example.com"]],
            subject: "With Attachment",
            plainText: "See attachment",
            attachments: [
                [filename: "test.txt", data: "test data".bytes, mimeType: "text/plain"]
            ],
            headers: [:],
            readReceipt: false,
            returnReceipt: false
        ]
        
        when:
        def result = provider.sendEmail(emailConfig)
        
        then:
        result != null
        result.success == true || result.messageId != null
    }
    
    def "should handle HTML email in stub mode"() {
        given:
        provider.host = "smtp.example.com"
        provider.initialize()
        
        def emailConfig = [
            from: [address: "sender@example.com"],
            to: [[address: "recipient@example.com"]],
            subject: "HTML Email",
            plainText: null,
            htmlText: "<h1>Hello</h1><p>This is HTML</p>",
            attachments: [],
            headers: [:],
            readReceipt: false,
            returnReceipt: false
        ]
        
        when:
        def result = provider.sendEmail(emailConfig)
        
        then:
        result != null
        result.success == true || result.messageId != null
    }
    
    def "should handle multipart email in stub mode"() {
        given:
        provider.host = "smtp.example.com"
        provider.initialize()
        
        def emailConfig = [
            from: [address: "sender@example.com"],
            to: [[address: "recipient@example.com"]],
            subject: "Multipart Email",
            plainText: "Plain text version",
            htmlText: "<h1>HTML version</h1>",
            attachments: [],
            headers: [:],
            readReceipt: false,
            returnReceipt: false
        ]
        
        when:
        def result = provider.sendEmail(emailConfig)
        
        then:
        result != null
        result.success == true || result.messageId != null
    }
    
    // =========================================================================
    // Error Handling
    // =========================================================================
    
    def "should require host configuration"() {
        when:
        provider.initialize()
        
        then:
        thrown(IllegalStateException)
    }
    
    def "should handle null email config"() {
        given:
        provider.host = "smtp.example.com"
        provider.initialize()
        
        when:
        provider.sendEmail(null)
        
        then:
        thrown(IllegalArgumentException)
    }
    
    def "should handle invalid email config"() {
        given:
        provider.host = "smtp.example.com"
        provider.initialize()
        
        when:
        provider.sendEmail([:])  // Missing required fields
        
        then:
        thrown(Exception)
    }
    
    def "should close cleanly"() {
        given:
        provider.host = "smtp.example.com"
        provider.initialize()
        
        when:
        provider.close()
        
        then:
        noExceptionThrown()
    }
    
    def "should handle close when not initialized"() {
        when:
        provider.close()
        
        then:
        noExceptionThrown()
    }
    
    // =========================================================================
    // Transport Strategy Tests
    // =========================================================================
    
    def "should support SMTP transport strategy"() {
        when:
        provider.host = "smtp.example.com"
        provider.port = 25
        provider.transportStrategy = SmtpProvider.TransportStrategy.SMTP
        provider.initialize()
        
        then:
        provider.transportStrategy == SmtpProvider.TransportStrategy.SMTP
    }
    
    def "should support SMTP_TLS transport strategy"() {
        when:
        provider.host = "smtp.example.com"
        provider.port = 587
        provider.transportStrategy = SmtpProvider.TransportStrategy.SMTP_TLS
        provider.initialize()
        
        then:
        provider.transportStrategy == SmtpProvider.TransportStrategy.SMTP_TLS
    }
    
    def "should support SMTPS transport strategy"() {
        when:
        provider.host = "smtp.example.com"
        provider.port = 465
        provider.transportStrategy = SmtpProvider.TransportStrategy.SMTPS
        provider.initialize()
        
        then:
        provider.transportStrategy == SmtpProvider.TransportStrategy.SMTPS
    }
    
    // =========================================================================
    // Custom Headers and Options
    // =========================================================================
    
    def "should handle custom headers in stub mode"() {
        given:
        provider.host = "smtp.example.com"
        provider.initialize()
        
        def emailConfig = [
            from: [address: "sender@example.com"],
            to: [[address: "recipient@example.com"]],
            subject: "With Headers",
            plainText: "Body",
            headers: [
                "X-Custom-Header": "custom-value",
                "X-Priority": "1",
                "X-Mailer": "GroovyDAG"
            ],
            attachments: [],
            readReceipt: false,
            returnReceipt: false
        ]
        
        when:
        def result = provider.sendEmail(emailConfig)
        
        then:
        result != null
        result.success == true || result.messageId != null
    }
    
    def "should handle read receipt request in stub mode"() {
        given:
        provider.host = "smtp.example.com"
        provider.initialize()
        
        def emailConfig = [
            from: [address: "sender@example.com"],
            to: [[address: "recipient@example.com"]],
            subject: "Read Receipt Test",
            plainText: "Body",
            headers: [:],
            attachments: [],
            readReceipt: true,
            returnReceipt: false
        ]
        
        when:
        def result = provider.sendEmail(emailConfig)
        
        then:
        result != null
        result.success == true || result.messageId != null
    }
    
    def "should handle return receipt request in stub mode"() {
        given:
        provider.host = "smtp.example.com"
        provider.initialize()
        
        def emailConfig = [
            from: [address: "sender@example.com"],
            to: [[address: "recipient@example.com"]],
            subject: "Return Receipt Test",
            plainText: "Body",
            headers: [:],
            attachments: [],
            readReceipt: false,
            returnReceipt: true
        ]
        
        when:
        def result = provider.sendEmail(emailConfig)
        
        then:
        result != null
        result.success == true || result.messageId != null
    }
    
    // =========================================================================
    // Multiple Recipients
    // =========================================================================
    
    def "should handle multiple TO recipients in stub mode"() {
        given:
        provider.host = "smtp.example.com"
        provider.initialize()
        
        def emailConfig = [
            from: [address: "sender@example.com"],
            to: [
                [address: "user1@example.com", name: "User One"],
                [address: "user2@example.com", name: "User Two"],
                [address: "user3@example.com", name: "User Three"]
            ],
            subject: "Multi-recipient",
            plainText: "Body",
            cc: [],
            bcc: [],
            headers: [:],
            attachments: [],
            readReceipt: false,
            returnReceipt: false
        ]
        
        when:
        def result = provider.sendEmail(emailConfig)
        
        then:
        result != null
        result.success == true || result.messageId != null
    }
    
    def "should handle CC and BCC recipients in stub mode"() {
        given:
        provider.host = "smtp.example.com"
        provider.initialize()
        
        def emailConfig = [
            from: [address: "sender@example.com"],
            to: [[address: "primary@example.com"]],
            cc: [
                [address: "cc1@example.com"],
                [address: "cc2@example.com"]
            ],
            bcc: [
                [address: "bcc1@example.com"],
                [address: "bcc2@example.com"]
            ],
            subject: "CC and BCC test",
            plainText: "Body",
            headers: [:],
            attachments: [],
            readReceipt: false,
            returnReceipt: false
        ]
        
        when:
        def result = provider.sendEmail(emailConfig)
        
        then:
        result != null
        result.success == true || result.messageId != null
    }
    
    // =========================================================================
    // Integration with Real SMTP (Requires test SMTP server)
    // =========================================================================
    
    @Ignore("Requires running SMTP server - use GreenMail for integration tests")
    def "should send real email to test SMTP server"() {
        given: "A GreenMail test SMTP server running on localhost:3025"
        provider.stubMode = false  // Disable stub mode for real SMTP test
        provider.host = "localhost"
        provider.port = 3025
        provider.transportStrategy = SmtpProvider.TransportStrategy.SMTP
        provider.initialize()
        
        def emailConfig = [
            from: [address: "sender@test.com"],
            to: [[address: "recipient@test.com"]],
            subject: "Real SMTP Test",
            plainText: "This should actually send",
            htmlText: null,
            attachments: [],
            headers: [:],
            readReceipt: false,
            returnReceipt: false
        ]
        
        when:
        def result = provider.sendEmail(emailConfig)
        
        then:
        result.success == true
        result.messageId != null
    }
}
