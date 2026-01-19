package org.softwood.dag.task.messaging

import spock.lang.Specification

/**
 * Tests for reflection-based messaging producers.
 * Tests verify stub mode functionality (without actual client libraries).
 *
 * UPDATED: All tests now use named parameter syntax for clarity and to avoid method overload ambiguity.
 */
class ReflectionProducersTest extends Specification {

    // =========================================================================
    // RabbitMqProducer Tests
    // =========================================================================

    def "RabbitMqProducer should work in stub mode without library"() {
        given:
        def producer = new RabbitMqProducer(
                host: "localhost",
                port: 5672,
                exchange: "test-exchange",
                exchangeType: "topic"
        )

        when:
        producer.initialize()

        then:
        producer.isConnected()
        producer.getProducerType() == "RabbitMQ"
    }

    def "RabbitMqProducer should send message in stub mode"() {
        given:
        def producer = new RabbitMqProducer(
                host: "localhost",
                exchange: "orders"
        )
        producer.initialize()

        when:
        def result = producer.send(destination: "order.created", message: [orderId: 123, status: "pending"])

        then:
        result.success == true
        result.stub == true
        result.exchange == "orders"
        result.routingKey == "order.created"
        result.timestamp != null
    }

    def "RabbitMqProducer should handle routing key from key parameter"() {
        given:
        def producer = new RabbitMqProducer(exchange: "orders")
        producer.initialize()

        when:
        def result = producer.send(destination: "orders", key: "customer-123", message: [orderId: 456])

        then:
        result.success == true
        result.routingKey == "customer-123"
    }

    def "RabbitMqProducer should accept headers"() {
        given:
        def producer = new RabbitMqProducer(exchange: "orders")
        producer.initialize()

        when:
        def result = producer.send(
                destination: "order.created",
                message: [orderId: 789],
                headers: [
                        "correlation-id": "abc-123",
                        "message-type": "OrderCreated"
                ]
        )

        then:
        result.success == true
    }

    // =========================================================================
    // KafkaProducer Tests
    // =========================================================================

    def "KafkaProducer should work in stub mode without library"() {
        given:
        def producer = new KafkaProducer(
                bootstrapServers: "kafka1:9092,kafka2:9092",
                clientId: "test-producer",
                compression: "snappy"
        )

        when:
        producer.initialize()

        then:
        producer.isConnected()
        producer.getProducerType() == "Kafka"
    }

    def "KafkaProducer should send message in stub mode"() {
        given:
        def producer = new KafkaProducer(bootstrapServers: "localhost:9092")
        producer.initialize()

        when:
        def result = producer.send(destination: "user-events", message: [userId: 123, action: "login"])

        then:
        result.success == true
        result.stub == true
        result.topic == "user-events"
        result.partition == 0
        result.offset == 0L
        result.timestamp != null
    }

    def "KafkaProducer should handle partition key"() {
        given:
        def producer = new KafkaProducer(bootstrapServers: "localhost:9092")
        producer.initialize()

        when:
        def result = producer.send(destination: "orders", key: "customer-456", message: [orderId: 789])

        then:
        result.success == true
        result.topic == "orders"
    }

    def "KafkaProducer should configure compression"() {
        given:
        def producer = new KafkaProducer(
                bootstrapServers: "localhost:9092",
                compression: "lz4",
                idempotence: true
        )

        when:
        producer.initialize()

        then:
        producer.isConnected()
    }

    // =========================================================================
    // AwsSqsProducer Tests
    // =========================================================================

    def "AwsSqsProducer should work in stub mode without library"() {
        given:
        def producer = new AwsSqsProducer(
                region: "us-east-1",
                queueUrl: "https://sqs.us-east-1.amazonaws.com/123456789/test-queue"
        )

        when:
        producer.initialize()

        then:
        producer.isConnected()
        producer.getProducerType() == "AWS-SQS"
    }

    def "AwsSqsProducer should send to standard queue in stub mode"() {
        given:
        def producer = new AwsSqsProducer(
                region: "us-west-2",
                queueUrl: "https://sqs.us-west-2.amazonaws.com/123456789/orders"
        )
        producer.initialize()

        when:
        def result = producer.send(destination: "orders", message: [orderId: 123])

        then:
        result.success == true
        result.stub == true
        result.messageId != null
        result.queueUrl == "https://sqs.us-west-2.amazonaws.com/123456789/orders"
        result.timestamp != null
    }

