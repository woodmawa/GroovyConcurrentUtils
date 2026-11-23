package org.softwood.dag.task.spi

import org.softwood.dag.task.ITask
import org.softwood.dag.task.TaskContext
import org.softwood.dag.task.TaskFactory
import spock.lang.Specification

/**
 * Comprehensive tests for Task SPI (Service Provider Interface).
 * 
 * <p>Tests demonstrate:</p>
 * <ul>
 *   <li>Provider registration and discovery</li>
 *   <li>TaskFactory integration with custom types</li>
 *   <li>Priority-based provider selection</li>
 *   <li>Alias support for type names</li>
 *   <li>Full end-to-end custom task execution</li>
 * </ul>
 */
class TaskProviderIntegrationTest extends Specification {
    
    TaskContext ctx
    
    def setup() {
        ctx = new TaskContext()
        // Reset registry before each test
        TaskProviderRegistry.reset()
    }
    
    def cleanup() {
        TaskProviderRegistry.reset()
    }
    
    // =========================================================================
    // Provider Registration Tests
    // =========================================================================
    
    def "register provider successfully"() {
        given: "a custom task provider"
        def provider = new KafkaProducerTaskProvider()
        
        when: "we register the provider"
        TaskProviderRegistry.register(provider)
        
        then: "it is registered and discoverable"
        TaskProviderRegistry.hasProvider("kafka-producer")
        TaskProviderRegistry.findProvider("kafka-producer") == provider
    }
    
    def "unregister provider successfully"() {
        given: "a registered provider (manually registered, not auto-discovered)"
        def provider = new TestTaskProvider("custom-test-type", 5)
        TaskProviderRegistry.register(provider)
        TaskProviderRegistry.clear()  // Mark discovery complete to prevent auto-discovery
        TaskProviderRegistry.register(provider)  // Re-register after clear
        
        when: "we unregister it"
        boolean removed = TaskProviderRegistry.unregister("custom-test-type")
        
        then: "it is removed from registry"
        removed
        !TaskProviderRegistry.hasProvider("custom-test-type")
        TaskProviderRegistry.findProvider("custom-test-type") == null
    }
    
    def "cannot register null provider"() {
        when: "we try to register null"
        TaskProviderRegistry.register(null)
        
        then: "an exception is thrown"
        thrown(IllegalArgumentException)
    }
    
    def "cannot register provider with blank type name"() {
        given: "a provider with blank type name"
        def provider = new ITaskProvider() {
            String getTaskTypeName() { "  " }
            Class<? extends ITask> getTaskClass() { KafkaProducerTask }
            ITask createTask(String id, String name, TaskContext ctx) { null }
            boolean supports(String typeString) { false }
        }
        
        when: "we try to register it"
        TaskProviderRegistry.register(provider)
        
        then: "an exception is thrown"
        thrown(IllegalArgumentException)
    }
    
    // =========================================================================
    // Priority Tests
    // =========================================================================
    
    def "higher priority provider replaces lower priority"() {
        given: "two providers for the same type with different priorities"
        def lowPriorityProvider = new TestTaskProvider("test-type", 5)
        def highPriorityProvider = new TestTaskProvider("test-type", 10)
        
        when: "we register low priority first, then high priority"
        TaskProviderRegistry.register(lowPriorityProvider)
        TaskProviderRegistry.register(highPriorityProvider)
        
        then: "high priority provider is registered"
        TaskProviderRegistry.findProvider("test-type") == highPriorityProvider
    }
    
    def "lower priority provider does not replace higher priority"() {
        given: "two providers for the same type with different priorities"
        def highPriorityProvider = new TestTaskProvider("test-type", 10)
        def lowPriorityProvider = new TestTaskProvider("test-type", 5)
        
        when: "we register high priority first, then low priority"
        TaskProviderRegistry.register(highPriorityProvider)
        TaskProviderRegistry.register(lowPriorityProvider)
        
        then: "high priority provider remains registered"
        TaskProviderRegistry.findProvider("test-type") == highPriorityProvider
    }
    
    def "multiple providers with supports() returns highest priority"() {
        given: "three providers supporting the same alias with different priorities"
        def provider1 = new MultiAliasProvider("type1", ["alias-a"], 1)
        def provider2 = new MultiAliasProvider("type2", ["alias-a"], 15)
        def provider3 = new MultiAliasProvider("type3", ["alias-a"], 8)
        
        when: "all are registered"
        TaskProviderRegistry.register(provider1)
        TaskProviderRegistry.register(provider2)
        TaskProviderRegistry.register(provider3)
        
        and: "we search by alias"
        def result = TaskProviderRegistry.findProvider("alias-a")
        
        then: "highest priority provider is returned"
        result == provider2
    }
    
    // =========================================================================
    // Alias Support Tests
    // =========================================================================
    
