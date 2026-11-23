package org.softwood.dataflow

import groovy.transform.CompileDynamic
import org.softwood.pool.ConcurrentPool
import org.softwood.promise.core.dataflow.DataflowPromise
import org.softwood.promise.core.dataflow.DataflowPromiseFactory
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Timeout

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GCU 1.2.0 dataflow integrity (review addendum 2026-11-18, findings DF1-DF5).
 *
 * TaskGraph runs entirely on DataflowPromise / DataflowVariable: a callback that is never
 * delivered, or a get() that never returns, is a node that never finishes. Each feature was
 * run red against 1.1.2 before its fix.
 */
@CompileDynamic
@Timeout(180)
class DataflowIntegritySpec extends Specification {

    @Shared ConcurrentPool pool = new ConcurrentPool('dataflow-integrity-spec')

    def cleanupSpec() {
        pool?.shutdown()
    }

    def "DF1: a listener registered while the variable is being bound is always delivered"() {
        when:
        int lost = 0
        20000.times {
            def dfv = new DataflowVariable(pool)
            def delivered = new CountDownLatch(1)
            def go = new AtomicBoolean(false)
            Thread binder = Thread.startVirtualThread {
                while (!go.get()) { Thread.onSpinWait() }
                dfv.bind(1)
            }
            Thread registrar = Thread.startVirtualThread {
                while (!go.get()) { Thread.onSpinWait() }
                dfv.whenBound { v -> delivered.countDown() }
            }
            go.set(true)
            binder.join(5000)
            registrar.join(5000)
            if (!delivered.await(500, TimeUnit.MILLISECONDS)) lost++
        }

        then:
        lost == 0
    }

    def "DF2: get() racing accept() on a promise always returns the value"() {
        when:
        int stuck = 0
        20000.times {
            def promise = new DataflowPromise(new DataflowVariable(pool))
            def go = new AtomicBoolean(false)
            Thread completer = Thread.startVirtualThread {
                while (!go.get()) { Thread.onSpinWait() }
                promise.accept(42)
            }
            go.set(true)
            try {
                if (promise.get(500, TimeUnit.MILLISECONDS) != 42) stuck++
            } catch (TimeoutException ignored) {
                stuck++
            }
            completer.join(5000)
        }

        then:
        stuck == 0
    }

    def "DF3: cancel(true) on an executeAsync promise interrupts the running work"() {
        given:
        def factory = new DataflowPromiseFactory(pool)
        def started = new CountDownLatch(1)
        def finished = new AtomicBoolean(false)

        when:
        def promise = factory.executeAsync {
            started.countDown()
            Thread.sleep(800)
            finished.set(true)
            'late'
        }
        boolean running = started.await(5, TimeUnit.SECONDS)
        boolean cancelled = promise.cancel(true)
        Thread.sleep(1200)

        then:
        running
        cancelled
        promise.isCancelled()
        !finished.get()
    }

    def "DF3: cancel(false) leaves the running work to finish"() {
        given:
        def factory = new DataflowPromiseFactory(pool)
        def started = new CountDownLatch(1)
        def finished = new AtomicBoolean(false)

        when:
        def promise = factory.executeAsync {
            started.countDown()
            Thread.sleep(300)
            finished.set(true)
            'late'
        }
        started.await(5, TimeUnit.SECONDS)
        promise.cancel(false)
        Thread.sleep(800)

        then:
        promise.isCancelled()
        finished.get()
    }

    def "DF4: the process-wide listener error list is bounded"() {
        given:
        DataflowExpression.clearListenerErrors()

        when:
        300.times {
            def dfv = new DataflowVariable(pool)
            dfv.whenBound { v -> throw new IllegalStateException('listener failure') }
            dfv.bind(it)
        }
        Thread.sleep(500)

        then:
        DataflowExpression.getListenerErrors().size() > 0
        DataflowExpression.getListenerErrors().size() <= 100

        cleanup:
        DataflowExpression.clearListenerErrors()
    }

    def "DF5: an error on a source variable wired through Dataflows is not turned into a null value"() {
        given:
        def df = new Dataflows(new DataflowFactory(pool))
        def source = new DataflowVariable(pool)

        when:
        df.target = source
        source.bindError(new IOException('source failed'))
        Thread.sleep(300)
        def target = df.getDataflowVariable('target')

        then:
        target.isBound()
        target.hasError()
        target.getError() instanceof IOException
    }
}
