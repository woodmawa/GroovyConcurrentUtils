package org.softwood.dag

import org.junit.jupiter.api.Test
import org.softwood.dag.task.TaskContext
import org.softwood.dag.task.TaskType

import java.util.concurrent.ConcurrentLinkedQueue

import static org.junit.jupiter.api.Assertions.*

/**
 * Tests for GraphEventListener functionality.
 */
class GraphEventListenerTest {

    @Test
    void testListenerReceivesEvents() {
        // Given: A simple workflow with a listener
        def events = new ConcurrentLinkedQueue<GraphEvent>()
        def listener = new TestListener(events)

        def workflow = TaskGraph.build {
            serviceTask("task1") {
                action { ctx, prev ->
                    ctx.promiseFactory.createPromise("result1")
                }
            }
        }

        workflow.addListener(listener)

        // When: Workflow executes
        def result = workflow.start().get()

        // Then: Listener received events
        assertTrue(events.size() > 0, "Should receive at least one event")

        // Should have GRAPH_STARTED event
        def startedEvent = events.find { it.type == GraphEventType.GRAPH_STARTED }
        assertNotNull(startedEvent, "Should receive GRAPH_STARTED event")
        assertEquals(workflow.id, startedEvent.graphId)

        // Should have task events
        def taskEvents = events.findAll { it.isTaskEvent() }
        assertTrue(taskEvents.size() > 0, "Should receive task events")

        println "Received ${events.size()} events:"
        events.each { println "  - ${it}" }
    }

    @Test
    void testListenerFiltering() {
        // Given: A listener that only accepts failure events
        def events = new ConcurrentLinkedQueue<GraphEvent>()
        def listener = new FilteringListener(events)

        def workflow = TaskGraph.build {
            serviceTask("success-task") {
                action { ctx, prev ->
                    ctx.promiseFactory.createPromise("success")
                }
            }
        }

        workflow.addListener(listener)

        // When: Workflow executes successfully
        workflow.start().get()

        // Then: Listener received no events (all filtered out)
        assertEquals(0, events.size(), "Filtering listener should not receive success events")
    }

    @Test
    void testMultipleListeners() {
        // Given: Two listeners
        def events1 = new ConcurrentLinkedQueue<GraphEvent>()
        def events2 = new ConcurrentLinkedQueue<GraphEvent>()

        def workflow = TaskGraph.build {
            serviceTask("task1") {
                action { ctx, prev ->
                    ctx.promiseFactory.createPromise("result")
                }
            }
        }

        workflow.addListener(new TestListener(events1))
        workflow.addListener(new TestListener(events2))

        // When: Workflow executes
        workflow.start().get()

        // Then: Both listeners received events
        assertTrue(events1.size() > 0)
        assertTrue(events2.size() > 0)
        assertEquals(events1.size(), events2.size(), "Both listeners should receive same number of events")
    }

    @Test
    void testListenerRemoval() {
        // Given: A listener that is then removed
        def events = new ConcurrentLinkedQueue<GraphEvent>()
        def listener = new TestListener(events)

        def workflow = TaskGraph.build {
            serviceTask("task1") {
                action { ctx, prev ->
                    ctx.promiseFactory.createPromise("result")
                }
            }
        }

        workflow.addListener(listener)
        assertEquals(1, workflow.getListenerCount())

        // When: Listener is removed
        boolean removed = workflow.removeListener(listener)

        // Then: Listener is no longer registered
        assertTrue(removed)
        assertEquals(0, workflow.getListenerCount())
    }

    @Test
    void testListenerErrorHandling() {
        // Given: A listener that throws an exception
        def workflow = TaskGraph.build {
            serviceTask("task1") {
                action { ctx, prev ->
                    ctx.promiseFactory.createPromise("result")
                }
            }
        }

        workflow.addListener(new FailingListener())

        // When: Workflow executes
        def result = workflow.start().get()

        // Then: Workflow completes despite listener failure
        assertEquals("result", result)
    }

    @Test
    void testEventMetadata() {
        // Given: A workflow with metadata tracking
        def events = new ConcurrentLinkedQueue<GraphEvent>()
        def listener = new TestListener(events)

        def workflow = TaskGraph.build {
            serviceTask("task1") {
                action { ctx, prev ->
                    ctx.promiseFactory.createPromise("result")
                }
            }
        }

        workflow.id = "test-workflow"
        workflow.addListener(listener)

        // When: Workflow executes
        workflow.start().get()

        // Then: Events contain graph metadata
        def startEvent = events.find { it.type == GraphEventType.GRAPH_STARTED }
        assertNotNull(startEvent)
        assertEquals("test-workflow", startEvent.graphId)
        assertNotNull(startEvent.runId)
        assertTrue(startEvent.metrics.containsKey('totalTasks'))
    }

    // Test listener implementation
    static class TestListener implements GraphEventListener {
        private final ConcurrentLinkedQueue<GraphEvent> events

        TestListener(ConcurrentLinkedQueue<GraphEvent> events) {
            this.events = events
        }

        @Override
        void onEvent(GraphEvent event) {
            events.add(event)
        }
    }

    // Filtering listener (only failures)
    static class FilteringListener implements GraphEventListener {
        private final ConcurrentLinkedQueue<GraphEvent> events

        FilteringListener(ConcurrentLinkedQueue<GraphEvent> events) {
            this.events = events
        }

        @Override
        void onEvent(GraphEvent event) {
            events.add(event)
        }

        @Override
        boolean accepts(GraphEvent event) {
            return event.isFailure()
        }
    }

    // Failing listener (tests error handling)
    static class FailingListener implements GraphEventListener {
        @Override
        void onEvent(GraphEvent event) {
            throw new RuntimeException("Listener intentionally failed")
        }
    }
}
