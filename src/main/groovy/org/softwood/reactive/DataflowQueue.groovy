package org.softwood.reactive

import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks

import java.util.concurrent.LinkedBlockingQueue


class DataflowQueue<T> {

    private Flux<T> flux
    private LinkedBlockingQueue<T> queue
    private Sinks.Many<T> sink

    DataflowQueue (int capacity, ErrorHandlingStrategy = new DefaultErrorHandlingStrategy()) {

    }

    void enqueue (T item) {

    }

    /**
     * Groovy operator overload for {@link #enqueue(Object)}.
     *
     * @param item item to enqueue
     * @return this queue (fluent API)
     */
    DataflowQueue<T> leftShift (T item) {
        enqueue (item)
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
