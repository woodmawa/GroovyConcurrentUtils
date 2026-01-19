package org.softwood.dag.task.messaging

import groovy.util.logging.Slf4j
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory receive storage implementation.
 * 
 * <p>Provides IReceiveStorage interface without actual persistence.
 * Useful for testing or when persistence is not required.</p>
 * 
 * <p><strong>WARNING:</strong> Data is lost on application restart!
 * Use Redis or Database storage for production durability.</p>
 * 
 * <h3>Usage:</h3>
 * <pre>
 * def receiver = new WebhookReceiver()
 * receiver.storage = new InMemoryReceiveStorage()
 * 
 * receiveTask("test-receive") {
 *     receiver receiver
 *     persist true  // Will persist to memory only
 *     correlationKey { prev -> prev.id }
 * }
 * </pre>
 * 
 * @since 2.1.0
 */
@Slf4j
class InMemoryReceiveStorage implements IReceiveStorage {
    
    private final Map<String, Map<String, Object>> storage = new ConcurrentHashMap<>()
    
    @Override
    void save(String key, Map<String, Object> data) {
        storage.put(key, new HashMap<>(data))
        log.debug("InMemoryReceiveStorage: saved $key")
    }
    
    @Override
    Map<String, Object> get(String key) {
        def data = storage.get(key)
        if (data) {
            log.debug("InMemoryReceiveStorage: retrieved $key")
            return new HashMap<>(data)
        }
        return null
    }
    
    @Override
    void delete(String key) {
        def removed = storage.remove(key)
        if (removed) {
            log.debug("InMemoryReceiveStorage: deleted $key")
        }
    }
    
    @Override
    List<String> listKeys(String pattern) {
        // Simple wildcard matching (not full regex)
        def regex = pattern.replace("*", ".*").replace("?", ".")
        def matchingKeys = storage.keySet().findAll { it.matches(regex) }
        log.debug("InMemoryReceiveStorage: found ${matchingKeys.size()} keys matching '$pattern'")
        return new ArrayList<>(matchingKeys)
    }
    
    @Override
    boolean exists(String key) {
        return storage.containsKey(key)
    }
    
    @Override
    void clear() {
        def size = storage.size()
        storage.clear()
        log.info("InMemoryReceiveStorage: cleared $size entries")
    }
    
    @Override
    void close() {
        clear()
        log.debug("InMemoryReceiveStorage: closed")
    }
    
    /**
     * Get current storage size (for monitoring/testing).
     */
    int size() {
        return storage.size()
    }
}
