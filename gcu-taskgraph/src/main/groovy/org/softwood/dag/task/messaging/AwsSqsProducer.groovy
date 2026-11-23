package org.softwood.dag.task.messaging

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

/**
 * AWS SQS (Simple Queue Service) message producer.
 * 
 * <p><strong>ZERO COMPILE-TIME DEPENDENCIES:</strong> Uses reflection to avoid
 * compile-time dependency on AWS SDK. Add AWS SDK v1 (com.amazonaws:aws-java-sdk-sqs:1.12+)
 * or AWS SDK v2 (software.amazon.awssdk:sqs:2.20+) at runtime. Runs in stub mode if not available.</p>
 * 
 * <h3>Features:</h3>
 * <ul>
 *   <li>Standard and FIFO queues</li>
 *   <li>Message deduplication</li>
 *   <li>Delay delivery</li>
 *   <li>Dead letter queues</li>
 *   <li>IAM authentication</li>
 * </ul>
 * 
 * <h3>Usage:</h3>
 * <pre>
 * // Standard queue
 * def producer = new AwsSqsProducer(
 *     region: "us-east-1",
 *     queueUrl: "https://sqs.us-east-1.amazonaws.com/123456789/my-queue"
 * )
 * producer.send("my-queue", [orderId: 123, status: "pending"])
 * 
 * // FIFO queue with message group
 * def producer = new AwsSqsProducer(
 *     region: "us-west-2",
 *     queueUrl: "https://sqs.us-west-2.amazonaws.com/123456789/orders.fifo",
 *     fifo: true
 * )
 * producer.send("orders.fifo", "customer-123", message)  // Key becomes messageGroupId
 * 
 * // With credentials
 * def producer = new AwsSqsProducer(
 *     region: "eu-west-1",
 *     queueUrl: "https://...",
 *     accessKeyId: "AKIA...",
 *     secretAccessKey: "secret..."
 * )
 * </pre>
 * 
 * @since 2.1.0
 */
@Slf4j
@CompileStatic
class AwsSqsProducer implements IMessageProducer {
    
    // =========================================================================
    // Configuration
    // =========================================================================
    
    String region = "us-east-1"
    String queueUrl
    String accessKeyId   // Optional - uses default credential chain if not provided
    String secretAccessKey
    boolean fifo = false
    int delaySeconds = 0  // 0-900 seconds
    
    // =========================================================================
    // Runtime - Using Object types to avoid compile-time dependency
    // =========================================================================
    
    private Object sqsClient  // AmazonSQS (v1) or SqsClient (v2)
    private volatile boolean connected = false
    private boolean awsAvailable = false
    private boolean usingV2 = false  // SDK v2 vs v1
    
    // =========================================================================
    // Initialization
    // =========================================================================
    
    void initialize() {
        if (connected) {
            log.warn("AwsSqsProducer already connected")
            return
        }
        
        if (!queueUrl) {
            throw new IllegalStateException("AWS SQS queueUrl not configured")
        }
        
        // Try AWS SDK v2 first (preferred)
        if (tryInitializeV2()) {
            return
        }
        
        // Fall back to AWS SDK v1
        if (tryInitializeV1()) {
            return
        }
        
        // Neither SDK available - stub mode
        log.warn("AWS SQS SDK not found (tried v1 and v2). Producer will run in stub mode. " +
                "Add software.amazon.awssdk:sqs:2.20+ or com.amazonaws:aws-java-sdk-sqs:1.12+ to classpath.")
        awsAvailable = false
        connected = true  // Allow stub mode
    }
    
    private boolean tryInitializeV2() {
        try {
            Class.forName("software.amazon.awssdk.services.sqs.SqsClient")
            awsAvailable = true
            usingV2 = true
            
            // Build SqsClient using reflection
            def builderClass = Class.forName("software.amazon.awssdk.services.sqs.SqsClient")
            def builderMethod = builderClass.getMethod("builder")
            def builder = builderMethod.invoke(null)
            
            // Set region
            def regionClass = Class.forName("software.amazon.awssdk.regions.Region")
            def ofMethod = regionClass.getMethod("of", String)
            def regionObj = ofMethod.invoke(null, region)
            def regionMethod = builder.getClass().getMethod("region", regionClass)
            builder = regionMethod.invoke(builder, regionObj)
            
            // Set credentials if provided
            if (accessKeyId && secretAccessKey) {
                def credsClass = Class.forName("software.amazon.awssdk.auth.credentials.AwsBasicCredentials")
                def createMethod = credsClass.getMethod("create", String, String)
                def creds = createMethod.invoke(null, accessKeyId, secretAccessKey)
                
                def providerClass = Class.forName("software.amazon.awssdk.auth.credentials.StaticCredentialsProvider")
                def providerCreateMethod = providerClass.getMethod("create", 
                    Class.forName("software.amazon.awssdk.auth.credentials.AwsCredentials"))
                def provider = providerCreateMethod.invoke(null, creds)
                
                def credsProviderMethod = builder.getClass().getMethod("credentialsProvider",
                    Class.forName("software.amazon.awssdk.auth.credentials.AwsCredentialsProvider"))
                builder = credsProviderMethod.invoke(builder, provider)
            }
            
            // Build client
            def buildMethod = builder.getClass().getMethod("build")
            sqsClient = buildMethod.invoke(builder)
            
            connected = true
            log.info("AwsSqsProducer connected (SDK v2): region={}, queueUrl={}", region, queueUrl)
            return true
            
        } catch (ClassNotFoundException e) {
            return false  // SDK v2 not available
        } catch (Exception e) {
            log.error("Failed to initialize AWS SQS SDK v2", e)
            throw new RuntimeException("AWS SQS v2 initialization failed: ${e.message}", e)
        }
    }
    
