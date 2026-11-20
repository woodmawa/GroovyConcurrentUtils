package org.softwood

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import groovy.json.JsonOutput
import groovy.transform.CompileDynamic
import org.slf4j.LoggerFactory
import org.softwood.actor.ActorSystem
import org.softwood.dag.GraphEvent
import org.softwood.dag.GraphEventListener
import org.softwood.dag.TaskGraph
import org.softwood.dag.task.TaskContext
import org.softwood.dataflow.DataflowFactory
import org.softwood.dataflow.DataflowVariable
import org.softwood.dataflow.Dataflows
import org.softwood.pool.ConcurrentPool
import org.softwood.promise.core.dataflow.DataflowPromise
import org.softwood.promise.core.dataflow.DataflowPromiseFactory
import org.softwood.security.JwtTokenService
import spock.lang.Specification
import spock.lang.Timeout
import spock.lang.Unroll

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Function
import java.util.function.Supplier

/**
 * GCU 1.2.2 hardening (brief gcu/brief-core-hardening-2026-11-18 rev 5, release 1).
 *
 * Each feature was run red against 1.2.1 before the fix it guards:
 *   M3 builder config, M4 lazy caches, DF7 one promise state, DF9 dependents pruned,
 *   DF11 listener after pool shutdown, DF12 read timeouts, DF13 a task's own InterruptedException,
 *   DF15 remaining task overloads, A5 mailbox resize on a running actor, S3 JWT with no exp,
 *   M1 opt-in result redaction.
 */
@CompileDynamic
@Timeout(60)
class Hardening122Spec extends Specification {

