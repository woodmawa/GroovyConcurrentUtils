package org.softwood

import groovy.transform.CompileDynamic
import org.softwood.dag.GraphEvent
import org.softwood.dag.GraphEventListener
import org.softwood.dag.TaskGraph
import org.softwood.dag.task.ControlTask
import org.softwood.dag.task.DefaultTaskEventDispatcher
import spock.lang.Specification
import spock.lang.Timeout
import spock.util.concurrent.PollingConditions

import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * GCU 1.4.0: a failed task says whether it will run again, and if not, why.
 *
 * A TASK_FAILED event is the task's last word: the task is not run again by its retry policy. From
 * 1.4.0 the event carries that in its metadata, so that whatever drives the graph can tell a failure
 * that retrying cannot fix from one that ran out of attempts:
 *   willRetry          false on TASK_FAILED, true on TASK_RETRYING
 *   notRetriedBecause  'not-retriable' | 'no-retries-configured' | 'retries-exhausted'
 */
@CompileDynamic
@Timeout(60)
class Hardening140FailureSignalSpec extends Specification {

    static class Collector implements GraphEventListener {
        final List<GraphEvent> events = new CopyOnWriteArrayList<GraphEvent>()

        @Override
        void onEvent(GraphEvent event) { events << event }

        List<GraphEvent> of(String type) { events.findAll { it.type.name() == type } }
    }

    private static Throwable failureOf(Closure work) {
        try {
            work.call()
            return null
        } catch (Throwable t) {
            return t
        }
    }

    private final PollingConditions soon = new PollingConditions(timeout: 5)

    /** Run the graph to its failure and wait for the task's TASK_FAILED event. */
    private Throwable runToFailure(TaskGraph graph, Collector seen) {
        Throwable failure = failureOf { graph.start().get() }
        soon.eventually { assert seen.of('TASK_FAILED').size() == 1 }
        return failure
    }

    private static Map<String, Object> failedMetadata(Collector seen) {
        return seen.of('TASK_FAILED')[0].getTaskEvent().metadata
    }

    def "a ControlTask whose rule throws is tried once and says it will not be retried"() {
        given:
        TaskGraph graph = TaskGraph.build { }
        graph.ctx.globals.set('__workingMemory', [resultsByNode: [llm: [value: [count: 3]]]])
        ControlTask task = new ControlTask('decide', 'decide', graph.ctx)
        task.eventDispatcher = new DefaultTaskEventDispatcher(graph)
        task.rules = [[when: 'input.missing.size() > 0', action: 'CONTINUE']] as List<Map<String, Object>>
        task.inputNodeId = 'llm'
        task.retry { maxAttempts 3; initialDelay Duration.ofMillis(10) }
        graph.addTask(task)
        def seen = new Collector()
        graph.addListener(seen)

        when:
        Throwable failure = runToFailure(graph, seen)

        then:
        failure != null
        seen.of('TASK_RETRYING').isEmpty()
        seen.of('TASK_FAILED')[0].getTaskEvent().attemptNumber == 1
        failedMetadata(seen).willRetry == false
        failedMetadata(seen).notRetriedBecause == 'not-retriable'
        seen.of('TASK_FAILED')[0].getTaskEvent().isPermanentFailure()
    }

    def "a failure that retrying cannot fix says so, whatever the retry policy"() {
        given:
        AtomicInteger attempts = new AtomicInteger()
        def graph = TaskGraph.build {
            task('bad') {
                retry { maxAttempts 3; initialDelay Duration.ofMillis(10) }
                action { ctx, prev ->
                    attempts.incrementAndGet()
                    throw new IllegalArgumentException('wrong configuration')
                }
            }
        }
        def seen = new Collector()
        graph.addListener(seen)

        when:
        runToFailure(graph, seen)

        then:
        attempts.get() == 1
        failedMetadata(seen).willRetry == false
        failedMetadata(seen).notRetriedBecause == 'not-retriable'
        seen.of('TASK_FAILED')[0].getTaskEvent().isPermanentFailure()
    }

