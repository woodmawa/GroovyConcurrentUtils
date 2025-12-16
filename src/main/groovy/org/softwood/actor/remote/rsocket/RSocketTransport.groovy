package org.softwood.actor.remote.rsocket

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.rsocket.Payload
import io.rsocket.RSocket
import io.rsocket.core.RSocketConnector
import io.rsocket.core.RSocketServer
import io.rsocket.transport.netty.client.TcpClientTransport
import io.rsocket.transport.netty.server.CloseableChannel
import io.rsocket.transport.netty.server.TcpServerTransport
import io.rsocket.util.DefaultPayload
import org.softwood.actor.ActorSystem
import org.softwood.actor.remote.RemotingTransport
import org.softwood.actor.remote.security.AuthConfig
import org.softwood.actor.remote.security.JwtTokenService
import org.softwood.actor.remote.security.TlsContextBuilder
import reactor.core.publisher.Mono
import reactor.netty.tcp.TcpClient
import reactor.netty.tcp.TcpServer

import javax.net.ssl.SSLContext
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * RSocket-based transport for remote actor communication.
 * 
 * <p>Provides high-performance binary transport using the RSocket protocol.
 * Supports both request/response (ask) and fire-and-forget (tell) patterns.</p>
 * 
 * <h2>Features</h2>
 * <ul>
 *   <li>Binary protocol (efficient)</li>
 *   <li>Reactive streams with backpressure</li>
 *   <li>Connection pooling and reuse</li>
 *   <li>Automatic JSON serialization</li>
 *   <li>Server mode for receiving messages</li>
 * </ul>
 * 
 * <h2>URI Format</h2>
 * <pre>
 * rsocket://host:port/system/actorName
 * rsocket://localhost:7000/mySystem/myActor
 * </pre>
 * 
 * @since 2.0.0
 */
@Slf4j
@CompileStatic
class RSocketTransport implements RemotingTransport {
    
    /** Local actor system for receiving messages */
    private final ActorSystem localSystem
    
    /** Server-side RSocket (if started) */
    private io.rsocket.core.RSocketServer server
    
    /** Connection pool: host:port -> RSocket */
    private final Map<String, RSocket> connections = new ConcurrentHashMap<>()
    
    /** Local server port */
    private final int localPort
    
    /** Whether server mode is enabled */
    private final boolean serverEnabled
    
    /** TLS configuration */
    private final TlsConfig tlsConfig
    
    /** Authentication configuration */
    private final AuthConfig authConfig
    
    /** JWT token service (if auth enabled) */
    private final JwtTokenService jwtService
    
    /** Client auth token */
    private volatile String clientAuthToken
    
    /**
     * TLS configuration holder.
     */
    static class TlsConfig {
        boolean enabled = false
        String keyStorePath
        String keyStorePassword
        String trustStorePath
        String trustStorePassword
        List<String> protocols = ['TLSv1.3', 'TLSv1.2']
    }
    
    /**
     * Creates RSocket transport with default configuration.
     * 
     * @param localSystem actor system for local delivery
     */
    RSocketTransport(ActorSystem localSystem) {
        this(localSystem, 7000, true, null, null)
    }
    
    /**
     * Creates RSocket transport with custom port.
     * 
     * @param localSystem actor system for local delivery
     * @param localPort port to listen on (if server enabled)
     * @param serverEnabled whether to start server
     */
    RSocketTransport(ActorSystem localSystem, int localPort, boolean serverEnabled) {
        this(localSystem, localPort, serverEnabled, null, null)
    }
    
    /**
     * Creates RSocket transport with TLS configuration.
     * 
     * @param localSystem actor system for local delivery
     * @param localPort port to listen on (if server enabled)
     * @param serverEnabled whether to start server
     * @param tlsConfig TLS configuration (null for no TLS)
     */
    RSocketTransport(ActorSystem localSystem, int localPort, boolean serverEnabled, TlsConfig tlsConfig) {
        this(localSystem, localPort, serverEnabled, tlsConfig, null)
    }
    
