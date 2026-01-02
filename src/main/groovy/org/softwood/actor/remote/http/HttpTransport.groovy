package org.softwood.actor.remote.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.softwood.actor.ActorSystem
import org.softwood.actor.remote.RemotingTransport
import org.softwood.security.AuthConfig
import org.softwood.security.JwtTokenService

import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import java.security.KeyStore
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/**
 * HTTP-based transport for remote actor communication.
 * 
 * <p>Provides simple REST-based transport using HTTP/JSON. This is a fallback
 * option when binary protocols are not available or when debugging is needed.</p>
 * 
 * <h2>Characteristics</h2>
 * <ul>
 *   <li>Text-based protocol (JSON)</li>
 *   <li>Easy to debug (curl, Postman, browser)</li>
 *   <li>No special dependencies</li>
 *   <li>Lower performance than RSocket</li>
 *   <li>Higher latency</li>
 *   <li>No streaming or backpressure</li>
 * </ul>
 * 
 * <h2>URI Format</h2>
 * <pre>
 * http://host:port/system/actorName
 * http://localhost:8080/mySystem/myActor
 * </pre>
 * 
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>POST /tell - Fire-and-forget message</li>
 *   <li>POST /ask - Request/response message</li>
 * </ul>
 * 
 * @since 2.0.0
 */
@Slf4j
@CompileStatic
class HttpTransport implements RemotingTransport {
    
    /** Local actor system for receiving messages */
    private final ActorSystem localSystem
    
    /** HTTP server instance */
    private HttpServer server
    
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
     * Creates HTTP transport with default configuration.
     * 
     * @param localSystem actor system for local delivery
     */
    HttpTransport(ActorSystem localSystem) {
        this(localSystem, 8080, true, null, null)
    }
    
    /**
     * Creates HTTP transport with custom port.
     * 
     * @param localSystem actor system for local delivery
     * @param localPort port to listen on (if server enabled)
     * @param serverEnabled whether to start server
     */
    HttpTransport(ActorSystem localSystem, int localPort, boolean serverEnabled) {
        this(localSystem, localPort, serverEnabled, null, null)
    }
    
    /**
     * Creates HTTP transport with TLS configuration.
     * 
     * @param localSystem actor system for local delivery
     * @param localPort port to listen on (if server enabled)
     * @param serverEnabled whether to start server
     * @param tlsConfig TLS configuration (null for plain HTTP)
     */
    HttpTransport(ActorSystem localSystem, int localPort, boolean serverEnabled, TlsConfig tlsConfig) {
        this(localSystem, localPort, serverEnabled, tlsConfig, null)
    }
    
    /**
     * Creates HTTP transport with TLS and authentication.
     * 
     * @param localSystem actor system for local delivery
     * @param localPort port to listen on (if server enabled)
     * @param serverEnabled whether to start server
     * @param tlsConfig TLS configuration (null for plain HTTP)
     * @param authConfig authentication configuration (null for no auth)
     */
    HttpTransport(ActorSystem localSystem, int localPort, boolean serverEnabled, 
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
        return 'http'
    }
    
    @Override
    void start() {
        if (serverEnabled) {
            startServer()
        }
        log.info("HTTP transport started (server=${serverEnabled}, port=${localPort})")
    }
    
    /**
     * Starts HTTP server to receive incoming actor messages.
     */
    private void startServer() {
        try {
            log.info("Starting HTTP${tlsConfig.enabled ? 'S' : ''} server on port ${localPort}")
            
            if (tlsConfig.enabled) {
                // HTTPS server
                def httpsServer = HttpsServer.create(new InetSocketAddress(localPort), 0)
                
                // Configure SSL
                def sslContext = createSSLContext()
                httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext))
                
                server = httpsServer
            } else {
                // Plain HTTP server
                server = HttpServer.create(new InetSocketAddress(localPort), 0)
            }
            
            server.setExecutor(Executors.newCachedThreadPool())
            