    private static boolean waitFor(long ms, Closure cond) {
        long end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cond.call()) return true
            Thread.sleep(10)
        }
        return cond.call() as boolean
    }

    // ------------------------------------------------------------------ TaskContext

    def "M3: the config given to TaskContext.builder() is the context's config"() {
        given:
        def pool = new ConcurrentPool('h122-m3')

        when:
        def ctx = TaskContext.builder().pool(pool).config([h122Marker: 'mine']).build()

        then:
        ctx.config.h122Marker == 'mine'

        cleanup:
        pool?.shutdown()
    }

    def "M4: each lazy cache on a TaskContext is created once when first used from several threads"() {
        given:
        def pool = new ConcurrentPool('h122-m4')
        def factory = new DataflowPromiseFactory(pool)
        int threads = 8
        def exec = Executors.newFixedThreadPool(threads)
        int splits = 0

        when:
        200.times {
            def ctx = new TaskContext(pool, factory)
            def barrier = new CyclicBarrier(threads)
            def futures = (1..threads).collect {
                exec.submit({
                    barrier.await()
                    [System.identityHashCode(ctx.getMemoCache()),
                     System.identityHashCode(ctx.getIdempotencyCache()),
                     System.identityHashCode(ctx.getDeadLetterQueue())]
                } as Callable)
            }
            def seen = futures.collect { it.get(10, TimeUnit.SECONDS) }
            (0..2).each { int i ->
                if (seen.collect { it[i] }.unique().size() > 1) splits++
            }
        }

        then:
        splits == 0

        cleanup:
        exec?.shutdownNow()
        pool?.shutdown()
    }

    // ------------------------------------------------------------------ dataflow promise

    def "DF7: a promise does not report done before its variable is bound"() {
        given:
        def pool = new ConcurrentPool('h122-df7')
        def holder = new AtomicReference<DataflowPromise>()
        def doneInsideBind = new AtomicReference<Boolean>()
        def doneInsideBindError = new AtomicReference<Boolean>()
        def okVar = new DataflowVariable(pool) {
            @Override
            void bind(Object v) {
                doneInsideBind.set(holder.get().isDone())
                super.bind(v)
            }
        }
        def okPromise = new DataflowPromise(okVar)
        def badVar = new DataflowVariable(pool) {
            @Override
            DataflowVariable bindError(Throwable t) {
                doneInsideBindError.set(holder.get().isDone())
                return super.bindError(t)
            }
        }
        def badPromise = new DataflowPromise(badVar)

        when:
        holder.set(okPromise)
        okPromise.accept('v')
        holder.set(badPromise)
        badPromise.fail(new IllegalStateException('x'))

        then:
        doneInsideBind.get() == false
        doneInsideBindError.get() == false
        okPromise.isDone() && okPromise.isCompleted() && okPromise.get() == 'v'
        badPromise.isDone() && badPromise.isFailed()

        cleanup:
        pool?.shutdown()
    }

    def "DF9: a derived promise that has completed is no longer held by the promise it came from"() {
        given:
        def pool = new ConcurrentPool('h122-df9')
        def source = new DataflowPromise(new DataflowVariable(pool))

        when:
        def derived = (1..50).collect { source.then { it } }
        derived.each { it.cancel(false) }

        then:
        waitFor(3000) { source.@dependents.size() == 0 }

        cleanup:
        pool?.shutdown()
    }

    def "DF13: a task's own InterruptedException does not mark the thread reading the promise as interrupted"() {
        given:
        def pool = new ConcurrentPool('h122-df13')
        def variable = new DataflowVariable(pool)
        def promise = new DataflowPromise(variable)
        variable.bindError(new InterruptedException('the task was interrupted'))

        when:
        Throwable seen = null
        try {
            promise.get()
        } catch (Throwable t) {
            seen = t
        }
        boolean flagged = Thread.interrupted()      // reads and clears
        Throwable seenTimed = null
        try {
            promise.get(1, TimeUnit.SECONDS)
        } catch (Throwable t) {
            seenTimed = t
        }
        boolean flaggedTimed = Thread.interrupted()

        then:
        !flagged
        !flaggedTimed
        seen instanceof RuntimeException
        seen.cause instanceof InterruptedException
        seenTimed instanceof RuntimeException
        seenTimed.cause instanceof InterruptedException

        cleanup:
        Thread.interrupted()
        pool?.shutdown()
    }

    // ------------------------------------------------------------------ dataflow variable / factory

    def "DF11: a listener that is due, or is registered, after the pool has shut down still runs"() {
        given:
        def pool = new ConcurrentPool('h122-df11')
        def variable = new DataflowVariable(pool)
        def ranQueued = new CountDownLatch(1)
        def ranLate = new CountDownLatch(1)
        variable.whenBound { ranQueued.countDown() }
        pool.shutdown()

        when:
        variable.bind(1)
        variable.whenBound { ranLate.countDown() }

        then:
        ranQueued.await(2, TimeUnit.SECONDS)
        ranLate.await(2, TimeUnit.SECONDS)
    }

    def "DF12: reading a Dataflows name that nobody binds times out when a read timeout is set"() {
        given:
        def pool = new ConcurrentPool('h122-df12a')
        def df = new Dataflows(new DataflowFactory(pool))
        def outcome = new AtomicReference()

        when:
        Thread.startDaemon {
            try {
                df.readTimeout = Duration.ofMillis(200)
                outcome.set(df.mistyped)
            } catch (Throwable t) {
                outcome.set(t)
            }
        }

        then:
        waitFor(3000) { outcome.get() != null }
        outcome.get() instanceof TimeoutException
        outcome.get().message.contains('mistyped')
    }

    def "DF12: a method call forwarded to an unbound DataflowVariable times out when a read timeout is set"() {
        given:
        def pool = new ConcurrentPool('h122-df12b')
        def variable = new DataflowVariable(pool)
        def outcome = new AtomicReference()

        when:
        Thread.startDaemon {
            try {
                variable.readTimeout = Duration.ofMillis(200)
                outcome.set(variable.toUpperCase())
            } catch (Throwable t) {
                outcome.set(t)
            }
        }

        then:
        waitFor(3000) { outcome.get() != null }
        outcome.get() instanceof TimeoutException
    }

    @Unroll
    def "DF15: cancel(true) interrupts work started through #kind"() {
        given:
        def pool = new ConcurrentPool('h122-df15')
        def factory = new DataflowFactory(pool)
        def started = new CountDownLatch(1)
        def interrupted = new CountDownLatch(1)
        def body = { ->
            started.countDown()
            try {
                Thread.sleep(4000)
            } catch (InterruptedException e) {
                interrupted.countDown()
                throw e
            }
            'done'
        }
        DataflowVariable variable = starter.call(factory, pool, body)
        def promise = new DataflowPromise(variable)

        when:
        boolean began = started.await(2, TimeUnit.SECONDS)
        promise.cancel(true)

        then:
        began
        interrupted.await(2, TimeUnit.SECONDS)
        promise.isCancelled()

        where:
        kind                              | starter
        'DataflowFactory.task(Callable)'  | { f, p, b -> f.task(b as Callable) }
        'DataflowFactory.task(Supplier)'  | { f, p, b -> f.task({ b.call() } as Supplier) }
        'DataflowFactory.task(Runnable)'  | { f, p, b -> f.task({ b.call() } as Runnable) }
        'DataflowFactory.task(Function)'  | { f, p, b -> f.task({ x -> b.call() } as Function, 1) }
        'DataflowVariable.task(Closure)'  | { f, p, b -> new DataflowVariable(p).task(b) }
        'DataflowVariable.task(Callable)' | { f, p, b -> new DataflowVariable(p).task(b as Callable) }
        'DataflowVariable.task(Supplier)' | { f, p, b -> new DataflowVariable(p).task({ b.call() } as Supplier) }
    }

    @Unroll
    def "DF15: work that fails after its variable was completed elsewhere raises no second exception (#kind)"() {
        given:
        def pool = new ConcurrentPool('h122-df15b')
        def factory = new DataflowFactory(pool)
        def gate = new CountDownLatch(1)
        def body = { ->
            gate.await(5, TimeUnit.SECONDS)
            throw new IllegalStateException('late failure')
        }
        DataflowVariable variable = starter.call(factory, pool, body)

        when:
        variable.bindCancelled()
        gate.countDown()
        Future handle = variable.@taskFuture

        then: 'the work is tracked'
        handle != null

        when: 'it ends: its own failure went nowhere, because the variable was already complete'
        handle.get(3, TimeUnit.SECONDS)

        then:
        noExceptionThrown()
        variable.isBound()

        where:
        kind                             | starter
        'DataflowFactory.task(Callable)' | { f, p, b -> f.task(b as Callable) }
        'DataflowVariable.task(Closure)' | { f, p, b -> new DataflowVariable(p).task(b) }
    }

    // ------------------------------------------------------------------ actors

    def "A5: an actor keeps processing after its mailbox size is changed while it is running"() {
        given:
        def system = new ActorSystem('h122-a5')
        def actor = system.actor('resized') { msg, ctx -> 'pong' }
        actor.askSync('warm', Duration.ofSeconds(2))
        Thread.sleep(100)      // the loop is now blocked waiting on the mailbox

        when:
        actor.setMaxMailboxSize(10)
        def reply
        try {
            reply = actor.askSync('ping', Duration.ofSeconds(2))
        } catch (Throwable t) {
            reply = t
        }

        then:
        reply == 'pong'
        actor.maxMailboxSize == 10

        cleanup:
        try {
            actor?.stopNow()
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ security

    def "S3: a correctly signed token with no exp claim is rejected as an invalid token"() {
        given:
        String secret = 'h122-secret-h122-secret-h122-secret-0123'
        def service = new JwtTokenService(secret)
        def enc = { byte[] b -> Base64.urlEncoder.withoutPadding().encodeToString(b) }
        def signed = { Map claims ->
            String head = enc('{"alg":"HS256","typ":"JWT"}'.getBytes('UTF-8'))
            String body = enc(JsonOutput.toJson(claims).getBytes('UTF-8'))
            def mac = Mac.getInstance('HmacSHA256')
            mac.init(new SecretKeySpec(secret.getBytes('UTF-8'), 'HmacSHA256'))
            String sig = enc(mac.doFinal((head + '.' + body).getBytes('UTF-8')))
            head + '.' + body + '.' + sig
        }
        long future = (System.currentTimeMillis().intdiv(1000) as long) + 3600L

        and: 'the forged signature is good: the same token with an exp validates'
        assert service.validateToken(signed([sub: 'x', roles: ['USER'], iat: 1, exp: future])).subject == 'x'

        when:
        service.validateToken(signed([sub: 'x', roles: ['USER'], iat: 1]))

        then:
        thrown(JwtTokenService.InvalidTokenException)
    }

    // ------------------------------------------------------------------ redaction

    def "M1: with no redactor a completion event's redactedResult is the result itself"() {
        given:
        def value = [token: 'plain']
        def events = new CopyOnWriteArrayList<GraphEvent>()
        def graph = TaskGraph.build {
            serviceTask('a') {
                action { ctx, prev -> ctx.promiseFactory.createPromise(value) }
            }
        }
        graph.addListener({ GraphEvent e -> events << e } as GraphEventListener)

        when:
        graph.start().get()
        def done = events.collect { it.getTaskEvent() }.find { it?.taskId == 'a' && it.result != null }

        then:
        done != null
        done.result == value
        done.redactedResult.is(done.result)
    }

    def "M1: a redactor on the context is used for logs and for redactedResult, and TaskEvent.result is unchanged"() {
        given:
        String secret = 'h122-SECRET-VALUE'
        def events = new CopyOnWriteArrayList<GraphEvent>()
        def graph = TaskGraph.build {
            serviceTask('a') {
                action { ctx, prev -> ctx.promiseFactory.createPromise([token: secret]) }
            }
            serviceTask('b') {
                dependsOn 'a'
                action { ctx, prev -> ctx.promiseFactory.createPromise('ok') }
            }
        }
        graph.ctx.resultRedactor = { String taskId, Object v -> v instanceof Map ? '[REDACTED]' : v }
        graph.addListener({ GraphEvent e -> events << e } as GraphEventListener)

        and: 'everything the library logs at debug is captured'
        Logger logger = LoggerFactory.getLogger('org.softwood') as Logger
        Level before = logger.level
        def appender = new ListAppender<ILoggingEvent>()
        appender.start()
        logger.level = Level.DEBUG
        logger.addAppender(appender)

        when:
        graph.start().get()
        Thread.sleep(200)
        def done = events.collect { it.getTaskEvent() }.find { it?.taskId == 'a' && it.result != null }
        def leaked = new ArrayList(appender.list).findAll { it.formattedMessage.contains(secret) }

        then:
        done != null
        done.result == [token: secret]
        done.redactedResult == '[REDACTED]'
        leaked.collect { it.loggerName + ': ' + it.formattedMessage } == []

        cleanup:
        logger?.detachAppender(appender)
        logger?.level = before
    }

    def "M1: a redactor that throws hides the value instead of letting it through"() {
        given:
        def pool = new ConcurrentPool('h122-m1')
        def ctx = new TaskContext(pool)
        ctx.resultRedactor = { v -> throw new IllegalStateException('broken redactor') }

        expect:
        ctx.redact('t', 'sensitive') == '[REDACTED]'

        cleanup:
        pool?.shutdown()
    }
}
