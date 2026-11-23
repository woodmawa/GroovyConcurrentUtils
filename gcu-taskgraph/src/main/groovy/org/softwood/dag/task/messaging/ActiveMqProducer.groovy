package org.softwood.dag.task.messaging

import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

/**
 * Apache ActiveMQ Artemis message producer.
 * 
 * <p><strong>ZERO COMPILE-TIME DEPENDENCIES:</strong> Uses reflection to avoid
 * compile-time dependency on ActiveMQ Artemis client. Add org.apache.activemq:artemis-jms-client:2.28+
 * at runtime for full functionality. Runs in stub mode for testing if library not available.</p>
 * 
 * <p><strong>Note:</strong> Apache Apollo is deprecated. This implements ActiveMQ Artemis,
 * which is the modern successor and actively maintained.</p>
 * 
 * <h3>Features:</h3>
 * <ul>
 *   <li>JMS 2.0 support</li>
 *   <li>Queues and topics</li>
 *   <li>Persistent and non-persistent messages</li>
 *   <li>Message priority</li>
 *   <li>Transactions</li>
 * </ul>
 * 
 * <h3>Usage:</h3>
 * <pre>
 * // Basic queue
 * def producer = new ActiveMqProducer(
 *     brokerUrl: "tcp://localhost:61616",
 *     queueName: "orders"
 * )
 * producer.send("orders", [orderId: 123])
 * 
 * // Topic (pub/sub)
 * def producer = new ActiveMqProducer(
 *     brokerUrl: "tcp://activemq.example.com:61616",
 *     topicName: "notifications",
 *     username: "user",
 *     password: "secret"
 * )
 * producer.send("notifications", message)
 * </pre>
 * 
 * @since 2.1.0
 */
@Slf4j
@CompileStatic
class ActiveMqProducer implements IMessageProducer {
    
    // =========================================================================
    // Configuration
    // =========================================================================
    
    String brokerUrl = "tcp://localhost:61616"
    String username = "admin"
    String password = "admin"
    String queueName   // If set, sends to queue
    String topicName   // If set, sends to topic
    boolean persistent = true
    int priority = 4  // 0-9, default 4
    
    // =========================================================================
    // Runtime - Using Object types to avoid compile-time dependency
    // =========================================================================
    
    private Object connectionFactory  // ActiveMQConnectionFactory
    private Object connection          // Connection
    private Object session             // Session
    private Object producer            // MessageProducer
    private volatile boolean connected = false
    private boolean activeMqAvailable = false
    
    // =========================================================================
    // Initialization
    // =========================================================================
    
    void initialize() {
        if (connected) {
            log.warn("ActiveMqProducer already connected")
            return
        }
        
        if (!queueName && !topicName) {
            throw new IllegalStateException("Either queueName or topicName must be configured")
        }
        
        try {
            // Check if ActiveMQ Artemis client is available
            Class.forName("org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory")
            activeMqAvailable = true
            
            // Use reflection to create connection
            def factoryClass = Class.forName("org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory")
            connectionFactory = factoryClass.getDeclaredConstructor(String, String, String)
                .newInstance(brokerUrl, username, password)
            
            // Create connection
            connection = connectionFactory.getClass().getMethod("createConnection").invoke(connectionFactory)
            
            // Start connection
            connection.getClass().getMethod("start").invoke(connection)
            
            // Create session (non-transacted, auto-acknowledge)
            def sessionConstantsClass = Class.forName("javax.jms.Session")
            def autoAckField = sessionConstantsClass.getField("AUTO_ACKNOWLEDGE")
            def autoAck = autoAckField.get(null)
            
            session = connection.getClass().getMethod("createSession", boolean, int)
                .invoke(connection, false, autoAck)
            
            // Create destination (queue or topic)
            def destination
            if (queueName) {
                destination = session.getClass().getMethod("createQueue", String)
                    .invoke(session, queueName)
            } else {
                destination = session.getClass().getMethod("createTopic", String)
                    .invoke(session, topicName)
            }
            
            // Create producer
            producer = session.getClass().getMethod("createProducer", 
                Class.forName("javax.jms.Destination"))
                .invoke(session, destination)
            
            // Set delivery mode
            def deliveryModeClass = Class.forName("javax.jms.DeliveryMode")
            def deliveryMode = persistent 
                ? deliveryModeClass.getField("PERSISTENT").get(null)
                : deliveryModeClass.getField("NON_PERSISTENT").get(null)
            producer.getClass().getMethod("setDeliveryMode", int).invoke(producer, deliveryMode)
            
            // Set priority
            producer.getClass().getMethod("setPriority", int).invoke(producer, priority)
            
            connected = true
            log.info("ActiveMqProducer connected: {} (queue={}, topic={})", 
                brokerUrl, queueName, topicName)
            
        } catch (ClassNotFoundException e) {
            log.warn("ActiveMQ Artemis client not found. Producer will run in stub mode. " +
                    "Add org.apache.activemq:artemis-jms-client:2.28+ to classpath for full functionality.")
            activeMqAvailable = false
            connected = true  // Allow stub mode
        } catch (Exception e) {
            log.error("Failed to initialize ActiveMqProducer", e)
            throw new RuntimeException("ActiveMQ initialization failed: ${e.message}", e)
        }
    }
    

// ============================================================================
// ActiveMqProducer.groovy - REPLACEMENT FOR send() METHODS (around lines ~151-220)
// DELETE the 4 @CompileDynamic send() methods and REPLACE with this ONE method:
// ============================================================================

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

