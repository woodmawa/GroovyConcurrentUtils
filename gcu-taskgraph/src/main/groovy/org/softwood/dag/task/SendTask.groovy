package org.softwood.dag.task

import groovy.util.logging.Slf4j
import org.softwood.promise.Promise

import java.time.Duration

/**
 * SendTask - Send Messages/Events to External Systems
 *
 * @deprecated Use {@link HttpTask} for HTTP/REST API calls instead.
 * SendTask only implements HTTP operations and is redundant with HttpTask which provides
 * superior functionality. Will be removed in version 2.0.0.
 * 
 * <p><strong>Migration Guide:</strong></p>
 * <pre>
 * // Before (SendTask)
 * task("notify", TaskType.SEND) {
 *     url "https://api.example.com/notify"
 *     method "POST"
 *     payload { prev -> prev }
 * }
 * 
 * // After (HttpTask)
 * httpTask("notify") {
 *     url "https://api.example.com/notify"
 *     method POST
 *     body { prev -> prev }
 * }
 * </pre>
 * 
 * <p><strong>For other protocols:</strong></p>
 * <ul>
 *   <li>Email: Use {@link MailTask}</li>
 *   <li>Messaging (Kafka, AMQP, EventBus): Use {@link MessagingTask}</li>
 *   <li>SMS/RCS: Coming soon - SmsTask</li>
 * </ul>
 *
 * Sends messages to external systems via various protocols (HTTP, messaging, etc.).
 * Does not wait for responses - use ReceiveTask to handle async responses.
 *
 * <h3>Key Features:</h3>
 * <ul>
 *   <li>HTTP/REST API calls</li>
 *   <li>Message queue publishing (extensible)</li>
 *   <li>Webhook emissions</li>
 *   <li>Email/SMS sending (extensible)</li>
 *   <li>Fire-and-forget or wait-for-ack modes</li>
 * </h3>
 *
 * <h3>DSL Example - HTTP:</h3>
 * <pre>
 * task("notify-customer", TaskType.SEND) {
 *     protocol "http"
 *     method "POST"
 *     url "https://api.example.com/notifications"
 *     
 *     headers {
 *         "Content-Type" "application/json"
 *         "Authorization" "Bearer \${ctx.token}"
 *     }
 *     
 *     payload { ctx, prev ->
 *         [
 *             customerId: prev.customerId,
 *             message: "Order confirmed",
 *             timestamp: System.currentTimeMillis()
 *         ]
 *     }
 *     
 *     onSuccess { response ->
 *         println "Sent: \${response.statusCode}"
 *     }
 * }
 * </pre>
 *
 * <h3>DSL Example - Simple:</h3>
 * <pre>
 * task("send-webhook", TaskType.SEND) {
 *     url "https://webhook.site/your-id"
 *     payload { ctx, prev -> prev }
 * }
 * </pre>
 */
@Deprecated
@Slf4j
class SendTask extends TaskBase<Map> {

    // =========================================================================
    // Configuration
    // =========================================================================
    
    /** Protocol (http, kafka, rabbitmq, email, etc.) */
    String protocol = "http"
    
    /** Target URL/endpoint */
    String url
    
    /** HTTP method (GET, POST, PUT, DELETE, etc.) */
    String method = "POST"
    
    /** Request headers */
    Map<String, String> headers = [:]
    
    /** Payload provider closure */
    Closure payloadProvider
    
    /** Success callback */
    Closure onSuccessHandler
    
    /** Failure callback */
    Closure onFailureHandler
    
    /** Send timeout */
    Duration sendTimeout = Duration.ofSeconds(30)

    SendTask(String id, String name, TaskContext ctx) {
        super(id, name, ctx)
    }

    // =========================================================================
    // DSL Methods
    // =========================================================================
    
    void protocol(String value) {
        this.protocol = value?.toLowerCase()
    }
    
    void url(String value) {
        this.url = value
    }
    
    void method(String value) {
        this.method = value?.toUpperCase()
    }
    
    void headers(@DelegatesTo(Map) Closure config) {
        config.delegate = this.headers
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.call()
    }
    
    void payload(Closure provider) {
        this.payloadProvider = provider
    }
    
    void onSuccess(Closure handler) {
        this.onSuccessHandler = handler
    }
    
    void onFailure(Closure handler) {
        this.onFailureHandler = handler
    }
    
    void sendTimeout(Duration timeout) {
        this.sendTimeout = timeout
    }

    // =========================================================================
    // Task Execution
    // =========================================================================

    @Override
    protected Promise<Map> runTask(TaskContext ctx, Object prevValue) {
        
        if (!url) {
            throw new IllegalStateException("SendTask($id): url is required")
        }
        
        log.debug("SendTask($id): sending via $protocol to $url")
        
        return ctx.promiseFactory.executeAsync {
            
            // Prepare payload
            def payload = payloadProvider ? payloadProvider.call(ctx, prevValue) : prevValue
            
            // Send based on protocol
            Map result
            switch (protocol) {
                case "http":
                case "https":
                    result = sendHttp(payload)
                    break
                default:
                    throw new IllegalStateException("SendTask($id): unsupported protocol: $protocol")
            }
            
            // Call success handler if provided
            if (result.success && onSuccessHandler) {
                try {
                    onSuccessHandler.call(result)
                } catch (Exception e) {
                    log.warn("SendTask($id): error in success handler", e)
                }
            }
            
            // Call failure handler if provided
            if (!result.success && onFailureHandler) {
                try {
                    onFailureHandler.call(result)
                } catch (Exception e) {
                    log.warn("SendTask($id): error in failure handler", e)
                }
            }
            
            return result
        }
    }

    // =========================================================================
    // Protocol Implementations
    // =========================================================================
    
    private Map sendHttp(Object payload) {
        
        log.debug("SendTask($id): sending HTTP $method to $url")
        
        try {
            def connection = new URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.connectTimeout = sendTimeout.toMillis() as int
            connection.readTimeout = sendTimeout.toMillis() as int
            connection.doOutput = (method in ["POST", "PUT", "PATCH"])
            
            // Set headers
            headers.each { key, value ->
                connection.setRequestProperty(key, value)
            }
            
            // Set default Content-Type if not specified
            if (!headers.containsKey("Content-Type")) {
                connection.setRequestProperty("Content-Type", "application/json")
            }
            
            // Write payload if applicable
            if (connection.doOutput && payload) {
                def payloadString = payload instanceof String ? payload : groovy.json.JsonOutput.toJson(payload)
                connection.outputStream.withWriter { writer ->
                    writer.write(payloadString)
                }
            }
            
            // Get response
            def statusCode = connection.responseCode
            def success = statusCode >= 200 && statusCode < 300
            
            def responseBody = null
            try {
                def stream = success ? connection.inputStream : connection.errorStream
                if (stream) {
                    responseBody = stream.text
                }
            } catch (Exception e) {
                log.debug("SendTask($id): could not read response body", e)
            }
            
            def result = [
                success: success,
                statusCode: statusCode,
                statusMessage: connection.responseMessage,
                responseBody: responseBody,
                headers: connection.headerFields,
                sentAt: System.currentTimeMillis()
            ]
            
            log.info("SendTask($id): HTTP $method completed with status $statusCode")
            
            return result
            
        } catch (Exception e) {
            log.error("SendTask($id): HTTP send failed", e)
            return [
                success: false,
                error: e.message,
                exception: e.class.name,
                sentAt: System.currentTimeMillis()
            ]
        }
    }
}
