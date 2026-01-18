package org.softwood.dag.resilience

import groovy.transform.Immutable

/**
 * Represents an idempotency key for caching task results.
 * 
 * <p>An idempotency key uniquely identifies a task execution based on:
 * - Task ID
 * - Input value (serialized to string)
 * - Optional custom key component</p>
 * 
 * <h3>Usage:</h3>
 * <pre>
 * def key = new IdempotencyKey(
 *     taskId: "api-call",
 *     inputHash: "abc123",
 *     customKey: "user-456"
 * )
 * </pre>
 */
@Immutable
class IdempotencyKey {
    
    /** The task ID */
    String taskId
    
    /** Hash of the input value */
    String inputHash
    
    /** Optional custom key component */
    String customKey
    
    /**
     * Generate a composite key string.
     */
    String toKey() {
        if (customKey) {
            // When custom key is provided, use only taskId + customKey
            // (don't include inputHash - custom key replaces it)
            return "${taskId}:${customKey}"
        }
        return "${taskId}:${inputHash}"
    }
    
    /**
     * Generate an idempotency key from task ID and input.
     * 
     * @param taskId the task identifier
     * @param input the task input value
     * @param customKey optional custom key component
     * @return the generated key
     */
    static IdempotencyKey from(String taskId, Object input, String customKey = null) {
        String inputHash = generateInputHash(input)
        return new IdempotencyKey(
            taskId: taskId,
            inputHash: inputHash,
            customKey: customKey
        )
    }
    
    /**
     * Generate a hash from the input value.
     * 
     * <p>Uses the input's toString() and hashCode() to generate
     * a stable hash. For null inputs, returns "null".</p>
     */
    static String generateInputHash(Object input) {
        if (input == null) {
            return "null"
        }
        
        // Use both string representation and hashCode for stability
        String str = input.toString()
        int hash = input.hashCode()
        
        // Combine for a stable hash
        return "${str.hashCode()}_${hash}"
    }
    
    @Override
    String toString() {
        return toKey()
    }
}
