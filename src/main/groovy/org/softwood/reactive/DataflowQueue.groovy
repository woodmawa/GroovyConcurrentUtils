package org.softwood.reactive

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore

@Slf4j
@CompileStatic
class DataflowQueue<T> {

    private int capacity
    private LinkedBlockingQueue<T> queue
    private Sinks.Many<T> sink
    private Flux<T> flux
    private ErrorHandlingStrategy errorStrategy
    private Semaphore permits

    //default constructor
    DataflowQueue () {
        DataflowQueue (25)
    }

    DataflowQueue (int capacity, ErrorHandlingStrategy = new DefaultErrorHandlingStrategy()) {
        this.capacity = capacity
        this.queue = new LinkedBlockingQueue<>(capacity)
        this.permits = new Semaphore (capacity, true)
        this.errorStrategy = new DefaultErrorHandlingStrategy ()

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




}
