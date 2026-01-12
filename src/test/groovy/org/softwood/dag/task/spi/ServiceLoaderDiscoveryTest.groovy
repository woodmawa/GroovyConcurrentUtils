package org.softwood.dag.task.spi

import org.softwood.dag.task.ITask
import org.softwood.dag.task.TaskContext
import org.softwood.dag.task.TaskFactory
import spock.lang.Specification

/**
 * Tests for ServiceLoader-based provider discovery.
 * 
 * <p>Verifies that custom providers can be automatically discovered via
 * Java's ServiceLoader mechanism when properly configured in
 * META-INF/services/org.softwood.dag.task.spi.ITaskProvider</p>
 */
class ServiceLoaderDiscoveryTest extends Specification {
    
    TaskContext ctx
    
    def setup() {
        ctx = new TaskContext()
        TaskProviderRegistry.reset()
    }
    
    def cleanup() {
        TaskProviderRegistry.reset()
    }
    
    // =========================================================================
    // ServiceLoader Discovery Tests
    // =========================================================================
    
    def "ServiceLoader discovers providers from classpath"() {
        when: "we explicitly trigger discovery"
        List<ITaskProvider> discovered = TaskProviderRegistry.discoverProviders()
        
        then: "KafkaProducerTaskProvider is discovered"
        discovered.size() >= 1
        discovered.any { it instanceof KafkaProducerTaskProvider }
        
        and: "provider is registered"
        TaskProviderRegistry.hasProvider("kafka-producer")
    }
    
    def "discovery is triggered automatically on first access"() {
        when: "we access registry without explicit discovery"
        def provider = TaskProviderRegistry.findProvider("kafka-producer")
        
        then: "provider is found via automatic discovery"
        provider != null
        provider instanceof KafkaProducerTaskProvider
    }
    
    def "discovery only runs once"() {
        when: "we call discoverProviders multiple times"
        def first = TaskProviderRegistry.discoverProviders()
        def second = TaskProviderRegistry.discoverProviders()
        
        then: "second call returns empty (already discovered)"
        first.size() >= 1
        second.isEmpty()
    }
    
    def "TaskFactory uses auto-discovered providers"() {
        when: "we create task without explicit registration"
        ITask task = TaskFactory.createTask("kafka-producer", "task1", "Kafka Task", ctx)
        
        then: "task is created via auto-discovered provider"
        task != null
        task instanceof KafkaProducerTask
    }
    
    def "manually registered providers override discovered providers with same name"() {
        given: "allow auto-discovery to happen first"
        TaskProviderRegistry.findProvider("kafka-producer")
        
        and: "a manually registered provider with higher priority"
        def highPriorityProvider = new HighPriorityKafkaProvider()
        
        when: "we register the high-priority provider"
        TaskProviderRegistry.register(highPriorityProvider)
        
        then: "high-priority provider takes precedence"
        TaskProviderRegistry.findProvider("kafka-producer") == highPriorityProvider
    }
    
    def "reset allows rediscovery"() {
        given: "discovery has been performed"
        TaskProviderRegistry.discoverProviders()
        
        when: "we reset and discover again"
        TaskProviderRegistry.reset()
        def rediscovered = TaskProviderRegistry.discoverProviders()
        
        then: "providers are discovered again"
        rediscovered.size() >= 1
        rediscovered.any { it instanceof KafkaProducerTaskProvider }
    }
    
    def "TaskFactory error message includes discovered custom types"() {
        given: "discovery has occurred"
        TaskProviderRegistry.findProvider("kafka-producer")
        
        when: "we try to create unknown type"
        TaskFactory.createTask("unknown-type", "task1", "Task", ctx)
        
        then: "error message lists kafka-producer as available custom type"
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("kafka-producer")
    }
    
    // =========================================================================
    // Helper Classes
    // =========================================================================
    
    /**
     * Test provider with higher priority than default KafkaProducerTaskProvider.
     */
    static class HighPriorityKafkaProvider implements ITaskProvider {
        
        @Override
        String getTaskTypeName() {
            return "kafka-producer"
        }
        
        @Override
        Class<? extends ITask> getTaskClass() {
            return KafkaProducerTask
        }
        
        @Override
        ITask createTask(String id, String name, TaskContext ctx) {
            return new KafkaProducerTask(id, name, ctx)
        }
        
        @Override
        boolean supports(String typeString) {
            return typeString?.trim()?.toLowerCase() == "kafka-producer"
        }
        
        @Override
        int getPriority() {
            return 100  // Much higher than default 10
        }
    }
}
