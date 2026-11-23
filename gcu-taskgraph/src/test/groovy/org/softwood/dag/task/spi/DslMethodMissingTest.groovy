package org.softwood.dag.task.spi

import org.softwood.dag.TaskGraph
import org.softwood.dag.task.ITask
import org.softwood.dag.task.ServiceTask
import org.softwood.dag.task.TaskContext
import org.softwood.promise.Promises
import spock.lang.Specification

class DslMethodMissingTest extends Specification {

    def setup() { TaskProviderRegistry.reset() }
    def cleanup() { TaskProviderRegistry.reset() }

    static class StubTask extends ServiceTask {
        String customProperty = ""
        StubTask(String id, String name, TaskContext ctx) { super(id, name, ctx) }
        void customProperty(String val) { this.customProperty = val }
    }

    static class StubTaskProvider implements ITaskProvider {
        String getTaskTypeName() { "stub" }
        Class<? extends ITask> getTaskClass() { StubTask }
        ITask createTask(String id, String name, TaskContext ctx) { new StubTask(id, name, ctx) }
        boolean supports(String ts) { ts?.trim()?.toLowerCase() in ["stub", "stubtest"] }
    }

    def "methodMissing resolves SPI type with Task suffix"() {
        given:
        TaskProviderRegistry.register(new StubTaskProvider())

        when:
        def graph = TaskGraph.build {
            stubTask("myStub") {
                action { ctx, prev -> Promises.newPromise("stubResult") }
            }
        }

        then:
        graph.tasks.containsKey("myStub")
        graph.tasks["myStub"] instanceof StubTask
    }

    def "methodMissing resolves SPI type without Task suffix"() {
        given:
        TaskProviderRegistry.register(new StubTaskProvider())

        when:
        def graph = TaskGraph.build {
            stub("myStub") {
                action { ctx, prev -> Promises.newPromise("result") }
            }
        }

        then:
        graph.tasks.containsKey("myStub")
        graph.tasks["myStub"] instanceof StubTask
    }

    def "SPI DSL task properties are configurable"() {
        given:
        TaskProviderRegistry.register(new StubTaskProvider())

        when:
        def graph = TaskGraph.build {
            stubTask("configured") {
                customProperty "hello from SPI"
                action { ctx, prev -> Promises.newPromise("done") }
            }
        }

        then:
        (graph.tasks["configured"] as StubTask).customProperty == "hello from SPI"
    }

    def "SPI DSL task integrates with dependsOn"() {
        given:
        TaskProviderRegistry.register(new StubTaskProvider())

        when:
        def graph = TaskGraph.build {
            serviceTask("first") {
                action { ctx, prev -> Promises.newPromise("step1") }
            }
            stubTask("second") {
                dependsOn "first"
                action { ctx, prev -> Promises.newPromise("step2") }
            }
        }

        then:
        graph.tasks.size() == 2
        graph.tasks["second"].predecessors.contains("first")
    }

    def "SPI DSL task executes in graph"() {
        given:
        TaskProviderRegistry.register(new StubTaskProvider())

        when:
        def graph = TaskGraph.build {
            stubTask("runner") {
                action { ctx, prev -> Promises.newPromise("spi-executed") }
            }
        }
        def result = graph.start().get()

        then:
        result == "spi-executed"
    }

    def "methodMissing throws for unregistered type"() {
        when:
        TaskGraph.build {
            unknownFooTask("x") {
                action { ctx, prev -> Promises.newPromise("nope") }
            }
        }

        then:
        thrown(MissingMethodException)
    }

    def "existing built-in DSL methods still work alongside SPI"() {
        given:
        TaskProviderRegistry.register(new StubTaskProvider())

        when:
        def graph = TaskGraph.build {
            serviceTask("builtin") {
                action { ctx, prev -> Promises.newPromise("built-in") }
            }
            stubTask("custom") {
                dependsOn "builtin"
                action { ctx, prev -> Promises.newPromise("custom") }
            }
        }

        then:
        graph.tasks.size() == 2
        graph.tasks["builtin"] instanceof ServiceTask
        graph.tasks["custom"] instanceof StubTask
    }

    def "KafkaProducerTask works via methodMissing with ServiceLoader"() {
        given:
        TaskProviderRegistry.reset()

        when:
        def graph = TaskGraph.build {
            kafkaProducerTask("publish") {
                topic "test-topic"
            }
        }

        then:
        graph.tasks.containsKey("publish")
        graph.tasks["publish"] instanceof KafkaProducerTask
        (graph.tasks["publish"] as KafkaProducerTask).topic == "test-topic"
    }
}