            // POST /actor/{systemName}/{actorName}/tell
            server.createContext("/actor/", new HttpHandler() {
                @Override
                void handle(HttpExchange exchange) throws IOException {
                    if (exchange.requestMethod != "POST") {
                        sendResponse(exchange, 405, [error: "Method not allowed"])
                        return
                    }
                    
                    def path = exchange.requestURI.path
                    def parts = path.split('/')
                    
                    if (parts.length < 5) {
                        sendResponse(exchange, 400, [error: "Invalid path format"])
                        return
                    }
                    
                    def systemName = parts[2]
                    def actorName = parts[3]
                    def operation = parts[4] // 'tell' or 'ask'
                    
                    def requestBody = exchange.requestBody.text
                    def message = parseJson(requestBody)
                    
                    if (operation == 'tell') {
                        handleTellRequest(exchange, actorName, message)
                    } else if (operation == 'ask') {
                        handleAskRequest(exchange, actorName, message)
                    } else {
                        sendResponse(exchange, 400, [error: "Unknown operation: ${operation}"])
                    }
                }
            })
            
            server.start()
            def protocol = tlsConfig.enabled ? "HTTPS" : "HTTP"
            log.info("${protocol} server listening on port ${localPort}")
            
        } catch (Exception e) {
            log.error("Failed to start HTTP server on port ${localPort}", e)
            throw e
        }
    }
    
    /**
     * Creates SSL context for HTTPS server.
     */
    private SSLContext createSSLContext() {
        try {
            // Verify keystore exists
            if (!tlsConfig.keyStorePath) {
                throw new IllegalStateException("HTTPS enabled but keyStorePath not configured")
            }
            
            def keystoreFile = new File(tlsConfig.keyStorePath)
            if (!keystoreFile.exists()) {
                throw new FileNotFoundException("Keystore not found: ${tlsConfig.keyStorePath}")
            }
            
            // Load keystore
            def keyStore = KeyStore.getInstance("JKS")
            new FileInputStream(keystoreFile).withCloseable { fis ->
                keyStore.load(fis, tlsConfig.keyStorePassword.toCharArray())
            }
            
            // Get key manager factory
            def kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm()
            )
            kmf.init(keyStore, tlsConfig.keyStorePassword.toCharArray())
            
            // Load truststore if configured
            TrustManagerFactory tmf = null
            if (tlsConfig.trustStorePath) {
                def trustStore = KeyStore.getInstance("JKS")
                new FileInputStream(tlsConfig.trustStorePath).withCloseable { fis ->
                    trustStore.load(fis, tlsConfig.trustStorePassword.toCharArray())
                }
                
                tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm()
                )
                tmf.init(trustStore)
            }
            
            // Create SSL context
            def sslContext = SSLContext.getInstance("TLS")
            sslContext.init(
                kmf.getKeyManagers(),
                tmf?.getTrustManagers(),
                new java.security.SecureRandom()
            )
            
            return sslContext
            
        } catch (Exception e) {
            log.error("Failed to create SSL context", e)
            throw new RuntimeException("HTTPS configuration error", e)
        }
    }
    
    /**
     * Configures HTTPS connection with truststore for client requests.
     */
    private void configureHttpsConnection(HttpsURLConnection httpsConn) {
        if (!tlsConfig.enabled) {
            return
        }
        
        try {
            // Create SSL context for client
            TrustManagerFactory tmf = null
            if (tlsConfig.trustStorePath) {
                def trustStore = KeyStore.getInstance("JKS")
                new FileInputStream(tlsConfig.trustStorePath).withCloseable { fis ->
                    trustStore.load(fis, tlsConfig.trustStorePassword.toCharArray())
                }
                
                tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm()
                )
                tmf.init(trustStore)
            }
            
            def sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, tmf?.getTrustManagers(), new java.security.SecureRandom())
            
            // Apply to connection
            httpsConn.setSSLSocketFactory(sslContext.getSocketFactory())
            
        } catch (Exception e) {
            log.error("Failed to configure HTTPS connection", e)
            throw new RuntimeException("HTTPS client configuration error", e)
        }
    }
    
    /**
     * Handles tell (fire-and-forget) request.
     */
    private void handleTellRequest(HttpExchange exchange, String actorName, Map message) {
        try {
            // Check authentication if enabled
            if (authConfig.enabled && authConfig.requireToken) {
                def token = extractToken(exchange, message)
                if (!token) {
                    log.warn("Rejected tell - missing authentication token")
                    sendResponse(exchange, 401, [error: 'Authentication required', code: 401])
                    return
                }
                
                try {
                    def claims = jwtService.validateToken(token)
                    log.debug("Authenticated tell from: ${claims.subject}")
                } catch (JwtTokenService.ExpiredTokenException e) {
                    log.warn("Rejected tell - token expired: ${e.message}")
                    sendResponse(exchange, 401, [error: 'Token expired', code: 401])
                    return
                } catch (JwtTokenService.InvalidTokenException e) {
                    log.warn("Rejected tell - invalid token: ${e.message}")
                    sendResponse(exchange, 401, [error: 'Invalid token', code: 401])
                    return
                }
            }
            
            if (localSystem?.hasActor(actorName)) {
                localSystem.getActor(actorName).tell(message.payload)
                sendResponse(exchange, 202, [status: 'accepted'])
                log.debug("Delivered tell to actor: ${actorName}")
            } else {
                sendResponse(exchange, 404, [error: "Actor not found: ${actorName}"])
                log.warn("Actor not found: ${actorName}")
            }
        } catch (Exception e) {
            log.error("Error handling tell", e)
            sendResponse(exchange, 500, [error: e.message ?: e.class.name])
        }
    }
    
    /**
     * Handles ask (request/response) request.
     */
    private void handleAskRequest(HttpExchange exchange, String actorName, Map message) {
        try {
            // Check authentication if enabled
            if (authConfig.enabled && authConfig.requireToken) {
                def token = extractToken(exchange, message)
                if (!token) {
                    log.warn("Rejected ask - missing authentication token")
                    sendResponse(exchange, 401, [error: 'Authentication required', code: 401])
                    return
                }
                
                try {
                    def claims = jwtService.validateToken(token)
                    log.debug("Authenticated ask from: ${claims.subject}")
                } catch (JwtTokenService.ExpiredTokenException e) {
                    log.warn("Rejected ask - token expired: ${e.message}")
                    sendResponse(exchange, 401, [error: 'Token expired', code: 401])
                    return
                } catch (JwtTokenService.InvalidTokenException e) {
                    log.warn("Rejected ask - invalid token: ${e.message}")
                    sendResponse(exchange, 401, [error: 'Invalid token', code: 401])
                    return
                }
            }
            
            if (localSystem?.hasActor(actorName)) {
                def timeoutMs = (message.timeout ?: 5000) as long
                def reply = localSystem.getActor(actorName)
                    .askSync(message.payload, Duration.ofMillis(timeoutMs))
                
                sendResponse(exchange, 200, [
                    status: 'ok',
                    reply: reply
                ])
                log.debug("Got reply from actor: ${actorName}")
            } else {
                sendResponse(exchange, 404, [error: "Actor not found: ${actorName}"])
                log.warn("Actor not found: ${actorName}")
            }
        } catch (Exception e) {
            log.error("Error handling ask", e)
            sendResponse(exchange, 500, [error: e.message ?: e.class.name])
        }
    }
    
    @Override
    void tell(String actorUri, Object message) {
        try {
            def target = parseUri(actorUri)
            def protocol = (target.scheme == 'https' || tlsConfig.enabled) ? 'https' : 'http'
            def url = new URL("${protocol}://${target.host}:${target.port}/actor/${target.system}/${target.actor}/tell")
            
            def connection = url.openConnection() as HttpURLConnection
            
            // Configure SSL for HTTPS
            if (protocol == 'https' && connection instanceof javax.net.ssl.HttpsURLConnection) {
                configureHttpsConnection(connection as javax.net.ssl.HttpsURLConnection)
            }
            
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            
            // Add auth token if present
            if (clientAuthToken) {
                connection.setRequestProperty(authConfig.tokenHeader, "${authConfig.tokenPrefix}${clientAuthToken}")
            }
            
            def payload = toJson([
                payload: message,
                token: clientAuthToken  // Also include in body for compatibility
            ])
            connection.outputStream.write(payload.bytes)
            
            def responseCode = connection.responseCode
            if (responseCode != 202) {
                log.warn("Tell to ${actorUri} returned ${responseCode}")
            } else {
                log.debug("Tell sent to ${actorUri}")
            }
            
        } catch (Exception e) {
            log.error("Failed to send tell to ${actorUri}", e)
            throw e
        }
    }
    
    @Override
    CompletableFuture<Object> ask(String actorUri, Object message, Duration timeout) {
        def future = new CompletableFuture<Object>()
        
        // Execute in background to avoid blocking
        Thread.startVirtualThread {
            try {
                def target = parseUri(actorUri)
                def protocol = (target.scheme == 'https' || tlsConfig.enabled) ? 'https' : 'http'
                def url = new URL("${protocol}://${target.host}:${target.port}/actor/${target.system}/${target.actor}/ask")
                
                def connection = url.openConnection() as HttpURLConnection
                
                // Configure SSL for HTTPS
                if (protocol == 'https' && connection instanceof javax.net.ssl.HttpsURLConnection) {
                    configureHttpsConnection(connection as javax.net.ssl.HttpsURLConnection)
                }
                
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = (int) timeout.toMillis()
                connection.readTimeout = (int) timeout.toMillis()
                
                // Add auth token if present
                if (clientAuthToken) {
                    connection.setRequestProperty(authConfig.tokenHeader, "${authConfig.tokenPrefix}${clientAuthToken}")
                }
                
                def payload = toJson([
                    payload: message,
                    timeout: timeout.toMillis(),
                    token: clientAuthToken  // Also include in body for compatibility
                ])
                connection.outputStream.write(payload.bytes)
                
                def responseCode = connection.responseCode
                if (responseCode == 200) {
                    def responseText = connection.inputStream.text
                    def response = parseJson(responseText)
                    
                    if (response.status == 'ok') {
                        future.complete(response.reply)
                    } else {
                        future.completeExceptionally(
                            new RuntimeException(response.error as String)
                        )
                    }
                } else {
                    def errorText = connection.errorStream?.text ?: "HTTP ${responseCode}"
                    future.completeExceptionally(new RuntimeException(errorText as String))
                }
                
            } catch (Exception e) {
                log.error("Failed to send ask to ${actorUri}", e)
                future.completeExceptionally(e)
            }
        }
        
        return future
    }
    
    /**
     * Parses actor URI into components.
     * Format: http://host:port/system/actorName or https://host:port/system/actorName
     */
    private static ActorUri parseUri(String uri) {
        def u = new URI(uri)
        
        if (u.scheme != 'http' && u.scheme != 'https') {
            throw new IllegalArgumentException("Invalid scheme: ${u.scheme} (expected 'http' or 'https')")
        }
        
        def parts = (u.path ?: '/').split('/')
        def system = parts.length > 1 ? parts[1] : ''
        def actor = parts.length > 2 ? parts[2] : ''
        
        if (!actor) {
            throw new IllegalArgumentException("Actor name missing in URI: ${uri}")
        }
        
        return new ActorUri(
            u.scheme,
            u.host ?: 'localhost',
            u.port > 0 ? u.port : (u.scheme == 'https' ? 8443 : 8080),
            system,
            actor
        )
    }
    
    /**
     * Sends HTTP response with JSON body.
     */
    private static void sendResponse(HttpExchange exchange, int statusCode, Map body) {
        def response = toJson(body)
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(statusCode, response.bytes.length)
        exchange.responseBody.write(response.bytes)
        exchange.responseBody.close()
    }
    
    /**
     * Converts object to JSON string.
     */
    private static String toJson(Object obj) {
        return groovy.json.JsonOutput.toJson(obj)
    }
    
    /**
     * Parses JSON string to map.
     */
    private static Map parseJson(String json) {
        return new groovy.json.JsonSlurper().parseText(json) as Map
    }
    
    // ═════════════════════════════════════════════════════════════
    // Authentication Methods
    // ═════════════════════════════════════════════════════════════
    
    /**
     * Extracts authentication token from HTTP request.
     * Checks both Authorization header and message body.
     */
    private String extractToken(HttpExchange exchange, Map message) {
        // Check Authorization header first (Bearer token)
        def authHeader = exchange.requestHeaders.getFirst(authConfig.tokenHeader)
        if (authHeader && authHeader.startsWith(authConfig.tokenPrefix)) {
            return authHeader.substring(authConfig.tokenPrefix.length())
        }
        
        // Fallback: check message body
        return message.token as String
    }
    
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
        log.info("Closing HTTP transport")
        
        if (server != null) {
            server.stop(0)
            log.info("HTTP server stopped")
        }
        
        log.info("HTTP transport closed")
    }
    
    /**
     * Parsed actor URI.
     */
    private static class ActorUri {
        final String scheme
        final String host
        final int port
        final String system
        final String actor
        
        ActorUri(String scheme, String host, int port, String system, String actor) {
            this.scheme = scheme
            this.host = host
            this.port = port
            this.system = system
            this.actor = actor
        }
    }
}
