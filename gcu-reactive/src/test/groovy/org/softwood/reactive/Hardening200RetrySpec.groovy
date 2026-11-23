package org.softwood.reactive

import org.reactivestreams.Subscription
import reactor.core.publisher.BaseSubscriber
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

/**
 * 2.0.0, WP9: ErrorMode.RETRY retries a publish that no subscriber could take,
 * instead of only logging that no retry exists.
 */
class Hardening200RetrySpec extends Specification {

    /** A subscriber that asks for nothing until told to. */
    static class Held extends BaseSubscriber<String> {
        final List<String> got = Collections.synchronizedList([])
        @Override protected void hookOnSubscribe(Subscription s) { /* no demand yet */ }
        @Override protected void hookOnNext(String v) { got << v }
    }

    static class Recording extends DefaultErrorHandlingStrategy {
        final List<String> calls = Collections.synchronizedList([])
        @Override void onError(Throwable t, Object item, ErrorMode mode) { calls << "${mode}:${item}".toString() }
    }

    def "with RETRY, a publish no subscriber could take is retried and arrives once demand appears"() {
        given:
        def topic = new DataflowTopic<String>()
        topic.errorMode = ErrorMode.RETRY
        topic.maxRetries = 5
        def strategy = new Recording()
        topic.errorStrategy = strategy
        def sub = new Held()
        topic.stream().subscribe(sub)
        Thread.start { Thread.sleep(150); sub.request(1) }

        when:
        topic.publish('late')

        then:
        new PollingConditions(timeout: 3).eventually { assert sub.got == ['late'] }
        strategy.calls.isEmpty()

        cleanup:
        sub.dispose()
    }

    def "with RETRY, a publish that still cannot be taken after maxRetries goes to the strategy once"() {
        given:
        def topic = new DataflowTopic<String>()
        topic.errorMode = ErrorMode.RETRY
        topic.maxRetries = 2
        def strategy = new Recording()
        topic.errorStrategy = strategy
        def sub = new Held()
        topic.stream().subscribe(sub)

        when:
        topic.publish('never')

        then:
        strategy.calls == ['RETRY:never']
        sub.got.isEmpty()

        cleanup:
        sub.dispose()
    }

    def "without RETRY, the same publish is not retried (DEFAULT hands it to the strategy at once)"() {
        given:
        def topic = new DataflowTopic<String>()
        def strategy = new Recording()
        topic.errorStrategy = strategy
        def sub = new Held()
        topic.stream().subscribe(sub)
        Thread.start { Thread.sleep(150); sub.request(1) }

        when:
        topic.publish('lost')
        Thread.sleep(400)

        then:
        strategy.calls == ['DEFAULT:lost']
        sub.got.isEmpty()

        cleanup:
        sub.dispose()
    }

    def "a publish no subscriber could take does not end the topic: later events still arrive"() {
        given:
        def topic = new DataflowTopic<String>()
        def strategy = new Recording()
        topic.errorStrategy = strategy
        def sub = new Held()
        topic.stream().subscribe(sub)

        when: 'the first event finds no demand'
        topic.publish('dropped')
        sub.request(1)
        topic.publish('kept')

        then:
        new PollingConditions(timeout: 3).eventually { assert sub.got == ['kept'] }
        strategy.calls == ['DEFAULT:dropped']

        cleanup:
        sub.dispose()
    }

    def "the default strategy no longer says that no retry is implemented"() {
        given:
        def src = DefaultErrorHandlingStrategy.getResourceAsStream('DefaultErrorHandlingStrategy.class')?.text ?: ''

        expect: 'the log message is about exhausted retries'
        !src.contains('no retry handler implemented')
    }
}
