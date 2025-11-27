package org.softwood.dataflow.queue

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Schedulers

import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * A bounded, single-consumer, FIFO work queue that bridges imperative producers with a
 * reactive {@link Flux} stream using Project Reactor.
 * <p>
 * Producers enqueue work via {@link #enqueue(Object)} or the Groovy {@code <<} operator.
 * Only <strong>one</strong> consumer (subscriber) is allowed — enforced using a unicast sink.
 * Back-pressure is enforced using a {@link Semaphore} with a configurable timeout.
 * </p>
 *
 * <h3>Example</h3>
 *
 * <pre>{@code
 * def queue = new DataflowWorkQueue<String>(10)
 *
 * // Producer
 * queue << "task-1"
 * queue << "task-2"
 *
 * // Single consumer
 * queue.stream().subscribe { task ->
 *     println "Processing $task"
 * }
 * }</pre>
 *
 * @param <T> work item type
 */
@Slf4j
@CompileStatic
class DataflowWorkQueue<T> {

    /**
     * Configuration for producer back-pressure behavior.
     */
    static class ErrorHandlingStrategy {

        /**
         * Maximum duration producers will wait to acquire capacity
         * before failing with {@link QueueException.QueueFullException}.
         */
        Duration publishTimeout = Duration.ofSeconds(5)

        /**
         * Whether to log errors (true by default).
         */
        boolean logErrors = true
    }

    private final LinkedBlockingQueue<T> queue
    private final Semaphore permits
    private final int capacity
    private final Sinks.Many<T> sink
    private final Flux<T> flux
    private final ErrorHandlingStrategy errorStrategy

    /**
     * Creates a bounded work queue with the specified capacity.
     *
     * @param capacity maximum number of items allowed at once
     * @param errorStrategy optional error-handling config
     */
    DataflowWorkQueue(int capacity, ErrorHandlingStrategy errorStrategy = new ErrorHandlingStrategy()) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0")
        }

        this.capacity = capacity
        this.queue = new LinkedBlockingQueue<>(capacity)
        this.permits = new Semaphore(capacity, true)
        this.errorStrategy = errorStrategy ?: new ErrorHandlingStrategy()

        // Unicast sink → exactly one consumer
        this.sink = Sinks.many().unicast().onBackpressureBuffer()

        // Build a single shared consumer pipeline
        this.flux = sink.asFlux()
                .publishOn(Schedulers.boundedElastic())
                .doOnNext { T ignored ->
                    // FIFO removal matches emission order
                    T removed = queue.poll()
                    if (removed == null && errorStrategy.logErrors) {
                        log.warn("Consumer attempted to poll but queue was empty")
                    }
                    permits.release()
                }
                .doOnError { Throwable err ->
                    if (errorStrategy.logErrors) {
                        log.error("Error in work queue stream", err)
                    }
                }
                .publish()
                .autoConnect(1)
    }

    /**
     * Enqueues an item into the queue.
     * <p>
     * Producers may block up to {@link ErrorHandlingStrategy#publishTimeout} if the queue
     * is currently full. After being added to the backing queue, the item is emitted into
     * the reactive sink. If emission ultimately fails, the queue state is rolled back.
     * </p>
     *
     * @param item item to enqueue
     * @throws QueueException.QueueFullException if capacity cannot be acquired in time
     * @throws QueueException.PublishingException if emission fails
     */
    void enqueue(T item) {
        try {
            // Bound outstanding items using a semaphore
            if (!permits.tryAcquire(errorStrategy.publishTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new QueueException.QueueFullException(
                        "Work queue is full (capacity=$capacity); publishTimeout=${errorStrategy.publishTimeout}"
                )
            }

            // Insert into underlying storage first
            if (!queue.offer(item)) {
                permits.release()
                throw new QueueException.QueueFullException("Queue offer failed despite available permit")
            }

            // Track emission success
            boolean emittedOk = true

            // Emit via Reactor sink; only retry on FAIL_NON_SERIALIZED
            sink.emitNext(item) { signalType, emitResult ->
                switch (emitResult) {
                    case Sinks.EmitResult.OK:
                        return false   // success — do not retry
                    case Sinks.EmitResult.FAIL_NON_SERIALIZED:
                        return true    // retry
                    case Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER:
                        emittedOk = false
                        return false
                    case Sinks.EmitResult.FAIL_TERMINATED:
                        emittedOk = false
                        return false
                    default:
                        emittedOk = false
                        return false
                }
            }

            if (!emittedOk) {
                queue.remove(item)
                permits.release()
                throw new QueueException.PublishingException("Emission failed for item $item")
            }

        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt()
            throw new QueueException.PublishingException("Interrupted while enqueuing item", ex)
        }
    }

    /**
     * Groovy operator overload for {@link #enqueue(Object)}.
     *
     * @param item item to enqueue
     * @return this queue (fluent API)
     */
    DataflowWorkQueue<T> leftShift(T item) {
        enqueue(item)
        return this
    }

    /**
     * Returns a hot Flux representing the stream of consumed items.
     * <p>
     * Only a single subscriber is permitted. The subscriber will receive items
     * in the same order they were enqueued.
     * </p>
     *
     * @return shared Flux
     */
    Flux<T> stream() {
        return flux
    }

    /**
     * Subscribes using closures for convenience.
     *
     * @param onNext item consumer
     * @param onError optional error handler
     * @param onComplete optional completion handler
     * @return the shared Flux
     */
    Flux<T> consume(Closure<?> onNext,
                    Closure<?> onError = null,
                    Closure<?> onComplete = null) {
        if (onNext != null) {
            if (onError && onComplete) {
                flux.subscribe(onNext, onError, onComplete)
            } else if (onError) {
                flux.subscribe(onNext, onError)
            } else {
                flux.subscribe(onNext)
            }
        }
        return flux
    }

    /**
     * Returns the next item in the queue without removing it.
     *
     * @return Optional containing next item or empty if queue is empty
     */
    Optional<T> peek() {
        return Optional.ofNullable(queue.peek())
    }

    /**
     * @return the current queue size
     */
    int size() {
        return queue.size()
    }

    /**
     * @return true if the queue is empty
     */
    boolean isEmpty() {
        return queue.isEmpty()
    }

    /**
     * Returns a snapshot of queue contents in FIFO order.
     *
     * @return immutable list copy
     */
    List<T> toList() {
        return Collections.unmodifiableList(new ArrayList<>(queue))
    }

    /**
     * Completes the reactive stream. No further items may be enqueued.
     */
    void complete() {
        sink.emitComplete(Sinks.EmitFailureHandler.FAIL_FAST)
    }
}