    def "AwsSqsProducer should handle FIFO queue with message group"() {
        given:
        def producer = new AwsSqsProducer(
                region: "us-east-1",
                queueUrl: "https://sqs.us-east-1.amazonaws.com/123456789/orders.fifo",
                fifo: true
        )
        producer.initialize()

        when:
        def result = producer.send(destination: "orders.fifo", key: "customer-123", message: [orderId: 456])

        then:
        result.success == true
        result.queueUrl.endsWith(".fifo")
    }

    def "AwsSqsProducer should handle delay"() {
        given:
        def producer = new AwsSqsProducer(
                region: "us-east-1",
                queueUrl: "https://sqs.us-east-1.amazonaws.com/123456789/delayed",
                delaySeconds: 60
        )

        when:
        producer.initialize()

        then:
        producer.isConnected()
    }

    // =========================================================================
    // ActiveMqProducer Tests
    // =========================================================================

    def "ActiveMqProducer should work in stub mode without library"() {
        given:
        def producer = new ActiveMqProducer(
                brokerUrl: "tcp://localhost:61616",
                queueName: "orders",
                username: "admin",
                password: "admin"
        )

        when:
        producer.initialize()

        then:
        producer.isConnected()
        producer.getProducerType() == "ActiveMQ-Artemis"
    }

    def "ActiveMqProducer should send to queue in stub mode"() {
        given:
        def producer = new ActiveMqProducer(
                brokerUrl: "tcp://localhost:61616",
                queueName: "orders"
        )
        producer.initialize()

        when:
        def result = producer.send(destination: "orders", message: [orderId: 123])

        then:
        result.success == true
        result.stub == true
        result.messageId != null
        result.destination == "orders"
        result.timestamp != null
    }

    def "ActiveMqProducer should send to topic in stub mode"() {
        given:
        def producer = new ActiveMqProducer(
                brokerUrl: "tcp://localhost:61616",
                topicName: "notifications"
        )
        producer.initialize()

        when:
        def result = producer.send(destination: "notifications", message: [event: "user.signup"])

        then:
        result.success == true
        result.destination == "notifications"
    }

    def "ActiveMqProducer should handle message priority"() {
        given:
        def producer = new ActiveMqProducer(
                brokerUrl: "tcp://localhost:61616",
                queueName: "priority-queue",
                priority: 9,
                persistent: true
        )

        when:
        producer.initialize()

        then:
        producer.isConnected()
    }

    def "ActiveMqProducer should require queue or topic"() {
        given:
        def producer = new ActiveMqProducer(
                brokerUrl: "tcp://localhost:61616"
        )

        when:
        producer.initialize()

        then:
        thrown(IllegalStateException)
    }

    // =========================================================================
    // AzureServiceBusProducer Tests
    // =========================================================================

    def "AzureServiceBusProducer should work in stub mode without library"() {
        given:
        def producer = new AzureServiceBusProducer(
                connectionString: "Endpoint=sb://test.servicebus.windows.net/;SharedAccessKeyName=RootManageSharedAccessKey;SharedAccessKey=fake",
                queueName: "orders"
        )

        when:
        producer.initialize()

        then:
        producer.isConnected()
        producer.getProducerType() == "Azure-ServiceBus"
    }

    def "AzureServiceBusProducer should send to queue in stub mode"() {
        given:
        def producer = new AzureServiceBusProducer(
                connectionString: "Endpoint=sb://myns.servicebus.windows.net/;SharedAccessKeyName=key;SharedAccessKey=secret",
                queueName: "orders"
        )
        producer.initialize()

        when:
        def result = producer.send(destination: "orders", message: [orderId: 123])

        then:
        result.success == true
        result.stub == true
        result.messageId != null
        result.destination == "orders"
        result.timestamp != null
    }

    def "AzureServiceBusProducer should send to topic in stub mode"() {
        given:
        def producer = new AzureServiceBusProducer(
                connectionString: "Endpoint=sb://myns.servicebus.windows.net/;SharedAccessKeyName=key;SharedAccessKey=secret",
                topicName: "notifications"
        )
        producer.initialize()

        when:
        def result = producer.send(destination: "notifications", message: [event: "user.created"])

        then:
        result.success == true
        result.destination == "notifications"
    }

