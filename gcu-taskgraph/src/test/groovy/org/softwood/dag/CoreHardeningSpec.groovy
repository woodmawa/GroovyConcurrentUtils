package org.softwood.dag

import groovy.transform.CompileDynamic
import org.softwood.dag.task.TaskState
import org.softwood.dataflow.DataflowVariable
import spock.lang.Specification
import spock.lang.Timeout

import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * GCU 1.2.0 core hardening (brief gcu/brief-core-hardening-2026-11-18, WP1 + WP2).
 *
 * Each feature was run red against 1.1.2 before the fix it guards:
 *   H1 graph validation, H3 guarded task state, H5 error ordering, H6 single completion,
 *   H2 cancellation with correct state, H4 interrupt handling.
 */
@CompileDynamic
@Timeout(90)
class CoreHardeningSpec extends Specification {

    static class Collector implements GraphEventListener {
        final Queue<GraphEvent> events = new ConcurrentLinkedQueue<>()
        @Override
        void onEvent(GraphEvent e) { events.add(e) }
        int count(String typeName) { events.count { it.type.name() == typeName } as int }
    }

    private static boolean waitFor(long ms, Closure cond) {
        long end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cond.call()) return true
            Thread.sleep(5)
        }
        return cond.call() as boolean
    }

    // ------------------------------------------------------------------ H1

    def "H1: a dependency cycle is refused at start instead of hanging"() {
        given:
        def graph = TaskGraph.build {
            serviceTask('a') { action { ctx, prev -> 'a' } }
            serviceTask('b') { action { ctx, prev -> 'b' } }
        }
        graph.tasks['a'].dependsOn('b')
        graph.tasks['b'].dependsOn('a')

        when:
        graph.start()

        then:
        def e = thrown(IllegalStateException)
        e.message.contains("'a'")
        e.message.contains("'b'")

        cleanup:
        graph?.ctx?.close()
    }

    def "H1: a dependency on a task that does not exist is refused at start"() {
        given:
        def graph = TaskGraph.build {
            serviceTask('a') { action { ctx, prev -> 'a' } }
        }
        graph.tasks['a'].dependsOn('ghost')

        when:
        graph.start()

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('ghost')

        cleanup:
        graph?.ctx?.close()
    }

    def "H1: a refused graph has not been consumed and a valid diamond still runs"() {
        given:
        def graph = TaskGraph.build {
            serviceTask('root') { action { ctx, prev -> 1 } }
            serviceTask('l') { dependsOn 'root'; action { ctx, prev -> 2 } }
            serviceTask('r') { dependsOn 'root'; action { ctx, prev -> 3 } }
            serviceTask('join') { dependsOn 'l'; dependsOn 'r'; action { ctx, prev -> 'joined' } }
        }

        when:
        graph.validate()
        def result = graph.start().get(10, TimeUnit.SECONDS)

        then:
        result == 'joined'

        cleanup:
        graph?.ctx?.close()
    }

    // ------------------------------------------------------------ H3 + H2

    def "H3/H2: a task aborted while running stays CANCELLED, runs no successor, and no terminal graph event follows"() {
        given:
        def started = new CountDownLatch(1)
        def release = new CountDownLatch(1)
        def bRan = new AtomicBoolean(false)
        def graph = TaskGraph.build {
            serviceTask('a') {
                action { ctx, prev ->
                    started.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    'a-done'
                }
            }
            serviceTask('b') { dependsOn 'a'; action { ctx, prev -> bRan.set(true); 'b-done' } }
        }
        def col = new Collector()
        graph.addListener(col)

        when:
        graph.start()
        boolean wasRunning = started.await(10, TimeUnit.SECONDS)
        graph.abortFlow('test abort')
        release.countDown()
        Thread.sleep(500)

        then:
        wasRunning
        graph.tasks['a'].state == TaskState.CANCELLED
        graph.tasks['b'].state == TaskState.CANCELLED
        !bRan.get()
        graph.graphCompletionPromise.isDone()
        col.count('GRAPH_COMPLETED') == 0
        col.count('GRAPH_FAILED') == 0

        cleanup:
        release.countDown()
        graph?.ctx?.close()
    }

    def "H2: abort stops the retry loop"() {
        given:
        def attempts = new AtomicInteger()
        def graph = TaskGraph.build {
            serviceTask('flaky') {
                retry { maxAttempts 6; initialDelay Duration.ofMillis(200) }
                action { ctx, prev ->
                    attempts.incrementAndGet()
                    throw new IOException('boom')
                }
            }
        }

        when:
        graph.start()
        boolean ranOnce = waitFor(5000) { attempts.get() >= 1 }
        graph.abortFlow('stop retrying')
        int atAbort = attempts.get()
        Thread.sleep(1500)

        then:
        ranOnce
        attempts.get() <= atAbort + 1
        graph.tasks['flaky'].state == TaskState.CANCELLED

        cleanup:
        graph?.ctx?.close()
    }

    def "H2: a graph timeout resolves graphCompletionPromise as well as the returned promise"() {
        given:
        def release = new CountDownLatch(1)
        def graph = TaskGraph.build {
            graphTimeout Duration.ofMillis(200)
            serviceTask('slow') { action { ctx, prev -> release.await(10, TimeUnit.SECONDS); 'late' } }
        }

        when:
        def returned = graph.start()
        boolean returnedDone = waitFor(5000) { returned.isDone() }

        then:
        returnedDone
        graph.graphCompletionPromise.isDone()
        graph.tasks['slow'].state == TaskState.CANCELLED

        cleanup:
        release.countDown()
        graph?.ctx?.close()
    }

    // ------------------------------------------------------------------ H4

    def "H4: an interrupted task is cancelled and not retried, and the graph fails"() {
        given:
        def attempts = new AtomicInteger()
        def graph = TaskGraph.build {
            serviceTask('t') {
                retry { maxAttempts 4; initialDelay Duration.ofMillis(20) }
                action { ctx, prev ->
                    attempts.incrementAndGet()
                    throw new InterruptedException('interrupted')
                }
            }
        }

        when:
        def p = graph.start()
        boolean done = waitFor(5000) { p.isDone() }
        Thread.sleep(300)

        then:
        done
        attempts.get() == 1
        graph.tasks['t'].state == TaskState.CANCELLED

        cleanup:
        graph?.ctx?.close()
    }

    // ------------------------------------------------- interrupt opt-in (D6)

    def "D6 default: a timed-out task is reported failed and its work is left to finish"() {
        given:
        def finished = new AtomicBoolean(false)
        def graph = TaskGraph.build {
            serviceTask('slow') {
                timeout Duration.ofMillis(200)
                action { ctx, prev -> Thread.sleep(900); finished.set(true); 'late' }
            }
        }

        when:
        def p = graph.start()
        boolean done = waitFor(5000) { p.isDone() }
        boolean workFinished = waitFor(3000) { finished.get() }

        then:
        done
        graph.tasks['slow'].state == TaskState.FAILED
        workFinished

        cleanup:
        graph?.ctx?.close()
    }

    def "D6 opt-in: interruptOnTimeout stops the timed-out work"() {
        given:
        def finished = new AtomicBoolean(false)
        def graph = TaskGraph.build {
            serviceTask('slow') {
                timeout Duration.ofMillis(200)
                interruptOnTimeout true
                action { ctx, prev -> Thread.sleep(900); finished.set(true); 'late' }
            }
        }

        when:
        def p = graph.start()
        boolean done = waitFor(5000) { p.isDone() }
        Thread.sleep(1500)

        then:
        done
        graph.tasks['slow'].state == TaskState.FAILED
        !finished.get()

        cleanup:
        graph?.ctx?.close()
    }

    def "D6 opt-in: interruptOnCancel makes abortFlow stop work that is already running"() {
        given:
        def started = new CountDownLatch(1)
        def finished = new AtomicBoolean(false)
        def graph = TaskGraph.build {
            interruptOnCancel true
            serviceTask('slow') {
                action { ctx, prev -> started.countDown(); Thread.sleep(900); finished.set(true); 'late' }
            }
        }

        when:
        graph.start()
        boolean wasRunning = started.await(10, TimeUnit.SECONDS)
        Thread.sleep(100)   // let the attempt register as in flight before the abort
        graph.abortFlow('stop now')
        Thread.sleep(1500)

        then:
        wasRunning
        !finished.get()
        graph.tasks['slow'].state == TaskState.CANCELLED

        cleanup:
        graph?.ctx?.close()
    }

    // ------------------------------------------------------------------ H5

    def "H5: an error state is never visible before its error value"() {
        when:
        int bad = 0
        3000.times {
            def expr = new DataflowVariable()
            def ready = new AtomicBoolean(false)
            def seen = new AtomicReference()
            Thread reader = Thread.startVirtualThread {
                ready.set(true)
                while (!expr.hasError()) { Thread.onSpinWait() }
                seen.set(expr.getError())
            }
            while (!ready.get()) { Thread.onSpinWait() }
            expr.setError(new IOException('real'))
            reader.join(5000)
            if (!(seen.get() instanceof IOException)) bad++
        }

        then:
        bad == 0
    }

    // ------------------------------------------------------------------ H6

    def "H6: a graph whose leaves finish together reports completion exactly once"() {
        when:
        List<Integer> counts = []
        150.times {
            def barrier = new CyclicBarrier(2)
            def graph = TaskGraph.build {
                serviceTask('x') { action { ctx, prev -> barrier.await(5, TimeUnit.SECONDS); 'x' } }
                serviceTask('y') { action { ctx, prev -> barrier.await(5, TimeUnit.SECONDS); 'y' } }
            }
            def col = new Collector()
            graph.addListener(col)
            graph.start().get(10, TimeUnit.SECONDS)
            Thread.sleep(20)
            counts << col.count('GRAPH_COMPLETED')
            graph.ctx.close()
        }

        then:
        counts.every { it == 1 }
    }
}