    def "a task that runs out of attempts says each retry is coming and that the last failure is final"() {
        given:
        AtomicInteger attempts = new AtomicInteger()
        def graph = TaskGraph.build {
            task('flaky') {
                retry { maxAttempts 2; initialDelay Duration.ofMillis(10) }
                action { ctx, prev ->
                    attempts.incrementAndGet()
                    throw new IOException('down')
                }
            }
        }
        def seen = new Collector()
        graph.addListener(seen)

        when:
        runToFailure(graph, seen)

        then:
        attempts.get() == 2
        seen.of('TASK_RETRYING').size() == 1
        seen.of('TASK_RETRYING')[0].getTaskEvent().metadata.willRetry == true
        failedMetadata(seen).willRetry == false
        failedMetadata(seen).notRetriedBecause == 'retries-exhausted'
        !seen.of('TASK_FAILED')[0].getTaskEvent().isPermanentFailure()
        failedMetadata(seen).retriesExhausted == true
    }

    def "a task with no retry policy says that is why it was not retried"() {
        given:
        def graph = TaskGraph.build {
            task('once') {
                action { ctx, prev -> throw new IOException('down') }
            }
        }
        def seen = new Collector()
        graph.addListener(seen)

        when:
        runToFailure(graph, seen)

        then:
        seen.of('TASK_RETRYING').isEmpty()
        failedMetadata(seen).willRetry == false
        failedMetadata(seen).notRetriedBecause == 'no-retries-configured'
    }

    // ------------------------------------------------------------------ the same signal on the exception

    /** TaskFailureSignal.of(failure), reached by name so that the spec compiles before the class exists. */
    private static Object signalOn(Throwable failure) {
        Class.forName('org.softwood.dag.task.TaskFailureSignal').of(failure)
    }

    private static boolean causedBy(Throwable t, Class type) {
        while (t != null) {
            if (type.isInstance(t)) return true
            t = t.cause
        }
        return false
    }

    def "the exception a graph fails with carries the signal, and is still the exception the task threw"() {
        given:
        def graph = TaskGraph.build {
            task('bad') {
                retry { maxAttempts 3; initialDelay Duration.ofMillis(10) }
                action { ctx, prev -> throw new IllegalArgumentException('wrong configuration') }
            }
        }

        when:
        Throwable failure = runToFailure(graph, attach(graph))
        def signal = signalOn(failure)

        then: 'the type the caller catches has not changed'
        causedBy(failure, IllegalArgumentException)

        and:
        signal != null
        signal.taskId == 'bad'
        signal.notRetriedBecause == 'not-retriable'
        signal.permanent
        signal.attempts == 1
    }

    def "the exception from a task that ran out of attempts says so and is not permanent"() {
        given:
        def graph = TaskGraph.build {
            task('flaky') {
                retry { maxAttempts 2; initialDelay Duration.ofMillis(10) }
                action { ctx, prev -> throw new IOException('down') }
            }
        }

        when:
        Throwable failure = runToFailure(graph, attach(graph))
        def signal = signalOn(failure)

        then:
        causedBy(failure, IOException)
        signal.taskId == 'flaky'
        signal.notRetriedBecause == 'retries-exhausted'
        !signal.permanent
        signal.attempts == 2
    }

    def "the exception from a task with no retry policy says that"() {
        given:
        def graph = TaskGraph.build {
            task('once') {
                action { ctx, prev -> throw new IOException('down') }
            }
        }

        when:
        Throwable failure = runToFailure(graph, attach(graph))

        then:
        causedBy(failure, IOException)
        signalOn(failure).notRetriedBecause == 'no-retries-configured'
        !signalOn(failure).permanent
    }

    def "a ControlTask failure is permanent on the exception too"() {
        given:
        TaskGraph graph = TaskGraph.build { }
        graph.ctx.globals.set('__workingMemory', [resultsByNode: [llm: [value: [count: 3]]]])
        ControlTask task = new ControlTask('decide', 'decide', graph.ctx)
        task.eventDispatcher = new DefaultTaskEventDispatcher(graph)
        task.rules = [[when: 'input.missing.size() > 0', action: 'CONTINUE']] as List<Map<String, Object>>
        task.inputNodeId = 'llm'
        graph.addTask(task)

        when:
        Throwable failure = runToFailure(graph, attach(graph))

        then:
        signalOn(failure).taskId == 'decide'
        signalOn(failure).permanent
    }

    def "an exception that no task failed with carries no signal"() {
        expect:
        signalOn(new IllegalStateException('nothing to do with a task')) == null
        signalOn(null) == null
    }

    private static Collector attach(TaskGraph graph) {
        def seen = new Collector()
        graph.addListener(seen)
        return seen
    }
}
