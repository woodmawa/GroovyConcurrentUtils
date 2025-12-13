package org.softwood.actor.remote.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.softwood.actor.ActorSystem
import org.softwood.actor.remote.RemotingTransport

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
    
    /**
     * Creates HTTP transport with default configuration.
     * 
     * @param localSystem actor system for local delivery
     */
    HttpTransport(ActorSystem localSystem) {
        this(localSystem, 8080, true)
    }
    
    /**
     * Creates HTTP transport with custom port.
     * 
     * @param localSystem actor system for local delivery
     * @param localPort port to listen on (if server enabled)
     * @param serverEnabled whether to start server
     */
    HttpTransport(ActorSystem localSystem, int localPort, boolean serverEnabled) {
        this.localSystem = localSystem
        this.localPort = localPort
        this.serverEnabled = serverEnabled
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
            server = HttpServer.create(new InetSocketAddress(localPort), 0)
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
            log.info("HTTP server listening on port ${localPort}")
            
        } catch (Exception e) {
            log.error("Failed to start HTTP server on port ${localPort}", e)
            throw e
        }
    }
    
    /**
     * Handles tell (fire-and-forget) request.
     */
    private void handleTellRequest(HttpExchange exchange, String actorName, Map message) {
        try {
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
            def url = new URL("http://${target.host}:${target.port}/actor/${target.system}/${target.actor}/tell")
            
            def connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            
            def payload = toJson([payload: message])
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
                def url = new URL("http://${target.host}:${target.port}/actor/${target.system}/${target.actor}/ask")
                
                def connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = (int) timeout.toMillis()
                connection.readTimeout = (int) timeout.toMillis()
                
                def payload = toJson([
                    payload: message,
                    timeout: timeout.toMillis()
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
     * Format: http://host:port/system/actorName
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
            u.host ?: 'localhost',
            u.port > 0 ? u.port : 8080,
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
