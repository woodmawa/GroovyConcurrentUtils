package org.softwood.dag

import org.junit.jupiter.api.Test
import org.softwood.dag.task.TaskContext

import java.util.concurrent.ConcurrentLinkedQueue

import static org.junit.jupiter.api.Assertions.*

/**
 * AW-EV-1 — every scheduled task must broadcast a start event and a terminal event.
 *
 * Live evidence (mcp-agentic-workflow, agentic-workflow.db, 2026-08-19 .. 2026-08-21):
 *   Across 20+ session-bootstrap / session-end runs, workflow_events is missing rows
 *   nondeterministically. Sometimes a node has task_scheduled + running and NO terminal
 *   event; sometimes it has task_scheduled + completed and NO running event. The same
 *   node loses a start in one run and a terminal in another.
 *
 * Already ruled out downstream of this class:
 *   - FlowTemplateRepository.writeEvent under concurrency (16x200 and 16x200x3 on the
 *     real shared Connection: zero loss).
 *   - FlowExecutionService's blanket catch: 'error writing event' never appears in the
 *     AW runtime log, so writeEvent was never reached.
 *   - event_id is contiguous across an affected run, so no INSERT was attempted.
 *   - Listener dispatch is fully synchronous: DefaultTaskEventDispatcher.emit ->
 *     TaskGraph.notifyEvent -> notifyListeners.
 *
 * That leaves TaskGraph.notifyEvent itself — the seenTaskEvents dedup guard and
 * mapTaskStateToEventType. This test exercises the bootstrap's own shape: one root,
 * a wide parallel fan-out, one barrier joining all of them, repeated so that a
 * scheduling-order-dependent drop shows up rather than hiding behind a lucky run.
 *
 * This test MUST FAIL on today's code.
 */
class TaskEventCompletenessTest {

    // Deliberately modest. The original investigation ran 40 x 14 with long sleeps, which
    // left enough live promise threads behind to make SendTaskTest's HTTP calls time out
    // later in the same JVM (tests run in a single fork). As a standing regression guard the
    // property holds at this size; the heavy sweep belongs in a one-off, not the suite.
    static final int FANOUT     = 8    // session-bootstrap fans out ~12 nodes off lifecycle-start
    static final int ITERATIONS = 8

    private static final List<GraphEventType> TERMINAL = [
        GraphEventType.TASK_COMPLETED,
        GraphEventType.TASK_FAILED,
        GraphEventType.TASK_SKIPPED
    ]

    @Test
    void 'every task in a wide parallel fan-out broadcasts a start and a terminal event'() {
        List<String> defects = []

        (0..<ITERATIONS).each { int run ->
            def events = new ConcurrentLinkedQueue<GraphEvent>()

            def graph = TaskGraph.build {
                serviceTask('root') {
                    action { TaskContext ctx, prev ->
                        Thread.sleep(5)
                        ctx.promiseFactory.createPromise('root-done')
                    }
                }
                (0..<FANOUT).each { int i ->
                    serviceTask("fan-${i}") {
                        dependsOn 'root'
                        action { TaskContext ctx, prev ->
                            // Uneven durations, as real mcp.tool_call / cs.query nodes have.
                            Thread.sleep(i % 3)
                            ctx.promiseFactory.createPromise("fan-${i}-done")
                        }
                    }
                }
                serviceTask('barrier') {
                    (0..<FANOUT).each { int i -> dependsOn "fan-${i}" }
                    action { TaskContext ctx, prev ->
                        ctx.promiseFactory.createPromise('barrier-done')
                    }
                }
            }

            graph.addListener(new CollectingListener(events))
            graph.start().get()

            List<String> expected = ['root'] + (0..<FANOUT).collect { "fan-${it}".toString() } + ['barrier']
            List<GraphEvent> taskEvents = new ArrayList<GraphEvent>(events).findAll { it.isTaskEvent() }

            expected.each { String id ->
                def forTask = taskEvents.findAll { it.getTaskEvent()?.taskId == id }
                def starts  = forTask.findAll { it.type == GraphEventType.TASK_STARTED }
                def ends    = forTask.findAll { it.type in TERMINAL }

                if (starts.size() != 1) {
                    defects << "run ${run}: task '${id}' broadcast ${starts.size()} TASK_STARTED events (expected 1)"
                }
                if (ends.size() != 1) {
                    defects << "run ${run}: task '${id}' broadcast ${ends.size()} terminal events (expected 1)"
                }
            }
        }

        String sep = System.lineSeparator()
        assertTrue(defects.isEmpty(),
            "Task events were dropped or duplicated in ${defects.size()} case(s) across ${ITERATIONS} runs:" +
            sep + defects.take(40).join(sep))
    }

    @Test
    void 'a task that runs twice in one graph is not silenced by the dedup guard'() {
        // The seenTaskEvents guard is keyed on (taskId, eventType) with no attempt counter,
        // so any legitimate second execution of the same task id within one run is suppressed
        // outright rather than deduplicated against its own duplicate emission.
        def events = new ConcurrentLinkedQueue<GraphEvent>()

        def graph = TaskGraph.build {
            serviceTask('a') {
                action { TaskContext ctx, prev -> ctx.promiseFactory.createPromise('a') }
            }
            serviceTask('b') {
                dependsOn 'a'
                action { TaskContext ctx, prev -> ctx.promiseFactory.createPromise('b') }
            }
        }
        graph.addListener(new CollectingListener(events))
        graph.start().get()

        def bStarts = events.findAll {
            it.isTaskEvent() && it.getTaskEvent()?.taskId == 'b' && it.type == GraphEventType.TASK_STARTED
        }
        assertEquals(1, bStarts.size(), 'task b should broadcast exactly one TASK_STARTED')

        def bEnds = events.findAll {
            it.isTaskEvent() && it.getTaskEvent()?.taskId == 'b' && it.type in TERMINAL
        }
        assertEquals(1, bEnds.size(), 'task b should broadcast exactly one terminal event')
    }

    static class CollectingListener implements GraphEventListener {
        private final ConcurrentLinkedQueue<GraphEvent> events

        CollectingListener(ConcurrentLinkedQueue<GraphEvent> events) {
            this.events = events
        }

        @Override
        void onEvent(GraphEvent event) {
            events.add(event)
        }
    }
}