    def "find provider by exact type name (case insensitive)"() {
        given: "a registered provider"
        TaskProviderRegistry.register(new KafkaProducerTaskProvider())
        
        expect: "provider found by various case combinations"
        TaskProviderRegistry.findProvider("kafka-producer") != null
        TaskProviderRegistry.findProvider("KAFKA-PRODUCER") != null
        TaskProviderRegistry.findProvider("Kafka-Producer") != null
    }
    
    def "find provider by alias via supports() method"() {
        given: "a registered provider with alias support"
        TaskProviderRegistry.register(new KafkaProducerTaskProvider())
        
        expect: "provider found by all supported aliases"
        TaskProviderRegistry.findProvider("kafka-producer") != null
        TaskProviderRegistry.findProvider("kafka_producer") != null
        TaskProviderRegistry.findProvider("kafkaproducer") != null
        TaskProviderRegistry.findProvider("kafka.producer") != null
    }
    
    def "findProvider returns null for unknown type"() {
        when: "we search for non-existent type"
        TaskProviderRegistry.clear()  // Prevent auto-discovery
        def result = TaskProviderRegistry.findProvider("unknown-type")
        
        then: "null is returned"
        result == null
    }
    
    def "findProvider returns null for null or blank input"() {
        when: "prevent auto-discovery"
        TaskProviderRegistry.clear()
        
        then:
        TaskProviderRegistry.findProvider(null) == null
        TaskProviderRegistry.findProvider("") == null
        TaskProviderRegistry.findProvider("  ") == null
    }
    
    // =========================================================================
    // TaskFactory Integration Tests
    // =========================================================================
    
    def "TaskFactory creates custom task via string type"() {
        given: "a registered custom provider"
        TaskProviderRegistry.register(new KafkaProducerTaskProvider())
        
        when: "we create task via TaskFactory"
        ITask task = TaskFactory.createTask("kafka-producer", "task1", "Kafka Task", ctx)
        
        then: "custom task is created"
        task != null
        task instanceof KafkaProducerTask
        task.id == "task1"
        task.name == "Kafka Task"
    }
    
    def "TaskFactory creates custom task via alias"() {
        given: "a registered custom provider"
        TaskProviderRegistry.register(new KafkaProducerTaskProvider())
        
        when: "we create task using alias"
        ITask task = TaskFactory.createTask("kafka_producer", "task1", "Kafka Task", ctx)
        
        then: "custom task is created"
        task != null
        task instanceof KafkaProducerTask
    }
    
    def "TaskFactory throws helpful error for unknown type"() {
        when: "we try to create unknown task type"
        TaskFactory.createTask("totally-unknown", "task1", "Task", ctx)
        
        then: "exception contains helpful message"
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("Unknown task type")
        ex.message.contains("totally-unknown")
        ex.message.contains("Built-in types")
        ex.message.contains("Custom types")
    }
    
    def "TaskFactory uses custom providers for non-built-in types"() {
        given: "a custom provider (using non-built-in type name)"
        def customProvider = new TestTaskProvider("my-custom-task", 100)
        TaskProviderRegistry.register(customProvider)
        
        when: "we create a task with the custom type"
        ITask task = TaskFactory.createTask("my-custom-task", "task1", "Task", ctx)
        
        then: "custom provider is used"
        task instanceof TestTask
        task.id == "task1"
    }
    
    // =========================================================================
    // End-to-End Integration Tests
    // =========================================================================
    
    def "custom Kafka task executes successfully"() {
        given: "a Kafka producer task"
        TaskProviderRegistry.register(new KafkaProducerTaskProvider())
        KafkaProducerTask task = TaskFactory.createTask("kafka-producer", "kafka1", "Publish Order", ctx) as KafkaProducerTask
        
        and: "task is configured"
        task.topic("orders")
        task.key { order -> order.id }
        task.value { order -> order.toJson() }
        task.config("bootstrap.servers", "localhost:9092")
        
        and: "input data as a promise"
        def order = [
            id: "order-123",
            amount: 99.99,
            toJson: { -> '{"id":"order-123","amount":99.99}' }
        ]
        def orderPromise = ctx.promiseFactory.createPromise(order)
        
        when: "task executes"
        def resultPromise = task.execute(orderPromise)
        def result = resultPromise.get()  // Wait for completion
        
        then: "task completes successfully"
        task.isCompleted()
        !task.isSkipped()
        !task.isFailed()
        
        and: "result contains expected data"
        result.success == true
        result.topic == "orders"
        result.messageCount == 1
        
        and: "message was recorded"
        task.sentMessages.size() == 1
        task.sentMessages[0].topic == "orders"
        task.sentMessages[0].key == "order-123"
        task.sentMessages[0].value == '{"id":"order-123","amount":99.99}'
        task.sentMessages[0].config["bootstrap.servers"] == "localhost:9092"
    }
    
    def "custom task integrates with TaskGraph DSL"() {
        given: "a registered provider"
        TaskProviderRegistry.register(new KafkaProducerTaskProvider())
        
        when: "task is created in DSL-style"
        // Simulating DSL usage pattern
        ITask task = TaskFactory.createTask("kafka-producer", "kafka1", "Publish", ctx)
        
        then: "task is properly created"
        task instanceof KafkaProducerTask
        task.id == "kafka1"
        task.name == "Publish"
    }
    
