package org.softwood.reactive

import org.junit.jupiter.api.Test
import reactor.test.StepVerifier

import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tests for DataflowTopic using the new external ErrorHandlingStrategy model.
 *
 * NOTE:
 * - No test uses the internal emission handler anymore
 * - All tests explicitly set `errorStrategy` where needed
 * - DefaultErrorHandlingStrategy is used automatically unless overridden
 */
class DataflowTopicTest {

    @Test
    void testSingleSubscriberReceivesEvents() {
        def topic = new DataflowTopic<String>()
        topic.errorStrategy = new DefaultErrorHandlingStrategy()   // NEW

        StepVerifier.create(topic.stream().take(2))
                .then { topic.publish("A") }
                .then { topic.publish("B") }
                .expectNext("A", "B")
                .verifyComplete()
    }

    @Test
    void testMultiSubscriberBroadcast() {
        def topic = new DataflowTopic<Integer>()
        topic.errorStrategy = new DefaultErrorHandlingStrategy()   // NEW

        // pure broadcast: disable timeout and retry influence
        topic.timeout = Duration.ofDays(1)
        topic.maxRetries = 0

        def results1 = new CopyOnWriteArrayList<Integer>()
        def results2 = new CopyOnWriteArrayList<Integer>()

        topic.stream().subscribe { results1 << it }
        topic.stream().subscribe { results2 << it }

        topic.publish(1)
        topic.publish(2)
        topic.publish(3)
        topic.complete()

        StepVerifier.create(topic.awaitCompletion())
                .verifyComplete()

        assert results1 == [1, 2, 3]
        assert results2 == [1, 2, 3]
    }

    @Test
    void testLateSubscriberOnlyGetsNewEvents() {
        def topic = new DataflowTopic<String>()
        topic.errorStrategy = new DefaultErrorHandlingStrategy()   // NEW

        // publish BEFORE subscription → should be lost (hot topic)
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
        topic.errorStrategy = new DefaultErrorHandlingStrategy()   // NEW

        StepVerifier.create(topic.stream())
                .then { topic.publish("X") }
                .then { topic.complete() }
                .expectNext("X")
                .verifyComplete()

        // Emits after complete must have no effect
        topic.publish("ignored")
    }

    @Test
    void testPublishOperator() {
        def topic = new DataflowTopic<String>()
        topic.errorStrategy = new DefaultErrorHandlingStrategy()   // NEW

        StepVerifier.create(topic.stream().take(2))
                .then { topic << "hello" }
                .then { topic << "world" }
                .expectNext("hello", "world")
                .verifyComplete()
    }

    @Test
    void testTimeoutAndRetry() {
        StepVerifier.withVirtualTime({
            def topic = new DataflowTopic<String>()
            topic.errorStrategy = new DefaultErrorHandlingStrategy()   // NEW

            topic.timeout = Duration.ofMillis(200)
            topic.maxRetries = 1

            topic.stream().take(1)
        })
        // enough time for timeout + retry + completion
                .thenAwait(Duration.ofMillis(600))
                .verifyComplete()
    }
}
