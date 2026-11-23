package org.softwood.dag.task.objectstorage.providers

import groovy.util.logging.Slf4j
import org.softwood.dag.task.objectstorage.*

/**
 * Abstract base provider with common functionality
 */
@Slf4j
abstract class AbstractObjectStoreProvider implements ObjectStoreProvider {
    
    /** Provider configuration */
    protected Map<String, Object> config = [:]
    
    /** Provider capabilities */
    protected ProviderCapabilities capabilities
    
    /** Initialization flag */
    protected boolean initialized = false
    
    /**
     * Configure the provider
     */
    void configure(Map<String, Object> config) {
        this.config = new HashMap<>(config)
        validateConfig()
    }
    
    /**
     * Validate configuration - override in subclasses
     */
    protected void validateConfig() {
        // Default: no validation
    }
    
    /**
     * Get configuration value with default
     */
    protected <T> T getConfigValue(String key, T defaultValue) {
        return (T) config.getOrDefault(key, defaultValue)
    }
    
    /**
     * Get required configuration value
     */
    protected <T> T getRequiredConfigValue(String key) {
        def value = config.get(key)
        if (value == null) {
            throw new ObjectStoreException("Required configuration '${key}' missing for provider ${providerId}")
        }
        return (T) value
    }
    
    @Override
    ProviderCapabilities getCapabilities() {
        return capabilities
    }
    
    @Override
    void close() {
        log.debug "Closing provider ${providerId}"
        initialized = false
    }
    
    // =========================================================================
    // Default Implementations
    // =========================================================================
    
    @Override
    boolean containerExists(String container) throws ObjectStoreException {
        try {
            listContainers().any { it.name == container }
        } catch (Exception e) {
            throw new ObjectStoreException("Failed to check container existence: ${container}", e)
        }
    }
    
    @Override
    boolean exists(ObjectRef ref) {
        return stat(ref) != null
    }
    
    @Override
    ObjectInfo put(ObjectRef ref, byte[] data, PutOptions options) {
        return put(ref, new ByteArrayInputStream(data), data.length, options)
    }
    
    @Override
    ObjectInfo put(ObjectRef ref, String text, PutOptions options) {
        def charset = options.charset ?: 'UTF-8'
        def bytes = text.getBytes(charset)
        return put(ref, bytes, options)
    }
    
    @Override
    byte[] getBytes(ObjectRef ref, GetOptions options) {
        def read = get(ref, options)
        try {
            return read.stream().bytes
        } finally {
            read.close()
        }
    }
    
    @Override
    String getText(ObjectRef ref, GetOptions options) {
        def bytes = getBytes(ref, options)
        def charset = options.charset ?: 'UTF-8'
        return new String(bytes, charset)
    }
    
    /**
     * Build basic ObjectRead implementation
     */
    protected ObjectRead createObjectRead(ObjectInfo info, InputStream stream) {
        return new ObjectRead() {
            @Override
            ObjectInfo getInfo() { return info }
            
            @Override
            InputStream getStream() { return stream }
            
            @Override
            void close() throws IOException {
                stream?.close()
            }
        }
    }
    
    /**
     * Build basic PagedResult implementation
     */
    protected <T> PagedResult<T> createPagedResult(List<T> items, String nextToken) {
        return new PagedResult<T>() {
            @Override
            List<T> getResults() { return items }
            
            @Override
            String getNextPageToken() { return nextToken }
            
            @Override
            boolean hasMore() { return nextToken != null }
            
            @Override
            Iterator<T> iterator() { return items.iterator() }
        }
    }
}
