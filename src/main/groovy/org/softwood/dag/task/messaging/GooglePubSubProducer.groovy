package org.softwood.dag.task.messaging

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

/**
 * Google Cloud Pub/Sub message producer.
 *
 * <p><strong>ZERO COMPILE-TIME DEPENDENCIES:</strong> Uses reflection to avoid
 * compile-time dependency on Google Cloud client libraries. Add com.google.cloud:google-cloud-pubsub:1.120+
 * at runtime for full functionality. Runs in stub mode for testing if library not available.</p>
 *
 * <h3>Features:</h3>
 * <ul>
 *   <li>Topic-based publish/subscribe</li>
 *   <li>Message ordering (via ordering key)</li>
 *   <li>At-least-once delivery</li>
 *   <li>Message attributes (headers)</li>
 *   <li>Batch publishing</li>
 * </ul>
 *
 * <h3>Usage:</h3>
 * <pre>
 * // Basic
 * def producer = new GooglePubSubProducer(
 *     projectId: "my-gcp-project",
 *     topicName: "orders"
 * )
 * producer.initialize()
 * producer.send(destination: "orders", message: [orderId: 123])
 *
 * // With ordering key (ensures message order)
 * def producer = new GooglePubSubProducer(
 *     projectId: "my-gcp-project",
 *     topicName: "orders",
 *     enableOrdering: true
 * )
 * producer.initialize()
 * producer.send(
 *     destination: "orders",
 *     key: "customer-123",  // Ordering key
 *     message: [orderId: 456]
 * )
 *
 * // With credentials
 * def producer = new GooglePubSubProducer(
 *     projectId: "my-gcp-project",
 *     topicName: "orders",
 *     credentialsPath: "/path/to/service-account.json"
 * )
 * </pre>
 *
 * @since 2.1.0
 */
@Slf4j
@CompileStatic
class GooglePubSubProducer implements IMessageProducer {

    // =========================================================================
    // Configuration
    // =========================================================================

    String projectId
    String topicName
    String credentialsPath  // Optional - uses default credentials if not provided
    boolean enableOrdering = false
    int batchSize = 100
    long batchingDelayMs = 10L

    // =========================================================================
    // Runtime - Using Object types to avoid compile-time dependency
    // =========================================================================

    private Object publisher  // Publisher
    private volatile boolean connected = false
    private boolean gcpAvailable = false

    // =========================================================================
    // Initialization
    // =========================================================================

    void initialize() {
        if (connected) {
            log.warn("GooglePubSubProducer already connected")
            return
        }

        if (!projectId) {
            throw new IllegalStateException("Google Cloud projectId not configured")
        }

        if (!topicName) {
            throw new IllegalStateException("Google Cloud topicName not configured")
        }

        try {
            // Check if Google Cloud Pub/Sub is available
            Class.forName("com.google.cloud.pubsub.v1.Publisher")
            gcpAvailable = true

            // Build topic name using reflection
            def topicNameClass = Class.forName("com.google.pubsub.v1.TopicName")
            def ofMethod = topicNameClass.getMethod("of", String, String)
            def topicNameObj = ofMethod.invoke(null, projectId, topicName)

            // Build Publisher
            def publisherClass = Class.forName("com.google.cloud.pubsub.v1.Publisher")
            def builderMethod = publisherClass.getMethod("newBuilder",
                    Class.forName("com.google.pubsub.v1.TopicName"))
            def builder = builderMethod.invoke(null, topicNameObj)

            // Set credentials if provided
            if (credentialsPath) {
                def credentialsClass = Class.forName("com.google.auth.oauth2.ServiceAccountCredentials")
                def fileInputStream = new FileInputStream(credentialsPath)
                def fromStreamMethod = credentialsClass.getMethod("fromStream", java.io.InputStream)
                def credentials = fromStreamMethod.invoke(null, fileInputStream)
                fileInputStream.close()

                def credentialsProviderClass = Class.forName("com.google.api.gax.core.FixedCredentialsProvider")
                def createMethod = credentialsProviderClass.getMethod("create",
                        Class.forName("com.google.auth.Credentials"))
                def credentialsProvider = createMethod.invoke(null, credentials)

                def setCredentialsProviderMethod = builder.getClass().getMethod("setCredentialsProvider",
                        Class.forName("com.google.api.gax.core.CredentialsProvider"))
                builder = setCredentialsProviderMethod.invoke(builder, credentialsProvider)
            }

            // Configure batching
            def batchingSettingsClass = Class.forName("com.google.api.gax.batching.BatchingSettings")
            def batchingBuilder = batchingSettingsClass.getMethod("newBuilder").invoke(null)

            // Set batch size
            def setElementCountThresholdMethod = batchingBuilder.getClass().getMethod("setElementCountThreshold", Long)
            batchingBuilder = setElementCountThresholdMethod.invoke(batchingBuilder, (long)batchSize)

            // Set batching delay
            def durationClass = Class.forName("org.threeten.bp.Duration")
            def ofMillisMethod = durationClass.getMethod("ofMillis", long)
            def delayDuration = ofMillisMethod.invoke(null, batchingDelayMs)

            def setDelayThresholdMethod = batchingBuilder.getClass().getMethod("setDelayThreshold", durationClass)
            batchingBuilder = setDelayThresholdMethod.invoke(batchingBuilder, delayDuration)

            def batchingSettings = batchingBuilder.getClass().getMethod("build").invoke(batchingBuilder)

            def setBatchingSettingsMethod = builder.getClass().getMethod("setBatchingSettings", batchingSettingsClass)
            builder = setBatchingSettingsMethod.invoke(builder, batchingSettings)

            // Enable ordering if configured
            if (enableOrdering) {
                def setEnableMessageOrderingMethod = builder.getClass().getMethod("setEnableMessageOrdering", boolean)
                builder = setEnableMessageOrderingMethod.invoke(builder, true)
            }

            // Build publisher
            def buildMethod = builder.getClass().getMethod("build")
            publisher = buildMethod.invoke(builder)

            connected = true
            log.info("GooglePubSubProducer connected: project={}, topic={}", projectId, topicName)

        } catch (ClassNotFoundException e) {
            log.warn("Google Cloud Pub/Sub client not found. Producer will run in stub mode. " +
                    "Add com.google.cloud:google-cloud-pubsub:1.120+ to classpath for full functionality.")
            gcpAvailable = false
            connected = true  // Allow stub mode
        } catch (Exception e) {
            log.error("Failed to initialize GooglePubSubProducer", e)
            throw new RuntimeException("Google Cloud Pub/Sub initialization failed: ${e.message}", e)
        }
    }

