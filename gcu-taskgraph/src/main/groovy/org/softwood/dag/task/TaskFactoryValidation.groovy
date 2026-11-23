package org.softwood.dag.task

import java.util.regex.Pattern

/**
 * Validation utilities for TaskFactory.
 * Prevents injection attacks and ensures safe task IDs.
 */
class TaskFactoryValidation {
    
    /** Valid task ID pattern: alphanumeric, dash, underscore only */
    private static final Pattern VALID_TASK_ID = ~/^[a-zA-Z0-9_-]{1,100}$/
    
    /** Reserved words that cannot be used as task IDs */
    private static final Set<String> RESERVED_WORDS = [
        // Core keywords
        'graph', 'task', 'execute', 'result', 'state', 'ctx', 'context',
        'input', 'output', 'promise', 'factory',
        
        // Control flow
        'if', 'else', 'while', 'for', 'do', 'switch', 'case', 'break', 
        'continue', 'return', 'throw', 'try', 'catch', 'finally',
        
        // Boolean/logic
        'and', 'or', 'not', 'true', 'false', 'null',
        
        // Common dangerous patterns
        'eval', 'exec', 'system', 'delete', 'drop', 'truncate'
    ] as Set
    
    /**
     * Validates a task ID for safety and compliance.
     * 
     * @param id the task ID to validate
     * @throws IllegalArgumentException if validation fails
     */
    static void validateTaskId(String id) {
        // Check null/empty
        if (!id) {
            throw new IllegalArgumentException(
                "Task ID cannot be null or empty"
            )
        }
        
        // Check length and character set
        if (!id.matches(VALID_TASK_ID)) {
            if (id.length() > 100) {
                throw new IllegalArgumentException(
                    "Task ID must be 100 characters or less (got ${id.length()}): '${sanitizeForErrorMessage(id)}'"
                )
            }
            
            throw new IllegalArgumentException(
                "Task ID must contain only alphanumeric characters, dash, or underscore. " +
                "Got: '${sanitizeForErrorMessage(id)}'"
            )
        }
        
        // Check reserved words
        if (RESERVED_WORDS.contains(id.toLowerCase())) {
            throw new IllegalArgumentException(
                "Task ID cannot be a reserved word: '${id}'"
            )
        }
        
        // Check for SQL injection patterns
        if (containsSqlInjectionPattern(id)) {
            throw new IllegalArgumentException(
                "Task ID contains suspicious SQL patterns: '${sanitizeForErrorMessage(id)}'"
            )
        }
        
        // Check for path traversal
        if (id.contains('..') || id.contains('/') || id.contains('\\')) {
            throw new IllegalArgumentException(
                "Task ID cannot contain path traversal characters: '${sanitizeForErrorMessage(id)}'"
            )
        }
    }
    
    /**
     * Validates task name (more lenient than ID).
     * Names can contain spaces and more characters.
     * 
     * @param name the task name to validate
     * @throws IllegalArgumentException if validation fails
     */
    static void validateTaskName(String name) {
        if (!name) {
            throw new IllegalArgumentException(
                "Task name cannot be null or empty"
            )
        }
        
        if (name.length() > 200) {
            throw new IllegalArgumentException(
                "Task name must be 200 characters or less (got ${name.length()}): '${sanitizeForErrorMessage(name)}'"
            )
        }
        
        // Names can have more characters, but still prevent dangerous patterns
        if (containsSqlInjectionPattern(name)) {
            throw new IllegalArgumentException(
                "Task name contains suspicious SQL patterns: '${sanitizeForErrorMessage(name)}'"
            )
        }
    }
    
    /**
     * Check for common SQL injection patterns.
     */
    private static boolean containsSqlInjectionPattern(String str) {
        def lowerStr = str.toLowerCase()
        
        // Common SQL injection patterns
        def patterns = [
            "';", "'\"", "';--", "' or ", "' and ", " or 1=1", " and 1=1",
            "drop table", "drop database", "delete from", "truncate table",
            "insert into", "update ", "exec(", "execute(", "xp_cmdshell"
        ]
        
        return patterns.any { pattern -> lowerStr.contains(pattern) }
    }
    
    /**
     * Sanitize string for safe display in error messages.
     * Truncates and removes control characters.
     */
    private static String sanitizeForErrorMessage(String str) {
        if (!str) return "<empty>"
        
        // Remove control characters
        def sanitized = str.replaceAll(/[\p{Cntrl}]/, '')
        
        // Truncate if too long
        if (sanitized.length() > 50) {
            sanitized = sanitized.substring(0, 50) + "..."
        }
        
        return sanitized
    }
    
    /**
     * Check if a task ID is valid without throwing.
     * 
     * @param id the task ID to check
     * @return true if valid, false otherwise
     */
    static boolean isValidTaskId(String id) {
        try {
            validateTaskId(id)
            return true
        } catch (IllegalArgumentException e) {
            return false
        }
    }
}
