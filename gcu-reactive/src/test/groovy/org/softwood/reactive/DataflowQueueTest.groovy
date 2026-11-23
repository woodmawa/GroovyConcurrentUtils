package org.softwood.reactive

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import reactor.test.StepVerifier

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import static org.junit.jupiter.api.Assertions.*


class DataflowQueueTest {

    @Test
    void testBasicEnqueueAndConsume() {
        def queue = new DataflowQueue<String>(5)

        StepVerifier.create(queue.stream().take(2))
                .then { queue.enqueue("A") }
                .then { queue.enqueue("B") }
                .expectNext("A", "B")
                .verifyComplete()
    }

    @Test
    void testLeftShiftOperator() {
        def queue = new DataflowQueue<Integer>(5)

        StepVerifier.create(queue.stream().take(2))
                .then { queue << 1 }
                .then { queue << 2 }
                .expectNext(1, 2)
                .verifyComplete()
    }

    @Test
    void testQueueFullThrowsException() {
        def queue = new DataflowQueue<String>(1)

        queue.enqueue("X")

        assertThrows(QueueException.QueueFullException) {
            queue.enqueue("Y")
        }
    }

    @Test
    void testQueueCapacityReleasesWhenConsumed() {
        def queue = new DataflowQueue<Integer>(2)

        // fill queue
        queue.enqueue(1)
        queue.enqueue(2)

        StepVerifier.create(queue.stream())
                .expectNext(1)
                .then {
                    // now that one element was consumed, capacity should free
                    assertDoesNotThrow({ queue.enqueue(3) } as Executable)
                }
                .expectNext(2)
                .expectNext(3)
                .thenCancel()
                .verify()
    }

    @Test
    void testConcurrentProducers() {
        def queue = new DataflowQueue<Integer>(50)
        def exec = Executors.newFixedThreadPool(4)
        def counter = new AtomicInteger()

        (1..20).each {
            exec.submit { queue.enqueue(counter.incrementAndGet()) }
        }

        exec.shutdown()
        exec.awaitTermination(2, TimeUnit.SECONDS)

        StepVerifier.create(queue.stream().take(20))
                .expectNextCount(20)
                .verifyComplete()
    }

    @Test
    void testPeekAndSize() {
        def queue = new DataflowQueue<String>(5)

        queue.enqueue("A")
        queue.enqueue("B")

        assertEquals(2, queue.size())
        assertEquals("A", queue.peek().orElse(null))
    }

    @Test
    void testCompleteStopsFurtherEmissions() {
        def queue = new DataflowQueue<String>(5)

        StepVerifier.create(queue.stream())
                .then { queue.enqueue("foo") }
                .then { queue.complete() }
                .expectNext("foo")
                .verifyComplete()

        assertThrows(QueueException.PublishingException) {
            queue.enqueue("ignored")
        }
    }
}