    def "AzureServiceBusProducer should handle session ID"() {
        given:
        def producer = new AzureServiceBusProducer(
                connectionString: "Endpoint=sb://myns.servicebus.windows.net/;SharedAccessKeyName=key;SharedAccessKey=secret",
                queueName: "sessions"
        )
        producer.initialize()

        when:
        def result = producer.send(destination: "sessions", key: "session-123", message: [data: "test"])

        then:
        result.success == true
        result.sessionId == "session-123"
    }

    def "AzureServiceBusProducer should require connectionString"() {
        given:
        def producer = new AzureServiceBusProducer(
                queueName: "orders"
        )

        when:
        producer.initialize()

        then:
        thrown(IllegalStateException)
    }

    def "AzureServiceBusProducer should require queue or topic"() {
        given:
        def producer = new AzureServiceBusProducer(
                connectionString: "Endpoint=sb://test.servicebus.windows.net/;..."
        )

        when:
        producer.initialize()

        then:
        thrown(IllegalStateException)
    }

    // =========================================================================
    // Lifecycle Tests (All Producers)
    // =========================================================================

    def "All producers should support close()"() {
        given:
        def producers = [
                new RabbitMqProducer(exchange: "test"),
                new KafkaProducer(bootstrapServers: "localhost:9092"),
                new AwsSqsProducer(region: "us-east-1", queueUrl: "https://..."),
                new ActiveMqProducer(brokerUrl: "tcp://localhost:61616", queueName: "test"),
                new AzureServiceBusProducer(connectionString: "Endpoint=...", queueName: "test")
        ]

        when:
        producers.each { it.initialize() }
        producers.each { it.close() }

        then:
        noExceptionThrown()
    }

    def "All producers should support flush()"() {
        given:
        def producers = [
                new RabbitMqProducer(exchange: "test"),
                new KafkaProducer(bootstrapServers: "localhost:9092"),
                new AwsSqsProducer(region: "us-east-1", queueUrl: "https://..."),
                new ActiveMqProducer(brokerUrl: "tcp://localhost:61616", queueName: "test"),
                new AzureServiceBusProducer(connectionString: "Endpoint=...", queueName: "test")
        ]

        when:
        producers.each { it.initialize() }
        producers.each { it.flush() }

        then:
        noExceptionThrown()
    }

    def "All producers should have unique producer types"() {
        given:
        def producers = [
                new RabbitMqProducer(exchange: "test"),
                new KafkaProducer(bootstrapServers: "localhost:9092"),
                new AwsSqsProducer(region: "us-east-1", queueUrl: "https://..."),
                new ActiveMqProducer(brokerUrl: "tcp://localhost:61616", queueName: "test"),
                new AzureServiceBusProducer(connectionString: "Endpoint=...", queueName: "test")
        ]

        when:
        producers.each { it.initialize() }
        def types = producers.collect { it.getProducerType() }

        then:
        types.size() == types.unique().size()
        types.contains("RabbitMQ")
        types.contains("Kafka")
        types.contains("AWS-SQS")
        types.contains("ActiveMQ-Artemis")
        types.contains("Azure-ServiceBus")
    }

    // =========================================================================
    // Message Serialization Tests
    // =========================================================================

    def "Producers should serialize Map messages to JSON"() {
        given:
        def producer = new KafkaProducer(bootstrapServers: "localhost:9092")
        producer.initialize()

        when:
        def result = producer.send(
                destination: "test",
                message: [
                        orderId: 123,
                        items: [[name: "item1", qty: 2]],
                        total: 99.99
                ]
        )

        then:
        result.success == true
    }

    def "Producers should handle String messages"() {
        given:
        def producer = new RabbitMqProducer(exchange: "test")
        producer.initialize()

        when:
        def result = producer.send(destination: "test", message: "Simple string message")

        then:
        result.success == true
    }

    // =========================================================================
    // GooglePubSubProducer Tests
    // =========================================================================

    def "GooglePubSubProducer should work in stub mode without library"() {
        given:
        def producer = new GooglePubSubProducer(
                projectId: "test-project",
                topicName: "orders"
        )

        when:
        producer.initialize()

        then:
        producer.isConnected()
        producer.getProducerType() == "GCP-PubSub"
    }

