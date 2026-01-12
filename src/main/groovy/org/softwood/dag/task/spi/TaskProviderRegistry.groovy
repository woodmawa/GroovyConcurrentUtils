package org.softwood.dag.task.spi

import groovy.util.logging.Slf4j

import java.util.concurrent.ConcurrentHashMap

/**
 * Registry for task providers with ServiceLoader discovery support.
 *
 * <p>Manages both manually registered providers and those discovered via
 * Java's ServiceLoader mechanism. Thread-safe and supports priority-based
 * provider selection.</p>
 */
@Slf4j
class TaskProviderRegistry {
    
    // Thread-safe storage for registered providers
    private static final Map<String, ITaskProvider> providers = new ConcurrentHashMap<>()
    
    // Cache for discovered providers to avoid repeated classpath scanning
    private static volatile boolean discoveryCompleted = false
    private static final Object discoveryLock = new Object()
    
    /**
     * Register a task provider programmatically.
     *
     * @param provider provider to register
     * @throws IllegalArgumentException if provider is null or type name is blank
     */
    static void register(ITaskProvider provider) {
        if (!provider) {
            throw new IllegalArgumentException("Provider cannot be null")
        }
        
        String typeName = provider.taskTypeName?.trim()
        if (!typeName) {
            throw new IllegalArgumentException("Provider must have a non-blank task type name")
        }
        
        // Check for conflicts
        ITaskProvider existing = providers.get(typeName)
        if (existing) {
            // Allow override if new provider has higher priority
            if (provider.priority > existing.priority) {
                log.info("Replacing provider for '{}': {} (priority {}) -> {} (priority {})",
                        typeName, existing.class.simpleName, existing.priority,
                        provider.class.simpleName, provider.priority)
                providers.put(typeName, provider)
            } else {
                log.warn("Ignoring provider {} for '{}' - existing provider {} has higher priority ({} vs {})",
                        provider.class.simpleName, typeName, existing.class.simpleName,
                        existing.priority, provider.priority)
            }
        } else {
            log.debug("Registered provider for '{}': {} (priority {})",
                    typeName, provider.class.simpleName, provider.priority)
            providers.put(typeName, provider)
        }
    }
    
    /**
     * Unregister a provider by type name.
     *
     * @param typeName task type name
     * @return true if a provider was removed
     */
    static boolean unregister(String typeName) {
        boolean removed = providers.remove(typeName) != null
        if (removed) {
            log.debug("Unregistered provider for '{}'", typeName)
        }
        return removed
    }
    
    /**
     * Find a provider that supports the given type string.
     * Checks both registered providers and performs ServiceLoader discovery if needed.
     *
     * @param typeString task type string (case-insensitive)
     * @return matching provider, or null if none found
     */
    static ITaskProvider findProvider(String typeString) {
        if (!typeString) {
            return null
        }
        
        // Ensure discovery has been performed
        ensureDiscoveryCompleted()
        
        // First try exact match by primary type name
        String normalized = typeString.trim().toLowerCase()
        ITaskProvider provider = providers.values().find { 
            it.taskTypeName?.toLowerCase() == normalized 
        }
        
        if (provider) {
            return provider
        }
        
        // Then try supports() method for aliases
        List<ITaskProvider> candidates = providers.values().findAll { 
            it.supports(typeString) 
        }
        
        if (candidates.isEmpty()) {
            return null
        }
        
        if (candidates.size() == 1) {
            return candidates[0]
        }
        
        // Multiple candidates - return highest priority
        return candidates.max { it.priority }
    }
    
    /**
     * Get all registered providers.
     *
     * @return unmodifiable collection of providers
     */
    static Collection<ITaskProvider> getAllProviders() {
        ensureDiscoveryCompleted()
        return Collections.unmodifiableCollection(providers.values())
    }
    
    /**
     * Discover providers via ServiceLoader.
     * This is called automatically on first access but can be called explicitly.
     * 
     * <p>Discovery only runs once - subsequent calls return empty list.
     * Use reset() to allow re-discovery.</p>
     *
     * @return list of newly discovered providers (empty if already discovered)
     */
    static List<ITaskProvider> discoverProviders() {
        // Check if discovery already completed
        if (discoveryCompleted) {
            log.debug("Discovery already completed, returning empty list")
            return []
        }
        
        List<ITaskProvider> discovered = []
        
        synchronized (discoveryLock) {
            // Double-check inside lock
            if (discoveryCompleted) {
                return []
            }
            
            try {
                ServiceLoader<ITaskProvider> loader = ServiceLoader.load(ITaskProvider)
                for (ITaskProvider provider : loader) {
                    discovered.add(provider)
                    register(provider)
                }
                
                if (discovered) {
                    log.info("Discovered {} task provider(s) via ServiceLoader", discovered.size())
                }
            } catch (Exception e) {
                log.error("Error during ServiceLoader discovery", e)
            }
            
            discoveryCompleted = true
        }
        
        return discovered
    }
    
    /**
     * Clear all registered providers.
     * Useful for testing. Sets discovery flag to prevent auto-rediscovery.
     */
    static void clear() {
        int count = providers.size()
        providers.clear()
        synchronized (discoveryLock) {
            discoveryCompleted = true  // Prevent auto-rediscovery after clear
        }
        log.debug("Cleared {} registered provider(s)", count)
    }
    
    /**
     * Reset the registry completely, including discovery state.
     * Useful for testing scenarios that need to re-run discovery.
     */
    static void reset() {
        providers.clear()
        synchronized (discoveryLock) {
            discoveryCompleted = false
        }
        log.debug("Reset provider registry")
    }
    
    /**
     * Check if a provider exists for the given type.
     *
     * @param typeString task type string
     * @return true if a provider exists
     */
    static boolean hasProvider(String typeString) {
        return findProvider(typeString) != null
    }
    
    /**
     * Get all registered type names.
     *
     * @return set of type names
     */
    static Set<String> getRegisteredTypes() {
        ensureDiscoveryCompleted()
        return Collections.unmodifiableSet(providers.keySet())
    }
    
    // =========================================================================
    // Private Helpers
    // =========================================================================
    
    private static void ensureDiscoveryCompleted() {
        if (!discoveryCompleted) {
            synchronized (discoveryLock) {
                if (!discoveryCompleted) {
                    discoverProviders()
                    discoveryCompleted = true
                }
            }
        }
    }
}