    // =========================================================================
    // IMessageProducer Implementation
    // =========================================================================

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

        if (!gcpAvailable) {
            return stubSend(destination, key, message, headers)
        }

        try {
            // Serialize message
            def messageStr = serializeMessage(message)

            // Create PubsubMessage using reflection
            def pubsubMessageClass = Class.forName("com.google.pubsub.v1.PubsubMessage")
            def builderMethod = pubsubMessageClass.getMethod("newBuilder")
            def builder = builderMethod.invoke(null)

            // Set message data
            def byteStringClass = Class.forName("com.google.protobuf.ByteString")
            def copyFromUtf8Method = byteStringClass.getMethod("copyFromUtf8", String)
            def data = copyFromUtf8Method.invoke(null, messageStr)

            def setDataMethod = builder.getClass().getMethod("setData", byteStringClass)
            builder = setDataMethod.invoke(builder, data)

            // Set ordering key if provided and enabled
            if (enableOrdering && key) {
                def setOrderingKeyMethod = builder.getClass().getMethod("setOrderingKey", String)
                builder = setOrderingKeyMethod.invoke(builder, key)
            }

            // Set attributes (headers)
            if (headers) {
                def putAllAttributesMethod = builder.getClass().getMethod("putAllAttributes", Map)
                builder = putAllAttributesMethod.invoke(builder, headers)
            }

            def pubsubMessage = builder.getClass().getMethod("build").invoke(builder)

            // Publish message
            def publishMethod = publisher.getClass().getMethod("publish", pubsubMessageClass)
            def apiFuture = publishMethod.invoke(publisher, pubsubMessage)

            // Get message ID (blocking)
            def getMethod = apiFuture.getClass().getMethod("get")
            def messageId = getMethod.invoke(apiFuture)

            log.debug("GooglePubSubProducer: published to topic='{}', messageId={}", topicName, messageId)

            return [
                    success: true,
                    messageId: messageId,
                    topic: topicName,
                    orderingKey: key,
                    timestamp: System.currentTimeMillis()
            ]

        } catch (Exception e) {
            log.error("Error publishing to Google Cloud Pub/Sub", e)
            return [
                    success: false,
                    error: e.message,
                    timestamp: System.currentTimeMillis()
            ] as Map<String, Object>
        }
    }

    @Override
    void flush() {
        // Pub/Sub publisher handles batching automatically
        log.trace("GooglePubSubProducer: flush() called (no-op)")
    }

    @Override
    void close() {
        if (publisher && gcpAvailable) {
            try {
                // Shutdown publisher
                def shutdownMethod = publisher.getClass().getMethod("shutdown")
                shutdownMethod.invoke(publisher)
                log.info("GooglePubSubProducer: closed")
            } catch (Exception e) {
                log.error("Error closing Google Cloud Pub/Sub publisher", e)
            }
        }
        connected = false
    }

    @Override
    String getProducerType() {
        return "GCP-PubSub"
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
        return groovy.json.JsonOutput.toJson(message)
    }

    // =========================================================================
    // Stub Mode
    // =========================================================================

    private Map<String, Object> stubSend(String destination, String key, Object message, Map<String, String> headers) {
        log.warn("GooglePubSubProducer running in stub mode: send(topic='{}', orderingKey='{}', message={})",
                topicName, key, message)
        return [
                success: true,
                stub: true,
                messageId: UUID.randomUUID().toString(),
                topic: topicName,
                orderingKey: key,
                timestamp: System.currentTimeMillis()
        ] as Map<String, Object>
    }
}