package org.softwood.actor

import org.softwood.actor.remote.RemoteActorRef
import org.softwood.actor.remote.RemotingTransport
import spock.lang.AutoCleanup
import spock.lang.Specification

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 2.0.0, WP9: one meaning for each way of sending, local and remote.
 * ask returns a CompletableFuture; askSync and << wait for the reply; >> sends without waiting.
 */
class Hardening200ActorSpec extends Specification {

    @AutoCleanup('shutdown')
    ActorSystem system = new ActorSystem('wp9')

    private Actor echo(String name = 'echo') {
        system.actor {
            delegate.name name
            onMessage { msg, ctx -> ctx.reply("echo: $msg".toString()) }
        }
    }

    def "the Actor interface declares ask as returning a CompletableFuture"() {
        expect:
        Actor.getMethod('ask', Object, Duration).returnType == CompletableFuture
    }

    def "a local ask returns a future that completes with the reply"() {
        given:
        def actor = echo()

        when:
        def answer = actor.ask('hi', Duration.ofSeconds(2))

        then:
        answer instanceof CompletableFuture
        (answer as CompletableFuture).get(2, TimeUnit.SECONDS) == 'echo: hi'
    }

    def "a local ask does not wait for the handler"() {
        given:
        def gate = new CountDownLatch(1)
        def actor = system.actor {
            name 'slow'
            onMessage { msg, ctx -> gate.await(5, TimeUnit.SECONDS); ctx.reply('done') }
        }

        when:
        long t0 = System.nanoTime()
        def future = actor.ask('go', Duration.ofSeconds(5))
        long tookMs = (System.nanoTime() - t0).intdiv(1_000_000)

        then:
        tookMs < 1000
        !(future as CompletableFuture).isDone()

        when:
        gate.countDown()

        then:
        (future as CompletableFuture).get(5, TimeUnit.SECONDS) == 'done'
    }

    def "a local ask that gets no reply in time completes with a TimeoutException"() {
        given:
        def actor = system.actor {
            name 'silent'
            onMessage { msg, ctx -> ctx.deferReply() }
        }

        when:
        (actor.ask('anyone?', Duration.ofMillis(200)) as CompletableFuture).get(5, TimeUnit.SECONDS)

        then:
        def e = thrown(ExecutionException)
        e.cause instanceof TimeoutException
    }

    def "askSync and << still wait and return the reply"() {
        given:
        def actor = echo()

        expect:
        actor.askSync('a', Duration.ofSeconds(2)) == 'echo: a'
        (actor << 'b') == 'echo: b'
    }

    def ">> sends without waiting and returns the actor, so sends can be chained"() {
        given:
        def gate = new CountDownLatch(1)
        def seen = Collections.synchronizedList([])
        def actor = system.actor {
            name 'collector'
            onMessage { msg, ctx -> gate.await(5, TimeUnit.SECONDS); seen << msg }
        }

        when:
        long t0 = System.nanoTime()
        def back = actor >> 'one' >> 'two'
        long tookMs = (System.nanoTime() - t0).intdiv(1_000_000)

        then:
        back.is(actor)
        tookMs < 1000
        seen.isEmpty()

        when:
        gate.countDown()

        then:
        new spock.util.concurrent.PollingConditions(timeout: 5).eventually { assert seen == ['one', 'two'] }
    }

    def "a remote reference follows the same rules: ask is a future, >> is a tell, << waits"() {
        given:
        def told = []
        def transport = Stub(RemotingTransport) {
            ask(_, _, _) >> { String uri, Object msg, Duration t -> CompletableFuture.completedFuture("remote: $msg".toString()) }
            tell(_, _) >> { String uri, Object msg -> told << msg }
        }
        def ref = new RemoteActorRef('http://localhost:1/sys/x', transport)

        expect:
        ref.ask('q', Duration.ofSeconds(1)).get(1, TimeUnit.SECONDS) == 'remote: q'
        (ref << 'w') == 'remote: w'
        (ref >> 'f').is(ref)
        told == ['f']
    }
}
