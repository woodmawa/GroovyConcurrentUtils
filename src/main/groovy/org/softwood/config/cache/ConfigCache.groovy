package org.softwood.config.cache

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe cache for parsed configuration files.
 * 
 * Caches parsed config data and only re-parses when:
 * 1. The file has been modified (timestamp changed)
 * 2. The cache entry doesn't exist
 * 
 * This significantly improves performance when TaskContext or other
 * components repeatedly load the same config files.
 */
@Slf4j
@CompileStatic
class ConfigCache {

    private static class CacheEntry {
        final Map<String, Object> data
        final FileTime lastModified
        final long accessCount
        final long createdAt

        CacheEntry(Map<String, Object> data, FileTime lastModified) {
            this.data = data
            this.lastModified = lastModified
            this.accessCount = 1
            this.createdAt = System.currentTimeMillis()
        }

        // Private constructor for withIncrementedAccess
        private CacheEntry(Map<String, Object> data, FileTime lastModified, long accessCount, long createdAt) {
            this.data = data
            this.lastModified = lastModified
            this.accessCount = accessCount
            this.createdAt = createdAt
        }

        CacheEntry withIncrementedAccess() {
            return new CacheEntry(data, lastModified, this.accessCount + 1, this.createdAt)
        }
    }

    // Cache key: resourcePath (e.g., "/config/application.yml")
    private static final ConcurrentHashMap<String, CacheEntry> cache = 
        new ConcurrentHashMap<>()

    private static volatile boolean enabled = true
    private static volatile long maxCacheSize = 1000
    private static volatile long maxCacheAge = 5 * 60 * 1000 // 5 minutes default

    /**
     * Get cached config data or parse and cache it.
     * 
     * @param resourcePath the classpath resource path (e.g., "/config/application.yml")
     * @param parser closure that parses the resource and returns Map<String, Object>
     * @return parsed config data (from cache if unchanged)
     */
    static Map<String, Object> getOrParse(
            String resourcePath,
            Closure<Map<String, Object>> parser
    ) {
        if (!enabled) {
            log.trace("ConfigCache disabled, parsing directly: {}", resourcePath)
            return parser.call()
        }

        // For classpath resources, we can't easily check modification time
        // So we use a simpler strategy: cache on first access, invalidate on manual clear
        CacheEntry entry = cache.get(resourcePath)
        
        if (entry != null) {
            // Check if entry is too old
            if (maxCacheAge > 0) {
                long age = System.currentTimeMillis() - entry.createdAt
                if (age > maxCacheAge) {
                    log.debug("Cache entry expired for {}, age: {}ms", resourcePath, age)
                    cache.remove(resourcePath)
                    entry = null
                }
            }
        }

        if (entry != null) {
            log.trace("ConfigCache HIT for {}, access count: {}", 
                resourcePath, entry.accessCount)
            // Update access count
            cache.put(resourcePath, entry.withIncrementedAccess())
            return entry.data
        }

        // Cache MISS - parse and cache
        log.debug("ConfigCache MISS for {}, parsing...", resourcePath)
        Map<String, Object> data = parser.call()
        
        // Check cache size limit
        if (maxCacheSize > 0 && cache.size() >= maxCacheSize) {
            log.warn("ConfigCache size limit reached ({}), clearing oldest entries", 
                maxCacheSize)
            evictOldestEntries()
        }

        FileTime now = FileTime.fromMillis(System.currentTimeMillis())
        cache.put(resourcePath, new CacheEntry(data, now))
        
        return data
    }

    /**
     * For file-based configs (not classpath), check modification time.
     * This is more advanced and supports hot-reload scenarios.
     */
    static Map<String, Object> getOrParseFile(
            Path filePath,
            Closure<Map<String, Object>> parser
    ) {
        if (!enabled) {
            return parser.call()
        }

        String key = filePath.toAbsolutePath().toString()
        
        // Get current file modification time
        FileTime currentModTime
        try {
            currentModTime = Files.getLastModifiedTime(filePath)
        } catch (IOException e) {
            log.debug("Cannot get modification time for {}, parsing fresh", key)
            return parser.call()
        }

        CacheEntry entry = cache.get(key)
        
        if (entry != null) {
            // Check if file was modified
            if (entry.lastModified.equals(currentModTime)) {
                log.trace("ConfigCache HIT for file {}, mod time unchanged", key)
                cache.put(key, entry.withIncrementedAccess())
                return entry.data
            } else {
                log.debug("ConfigCache INVALIDATE for {}, file modified", key)
            }
        }

        // Parse and cache
        log.debug("ConfigCache MISS for file {}, parsing...", key)
        Map<String, Object> data = parser.call()
        cache.put(key, new CacheEntry(data, currentModTime))
        
        return data
    }

    /**
     * Clear the entire cache. Useful for testing or hot-reload scenarios.
     */
    static void clear() {
        int size = cache.size()
        cache.clear()
        log.info("ConfigCache cleared, removed {} entries", size)
    }

    /**
     * Clear cache for a specific resource.
     */
    static void invalidate(String resourcePath) {
        CacheEntry removed = cache.remove(resourcePath)
        if (removed != null) {
            log.debug("ConfigCache invalidated: {}", resourcePath)
        }
    }

    /**
     * Get cache statistics.
     */
    static Map<String, Object> getStats() {
        int size = cache.size()
        long totalAccesses = cache.values().sum { it.accessCount } as Long ?: 0
        
        return [
            size: size,
            totalAccesses: totalAccesses,
            enabled: enabled,
            maxCacheSize: maxCacheSize,
            maxCacheAge: maxCacheAge,
            entries: cache.keySet().toList()
        ]
    }

    /**
     * Enable or disable caching globally.
     */
    static void setEnabled(boolean enabled) {
        this.enabled = enabled
        log.info("ConfigCache enabled: {}", enabled)
    }

    /**
     * Set maximum cache size. When exceeded, oldest entries are evicted.
     */
    static void setMaxCacheSize(long maxSize) {
        this.maxCacheSize = maxSize
        log.info("ConfigCache maxCacheSize: {}", maxSize)
    }

    /**
     * Set maximum cache age in milliseconds. Entries older than this are evicted.
     * Set to 0 to disable age-based eviction.
     */
    static void setMaxCacheAge(long ageMillis) {
        this.maxCacheAge = ageMillis
        log.info("ConfigCache maxCacheAge: {}ms", ageMillis)
    }

    /**
     * Evict the oldest 10% of entries (by creation time).
     */
    private static void evictOldestEntries() {
        List<Map.Entry<String, CacheEntry>> entries = 
            new ArrayList<>(cache.entrySet())
        
        // Sort by creation time (oldest first)
        entries.sort { a, b -> 
            Long.compare(a.value.createdAt, b.value.createdAt)
        }
        
        // Remove oldest 10%
        int toRemove = Math.max(1, (int)(entries.size() * 0.1))
        for (int i = 0; i < toRemove && i < entries.size(); i++) {
            String key = entries[i].key
            cache.remove(key)
            log.trace("Evicted cache entry: {}", key)
        }
        
        log.debug("Evicted {} oldest cache entries", toRemove)
    }
}