    /**
     * Creates RSocket transport with TLS and authentication.
     * 
     * @param localSystem actor system for local delivery
     * @param localPort port to listen on (if server enabled)
     * @param serverEnabled whether to start server
     * @param tlsConfig TLS configuration (null for no TLS)
     * @param authConfig authentication configuration (null for no auth)
     */
    RSocketTransport(ActorSystem localSystem, int localPort, boolean serverEnabled, 
                     TlsConfig tlsConfig, AuthConfig authConfig) {
        this.localSystem = localSystem
        this.localPort = localPort
        this.serverEnabled = serverEnabled
        this.tlsConfig = tlsConfig ?: new TlsConfig()
        this.authConfig = authConfig ?: new AuthConfig()
        
        // Initialize JWT service if authentication enabled
        if (this.authConfig.enabled) {
            this.authConfig.validate()
            this.jwtService = new JwtTokenService(
                this.authConfig.jwtSecret,
                this.authConfig.tokenExpiry
            )
            log.info("Authentication enabled with token expiry: ${this.authConfig.tokenExpiry}")
        } else {
            this.jwtService = null
        }
    }
    
    @Override
    String scheme() {
        return 'rsocket'
    }
    
    @Override
    void start() {
        if (serverEnabled) {
            startServer()
        }
        log.info("RSocket transport started (server=${serverEnabled}, port=${localPort})")
    }
    
    /**
     * Starts RSocket server to receive incoming actor messages.
     */
    private void startServer() {
        try {
            log.info("Starting RSocket server on port ${localPort} (TLS: ${tlsConfig.enabled})")
            
            // Create TCP server transport
            def transport
            
            if (tlsConfig.enabled) {
                // TLS-enabled server
                log.info("Configuring TLS with keystore: ${tlsConfig.keyStorePath}")
                
                def sslContext = createServerSslContext()
                
                transport = TcpServerTransport.create(
                    TcpServer.create()
                        .host("0.0.0.0")
                        .port(localPort)
                        .secure { spec ->
                            spec.sslContext(sslContext)
                        }
                )
            } else {
                // Plain TCP server
                transport = TcpServerTransport.create("0.0.0.0", localPort)
            }
            
            // Create server that handles incoming messages
            RSocketServer.create((setup, sendingSocket) -> {
                // Return acceptor that routes messages to actors
                return Mono.just(new RSocket() {
                    @Override
                    Mono<Void> fireAndForget(Payload payload) {
                        return handleTell(payload)
                    }
                    
                    @Override
                    Mono<Payload> requestResponse(Payload payload) {
                        return handleAsk(payload)
                    }
                })
            })
            .bind(transport)
            .subscribe(closeableChannel -> {
                def protocol = tlsConfig.enabled ? "TLS" : "TCP"
                log.info("RSocket server listening on port ${localPort} (${protocol})")
            })
            
        } catch (Exception e) {
            log.error("Failed to start RSocket server on port ${localPort}", e)
            throw e
        }
    }
    
