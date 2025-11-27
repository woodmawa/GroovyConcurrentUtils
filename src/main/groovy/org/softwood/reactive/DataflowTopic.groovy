package org.softwood.reactive

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
 * <p>
 * This class preserves the exact behavioural semantics of a pure best-effort
 * multicast sink while adding a unified pluggable {@link ErrorHandlingStrategy}
 * using {@link ErrorMode} classifiers. All failures are delegated through the
 * strategy instead of only logging internally, providing consistent and
 * testable error pathways.
 * </p>
 *
 * <h3>Sink characteristics</h3>
 * <ul>
 *     <li>Uses {@code Sinks.many().multicast().directBestEffort()} → a true hot topic</li>
 *     <li>No pre-subscriber buffering</li>
 *     <li>Each subscriber gets all future events</li>
 *     <li>Slow subscribers may drop events (best-effort delivery)</li>
 *     <li>Unified error handling through strategy rather than internal println/logging only</li>
 * </ul>
 *
 * <h3>Error handling</h3>
 * <p>
 * This implementation has been extended to integrate the unified
 * {@link ErrorHandlingStrategy} and classifier {@link ErrorMode}, allowing
 * consistent error processing across publish and subscriber operations while
 * preserving Reactor's non-blocking semantics.
 * </p>
 *
 *
 * @param <T> event type
 */
@Slf4j
@CompileStatic
class DataflowTopic<T> {

    /**
     * The underlying Reactor sink powering this hot topic.
     * <p>
     * This variant never buffers nor replays events and is the highest throughput
     * multicast mode. It also means any subscriber pressure is ignored.
     * </p>
     */
    private final Sinks.Many<T> sink

    /**
     * Maximum idle duration permitted for each subscriber before a
     * {@link TimeoutException} is raised.
     */
    Duration timeout = Duration.ofSeconds(5)

    /**
     * Maximum number of retry attempts for transient errors such as
     * {@link TimeoutException} or {@link IllegalStateException}.
     */
    int maxRetries = 3

    /** The pluggable error-handling strategy. */
    ErrorHandlingStrategy errorStrategy = new DefaultErrorHandlingStrategy()

    /** Default classifier used for delegation. */
    ErrorMode errorMode = ErrorMode.DEFAULT

    /**
     * Creates a new hot broadcast topic.
     *
     * @param ignoredBufferCapacity compatibility parameter; ignored
     */
    DataflowTopic(int ignoredBufferCapacity = 0) {
        this.sink = Sinks.many().multicast().directBestEffort()
    }

    /**
     * Emit a single event to all subscribers.
     *
     * <p>Internal behaviour:</p>
     * <ul>
     *   <li>Retries on {@code FAIL_NON_SERIALIZED} automatically.</li>
     *   <li>{@code FAIL_ZERO_SUBSCRIBER}: event is quietly dropped.</li>
     *   <li>Any other failure results in delegation to the configured
     *       {@link ErrorHandlingStrategy}.</li>
     * </ul>
     *
     * @param element event to emit
     */
    void publish(T element) {
        boolean ok = true

        sink.emitNext(element) { signalType, result ->
            switch (result) {
                case Sinks.EmitResult.OK:
                    return false
                case Sinks.EmitResult.FAIL_NON_SERIALIZED:
                    return true // retry immediately
                case Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER:
                    log.debug("Dropping {} — no subscribers", element)
                    ok = true
                    return false
                default:
                    ok = false
                    return false
            }
        }

        if (!ok) {
            def ex = new IllegalStateException("Final emission failure for $element")
            errorStrategy.onError(ex, element, errorMode)
        }
    }

    /**
     * Groovy operator alias for {@link #publish(Object)}.
     *
     * @param element event to emit
     * @return this topic
     */
    DataflowTopic<T> leftShift(T element) {
        publish(element)
        return this
    }

    /**
     * Creates a new subscriber view of the topic as a {@link Flux}.
     *
     * <p>
     * The sink itself is hot and shared across all subscribers. This method
     * applies timeout, retry and centralised error-handling rules to the
     * subscriber's stream but never replays or re-emits events.
     * </p>
     *
     * @return a new Flux representing this subscriber's live view
     */
    Flux<T> stream() {
        return sink.asFlux()
                .transform(this.&applyErrorHandling)
    }

    /**
     * Groovy-friendly subscription helper.
     *
     * @param onNext callback for next event
     * @param onError optional callback for errors
     * @param onComplete optional callback when stream completes
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
     * Returns a {@link Mono} that completes when the underlying sink completes.
     */
    Mono<Void> awaitCompletion() {
        return sink.asFlux().then()
    }

    /**
     * Completes the topic. Any failure during completion is handled by
     * the configured {@link ErrorHandlingStrategy}.
     */
    void complete() {
        try {
            sink.emitComplete(Sinks.EmitFailureHandler.FAIL_FAST)
        } catch (Throwable t) {
            errorStrategy.onError(t, null, errorMode)
        }
    }

    /**
     * Applies per-subscriber timeout, retry and centralised error-handling logic.
     *
     * <p>
     * Behavioural rules:
     * </p>
     * <ul>
     *   <li>TimeoutException → retry (up to {@link #maxRetries}), then empty()</li>
     *   <li>IllegalStateException → retry (up to max), then empty()</li>
     *   <li>All other errors → delegated then fail subscriber</li>
     * </ul>
     *
     * @param source the upstream Flux from the sink
     * @return transformed Flux with subscriber-local error policy
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
                    errorStrategy.onError(ex, null, errorMode)

                    if (ex instanceof TimeoutException || ex instanceof IllegalStateException) {
                        return Flux.empty()
                    }

                    return Flux.error(ex)
                }
    }
}
