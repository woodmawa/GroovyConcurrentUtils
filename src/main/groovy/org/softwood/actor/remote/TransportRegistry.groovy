package org.softwood.actor.remote

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.softwood.actor.ActorSystem
import org.softwood.actor.remote.http.HttpTransport
import org.softwood.actor.remote.microstream.MicroStreamTransport
import org.softwood.actor.remote.rsocket.RSocketTransport

import java.util.concurrent.ConcurrentHashMap

/**
 * Factory and registry for RemotingTransport implementations.
 * 
 * <p>Manages transport lifecycle and provides scheme-based lookup.
 * Supports pluggable transports configured via config files.</p>
 * 
 * <h2>Supported Transports</h2>
 * <ul>
 *   <li><strong>rsocket</strong> - RSocket binary protocol (default, recommended)</li>
 *   <li><strong>http</strong> - HTTP/JSON protocol (fallback, debugging)</li>
 *   <li><strong>microstream</strong> - MicroStream protocol (experimental)</li>
 * </ul>
 * 
 * <h2>Configuration</h2>
 * Set transport in config.groovy:
 * <pre>
 * actor {
 *     remote {
 *         transport = 'rsocket'  // or 'http', 'microstream'
 *         rsocket {
 *             port = 7000
 *         }
 *     }
 * }
 * </pre>
 * 
 * @since 2.0.0
 */
@Slf4j
@CompileStatic
class TransportRegistry {
    
    /** Registered transports by scheme */
    private static final Map<String, RemotingTransport> transports = new ConcurrentHashMap<>()
    
    /** Default transport scheme */
    private static String defaultScheme = 'rsocket'
    
    /**
     * Registers a transport implementation.
     * 
     * @param transport transport to register
     */
    static void register(RemotingTransport transport) {
        def scheme = transport.scheme()
        
        if (transports.containsKey(scheme)) {
            log.warn("Transport '${scheme}' already registered, replacing")
        }
        
        transports.put(scheme, transport)
        log.info("Registered transport: ${scheme}")
    }
    
    /**
     * Gets transport by scheme.
     * 
     * @param scheme transport scheme (e.g., 'rsocket', 'http')
     * @return transport instance
     * @throws IllegalArgumentException if scheme not found
     */
    static RemotingTransport get(String scheme) {
        def transport = transports.get(scheme)
        
        if (transport == null) {
            throw new IllegalArgumentException("No transport registered for scheme: ${scheme}")
        }
        
        return transport
    }
    
    /**
     * Gets default transport.
     * 
     * @return default transport instance
     */
    static RemotingTransport getDefault() {
        return get(defaultScheme)
    }
    
    /**
     * Sets the default transport scheme.
     * 
     * @param scheme scheme to use as default
     */
    static void setDefaultScheme(String scheme) {
        if (!transports.containsKey(scheme)) {
            throw new IllegalArgumentException("Cannot set unknown scheme as default: ${scheme}")
        }
        defaultScheme = scheme
        log.info("Default transport set to: ${scheme}")
    }
    
    /**
     * Checks if transport is registered.
     * 
     * @param scheme transport scheme
     * @return true if registered
     */
    static boolean hasTransport(String scheme) {
        return transports.containsKey(scheme)
    }
    
    /**
     * Gets all registered transport schemes.
     * 
     * @return set of schemes
     */
    static Set<String> getSchemes() {
        return new HashSet<>(transports.keySet())
    }
    
    /**
     * Initializes transports based on configuration.
     * 
     * @param config configuration map
     * @param actorSystem actor system for local delivery
     */
    static void initializeFromConfig(ConfigObject config, ActorSystem actorSystem) {
        log.info("Initializing transports from configuration")
        
        def actorConfig = config.actor as ConfigObject
        def remoteConfig = actorConfig?.remote as ConfigObject
        
        if (remoteConfig == null) {
            log.warn("No actor.remote configuration found, using defaults")
            // Register default RSocket transport
            registerRSocketTransport(actorSystem, 7000, true)
            return
        }
        
        // Get default transport
        def defaultTransport = remoteConfig.transport as String ?: 'rsocket'
        log.info("Default transport: ${defaultTransport}")
        
        // Initialize RSocket transport if enabled
        def rsocketConfig = remoteConfig.rsocket as ConfigObject
        if (rsocketConfig?.enabled) {
            def port = (rsocketConfig.port ?: 7000) as int
            registerRSocketTransport(actorSystem, port, true)
        }
        
        // Initialize HTTP transport if enabled
        def httpConfig = remoteConfig.http as ConfigObject
        if (httpConfig?.enabled) {
            def port = (httpConfig.port ?: 8080) as int
            registerHttpTransport(actorSystem, port, true)
        }
        
        // Initialize MicroStream transport if requested
        if (defaultTransport == 'microstream') {
            registerMicroStreamTransport(actorSystem)
        }
        
        // Set default scheme
        if (hasTransport(defaultTransport)) {
            setDefaultScheme(defaultTransport)
        } else {
            log.warn("Default transport '${defaultTransport}' not available, using first registered")
            if (!transports.isEmpty()) {
                setDefaultScheme(transports.keySet().first())
            }
        }
        
        // Start all transports
        startAll()
    }
    
    /**
     * Registers RSocket transport.
     */
    private static void registerRSocketTransport(ActorSystem actorSystem, int port, boolean serverEnabled) {
        try {
            def transport = new RSocketTransport(actorSystem, port, serverEnabled)
            register(transport)
            log.info("RSocket transport registered (port=${port})")
        } catch (Exception e) {
            log.error("Failed to register RSocket transport", e)
        }
    }
    
    /**
     * Registers HTTP transport.
     */
    private static void registerHttpTransport(ActorSystem actorSystem, int port, boolean serverEnabled) {
        try {
            def transport = new HttpTransport(actorSystem, port, serverEnabled)
            register(transport)
            log.info("HTTP transport registered (port=${port})")
        } catch (Exception e) {
            log.error("Failed to register HTTP transport", e)
        }
    }
    
    /**
     * Registers MicroStream transport.
     */
    private static void registerMicroStreamTransport(ActorSystem actorSystem) {
        try {
            def transport = new MicroStreamTransport(actorSystem)
            register(transport)
            log.info("MicroStream transport registered")
        } catch (Exception e) {
            log.error("Failed to register MicroStream transport", e)
        }
    }
    
    /**
     * Starts all registered transports.
     */
    static void startAll() {
        log.info("Starting all transports")
        
        transports.values().each { transport ->
            try {
                transport.start()
            } catch (Exception e) {
                log.error("Failed to start transport: ${transport.scheme()}", e)
            }
        }
    }
    
    /**
     * Stops all registered transports.
     */
    static void stopAll() {
        log.info("Stopping all transports")
        
        transports.values().each { transport ->
            try {
                transport.close()
            } catch (Exception e) {
                log.error("Failed to stop transport: ${transport.scheme()}", e)
            }
        }
        
        transports.clear()
    }
    
    /**
     * Clears all registered transports without stopping them.
     * Useful for testing.
     */
    static void clear() {
        transports.clear()
        defaultScheme = 'rsocket'
    }
}
