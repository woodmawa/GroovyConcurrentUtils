package org.softwood.dag.task

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import static org.junit.jupiter.api.Assertions.*

/**
 * Tests for TaskFactory input validation.
 * Ensures task IDs are safe and prevent injection attacks.
 */
class TaskFactoryValidationTest {
    
    private TaskContext ctx
    
    @BeforeEach
    void setup() {
        ctx = new TaskContext()
    }
    
    // =========================================================================
    // Valid Task ID Tests
    // =========================================================================
    
    @Test
    void "valid task IDs should be accepted"() {
        // Should not throw
        TaskFactory.createServiceTask("task1", "Task 1", ctx)
        TaskFactory.createServiceTask("my-task", "My Task", ctx)
        TaskFactory.createServiceTask("my_task", "My Task", ctx)
        TaskFactory.createServiceTask("Task-123", "Task 123", ctx)
        TaskFactory.createServiceTask("a", "A", ctx)
        TaskFactory.createServiceTask("ABC123_xyz-789", "Complex", ctx)
    }
    
    @Test
    void "task ID with 100 characters should be accepted"() {
        def longId = "a" * 100
        TaskFactory.createServiceTask(longId, "Long ID", ctx)
    }
    
    // =========================================================================
    // Null/Empty Task ID Tests
    // =========================================================================
    
    @Test
    void "null task ID should be rejected"() {
        def exception = assertThrows(IllegalArgumentException) {
            TaskFactory.createServiceTask(null, "Task", ctx)
        }
        assertTrue(exception.message.contains("cannot be null or empty"))
    }
    
    @Test
    void "empty task ID should be rejected"() {
        def exception = assertThrows(IllegalArgumentException) {
            TaskFactory.createServiceTask("", "Task", ctx)
        }
        assertTrue(exception.message.contains("cannot be null or empty"))
    }
    
    // =========================================================================
    // Length Validation Tests
    // =========================================================================
    
    @Test
    void "task ID over 100 characters should be rejected"() {
        def tooLong = "a" * 101
        def exception = assertThrows(IllegalArgumentException) {
            TaskFactory.createServiceTask(tooLong, "Too Long", ctx)
        }
        assertTrue(exception.message.contains("100 characters or less"))
    }
    
    // =========================================================================
    // Invalid Character Tests
    // =========================================================================
    
    @Test
    void "task ID with spaces should be rejected"() {
        def exception = assertThrows(IllegalArgumentException) {
            TaskFactory.createServiceTask("my task", "My Task", ctx)
        }
        assertTrue(exception.message.contains("alphanumeric"))
    }
    
    @Test
    void "task ID with special characters should be rejected"() {
        def invalidIds = ["task@123", "task#1", "task\$1", "task%1", "task&1", "task*1"]
        
        invalidIds.each { id ->
            def exception = assertThrows(IllegalArgumentException) {
                TaskFactory.createServiceTask(id, "Task", ctx)
            }
            assertTrue(exception.message.contains("alphanumeric"),
                "Expected rejection for ID: $id")
        }
    }
    
    // =========================================================================
    // Path Traversal Tests
    // =========================================================================
    
    @Test
    void "task ID with path traversal should be rejected"() {
        def exception = assertThrows(IllegalArgumentException) {
            TaskFactory.createServiceTask("../../../etc/passwd", "Malicious", ctx)
        }
        assertTrue(exception.message.contains("path traversal") || 
                   exception.message.contains("alphanumeric"))
    }
    
    @Test
    void "task ID with forward slash should be rejected"() {
        def exception = assertThrows(IllegalArgumentException) {
            TaskFactory.createServiceTask("path/to/task", "Malicious", ctx)
        }
        assertTrue(exception.message.contains("path traversal") || 
                   exception.message.contains("alphanumeric"))
    }
    
    @Test
    void "task ID with backslash should be rejected"() {
        def exception = assertThrows(IllegalArgumentException) {
            TaskFactory.createServiceTask("path\\to\\task", "Malicious", ctx)
        }
        assertTrue(exception.message.contains("path traversal") || 
                   exception.message.contains("alphanumeric"))
    }
    
    // =========================================================================
    // SQL Injection Tests
    // =========================================================================
    
    @Test
    void "task ID with SQL injection patterns should be rejected"() {
        def sqlInjections = [
            "'; DROP TABLE tasks; --",
            "' OR 1=1 --",
            "admin'--",
            "' UNION SELECT",
            "1' AND '1'='1"
        ]
        
        sqlInjections.each { id ->
            def exception = assertThrows(IllegalArgumentException) {
                TaskFactory.createServiceTask(id, "Malicious", ctx)
            }
            assertTrue(exception.message.contains("SQL") || 
                       exception.message.contains("alphanumeric"),
                "Expected rejection for SQL injection: $id")
        }
    }
    
    // =========================================================================
    // Reserved Word Tests
    // =========================================================================
    
    @Test
    void "task ID with reserved words should be rejected"() {
        def reservedWords = [
            "graph", "task", "execute", "result", "state", "ctx",
            "if", "else", "while", "for", "return",
            "and", "or", "not", "null", "true", "false",
            "eval", "exec", "delete", "drop"
        ]
        
        reservedWords.each { word ->
            def exception = assertThrows(IllegalArgumentException) {
                TaskFactory.createServiceTask(word, "Reserved", ctx)
            }
            assertTrue(exception.message.contains("reserved word"),
                "Expected rejection for reserved word: $word")
        }
    }
    
    @Test
    void "task ID with reserved word in different case should be rejected"() {
        def exception = assertThrows(IllegalArgumentException) {
            TaskFactory.createServiceTask("GRAPH", "Reserved", ctx)
        }
        assertTrue(exception.message.contains("reserved word"))
        
        exception = assertThrows(IllegalArgumentException) {
            TaskFactory.createServiceTask("Graph", "Reserved", ctx)
        }
        assertTrue(exception.message.contains("reserved word"))
    }
    
