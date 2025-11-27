package org.softwood.dataflow.queue

import org.junit.jupiter.api.Test
import reactor.test.StepVerifier
import reactor.test.scheduler.VirtualTimeScheduler

import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

class DataflowTopicTest {

    @Test
    void testSingleSubscriberReceivesEvents() {
        def topic = new DataflowTopic<String>()

        StepVerifier.create(topic.stream().take(2))
                .then { topic.publish("A") }
                .then { topic.publish("B") }
                .expectNext("A", "B")
                .verifyComplete()
    }

    @Test
    void testMultiSubscriberBroadcast() {
        def topic = new DataflowTopic<Integer>()

        // We’re testing pure broadcast, not timeout/retry here.
        topic.timeout = Duration.ofDays(1)
        topic.maxRetries = 0

        def results1 = new CopyOnWriteArrayList<Integer>()
        def results2 = new CopyOnWriteArrayList<Integer>()

        // Attach two simultaneous subscribers
        def sub1 = topic.stream().subscribe { results1 << it }
        def sub2 = topic.stream().subscribe { results2 << it }

        // Publish events AFTER subscription
        topic.publish(1)
        topic.publish(2)
        topic.publish(3)
        topic.complete()

        // Block until topic completes
        StepVerifier.create(topic.awaitCompletion())
                .verifyComplete()

        assert results1 == [1, 2, 3]
        assert results2 == [1, 2, 3]
    }

    @Test
    void testLateSubscriberOnlyGetsNewEvents() {
        def topic = new DataflowTopic<String>()

        // Publish old events before subscription
        topic.publish("old-1")
        topic.publish("old-2")

        StepVerifier.create(topic.stream().take(2))
                .then { topic.publish("new-1") }
                .then { topic.publish("new-2") }
                .expectNext("new-1", "new-2")
                .verifyComplete()
    }

    @Test
    void testCompletionEvent() {
        def topic = new DataflowTopic<String>()

        StepVerifier.create(topic.stream())
                .then { topic.publish("X") }
                .then { topic.complete() }
                .expectNext("X")
                .verifyComplete()

        // Emits after complete must have no visible effect
        topic.publish("ignored")
    }

    @Test
    void testPublishOperator() {
        def topic = new DataflowTopic<String>()

        StepVerifier.create(topic.stream().take(2))
                .then { topic << "hello" }
                .then { topic << "world" }
                .expectNext("hello", "world")
                .verifyComplete()
    }

    @Test
    void testTimeoutAndRetry() {
        // Let StepVerifier manage VirtualTimeScheduler; do NOT call getOrSet() globally
        StepVerifier.withVirtualTime({
            def topic = new DataflowTopic<String>()
            topic.timeout = Duration.ofMillis(200)
            topic.maxRetries = 1
            topic.stream().take(1)
        })
                .thenAwait(Duration.ofMillis(600)) // timeout + retry + completion
                .verifyComplete()
    }
}
