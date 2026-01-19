package org.softwood.dag.task.messaging

import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

/**
 * Azure Service Bus message producer.
 * 
 * <p><strong>ZERO COMPILE-TIME DEPENDENCIES:</strong> Uses reflection to avoid
 * compile-time dependency on Azure SDK. Add com.azure:azure-messaging-servicebus:7.13+
 * at runtime for full functionality. Runs in stub mode for testing if library not available.</p>
 * 
 * <h3>Features:</h3>
 * <ul>
 *   <li>Queues and topics</li>
 *   <li>Sessions</li>
 *   <li>Scheduled messages</li>
 *   <li>Dead letter queues</li>
 *   <li>AMQP 1.0 protocol</li>
 * </ul>
 * 
 * <h3>Usage:</h3>
 * <pre>
 * // Queue
 * def producer = new AzureServiceBusProducer(
 *     connectionString: "Endpoint=sb://myns.servicebus.windows.net/;SharedAccessKeyName=...",
 *     queueName: "orders"
 * )
 * producer.send("orders", [orderId: 123])
 * 
 * // Topic
 * def producer = new AzureServiceBusProducer(
 *     connectionString: "Endpoint=sb://...",
 *     topicName: "notifications"
 * )
 * producer.send("notifications", message)
 * 
 * // With session ID
 * producer.send("orders", "session-123", message)  // Key becomes sessionId
 * </pre>
 * 
 * @since 2.1.0
 */
@Slf4j
@CompileStatic
class AzureServiceBusProducer implements IMessageProducer {

    String connectionString
    String queueName
    String topicName

    private Object senderClient
    private volatile boolean connected = false
    private boolean azureAvailable = false

    void initialize() {
        if (connected) {
            log.warn("AzureServiceBusProducer already connected")
            return
        }

        if (!connectionString) {
            throw new IllegalStateException("Azure Service Bus connectionString not configured")
        }

        if (!queueName && !topicName) {
            throw new IllegalStateException("Either queueName or topicName must be configured")
        }

        try {
            Class.forName("com.azure.messaging.servicebus.ServiceBusClientBuilder")
            azureAvailable = true

            def builderClass = Class.forName("com.azure.messaging.servicebus.ServiceBusClientBuilder")
            def builder = builderClass.getDeclaredConstructor().newInstance()

            def connectionStringMethod = builder.getClass().getMethod("connectionString", String)
            builder = connectionStringMethod.invoke(builder, connectionString)

            def senderMethod = builder.getClass().getMethod("sender")
            def senderBuilder = senderMethod.invoke(builder)

            if (queueName) {
                def queueNameMethod = senderBuilder.getClass().getMethod("queueName", String)
                senderBuilder = queueNameMethod.invoke(senderBuilder, queueName)
            } else {
                def topicNameMethod = senderBuilder.getClass().getMethod("topicName", String)
                senderBuilder = topicNameMethod.invoke(senderBuilder, topicName)
            }

            def buildClientMethod = senderBuilder.getClass().getMethod("buildClient")
            senderClient = buildClientMethod.invoke(senderBuilder)

            connected = true
            log.info("AzureServiceBusProducer connected: queue={}, topic={}", queueName, topicName)

        } catch (ClassNotFoundException e) {
            log.warn("Azure Service Bus SDK not found. Producer will run in stub mode.")
            azureAvailable = false
            connected = true
        } catch (Exception e) {
            log.error("Failed to initialize AzureServiceBusProducer", e)
            throw new RuntimeException("Azure Service Bus initialization failed: ${e.message}", e)
        }
    }

    @Override
    Map<String, Object> send(Map<String, Object> params) {
        if (!connected) {
            throw new IllegalStateException("Producer not connected. Call initialize() first.")
        }

        // Extract and validate parameters
        String destination = params.destination ?: params.topic
        if (!destination) {
            throw new IllegalArgumentException("Either 'destination' or 'topic' parameter is required")
        }

        Object message = params.message
        if (message == null) {
            throw new IllegalArgumentException("'message' parameter is required")
        }

        String key = params.key
        Map<String, String> headers = (params.headers ?: [:]) as Map<String, String>

        if (!azureAvailable) {
            return stubSend(destination, key, message, headers)
        }

        try {
            // Serialize message
            def messageStr = serializeMessage(message)

            // Create ServiceBusMessage using reflection
            def messageClass = Class.forName("com.azure.messaging.servicebus.ServiceBusMessage")
            def sbMessage = messageClass.getDeclaredConstructor(String).newInstance(messageStr)

            // Set session ID if key provided
            if (key) {
                sbMessage.getClass().getMethod("setSessionId", String).invoke(sbMessage, key)
            }

            // Set application properties (headers)
            if (headers) {
                def appPropsMethod = sbMessage.getClass().getMethod("getApplicationProperties")
                def appProps = appPropsMethod.invoke(sbMessage) as Map<String, Object>

                headers.each { k, v ->
                    appProps.put(k, v)
                }
            }

            // Send message
            def sendMethod = senderClient.getClass().getMethod("sendMessage", messageClass)
            sendMethod.invoke(senderClient, sbMessage)

            // Get message ID
            def messageIdMethod = sbMessage.getClass().getMethod("getMessageId")
            def messageId = messageIdMethod.invoke(sbMessage)

            log.debug("AzureServiceBusProducer: sent to {} (messageId={})",
                    queueName ?: topicName, messageId)

            return [
                    success: true,
                    messageId: messageId ?: UUID.randomUUID().toString(),
                    destination: queueName ?: topicName,
                    sessionId: key,
                    timestamp: System.currentTimeMillis()
            ]as Map<String,Object>

        } catch (Exception e) {
            log.error("Error sending to Azure Service Bus", e)
            return [
                    success: false,
                    error: e.message,
                    timestamp: System.currentTimeMillis()
            ] as Map<String,Object>
        }
    }

    @Override
    void flush() {
        log.trace("AzureServiceBusProducer: flush() called (no-op)")
    }

    @Override
    void close() {
        if (senderClient && azureAvailable) {
            try {
                senderClient.getClass().getMethod("close").invoke(senderClient)
                log.info("AzureServiceBusProducer: closed")
            } catch (Exception e) {
                log.error("Error closing Azure Service Bus sender", e)
            }
        }
        connected = false
    }

    @Override
    String getProducerType() {
        return "Azure-ServiceBus"
    }

    @Override
    boolean isConnected() {
        return connected
    }

    private String serializeMessage(Object message) {
        if (message instanceof String) {
            return message as String
        }
        return JsonOutput.toJson(message)
    }

    // *** FIX IS HERE - LINE 179-189 ***
    private Map<String, Object> stubSend(String destination, String key, Object message, Map<String, String> headers) {
        log.warn("AzureServiceBusProducer running in stub mode: send(destination='{}', sessionId='{}', message={})",
                queueName ?: topicName, key, message)
        return [
                success: true,
                stub: true,
                messageId: UUID.randomUUID().toString(),
                destination: queueName ?: topicName,
                sessionId: key,  // THE FIX: This should now return "session-123" when key is provided
                timestamp: System.currentTimeMillis()
        ] as Map<String, Object>
    }
}