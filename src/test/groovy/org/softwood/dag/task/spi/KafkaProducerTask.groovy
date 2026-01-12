package org.softwood.dag.task.spi

import org.softwood.dag.task.ITask
import org.softwood.dag.task.TaskBase
import org.softwood.dag.task.TaskContext
import org.softwood.promise.Promise

/**
 * Example custom task demonstrating SPI extensibility.
 * 
 * <p>This task simulates Kafka message production. In a real implementation,
 * it would connect to Kafka, but for testing purposes it just records the
 * configuration and produces a mock result.</p>
 */
class KafkaProducerTask extends TaskBase {
    
    String topic
    Closure keyExtractor
    Closure valueExtractor
    Map<String, String> kafkaConfig = [:]
    
    // For testing - records what was "sent"
    List<Map<String, Object>> sentMessages = []
    
    KafkaProducerTask(String id, String name, TaskContext ctx) {
        super(id, name, ctx)
    }
    
    /**
     * Configure Kafka topic.
     */
    void topic(String topic) {
        this.topic = topic
    }
    
    /**
     * Configure key extraction logic.
     */
    void key(Closure keyExtractor) {
        this.keyExtractor = keyExtractor
    }
    
    /**
     * Configure value extraction logic.
     */
    void value(Closure valueExtractor) {
        this.valueExtractor = valueExtractor
    }
    
    /**
     * Add Kafka configuration property.
     */
    void config(String key, String value) {
        kafkaConfig[key] = value
    }
    
    /**
     * Add multiple Kafka configuration properties.
     */
    void config(Map<String, String> configs) {
        kafkaConfig.putAll(configs)
    }
    
    @Override
    protected Promise runTask(TaskContext ctx, Object input) {
        return ctx.promiseFactory.executeAsync {
            if (!topic) {
                throw new IllegalStateException("Kafka topic not configured")
            }
            
            // Extract key and value using closures
            def key = keyExtractor ? keyExtractor.call(input) : null
            def value = valueExtractor ? valueExtractor.call(input) : input
            
            // Record the "sent" message for testing
            def message = [
                topic: topic,
                key: key,
                value: value,
                timestamp: System.currentTimeMillis(),
                config: new HashMap(kafkaConfig)
            ]
            sentMessages.add(message)
            
            // Return success result
            return [
                success: true,
                topic: topic,
                messageCount: 1,
                offset: sentMessages.size() - 1
            ]
        }
    }
    
    @Override
    String toString() {
        "KafkaProducerTask(id=$id, topic=$topic)"
    }
}
