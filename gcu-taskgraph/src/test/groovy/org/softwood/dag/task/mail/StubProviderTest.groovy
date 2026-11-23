package org.softwood.dag.task.mail

import spock.lang.Specification

/**
 * Unit tests for StubProvider (testing without SMTP server).
 */
class StubProviderTest extends Specification {
    
    StubProvider provider
    
    def setup() {
        provider = new StubProvider()
        provider.initialize()
    }
    
    def cleanup() {
        provider?.close()
    }
    
    def "should initialize successfully"() {
        expect:
        provider != null
    }
    
    def "should send single email successfully"() {
        given:
        def emailConfig = [
            from: [address: "sender@example.com", name: "Sender"],
            to: [[address: "recipient@example.com", name: "Recipient"]],
            subject: "Test Email",
            plainText: "This is a test email",
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
        result.success == true
        result.messageId != null
        result.messageId.startsWith("stub-")
    }
    
    def "should send email with HTML"() {
        given:
        def emailConfig = [
            from: [address: "sender@example.com"],
            to: [[address: "recipient@example.com"]],
            subject: "HTML Email",
            plainText: null,
            htmlText: "<h1>Hello World</h1>",
            attachments: [],
            headers: [:],
            readReceipt: false,
            returnReceipt: false
        ]
        
        when:
        def result = provider.sendEmail(emailConfig)
        def sentEmails = provider.getSentEmails()
        
        then:
        result.success == true
        sentEmails.size() == 1
        sentEmails[0].htmlText == "<h1>Hello World</h1>"
    }
    
    def "should send email with attachments"() {
        given:
        def attachmentData = "test content".bytes
        def emailConfig = [
            from: [address: "sender@example.com"],
            to: [[address: "recipient@example.com"]],
            subject: "With Attachment",
            plainText: "See attachment",
            attachments: [
                [filename: "test.txt", data: attachmentData, mimeType: "text/plain"]
            ],
            headers: [:],
            readReceipt: false,
            returnReceipt: false
        ]
        
        when:
        def result = provider.sendEmail(emailConfig)
        def sentEmails = provider.getSentEmails()
        
        then:
        result.success == true
        sentEmails.size() == 1
        sentEmails[0].attachments.size() == 1
        sentEmails[0].attachments[0].filename == "test.txt"
    }
    
    def "should send email with multiple recipients"() {
        given:
        def emailConfig = [
            from: [address: "sender@example.com"],
            to: [
                [address: "user1@example.com"],
                [address: "user2@example.com"],
                [address: "user3@example.com"]
            ],
            cc: [[address: "cc@example.com"]],
            bcc: [[address: "bcc@example.com"]],
            subject: "Multi-recipient",
            plainText: "Body",
            attachments: [],
            headers: [:],
            readReceipt: false,
            returnReceipt: false
        ]
        
        when:
        def result = provider.sendEmail(emailConfig)
        def sentEmails = provider.getSentEmails()
        
        then:
        result.success == true
        sentEmails.size() == 1
        sentEmails[0].to.size() == 3
        sentEmails[0].cc.size() == 1
        sentEmails[0].bcc.size() == 1
    }
    
    def "should track sent emails"() {
        given:
        def email1 = [
            from: [address: "sender@example.com"],
            to: [[address: "user1@example.com"]],
            subject: "Email 1",
            plainText: "Body 1",
            attachments: [],
            headers: [:],
            readReceipt: false,
            returnReceipt: false
        ]
        def email2 = [
            from: [address: "sender@example.com"],
            to: [[address: "user2@example.com"]],
            subject: "Email 2",
            plainText: "Body 2",
            attachments: [],
            headers: [:],
            readReceipt: false,
            returnReceipt: false
        ]
        
        when:
        provider.sendEmail(email1)
        provider.sendEmail(email2)
        def sentEmails = provider.getSentEmails()
        
        then:
        sentEmails.size() == 2
        sentEmails[0].subject == "Email 1"
        sentEmails[1].subject == "Email 2"
    }
    
    def "should clear sent emails"() {
        given:
        def emailConfig = [
            from: [address: "sender@example.com"],
            to: [[address: "recipient@example.com"]],
            subject: "Test",
            plainText: "Body",
            attachments: [],
            headers: [:],
            readReceipt: false,
            returnReceipt: false
        ]
        
        when:
        provider.sendEmail(emailConfig)
        provider.sendEmail(emailConfig)
        
        then:
        provider.getSentEmails().size() == 2
        
        when:
        provider.clear()
        
        then:
        provider.getSentEmails().size() == 0
    }
    
    def "should send bulk emails successfully"() {
        given:
        def emails = [
            [
                from: [address: "sender@example.com"],
                to: [[address: "user1@example.com"]],
                subject: "Bulk 1",
                plainText: "Body 1",
                attachments: [],
                headers: [:],
                readReceipt: false,
                returnReceipt: false
            ],
            [
                from: [address: "sender@example.com"],
                to: [[address: "user2@example.com"]],
                subject: "Bulk 2",
                plainText: "Body 2",
                attachments: [],
                headers: [:],
                readReceipt: false,
                returnReceipt: false
            ],
            [
                from: [address: "sender@example.com"],
                to: [[address: "user3@example.com"]],
                subject: "Bulk 3",
                plainText: "Body 3",
                attachments: [],
                headers: [:],
                readReceipt: false,
                returnReceipt: false
            ]
        ]
        
        when:
        def results = provider.sendBulk(emails)
        
        then:
        results.size() == 3
        results.every { it.success == true }
        results.every { it.messageId != null }
        provider.getSentEmails().size() == 3
    }
    
    def "should handle empty bulk send"() {
        when:
        def results = provider.sendBulk([])
        
        then:
        results.size() == 0
    }
    
    def "should test connection successfully"() {
        when:
        def connected = provider.testConnection()
        
        then:
        connected == true
    }
    
    def "should execute custom code"() {
        given:
        def executed = false
        
        when:
        def result = provider.execute { mailer ->
            executed = true
            return [custom: "result"]
        }
        
        then:
        executed == true
        result.custom == "result"
    }
    
    def "should simulate send failures when configured"() {
        given:
        provider.simulateFailure = true
        def emailConfig = [
            from: [address: "sender@example.com"],
            to: [[address: "recipient@example.com"]],
            subject: "Should Fail",
            plainText: "Body",
            attachments: [],
            headers: [:],
            readReceipt: false,
            returnReceipt: false
        ]
        
        when:
        provider.sendEmail(emailConfig)
        
        then:
        thrown(RuntimeException)
    }
    
    def "should handle null or invalid email config gracefully"() {
        when:
        provider.sendEmail(null)
        
        then:
        thrown(IllegalArgumentException)
    }
    
    def "should provide detailed sent email information"() {
        given:
        def emailConfig = [
            from: [address: "sender@example.com", name: "Sender Name"],
            to: [
                [address: "user1@example.com", name: "User One"],
                [address: "user2@example.com", name: "User Two"]
            ],
            cc: [[address: "cc@example.com"]],
            subject: "Detailed Test",
            plainText: "Plain text body",
            htmlText: "<h1>HTML body</h1>",
            attachments: [
                [filename: "file1.txt", data: "data1".bytes, mimeType: "text/plain"],
                [filename: "file2.pdf", data: "data2".bytes, mimeType: "application/pdf"]
            ],
            headers: [
                "X-Custom": "value",
                "X-Priority": "1"
            ],
            readReceipt: true,
            returnReceipt: true
        ]
        
        when:
        provider.sendEmail(emailConfig)
        def sentEmails = provider.getSentEmails()
        
        then:
        sentEmails.size() == 1
        
        def sent = sentEmails[0]
        sent.from.address == "sender@example.com"
        sent.from.name == "Sender Name"
        sent.to.size() == 2
        sent.cc.size() == 1
        sent.subject == "Detailed Test"
        sent.plainText == "Plain text body"
        sent.htmlText == "<h1>HTML body</h1>"
        sent.attachments.size() == 2
        sent.headers.size() == 2
        sent.readReceipt == true
        sent.returnReceipt == true
    }
    
    def "should generate unique message IDs"() {
        given:
        def emailConfig = [
            from: [address: "sender@example.com"],
            to: [[address: "recipient@example.com"]],
            subject: "Test",
            plainText: "Body",
            attachments: [],
            headers: [:],
            readReceipt: false,
            returnReceipt: false
        ]
        
        when:
        def result1 = provider.sendEmail(emailConfig)
        def result2 = provider.sendEmail(emailConfig)
        def result3 = provider.sendEmail(emailConfig)
        
        then:
        result1.messageId != result2.messageId
        result2.messageId != result3.messageId
        result1.messageId != result3.messageId
    }
    
    def "should close cleanly"() {
        when:
        provider.close()
        
        then:
        noExceptionThrown()
    }
    
    def "should allow reinitialization after close"() {
        given:
        provider.close()
        
        when:
        provider.initialize()
        def result = provider.testConnection()
        
        then:
        result == true
    }
}
