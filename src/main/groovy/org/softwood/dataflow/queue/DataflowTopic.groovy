package org.softwood.dataflow.queue

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.util.retry.Retry

import java.time.Duration
import java.util.concurrent.TimeoutException

/**
 * A hot, multi-subscriber pub/sub topic built on Project Reactor.
 * <p>
 * Producers emit events using {@code #publish(Object)} or the Groovy {@code <<} operator.
 * Subscribers attach via {@code #stream()}, and each subscriber receives only events
 * <strong>from the moment of subscription onward</strong>. No replay is ever performed.
 * </p>
 *
 * <h3>Sink characteristics</h3>
 * <ul>
 *     <li>Uses {@code Sinks.many().multicast().directBestEffort()} → a true hot topic</li>
 *     <li>No pre-subscriber buffering</li>
 *     <li>Each subscriber gets all future events</li>
 *     <li>Slow subscribers may drop events (best-effort delivery)</li>
 * </ul>
 *
 * <h3>Error handling</h3>
 * Timeout and retry behavior is applied <strong>per subscriber</strong> inside {@code #stream()},
 * ensuring the shared hot source is never restarted or corrupted.
 *
 * @param <T> event type
 */
@Slf4j
@CompileStatic
class DataflowTopic<T> {

    private final Sinks.Many<T> sink

    /**
     * Duration after which a subscriber triggers a TimeoutException
     * if no events are emitted.
     */
    Duration timeout = Duration.ofSeconds(5)

    /**
     * Maximum number of retry attempts for timeout/illegal-state failures.
     */
    int maxRetries = 3

    /**
     * Creates a new hot broadcast topic.
     */
    DataflowTopic(int ignoredBufferCapacity = 0) {
        // true hot topic: no replay, no buffering pre-subscribe
        this.sink = Sinks.many().multicast().directBestEffort()
    }

    /**
     * Emit an event to all subscribers.
     * Performs internal retry on FAIL_NON_SERIALIZED.
     */
    void publish(T element) {
        boolean ok = true

        sink.emitNext(element) { signalType, result ->
            switch (result) {
                case Sinks.EmitResult.OK:
                    return false
                case Sinks.EmitResult.FAIL_NON_SERIALIZED:
                    return true
                case Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER:
                    // silently drop if nobody is listening
                    log.debug("Dropping {} — no subscribers", element)
                    ok = true
                    return false
                default:
                    log.warn("Emission failure {} for {}", result, element)
                    ok = false
                    return false
            }
        }

        if (!ok) {
            log.error("Final emission failure for {}", element)
        }
    }

    /**
     * Groovy operator for publish().
     *
     * @param element event to emit
     * @return this topic
     */
    DataflowTopic<T> leftShift(T element) {
        publish(element)
        return this
    }

    /**
     * Returns a per-subscriber Flux.
     *
     * The sink itself is shared and hot; timeout + retry is applied on
     * a per-subscriber basis here.
     *
     * @return Flux for this subscriber
     */
    Flux<T> stream() {
        return sink.asFlux()
                .transform(this.&applyErrorHandling)
    }

    /**
     * Convenience subscription API for Groovy users.
     *
     * @return this topic
     */
    DataflowTopic<T> subscribe(Closure<?> onNext,
                               Closure<?> onError = null,
                               Closure<?> onComplete = null) {
        Flux<T> f = stream()
        if (onNext != null) {
            if (onError && onComplete) {
                f.subscribe(onNext, onError, onComplete)
            } else if (onError) {
                f.subscribe(onNext, onError)
            } else {
                f.subscribe(onNext)
            }
        }
        return this
    }

    /**
     * Mono that completes when the topic completes.
     */
    Mono<Void> awaitCompletion() {
        return sink.asFlux().then()
    }

    /**
     * Completes the topic.
     */
    void complete() {
        sink.emitComplete(Sinks.EmitFailureHandler.FAIL_FAST)
    }

    /**
     * Per-subscriber timeout and retry logic.
     */
    private Flux<T> applyErrorHandling(Flux<T> source) {
        return source
                .timeout(timeout, Mono.error(new TimeoutException("Topic idle for $timeout")))
                .retryWhen(
                        Retry.backoff(maxRetries, Duration.ofMillis(100))
                                .maxBackoff(Duration.ofSeconds(10))
                                .filter { ex ->
                                    ex instanceof TimeoutException ||
                                            ex instanceof IllegalStateException
                                }
                )
                .onErrorResume { ex ->
                    if (ex instanceof TimeoutException || ex instanceof IllegalStateException) {
                        log.warn("Transient error: ${ex.message}", ex)
                        return Flux.empty()
                    }
                    log.error("Unhandled error", ex)
                    return Flux.error(ex)
                }
    }
}