    // =========================================================================
    // Task Name Validation Tests
    // =========================================================================
    
    @Test
    void "null task name should be rejected"() {
        def exception = assertThrows(IllegalArgumentException) {
            TaskFactory.createServiceTask("valid-id", null, ctx)
        }
        assertTrue(exception.message.contains("name"))
    }
    
    @Test
    void "empty task name should be rejected"() {
        def exception = assertThrows(IllegalArgumentException) {
            TaskFactory.createServiceTask("valid-id", "", ctx)
        }
        assertTrue(exception.message.contains("name"))
    }
    
    @Test
    void "task name over 200 characters should be rejected"() {
        def tooLong = "a" * 201
        def exception = assertThrows(IllegalArgumentException) {
            TaskFactory.createServiceTask("valid-id", tooLong, ctx)
        }
        assertTrue(exception.message.contains("200 characters"))
    }
    
    @Test
    void "task name with spaces should be accepted"() {
        // Names are more lenient than IDs
        TaskFactory.createServiceTask("valid-id", "My Task Name", ctx)
    }
    
    @Test
    void "task name with SQL injection should be rejected"() {
        def exception = assertThrows(IllegalArgumentException) {
            TaskFactory.createServiceTask("valid-id", "'; DROP TABLE tasks; --", ctx)
        }
        assertTrue(exception.message.contains("SQL"))
    }
    
    // =========================================================================
    // Validation Helper Method Tests
    // =========================================================================
    
    @Test
    void "isValidTaskId should return true for valid IDs"() {
        assertTrue(TaskFactoryValidation.isValidTaskId("task1"))
        assertTrue(TaskFactoryValidation.isValidTaskId("my-task"))
        assertTrue(TaskFactoryValidation.isValidTaskId("my_task"))
    }
    
    @Test
    void "isValidTaskId should return false for invalid IDs"() {
        assertFalse(TaskFactoryValidation.isValidTaskId(null))
        assertFalse(TaskFactoryValidation.isValidTaskId(""))
        assertFalse(TaskFactoryValidation.isValidTaskId("my task"))
        assertFalse(TaskFactoryValidation.isValidTaskId("../etc/passwd"))
        assertFalse(TaskFactoryValidation.isValidTaskId("'; DROP TABLE"))
    }
    
    // =========================================================================
    // Integration Tests with Different Task Types
    // =========================================================================
    
    @Test
    void "validation should apply to all task types"() {
        def maliciousId = "'; DROP TABLE tasks; --"
        
        // Test a few different task types
        assertThrows(IllegalArgumentException) {
            TaskFactory.createManualTask(maliciousId, "Malicious", ctx)
        }
        
        assertThrows(IllegalArgumentException) {
            TaskFactory.createTimerTask(maliciousId, "Malicious", ctx)
        }
        
        assertThrows(IllegalArgumentException) {
            TaskFactory.createHttpTask(maliciousId, "Malicious", ctx)
        }
        
        assertThrows(IllegalArgumentException) {
            TaskFactory.createTask(TaskType.SERVICE, maliciousId, "Malicious", ctx)
        }
    }
    
    // =========================================================================
    // Sanitization Tests
    // =========================================================================
    
    @Test
    void "error messages should sanitize malicious input"() {
        def maliciousId = "'; DROP TABLE tasks; --" + "\u0000\u0001\u0002"
        
        def exception = assertThrows(IllegalArgumentException) {
            TaskFactory.createServiceTask(maliciousId, "Malicious", ctx)
        }
        
        // Error message should not contain control characters
        assertFalse(exception.message.contains("\u0000"))
        assertFalse(exception.message.contains("\u0001"))
        
        // Should be truncated if too long
        def veryLong = "a" * 200
        exception = assertThrows(IllegalArgumentException) {
            TaskFactory.createServiceTask(veryLong, "Long", ctx)
        }
        assertTrue(exception.message.contains("..."))
    }
    
    // =========================================================================
    // Task Type Names as IDs
    // =========================================================================
    
    @Test
    void "task type names should be allowed as task IDs"() {
        // Task type names are NOT reserved - they're valid IDs
        TaskFactory.createServiceTask("send", "Send Task", ctx)
        TaskFactory.createServiceTask("receive", "Receive Task", ctx)
        TaskFactory.createServiceTask("service", "Service Task", ctx)
        TaskFactory.createServiceTask("manual", "Manual Task", ctx)
        TaskFactory.createServiceTask("signal", "Signal Task", ctx)
        TaskFactory.createServiceTask("timer", "Timer Task", ctx)
    }
    
    // =========================================================================
    // Edge Cases
    // =========================================================================
    
    @Test
    void "single character task ID should be accepted"() {
        TaskFactory.createServiceTask("a", "Single", ctx)
        TaskFactory.createServiceTask("1", "Number", ctx)
        TaskFactory.createServiceTask("_", "Underscore", ctx)
        TaskFactory.createServiceTask("-", "Dash", ctx)
    }
    
    @Test
    void "task ID with mixed case should be accepted"() {
        TaskFactory.createServiceTask("MyTask123", "Mixed Case", ctx)
    }
    
    @Test
    void "task ID with only numbers should be accepted"() {
        TaskFactory.createServiceTask("12345", "Numbers", ctx)
    }
    
    @Test
    void "task ID with only underscores should be accepted"() {
        TaskFactory.createServiceTask("___", "Underscores", ctx)
    }
    
    @Test
    void "task ID with only dashes should be accepted"() {
        TaskFactory.createServiceTask("---", "Dashes", ctx)
    }
}