    private boolean tryInitializeV1() {
        try {
            Class.forName("com.amazonaws.services.sqs.AmazonSQSClientBuilder")
            awsAvailable = true
            usingV2 = false
            
            // Build AmazonSQS using reflection
            def builderClass = Class.forName("com.amazonaws.services.sqs.AmazonSQSClientBuilder")
            def standardMethod = builderClass.getMethod("standard")
            def builder = standardMethod.invoke(null)
            
            // Set region
            def regionMethod = builder.getClass().getMethod("withRegion", String)
            builder = regionMethod.invoke(builder, region)
            
            // Set credentials if provided
            if (accessKeyId && secretAccessKey) {
                def credsClass = Class.forName("com.amazonaws.auth.BasicAWSCredentials")
                def creds = credsClass.getDeclaredConstructor(String, String)
                    .newInstance(accessKeyId, secretAccessKey)
                
                def providerClass = Class.forName("com.amazonaws.auth.AWSStaticCredentialsProvider")
                def provider = providerClass.getDeclaredConstructor(
                    Class.forName("com.amazonaws.auth.AWSCredentials"))
                    .newInstance(creds)
                
                def credsProviderMethod = builder.getClass().getMethod("withCredentials",
                    Class.forName("com.amazonaws.auth.AWSCredentialsProvider"))
                builder = credsProviderMethod.invoke(builder, provider)
            }
            
            // Build client
            def buildMethod = builder.getClass().getMethod("build")
            sqsClient = buildMethod.invoke(builder)
            
            connected = true
            log.info("AwsSqsProducer connected (SDK v1): region={}, queueUrl={}", region, queueUrl)
            return true
            
        } catch (ClassNotFoundException e) {
            return false  // SDK v1 not available
        } catch (Exception e) {
            log.error("Failed to initialize AWS SQS SDK v1", e)
            throw new RuntimeException("AWS SQS v1 initialization failed: ${e.message}", e)
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

        if (!awsAvailable) {
            return stubSend(destination, key, message, headers)
        }

        return usingV2 ? sendV2(destination, key, message, headers) : sendV1(destination, key, message, headers)
    }
    
    private Map<String, Object> sendV2(String destination, String key, Object message, Map<String, String> headers) {
        try {
            // Serialize message
            def messageBody = serializeMessage(message)
            
            // Build SendMessageRequest
            def requestClass = Class.forName("software.amazon.awssdk.services.sqs.model.SendMessageRequest")
            def builderMethod = requestClass.getMethod("builder")
            def builder = builderMethod.invoke(null)
            
            // Set queue URL
            def queueUrlMethod = builder.getClass().getMethod("queueUrl", String)
            builder = queueUrlMethod.invoke(builder, queueUrl)
            
            // Set message body
            def messageBodyMethod = builder.getClass().getMethod("messageBody", String)
            builder = messageBodyMethod.invoke(builder, messageBody)
            
            // Set delay
            if (delaySeconds > 0) {
                def delayMethod = builder.getClass().getMethod("delaySeconds", Integer)
                builder = delayMethod.invoke(builder, delaySeconds)
            }
            
            // FIFO queue attributes
            if (fifo) {
                // Message group ID (use key or default)
                def messageGroupId = key ?: "default"
                def groupIdMethod = builder.getClass().getMethod("messageGroupId", String)
                builder = groupIdMethod.invoke(builder, messageGroupId)
                
                // Message deduplication ID (use hash of message)
                def deduplicationId = generateDeduplicationId(messageBody)
                def dedupMethod = builder.getClass().getMethod("messageDeduplicationId", String)
                builder = dedupMethod.invoke(builder, deduplicationId)
            }
            
            // Add message attributes (headers)
            if (headers) {
                def attrsMap = [:]
                def attrClass = Class.forName("software.amazon.awssdk.services.sqs.model.MessageAttributeValue")
                
                headers.each { k, v ->
                    def attrBuilder = attrClass.getMethod("builder").invoke(null)
                    attrBuilder.getClass().getMethod("dataType", String).invoke(attrBuilder, "String")
                    attrBuilder.getClass().getMethod("stringValue", String).invoke(attrBuilder, v)
                    def attr = attrBuilder.getClass().getMethod("build").invoke(attrBuilder)
                    attrsMap[k] = attr
                }
                
                def attrsMethod = builder.getClass().getMethod("messageAttributes", Map)
                builder = attrsMethod.invoke(builder, attrsMap)
            }
            
            def request = builder.getClass().getMethod("build").invoke(builder)
            
            // Send message
            def sendMethod = sqsClient.getClass().getMethod("sendMessage", requestClass)
            def result = sendMethod.invoke(sqsClient, request)
            
            // Extract result
            def messageIdMethod = result.getClass().getMethod("messageId")
            def messageId = messageIdMethod.invoke(result)
            
            log.debug("AwsSqsProducer (v2): sent to queue={}, messageId={}", queueUrl, messageId)
            
            return [
                success: true,
                messageId: messageId,
                queueUrl: queueUrl,
                timestamp: System.currentTimeMillis()
            ]as Map<String, Object>
            
        } catch (Exception e) {
            log.error("Error sending to AWS SQS (v2)", e)
            return [
                success: false,
                error: e.message,
                timestamp: System.currentTimeMillis()
            ] as Map<String, Object>
        }
    }
    
    private Map<String, Object> sendV1(String destination, String key, Object message, Map<String, String> headers) {
        try {
            // Serialize message
            def messageBody = serializeMessage(message)
            
            // Build SendMessageRequest
            def requestClass = Class.forName("com.amazonaws.services.sqs.model.SendMessageRequest")
            def request = requestClass.getDeclaredConstructor().newInstance()
            
            // Set properties
            request.getClass().getMethod("setQueueUrl", String).invoke(request, queueUrl)
            request.getClass().getMethod("setMessageBody", String).invoke(request, messageBody)
            
            if (delaySeconds > 0) {
                request.getClass().getMethod("setDelaySeconds", Integer).invoke(request, delaySeconds)
            }
            
            // FIFO queue attributes
            if (fifo) {
                def messageGroupId = key ?: "default"
                request.getClass().getMethod("setMessageGroupId", String).invoke(request, messageGroupId)
                
                def deduplicationId = generateDeduplicationId(messageBody)
                request.getClass().getMethod("setMessageDeduplicationId", String)
                    .invoke(request, deduplicationId)
            }
            
            // Add message attributes
            if (headers) {
                def attrsMap = [:]
                def attrClass = Class.forName("com.amazonaws.services.sqs.model.MessageAttributeValue")
                
                headers.each { k, v ->
                    def attr = attrClass.getDeclaredConstructor().newInstance()
                    attr.getClass().getMethod("setDataType", String).invoke(attr, "String")
                    attr.getClass().getMethod("setStringValue", String).invoke(attr, v)
                    attrsMap[k] = attr
                }
                
                request.getClass().getMethod("setMessageAttributes", Map).invoke(request, attrsMap)
            }
            
            // Send message
            def sendMethod = sqsClient.getClass().getMethod("sendMessage", requestClass)
            def result = sendMethod.invoke(sqsClient, request)
            
            // Extract result
            def messageId = result.getClass().getMethod("getMessageId").invoke(result)
            
            log.debug("AwsSqsProducer (v1): sent to queue={}, messageId={}", queueUrl, messageId)
            
            return [
                success: true,
                messageId: messageId,
                queueUrl: queueUrl,
                timestamp: System.currentTimeMillis()
            ]
            
        } catch (Exception e) {
            log.error("Error sending to AWS SQS (v1)", e)
            return [
                success: false,
                error: e.message,
                timestamp: System.currentTimeMillis()
            ] as Map<String, Object>
        }
    }
    
    @Override
    void flush() {
        // SQS sends immediately, no buffering
        log.trace("AwsSqsProducer: flush() called (no-op)")
    }
    
    @Override
    void close() {
        if (sqsClient && awsAvailable) {
            try {
                sqsClient.getClass().getMethod("close").invoke(sqsClient)
                log.info("AwsSqsProducer: closed")
            } catch (Exception e) {
                log.error("Error closing AWS SQS client", e)
            }
        }
        connected = false
    }
    
    @Override
    String getProducerType() {
        return "AWS-SQS"
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
    
    private String generateDeduplicationId(String messageBody) {
        // Generate SHA-256 hash for deduplication
        try {
            def digest = java.security.MessageDigest.getInstance("SHA-256")
            def hash = digest.digest(messageBody.getBytes("UTF-8"))
            return hash.encodeHex().toString()
        } catch (Exception e) {
            // Fallback to timestamp-based ID
            return "${System.currentTimeMillis()}-${messageBody.hashCode()}"
        }
    }
    
    // =========================================================================
    // Stub Mode
    // =========================================================================
    
    private Map<String, Object> stubSend(String destination, String key, Object message, Map<String, String> headers) {
        log.warn("AwsSqsProducer running in stub mode: send(queue='{}', messageGroupId='{}', message={})",
            queueUrl, key, message)
        return [
            success: true,
            stub: true,
            messageId: UUID.randomUUID().toString(),
            queueUrl: queueUrl,
            timestamp: System.currentTimeMillis()
        ] as Map<String, Object>
    }
}