    /**
     * Creates SSL context for server.
     */
    private SslContext createServerSslContext() {
        try {
            // Verify keystore exists
            if (!tlsConfig.keyStorePath) {
                throw new IllegalStateException("TLS enabled but keyStorePath not configured")
            }
            
            def keystoreFile = new File(tlsConfig.keyStorePath)
            if (!keystoreFile.exists()) {
                throw new FileNotFoundException("Keystore not found: ${tlsConfig.keyStorePath}")
            }
            
            // Load keystore
            def keyStore = java.security.KeyStore.getInstance("JKS")
            new FileInputStream(keystoreFile).withCloseable { fis ->
                keyStore.load(fis, tlsConfig.keyStorePassword.toCharArray())
            }
            
            // Get key manager factory
            def kmf = javax.net.ssl.KeyManagerFactory.getInstance(
                javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()
            )
            kmf.init(keyStore, tlsConfig.keyStorePassword.toCharArray())
            
            // Build Netty SSL context
            def sslContextBuilder = SslContextBuilder.forServer(kmf)
            
            // Add truststore if configured (for mTLS)
            if (tlsConfig.trustStorePath) {
                def trustStore = java.security.KeyStore.getInstance("JKS")
                new FileInputStream(tlsConfig.trustStorePath).withCloseable { fis ->
                    trustStore.load(fis, tlsConfig.trustStorePassword.toCharArray())
                }
                
                def tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                    javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
                )
                tmf.init(trustStore)
                
                sslContextBuilder.trustManager(tmf)
            }
            
            // Set protocols (TLS 1.3, TLS 1.2)
            if (tlsConfig.protocols) {
                sslContextBuilder.protocols(tlsConfig.protocols as String[])
            }
            
            return sslContextBuilder.build()
            
        } catch (Exception e) {
            log.error("Failed to create SSL context", e)
            throw new RuntimeException("TLS configuration error", e)
        }
    }
    
    /**
     * Handles fire-and-forget (tell) messages.
     */
    private Mono<Void> handleTell(Payload payload) {
        return Mono.fromRunnable {
            try {
                def message = deserialize(payload)
                
                // Create auth context from token
                def authContext = null
                
                // Check authentication if enabled
                if (authConfig.enabled && authConfig.requireToken) {
                    def token = message.token as String
                    if (!token) {
                        log.warn("Rejected tell - missing authentication token")
                        return
                    }
                    
                    try {
                        def claims = jwtService.validateToken(token)
                        authContext = new org.softwood.actor.remote.security.AuthContext(claims, token)
                        log.debug("Authenticated tell from: ${claims.subject}")
                    } catch (JwtTokenService.ExpiredTokenException e) {
                        log.warn("Rejected tell - token expired: ${e.message}")
                        return
                    } catch (JwtTokenService.InvalidTokenException e) {
                        log.warn("Rejected tell - invalid token: ${e.message}")
                        return
                    }
                }
                
                def actorName = message.actor as String
                def msg = message.payload
                
                log.debug("handleTell: actorName=${actorName}, localSystem=${localSystem?.name}")
                log.debug("Available actors: ${localSystem?.getActorNames()}")
                
                if (localSystem?.hasActor(actorName)) {
                    // Pass auth context to actor (future: for per-message authorization)
                    localSystem.getActor(actorName).tell(msg)
                    log.debug("Delivered tell to actor: ${actorName}")
                } else {
                    log.warn("Actor not found: ${actorName}, available: ${localSystem?.getActorNames()}")
                }
            } catch (Exception e) {
                log.error("Error handling tell", e)
            } finally {
                payload.release()
            }
        }
    }
    
    /**
     * Handles request/response (ask) messages.
     */
    private Mono<Payload> handleAsk(Payload payload) {
        return Mono.fromCallable {
            try {
                def message = deserialize(payload)
                
                // Create auth context from token
                def authContext = null
                
                // Check authentication if enabled
                if (authConfig.enabled && authConfig.requireToken) {
                    def token = message.token as String
                    if (!token) {
                        log.warn("Rejected ask - missing authentication token")
                        return DefaultPayload.create(serialize([
                            status: 'error',
                            error: 'Authentication required',
                            code: 401
                        ]))
                    }
                    
                    try {
                        def claims = jwtService.validateToken(token)
                        authContext = new org.softwood.actor.remote.security.AuthContext(claims, token)
                        log.debug("Authenticated ask from: ${claims.subject}")
                    } catch (JwtTokenService.ExpiredTokenException e) {
                        log.warn("Rejected ask - token expired: ${e.message}")
                        return DefaultPayload.create(serialize([
                            status: 'error',
                            error: 'Token expired',
                            code: 401
                        ]))
                    } catch (JwtTokenService.InvalidTokenException e) {
                        log.warn("Rejected ask - invalid token: ${e.message}")
                        return DefaultPayload.create(serialize([
                            status: 'error',
                            error: 'Invalid token',
                            code: 401
                        ]))
                    }
                }
                
                def actorName = message.actor as String
                def msg = message.payload
                def timeoutMs = (message.timeout ?: 5000) as long
                
                log.debug("handleAsk: actorName=${actorName}, localSystem=${localSystem?.name}")
                log.debug("Available actors: ${localSystem?.getActorNames()}")
                
                if (localSystem?.hasActor(actorName)) {
                    // Pass auth context to actor (future: for per-message authorization)
                    def reply = localSystem.getActor(actorName)
                        .askSync(msg, Duration.ofMillis(timeoutMs))
                    
                    log.debug("Got reply from actor: ${actorName}")
                    return DefaultPayload.create(serialize([
                        status: 'ok',
                        reply: reply
                    ]))
                } else {
                    log.warn("Actor not found: ${actorName}, available: ${localSystem?.getActorNames()}")
                    return DefaultPayload.create(serialize([
                        status: 'error',
                        error: "Actor not found: ${actorName}"
                    ]))
                }
            } catch (Exception e) {
                log.error("Error handling ask", e)
                return DefaultPayload.create(serialize([
                    status: 'error',
                    error: e.message ?: e.class.name
                ]))
            } finally {
                payload.release()
            }
        }
    }
    
    @Override
    void tell(String actorUri, Object message) {
        try {
            def target = parseUri(actorUri)
            def rsocket = getOrCreateConnection(target.host, target.port)
            
            def envelope = [
                actor: target.actor,
                payload: message,
                token: clientAuthToken  // Include auth token
            ]
            
            // Fire and forget
            rsocket.fireAndForget(DefaultPayload.create(serialize(envelope)))
                .subscribe(
                    { Void v -> log.debug("Tell sent to ${actorUri}") },
                    { Throwable error -> log.error("Tell failed to ${actorUri}", error) }
                )
                
        } catch (Exception e) {
            log.error("Failed to send tell to ${actorUri}", e)
            throw e
        }
    }
    
    @Override
    CompletableFuture<Object> ask(String actorUri, Object message, Duration timeout) {
        def future = new CompletableFuture<Object>()
        
        try {
            def target = parseUri(actorUri)
            def rsocket = getOrCreateConnection(target.host, target.port)
            
            def envelope = [
                actor: target.actor,
                payload: message,
                timeout: timeout.toMillis(),
                token: clientAuthToken  // Include auth token
            ]
            
            // Request/response
            rsocket.requestResponse(DefaultPayload.create(serialize(envelope)))
                .subscribe(
                    { payload ->
                        try {
                            def response = deserialize(payload)
                            if (response.status == 'ok') {
                                future.complete(response.reply)
                            } else {
                                future.completeExceptionally(
                                    new RuntimeException(response.error as String)
                                )
                            }
                        } finally {
                            payload.release()
                        }
                    },
                    { error ->
                        log.error("Ask failed to ${actorUri}", error)
                        future.completeExceptionally(error)
                    }
                )
                
        } catch (Exception e) {
            log.error("Failed to send ask to ${actorUri}", e)
            future.completeExceptionally(e)
        }
        
        return future
    }
    
    /**
     * Gets existing connection or creates new one.
     */
    private RSocket getOrCreateConnection(String host, int port) {
        def key = "${host}:${port}" as String
        
        return connections.computeIfAbsent(key, new java.util.function.Function<String, RSocket>() {
            @Override
            RSocket apply(String k) {
                log.info("Creating RSocket connection to ${k} (TLS: ${tlsConfig.enabled})")
                
                try {
                    if (tlsConfig.enabled) {
                        // TLS-enabled client
                        def sslContext = createClientSslContext()
                        
                        return RSocketConnector.create()
                            .connect(
                                TcpClientTransport.create(
                                    TcpClient.create()
                                        .host(host)
                                        .port(port)
                                        .secure { spec ->
                                            spec.sslContext(sslContext)
                                        }
                                )
                            )
                            .block()
                    } else {
                        // Plain TCP client
                        return RSocketConnector.create()
                            .connect(TcpClientTransport.create(host, port))
                            .block()
                    }
                        
                } catch (Exception e) {
                    log.error("Failed to connect to ${k}", e)
                    throw e
                }
            }
        })
    }
    
    /**
     * Creates SSL context for client connections.
     */
    private SslContext createClientSslContext() {
        try {
            // Build Netty SSL context for client
            def sslContextBuilder = SslContextBuilder.forClient()
            
            // Add truststore (required for client to verify server)
            if (tlsConfig.trustStorePath) {
                def trustStore = java.security.KeyStore.getInstance("JKS")
                new FileInputStream(tlsConfig.trustStorePath).withCloseable { fis ->
                    trustStore.load(fis, tlsConfig.trustStorePassword.toCharArray())
                }
                
                def tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                    javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
                )
                tmf.init(trustStore)
                
                sslContextBuilder.trustManager(tmf)
            } else {
                log.warn("TLS enabled but no truststore configured - using system default")
            }
            
            // Add client certificate if configured (for mTLS)
            if (tlsConfig.keyStorePath) {
                log.debug("Configuring client certificate for mutual TLS")
                
                def keyStore = java.security.KeyStore.getInstance("JKS")
                new FileInputStream(tlsConfig.keyStorePath).withCloseable { fis ->
                    keyStore.load(fis, tlsConfig.keyStorePassword.toCharArray())
                }
                
                def kmf = javax.net.ssl.KeyManagerFactory.getInstance(
                    javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()
                )
                kmf.init(keyStore, tlsConfig.keyStorePassword.toCharArray())
                
                sslContextBuilder.keyManager(kmf)
            }
            
            // Set protocols (TLS 1.3, TLS 1.2)
            if (tlsConfig.protocols) {
                sslContextBuilder.protocols(tlsConfig.protocols as String[])
            }
            
            return sslContextBuilder.build()
            
        } catch (Exception e) {
            log.error("Failed to create client SSL context", e)
            throw new RuntimeException("TLS client configuration error", e)
        }
    }
    
    /**
     * Parses actor URI into components.
     * Format: rsocket://host:port/system/actorName
     */
    private static ActorUri parseUri(String uri) {
        def u = new URI(uri)
        
        if (u.scheme != 'rsocket') {
            throw new IllegalArgumentException("Invalid scheme: ${u.scheme} (expected 'rsocket')")
        }
        
        def parts = (u.path ?: '/').split('/')
        def system = parts.length > 1 ? parts[1] : ''
        def actor = parts.length > 2 ? parts[2] : ''
        
        if (!actor) {
            throw new IllegalArgumentException("Actor name missing in URI: ${uri}")
        }
        
        return new ActorUri(
            u.host ?: 'localhost',
            u.port > 0 ? u.port : 7000,
            system,
            actor
        )
    }
    
    /**
     * Serializes object to JSON bytes.
     */
    private static String serialize(Object obj) {
        return groovy.json.JsonOutput.toJson(obj)
    }
    
    /**
     * Deserializes JSON bytes to object.
     */
    private static Map deserialize(Payload payload) {
        def json = payload.dataUtf8
        return new groovy.json.JsonSlurper().parseText(json) as Map
    }
    
    // ═════════════════════════════════════════════════════════════
    // Authentication Methods
    // ═════════════════════════════════════════════════════════════
    
    /**
     * Sets authentication token for client requests.
     * 
     * @param token JWT token
     */
    void setAuthToken(String token) {
        if (authConfig.enabled && token && jwtService) {
            // Validate token format
            if (jwtService.isExpired(token)) {
                log.warn("Setting expired authentication token")
            }
        }
        this.clientAuthToken = token
        log.debug("Auth token set for client")
    }
    
    /**
     * Gets current authentication token.
     * 
     * @return current token or null
     */
    String getAuthToken() {
        return clientAuthToken
    }
    
    /**
     * Clears authentication token.
     */
    void clearAuthToken() {
        this.clientAuthToken = null
        log.debug("Auth token cleared")
    }
    
    @Override
    void close() {
        log.info("Closing RSocket transport")
        
        // Close all connections
        connections.values().each { rsocket ->
            try {
                rsocket.dispose()
            } catch (Exception e) {
                log.warn("Error closing connection", e)
            }
        }
        connections.clear()
        
        // Server cleanup handled by RSocket library
        log.info("RSocket transport closed")
    }
    
    /**
     * Parsed actor URI.
     */
    private static class ActorUri {
        final String host
        final int port
        final String system
        final String actor
        
        ActorUri(String host, int port, String system, String actor) {
            this.host = host
            this.port = port
            this.system = system
            this.actor = actor
        }
    }
}
