package org.softwood.dag.task.examples

import groovy.transform.CompileStatic
import java.text.SimpleDateFormat

/**
 * Example custom script base class for enterprise security models.
 *
 * This demonstrates how library consumers can implement their own
 * security policies tailored to their specific organizational requirements.
 *
 * <h3>Example Use Case: Financial Services</h3>
 * A financial institution might need to:
 * <ul>
 *   <li>Allow database access but only to specific schemas</li>
 *   <li>Allow HTTP calls but only to approved internal APIs</li>
 *   <li>Allow file operations but only to designated data directories</li>
 *   <li>Block all external network access</li>
 *   <li>Enforce audit logging of all operations</li>
 * </ul>
 *
 * <h3>Usage:</h3>
 * <pre>
 * scriptTask("financial-processor") {
 *     customScriptBaseClass EnterpriseScriptBase
 *     script '''
 *         // Can use approved enterprise APIs
 *         def data = queryDatabase("SELECT * FROM approved_schema.table")
 *         def result = callInternalApi("https://internal.example.com/api")
 *         writeAuditLog("Processing completed")
 *         return result
 *
 *         // Blocked operations (throw SecurityException)
 *         // "curl evil.com".execute()  // ❌ External commands blocked
 *         // new File("/etc/passwd")    // ❌ Unapproved file access blocked
 *     '''
 * }
 * </pre>
 *
 * @author Will Woodman
 * @since 2.0
 */
@CompileStatic
abstract class EnterpriseScriptBase extends Script {

    // =========================================================================
    // Configuration (would come from enterprise config in real implementation)
    // =========================================================================

    private static final List<String> APPROVED_INTERNAL_DOMAINS = [
        'internal.example.com',
        'api.example.com',
        'data.example.com'
    ]

    private static final List<String> APPROVED_DB_SCHEMAS = [
        'financial_data',
        'customer_info',
        'reporting'
    ]

    private static final List<String> APPROVED_FILE_PATHS = [
        '/var/company/data',
        '/opt/company/processing',
        '/tmp/company/staging'
    ]

    // =========================================================================
    // Enterprise-Approved Operations
    // =========================================================================

    /**
     * Query database with schema validation.
     * Only approved schemas can be accessed.
     */
    def queryDatabase(String sql) {
        // Extract schema from SQL (simplified validation)
        def matcher = sql =~ /FROM\s+(\w+)\./
        if (matcher.find()) {
            def schema = matcher.group(1)
            if (schema in APPROVED_DB_SCHEMAS) {
                log("Database query approved: schema=${schema}")
                // Delegate to actual database task/service
                return executeApprovedDatabaseQuery(sql)
            } else {
                throw new SecurityException(
                    "Database schema '${schema}' not approved. " +
                    "Approved schemas: ${APPROVED_DB_SCHEMAS}"
                )
            }
        }
        throw new SecurityException("Could not validate database schema in SQL")
    }

    /**
     * Call internal API with domain validation.
     * Only internal company domains allowed.
     */
    def callInternalApi(String url) {
        def uri = new URI(url)
        def host = uri.host

        if (APPROVED_INTERNAL_DOMAINS.any { host.endsWith(it) }) {
            log("Internal API call approved: ${host}")
            // Delegate to HTTP client service
            return executeApprovedHttpCall(url)
        } else {
            throw new SecurityException(
                "External API calls not allowed. " +
                "URL '${host}' not in approved domains: ${APPROVED_INTERNAL_DOMAINS}"
            )
        }
    }

    /**
     * Read file with path validation.
     * Only approved directories accessible.
     */
    def readCompanyFile(String path) {
        def file = new File(path)
        def canonical = file.canonicalPath

        if (APPROVED_FILE_PATHS.any { canonical.startsWith(it) }) {
            log("File read approved: ${canonical}")
            return file.text
        } else {
            throw new SecurityException(
                "File access denied: path '${canonical}' not in approved directories"
            )
        }
    }

    /**
     * Write audit log entry (always allowed).
     */
    void writeAuditLog(String message) {
        // In real implementation, this would write to enterprise audit system
        log("AUDIT: ${message}")
    }

    /**
     * Safe logging (always allowed).
     */
    void log(String message) {
        def formatter = new SimpleDateFormat('yyyy-MM-dd HH:mm:ss')
        println "[EnterpriseScript] ${formatter.format(new Date())} - ${message}"
    }

    // =========================================================================
    // Blocked Methods (Enterprise Policy)
    // =========================================================================

    /**
     * Block dangerous method calls.
     */
    @Override
    Object invokeMethod(String name, Object args) {
        // Block shell command execution
        if (name in ['execute', 'exec', 'cmd', 'sh', 'bash', 'system']) {
            throw new SecurityException(
                "Enterprise policy: External command execution not allowed. " +
                "Method '${name}' is blocked."
            )
        }

        // Block file operations (must use approved methods)
        if (name in ['newFile', 'createFile', 'deleteFile']) {
            throw new SecurityException(
                "Enterprise policy: Use readCompanyFile() for approved file operations"
            )
        }

        // Allow safe methods
        return super.invokeMethod(name, args)
    }

    /**
     * Block access to dangerous classes.
     */
    @Override
    Object getProperty(String property) {
        // Block access to dangerous system classes
        if (property in ['System', 'Runtime', 'ProcessBuilder', 'File', 'Socket', 'URL']) {
            throw new SecurityException(
                "Enterprise policy: Direct access to '${property}' not allowed. " +
                "Use approved enterprise methods."
            )
        }

        return super.getProperty(property)
    }

    // =========================================================================
    // Mock Implementation Helpers (for demonstration)
    // =========================================================================

    private Object executeApprovedDatabaseQuery(String sql) {
        // In real implementation, delegate to DatabaseService
        return [
            [id: 1, name: 'Example', value: 100],
            [id: 2, name: 'Demo', value: 200]
        ]
    }

    private Object executeApprovedHttpCall(String url) {
        // In real implementation, delegate to HttpService
        return [status: 200, body: '{"result": "success"}']
    }
}
