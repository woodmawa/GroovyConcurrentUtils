package org.softwood.dataflow.queue;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DataflowWorkQueueTest {

    @Test
    void testSingleItemEnqueueAndConsume() {
        DataflowWorkQueue<String> queue = new DataflowWorkQueue<>(10);

        queue.enqueue("task-1");

        StepVerifier.create(queue.stream())
                .expectNext("task-1")
                .thenCancel()
                .verify();
    }

    @Test
    void testFifoOrdering() {
        DataflowWorkQueue<Integer> queue = new DataflowWorkQueue<>(5);

        queue.enqueue(1);
        queue.enqueue(2);
        queue.enqueue(3);

        StepVerifier.create(queue.stream())
                .expectNext(1)
                .expectNext(2)
                .expectNext(3)
                .thenCancel()
                .verify();
    }

    @Test
    void testBackPressureBlocksWhenFull() throws Exception {
        DataflowWorkQueue<String> queue =
                new DataflowWorkQueue<>(2, new DataflowWorkQueue.ErrorHandlingStrategy() {{
                    publishTimeout = Duration.ofMillis(200);
                }});

        // Fill queue
        queue.enqueue("A");
        queue.enqueue("B");

        // Next enqueue must timeout → throw QueueFullException
        assertThrows(QueueException.QueueFullException.class,
                () -> queue.enqueue("C"));
    }

    @Test
    void testQueueCapacityReleasesWhenConsumed() {
        DataflowWorkQueue<Integer> queue = new DataflowWorkQueue<>(2);

        queue.enqueue(1);
        queue.enqueue(2);

        // Consumer will free capacity after reading values
        Flux<Integer> consumer = queue.stream();

        StepVerifier.create(consumer)
                .expectNext(1)
                .then(() -> assertDoesNotThrow(() -> queue.enqueue(3)))
                .expectNext(2)
                .expectNext(3)
                .thenCancel()
                .verify();
    }

    @Test
    void testConcurrentProducers() throws Exception {
        DataflowWorkQueue<Integer> queue = new DataflowWorkQueue<>(50);

        ExecutorService exec = Executors.newFixedThreadPool(5);
        AtomicInteger counter = new AtomicInteger();

        // produce 20 items concurrently
        for (int i = 0; i < 20; i++) {
            exec.submit(() -> queue.enqueue(counter.incrementAndGet()));
        }

        exec.shutdown();
        exec.awaitTermination(2, TimeUnit.SECONDS);

        StepVerifier.create(queue.stream().take(20))
                .expectNextCount(20)
                .verifyComplete();
    }

    @Test
    void testPeekAndSize() {
        DataflowWorkQueue<String> queue = new DataflowWorkQueue<>(5);

        queue.enqueue("A");
        queue.enqueue("B");

        assertEquals(2, queue.size());
        assertEquals("A", queue.peek().orElse(null));
    }

    @Test
    void testCompleteSignal() {
        DataflowWorkQueue<String> queue = new DataflowWorkQueue<>(5);

        queue.enqueue("X");
        queue.complete();

        StepVerifier.create(queue.stream())
                .expectNext("X")
                .verifyComplete();

        // Cannot enqueue after completion
        assertThrows(QueueException.PublishingException.class,
                () -> queue.enqueue("Y"));
    }
}
