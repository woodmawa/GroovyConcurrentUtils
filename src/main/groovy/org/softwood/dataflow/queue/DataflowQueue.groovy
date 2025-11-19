package org.softwood.dataflow.queue

import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Schedulers

import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

@Slf4j
@CompileStatic
class DataflowQueue<T> {

    // Underlying blocking queue for thread-safe storage
    private final LinkedBlockingQueue<T> queue

    // Maximum queue capacity for back pressure
    private final int maxCapacity

    // Semaphore to control back pressure
    private final Semaphore backPressureSemaphore

    // Reactor sink for publishing events
    private final Sinks.Many<T> sink

    // Error handling configuration
    private final ErrorHandlingStrategy errorStrategy

    /**
     * Error handling strategy configuration
     */
    static class ErrorHandlingStrategy {
        // Maximum number of retries for publishing
        int maxPublishRetries = 3

        // Timeout for publishing an item
        Duration publishTimeout = Duration.ofSeconds(5)

        // Base delay between retries
        Duration retryBaseDelay = Duration.ofMillis(100)

        // Whether to log errors
        boolean logErrors = true
    }

    /**
     * Constructor for ReactiveBlockingQueue
     * @param capacity Maximum number of elements the queue can hold
     * @param errorStrategy Custom error handling strategy
     */
    DataflowQueue (int capacity, ErrorHandlingStrategy errorStrategy = new ErrorHandlingStrategy()) {
        this.maxCapacity = capacity
        this.queue = new LinkedBlockingQueue<>(capacity)
        this.backPressureSemaphore = new Semaphore(capacity)
        this.errorStrategy = errorStrategy

        // Create a multicast sink that can handle multiple subscribers
        this.sink = Sinks.many().multicast().onBackpressureBuffer(capacity, false)
    }

    /**
    * Left shift operator for publishing items with enhanced error handling
    * @param item Item to be published
    * @return this queue for method chaining
    */
    @CompileDynamic
    DataflowQueue<T> leftShift(T item) {
        try {
            // Acquire permit with timeout
            if (!backPressureSemaphore.tryAcquire(errorStrategy.publishTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new QueueException.QueueFullException("Queue is full. Could not acquire semaphore within ${errorStrategy.publishTimeout}")
            }

            // Synchronize emission to prevent concurrent modifications
            synchronized (sink) {
                // Attempt to emit and get result code back
                Sinks.EmitResult emitResult = sink.tryEmitNext(item)

                switch (emitResult) {
                    case Sinks.EmitResult.OK:
                        // Offer to underlying queue
                        if (!queue.offer(item)) {

                            backPressureSemaphore.release()
                            throw new QueueException.QueueFullException("Underlying queue is full")
                        }
                        log.info "queue size is now ${queue.size()} after offering $item  "
                        break
                    case Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER:
                        backPressureSemaphore.release()
                        log.info("No subscribers available for emission")
                        break
                    case Sinks.EmitResult.FAIL_OVERFLOW:
                        backPressureSemaphore.release()
                        log.info("emit fail_overflow")
                        throw new QueueException("Sink overflow occurred")
                        break
                    case Sinks.EmitResult.FAIL_TERMINATED:
                        backPressureSemaphore.release()
                        log.info("emit fail_overflow")
                        throw new QueueException("Sink has been terminated")
                        break
                    case Sinks.EmitResult.FAIL_NON_SERIALIZED:
                        backPressureSemaphore.release()
                        log.info("emit fail_non_serialised access across threads")
                        throw new QueueException("Sink couldnt be serialized ")
                        break
                    case null: // Explicitly handle null
                        backPressureSemaphore.release()
                        log.warn("emitNext returned null unexpectedly")
                        // Decide how to handle this case: throw an exception, retry, etc.
                        break
                    default :
                        backPressureSemaphore.release()
                        log.info("emit return was null")
                        break
                }

            }

            return this
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
            log.info("left shift publishing interrupted $e.cause")
            throw new QueueException.PublishingException("Publishing interrupted", e)
        }
    }


    /**
     * Right shift operator for consuming items with error handling
     * @return Flux of queue items
     */
    @CompileDynamic
    Flux<T> rightShift(Closure subscriber, Closure error = null, Closure complete = null) {
        Flux<T> flux = sink.asFlux()
                .publishOn(Schedulers.boundedElastic())
                .doOnNext { T item ->
                    // Release a permit when an item is consumed
                    log.info "item [$item] releaesed, remove from the queue "
                    queue.remove(item)
                    backPressureSemaphore.release()
                }
                .doOnError { ex ->
                    if (errorStrategy.logErrors) {
                        log.error("Error consuming queue items", ex)
                    }
                }

        // Flexible subscription based on provided closures
        //call the susbcriber closure with the value
        if (subscriber) {
            if (error && complete) {
                flux.subscribe(subscriber, error, complete)
            } else if (error) {
                flux.subscribe(subscriber, error)
            } else {
                flux.subscribe(subscriber)
            }
        }

        flux
    }

    /**
     * Safely peek at the next item
     * @return Next item in the queue or null
     */
    Optional<T> safePeek() {
        try {
            Optional.ofNullable(queue.peek())
        } catch (Exception e) {
            if (errorStrategy.logErrors) {
                log.warn("Error peeking at queue", e)
            }
            Optional.empty()
        }
    }

    /**
     * Safely get the queue size
     * @return Current number of items in the queue
     */
    int safeSize() {
        try {
            queue.size()
        } catch (Exception e) {
            if (errorStrategy.logErrors) {
                log.warn("Error getting queue size", e)
            }
            0
        }
    }

    /**
     * Complete the sink with error handling
     */
    void complete() {
        try {
            sink.emitComplete(Sinks.EmitFailureHandler.FAIL_FAST)
        } catch (Exception e) {
            if (errorStrategy.logErrors) {
                log.error("Error completing queue", e)
            }
        }
    }

    @CompileDynamic
    List<T> toList () {

        Object [] arr = queue.toArray()
        List l = new ArrayList(*arr)
        l.asImmutable()
    }
}
