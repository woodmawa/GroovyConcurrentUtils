package org.softwood.dag.task.spi

import org.softwood.dag.task.ITask
import org.softwood.dag.task.TaskContext

/**
 * Provider for KafkaProducerTask - demonstrates SPI implementation pattern.
 * 
 * <p>This provider enables users to create Kafka producer tasks via DSL:</p>
 * <pre>
 * task("publish", "kafka-producer") {
 *     topic "orders"
 *     key { order -> order.id }
 *     value { order -> order.toJson() }
 *     config "bootstrap.servers", "localhost:9092"
 * }
 * </pre>
 */
class KafkaProducerTaskProvider implements ITaskProvider {
    
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
        if (!typeString) {
            return false
        }
        
        String normalized = typeString.trim().toLowerCase()
        
        // Support various aliases
        return normalized in [
            "kafka-producer",
            "kafka_producer",
            "kafkaproducer",
            "kafka.producer"
        ]
    }
    
    @Override
    int getPriority() {
        return 10  // Custom providers typically have higher priority than built-ins
    }
    
    @Override
    Map<String, Object> getMetadata() {
        return [
            description: "Kafka message producer task",
            author: "TaskGraph Team",
            version: "1.0.0",
            category: "messaging",
            aliases: ["kafka-producer", "kafka_producer", "kafkaproducer", "kafka.producer"]
        ]
    }
}