    def "GooglePubSubProducer should send to topic in stub mode"() {
        given:
        def producer = new GooglePubSubProducer(
                projectId: "my-gcp-project",
                topicName: "orders"
        )
        producer.initialize()

        when:
        def result = producer.send(destination: "orders", message: [orderId: 123])

        then:
        result.success == true
        result.stub == true
        result.messageId != null
        result.topic == "orders"
        result.timestamp != null
    }

    def "GooglePubSubProducer should handle ordering key"() {
        given:
        def producer = new GooglePubSubProducer(
                projectId: "my-gcp-project",
                topicName: "orders",
                enableOrdering: true
        )
        producer.initialize()

        when:
        def result = producer.send(destination: "orders", key: "customer-123", message: [orderId: 456])

        then:
        result.success == true
        result.orderingKey == "customer-123"
    }

    def "GooglePubSubProducer should configure batching"() {
        given:
        def producer = new GooglePubSubProducer(
                projectId: "my-gcp-project",
                topicName: "orders",
                batchSize: 50,
                batchingDelayMs: 20L
        )

        when:
        producer.initialize()

        then:
        producer.isConnected()
    }

    def "GooglePubSubProducer should require projectId"() {
        given:
        def producer = new GooglePubSubProducer(
                topicName: "orders"
        )

        when:
        producer.initialize()

        then:
        thrown(IllegalStateException)
    }

    def "GooglePubSubProducer should require topicName"() {
        given:
        def producer = new GooglePubSubProducer(
                projectId: "test-project"
        )

        when:
        producer.initialize()

        then:
        thrown(IllegalStateException)
    }

// =========================================================================
// UPDATE the "All producers" lifecycle tests to include GooglePubSubProducer
// =========================================================================

    def "All producers should support close()"() {
        given:
        def producers = [
                new RabbitMqProducer(exchange: "test"),
                new KafkaProducer(bootstrapServers: "localhost:9092"),
                new AwsSqsProducer(region: "us-east-1", queueUrl: "https://..."),
                new ActiveMqProducer(brokerUrl: "tcp://localhost:61616", queueName: "test"),
                new AzureServiceBusProducer(connectionString: "Endpoint=...", queueName: "test"),
                new GooglePubSubProducer(projectId: "test-project", topicName: "test")  // ADD THIS
        ]

        when:
        producers.each { it.initialize() }
        producers.each { it.close() }

        then:
        noExceptionThrown()
    }

    def "All producers should support flush()"() {
        given:
        def producers = [
                new RabbitMqProducer(exchange: "test"),
                new KafkaProducer(bootstrapServers: "localhost:9092"),
                new AwsSqsProducer(region: "us-east-1", queueUrl: "https://..."),
                new ActiveMqProducer(brokerUrl: "tcp://localhost:61616", queueName: "test"),
                new AzureServiceBusProducer(connectionString: "Endpoint=...", queueName: "test"),
                new GooglePubSubProducer(projectId: "test-project", topicName: "test")  // ADD THIS
        ]

        when:
        producers.each { it.initialize() }
        producers.each { it.flush() }

        then:
        noExceptionThrown()
    }

    def "All producers should have unique producer types"() {
        given:
        def producers = [
                new RabbitMqProducer(exchange: "test"),
                new KafkaProducer(bootstrapServers: "localhost:9092"),
                new AwsSqsProducer(region: "us-east-1", queueUrl: "https://..."),
                new ActiveMqProducer(brokerUrl: "tcp://localhost:61616", queueName: "test"),
                new AzureServiceBusProducer(connectionString: "Endpoint=...", queueName: "test"),
                new GooglePubSubProducer(projectId: "test-project", topicName: "test")  // ADD THIS
        ]

        when:
        producers.each { it.initialize() }
        def types = producers.collect { it.getProducerType() }

        then:
        types.size() == types.unique().size()
        types.contains("RabbitMQ")
        types.contains("Kafka")
        types.contains("AWS-SQS")
        types.contains("ActiveMQ-Artemis")
        types.contains("Azure-ServiceBus")
        types.contains("GCP-PubSub")  // ADD THIS
    }
}