        if (!activeMqAvailable) {
            return stubSend(destination, key, message, headers)
        }

        try {
            // Serialize message
            def messageStr = serializeMessage(message)

            // Create JMS message using reflection
            def textMessageClass = Class.forName("javax.jms.TextMessage")
            def createTextMessageMethod = session.getClass().getMethod("createTextMessage", String)
            def jmsMessage = createTextMessageMethod.invoke(session, messageStr)

            // Set JMS properties from headers
            if (headers) {
                headers.each { k, v ->
                    def setStringPropertyMethod = jmsMessage.getClass().getMethod("setStringProperty", String, String)
                    setStringPropertyMethod.invoke(jmsMessage, k, v)
                }
            }

            // Send message
            def sendMethod = producer.getClass().getMethod("send", Class.forName("javax.jms.Message"))
            sendMethod.invoke(producer, jmsMessage)

            // Get message ID
            def getJMSMessageIDMethod = jmsMessage.getClass().getMethod("getJMSMessageID")
            def messageId = getJMSMessageIDMethod.invoke(jmsMessage)

            log.debug("ActiveMqProducer: sent to {} (messageId={})", queueName ?: topicName, messageId)

            return [
                    success: true,
                    messageId: messageId ?: UUID.randomUUID().toString(),
                    destination: queueName ?: topicName,
                    timestamp: System.currentTimeMillis()
            ]as Map<String, Object>

        } catch (Exception e) {
            log.error("Error sending to ActiveMQ", e)
            return [
                    success: false,
                    error: e.message,
                    timestamp: System.currentTimeMillis()
            ] as Map<String, Object>
        }
    }
    
    @Override
    void flush() {
        // ActiveMQ sends immediately
        log.trace("ActiveMqProducer: flush() called (no-op)")
    }
    
    @Override
    void close() {
        if (producer && activeMqAvailable) {
            try {
                producer.getClass().getMethod("close").invoke(producer)
                log.debug("ActiveMqProducer: producer closed")
            } catch (Exception e) {
                log.error("Error closing producer", e)
            }
        }
        
        if (session && activeMqAvailable) {
            try {
                session.getClass().getMethod("close").invoke(session)
                log.debug("ActiveMqProducer: session closed")
            } catch (Exception e) {
                log.error("Error closing session", e)
            }
        }
        
        if (connection && activeMqAvailable) {
            try {
                connection.getClass().getMethod("close").invoke(connection)
                log.info("ActiveMqProducer: connection closed")
            } catch (Exception e) {
                log.error("Error closing connection", e)
            }
        }
        
        connected = false
    }
    
    @Override
    String getProducerType() {
        return "ActiveMQ-Artemis"
    }
    
    @Override
    boolean isConnected() {
        return connected
    }
    
    // =========================================================================
    // Helper Methods
    // =========================================================================
    
    private String serializeMessage(Object message) {
        if (message instanceof String) {
            return message as String
        }
        return JsonOutput.toJson(message)
    }
    
    // =========================================================================
    // Stub Mode
    // =========================================================================
    
    private Map<String, Object> stubSend(String destination, String key, Object message, Map<String, String> headers) {
        log.warn("ActiveMqProducer running in stub mode: send(destination='{}', correlationId='{}', message={})",
            queueName ?: topicName, key, message)
        return [
            success: true,
            stub: true,
            messageId: "ID:stub-${UUID.randomUUID()}",
            destination: queueName ?: topicName,
            timestamp: System.currentTimeMillis()
        ] as Map<String, Object>
    }
}
