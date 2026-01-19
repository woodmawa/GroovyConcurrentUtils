package org.softwood.dag.task.messaging

/**
 * Interface for persisting pending receive operations.
 * 
 * <p>Enables WebhookReceiver to survive application restarts by persisting
 * pending receive registrations to external storage (Redis, database, etc.).</p>
 * 
 * <h3>Implementations:</h3>
 * <ul>
 *   <li><b>InMemoryReceiveStorage</b> - Default (no persistence)</li>
 *   <li><b>RedisReceiveStorage</b> - Redis-based persistence [Optional]</li>
 *   <li><b>DatabaseReceiveStorage</b> - Database persistence [Optional]</li>
 * </ul>
 * 
 * <h3>Usage:</h3>
 * <pre>
 * def receiver = new WebhookReceiver()
 * receiver.storage = new RedisReceiveStorage(redisClient)
 * 
 * receiveTask("durable-receive") {
 *     receiver receiver
 *     persist true
 *     correlationKey { prev -> prev.orderId }
 * }
 * </pre>
 * 
 * @since 2.1.0
 */
interface IReceiveStorage {
    
    /**
     * Save a pending receive entry.
     * 
     * @param key storage key (e.g., "receive:correlation-123")
     * @param data receive data (correlationId, config, timestamp, etc.)
     * @throws IOException if storage operation fails
     */
    void save(String key, Map<String, Object> data) throws IOException
    
    /**
     * Retrieve a pending receive entry.
     * 
     * @param key storage key
     * @return receive data, or null if not found
     * @throws IOException if storage operation fails
     */
    Map<String, Object> get(String key) throws IOException
    
    /**
     * Delete a pending receive entry.
     * 
     * @param key storage key
     * @throws IOException if storage operation fails
     */
    void delete(String key) throws IOException
    
    /**
     * List all keys matching a pattern.
     * 
     * @param pattern key pattern (e.g., "receive:*")
     * @return list of matching keys
     * @throws IOException if storage operation fails
     */
    List<String> listKeys(String pattern) throws IOException
    
    /**
     * Check if a key exists.
     * 
     * @param key storage key
     * @return true if exists
     * @throws IOException if storage operation fails
     */
    boolean exists(String key) throws IOException
    
    /**
     * Clear all stored receive entries (for testing).
     * 
     * @throws IOException if storage operation fails
     */
    void clear() throws IOException
    
    /**
     * Close storage connection and clean up resources.
     */
    void close() throws IOException
}
