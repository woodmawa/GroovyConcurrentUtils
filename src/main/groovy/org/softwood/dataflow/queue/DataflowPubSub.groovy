package org.softwood.dataflow.queue

import groovy.util.logging.Slf4j
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Schedulers
import reactor.util.retry.Retry

import java.time.Duration
import java.util.concurrent.Flow
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * reactive data flow queue with back pressure
 *
 * @param <T>
 */
@Slf4j
class DataflowPubSub<T> {
    // Use Sinks.Many for a multi-cast publisher with back pressure
    private final Sinks.Many<T> sink
    private final Flux<T> flux
    private final AtomicBoolean hasSubscribers = new AtomicBoolean(false)

    // Configuration for timeouts and retries
    //todo - best read from spring config
    Duration timeout = Duration.ofSeconds(5)
    int maxRetries = 3

    DataflowPubSub() {
        // Use replay sink to ensure all elements are captured
        sink = Sinks.many().multicast().onBackpressureBuffer(256)
        // Add a subscriber count tracker
        flux = sink.asFlux()
                .doOnSubscribe { subscription ->
                    this.hasSubscribers.set(true)
                    log.debug "Subscriber added"
                }
                .doOnCancel {
                    this.hasSubscribers.set(false)
                    log.debug "Subscriber cancelled"
                }
                .publishOn(Schedulers.parallel())
                .onBackpressureBuffer()
                .transform(this::applyErrorHandling)
                .publish ()
                    .autoConnect (2)
    }

    /**
     * Reliable element emission with proper error handling
     * @param element Element to add
     * @return boolean indicating successful emission
     */
    boolean offer(T element) {
        // Check if there are subscribers before attempting to emit
        if (!hasSubscribers.get()) {
            log.warn "No active subscribers. Waiting before emitting element: ${element}"
            return false
        }

        try {
            log.info ("Offering element: {$element} to the queue")
            // Use a more comprehensive emission strategy
            def emitResult = sink.emitNext(element, (signalType, result) -> {
                switch (result) {
                    case Sinks.EmitResult.OK:
                        return true
                    case Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER:
                        log.debug "No subscribers for element: ${element}"
                        return true
                    case Sinks.EmitResult.FAIL_OVERFLOW:
                        log.warn "Queue overflow: Unable to add ${element}"
                        return false
                    case Sinks.EmitResult.FAIL_TERMINATED:
                        log.error "Queue terminated: Cannot add ${element}"
                        return false
                    default:
                        log.error "Unexpected emission result: ${result}"
                        return false
                }
            })

            return emitResult == Sinks.EmitResult.OK
        } catch (Exception e) {
            log.error "Critical emission error: ${e.message}", e
            return false
        }
    }

    /**
     * Left shift operator overload for adding elements
     * @param element The element to add to the queue
     * @return this ReactiveFifoQueue instance
     */
    DataflowPubSub<T> leftShift(T element) {

        offer(element)

        /*int attempts = 0
        while (attempts < 3) {
            if (offer(element)) {
                return this
            }

            try {
                Thread.sleep(50 * (attempts + 1))  // Exponential backoff
                attempts++
            } catch (InterruptedException ie) {
                log.error "Interruption during element addition retry: ${ie.message}"
                break
            }
        }*/
        return this
    }

    /**
     * Right shift operator overload for consuming elements
     * @param consumer The consumer function to process elements
     * @return this ReactiveFifoQueue instance
     */
    DataflowPubSub<T> rightShift(Closure consumer) {
        /*flux
                .doOnNext { value ->
                    log.debug "Processing value: ${value}"
                }
                .subscribe(
                        { value ->
                            try {
                                consumer.call(value)
                            } catch (Exception e) {
                                log.error "Consumer error: ${e.message}", e
                            }
                        },
                        { error ->
                            log.error "Stream error: ${error.message}", error
                        },
                        {
                            log.info "Stream completed"
                        }
                )
        return this*/

        /*flux.toStream().each (consumer)
        this*/
    }

    /**
     * Alternative right shift method supporting Flow.Subscriber
     * @param subscriber The Flow.Subscriber to attach to the stream
     * @return this ReactiveFifoQueue instance
     */
    DataflowPubSub<T> rightShift(Flow.Subscriber<? super T> subscriber) {
        flux.subscribe(
                value -> subscriber.onNext(value),
                error -> subscriber.onError(error),
                () -> subscriber.onComplete()
        )
        return this
    }

    /**
     * Fluent method to await completion with timeout
     * @return Mono representing completion
     */
    Mono<Void> awaitCompletion() {
        Mono.fromRunnable({ complete() })
                .timeout(timeout)
                .onErrorResume { error ->
                    log.error "Completion error: ${error.message}"
                    Mono.empty()
                }
    }

    /**
     * Creates a flux of elements from the queue
     * @return Flux of queue elements
     */
    Flux<T> stream() {
        flux
    }

    /**
     * Completes the sink, signaling no more elements will be added
     */
    void complete() {
        sink.emitComplete(Sinks.EmitFailureHandler.FAIL_FAST)
    }

    /**
     * Apply global error handling and retry strategy
     * @param flux Input flux to transform
     * @return Transformed flux with error handling
     */
    private Flux<T> applyErrorHandling(Flux<T> flux) {
        flux.timeout(timeout, Mono.error(new TimeoutException("Queue operation timed out")))
                .retryWhen(Retry.backoff(maxRetries, Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(10))
                        .transientErrors(true)
                        .filter { throwable ->
                            // Specify which errors are retriable
                            throwable instanceof TimeoutException ||
                                    throwable instanceof IllegalStateException
                        }
                )
                .onErrorResume(this::handleError)
    }

    /**
     * Centralized error handling strategy
     * @param throwable The error to handle
     * @return Mono with error handling result
     */
    private Mono<T> handleError(Throwable throwable) {
        switch (throwable) {
            case TimeoutException:
                log.warn "Timeout occurred: ${throwable.message}"
                return Mono.empty()
            case IllegalStateException:
                log.warn "Illegal state: ${throwable.message}"
                return Mono.empty()
            default:
                log.error "Unhandled error: ${throwable.message}", throwable
                return Mono.error(throwable)
        }
    }

}