    // =========================================================================
    // Registry Management Tests
    // =========================================================================
    
    def "getAllProviders returns all registered providers"() {
        given: "multiple registered providers"
        def provider1 = new KafkaProducerTaskProvider()
        def provider2 = new TestTaskProvider("custom-type", 5)
        TaskProviderRegistry.register(provider1)
        TaskProviderRegistry.register(provider2)
        
        when: "we get all providers"
        def providers = TaskProviderRegistry.getAllProviders()
        
        then: "both are returned"
        providers.size() == 2
        providers.contains(provider1)
        providers.contains(provider2)
    }
    
    def "getRegisteredTypes returns all type names"() {
        given: "multiple registered providers"
        TaskProviderRegistry.register(new KafkaProducerTaskProvider())
        TaskProviderRegistry.register(new TestTaskProvider("custom-type", 5))
        
        when: "we get registered types"
        def types = TaskProviderRegistry.getRegisteredTypes()
        
        then: "all type names are returned"
        types.size() == 2
        types.contains("kafka-producer")
        types.contains("custom-type")
    }
    
    def "clear removes all providers and prevents rediscovery"() {
        given: "registered providers"
        TaskProviderRegistry.register(new KafkaProducerTaskProvider())
        
        when: "we clear the registry"
        TaskProviderRegistry.clear()
        
        then: "no providers remain and discovery is marked complete"
        TaskProviderRegistry.getAllProviders().isEmpty()
        TaskProviderRegistry.getRegisteredTypes().isEmpty()
    }
    
    def "reset clears providers and resets discovery state"() {
        given: "registered providers with discovery completed"
        TaskProviderRegistry.register(new KafkaProducerTaskProvider())
        TaskProviderRegistry.clear()  // Mark discovery complete
        
        when: "we reset and prevent auto-rediscovery in the check"
        TaskProviderRegistry.reset()
        TaskProviderRegistry.clear()  // Prevent getAllProviders from triggering discovery
        
        then: "registry is completely reset"
        TaskProviderRegistry.getAllProviders().isEmpty()
        TaskProviderRegistry.getRegisteredTypes().isEmpty()
    }
    
    // =========================================================================
    // Metadata Tests
    // =========================================================================
    
    def "provider metadata is accessible"() {
        given: "a provider with metadata"
        def provider = new KafkaProducerTaskProvider()
        
        when: "we get metadata"
        def metadata = provider.metadata
        
        then: "metadata contains expected fields"
        metadata.description == "Kafka message producer task"
        metadata.author == "TaskGraph Team"
        metadata.version == "1.0.0"
        metadata.category == "messaging"
        metadata.aliases instanceof List
    }
    
    // =========================================================================
    // Helper Classes
    // =========================================================================
    
    /**
     * Simple test task for testing purposes.
     */
    static class TestTask extends org.softwood.dag.task.TaskBase {
        TestTask(String id, String name, TaskContext ctx) {
            super(id, name, ctx)
        }
        
        @Override
        protected org.softwood.promise.Promise runTask(TaskContext ctx, Object input) {
            return ctx.promiseFactory.executeAsync {
                "test-result"
            }
        }
    }
    
    /**
     * Test provider with configurable priority.
     */
    static class TestTaskProvider implements ITaskProvider {
        final String typeName
        final int priority
        
        TestTaskProvider(String typeName, int priority) {
            this.typeName = typeName
            this.priority = priority
        }
        
        @Override
        String getTaskTypeName() { typeName }
        
        @Override
        Class<? extends ITask> getTaskClass() { TestTask }
        
        @Override
        ITask createTask(String id, String name, TaskContext ctx) {
            new TestTask(id, name, ctx)
        }
        
        @Override
        boolean supports(String typeString) {
            typeString?.trim()?.toLowerCase() == typeName.toLowerCase()
        }
        
        @Override
        int getPriority() { priority }
    }
    
    /**
     * Provider with multiple alias support.
     */
    static class MultiAliasProvider implements ITaskProvider {
        final String typeName
        final List<String> aliases
        final int priority
        
        MultiAliasProvider(String typeName, List<String> aliases, int priority) {
            this.typeName = typeName
            this.aliases = aliases
            this.priority = priority
        }
        
        @Override
        String getTaskTypeName() { typeName }
        
        @Override
        Class<? extends ITask> getTaskClass() { TestTask }
        
        @Override
        ITask createTask(String id, String name, TaskContext ctx) {
            new TestTask(id, name, ctx)
        }
        
        @Override
        boolean supports(String typeString) {
            if (!typeString) return false
            String normalized = typeString.trim().toLowerCase()
            return normalized == typeName.toLowerCase() || 
                   aliases*.toLowerCase().contains(normalized)
        }
        
        @Override
        int getPriority() { priority }
    }
}
