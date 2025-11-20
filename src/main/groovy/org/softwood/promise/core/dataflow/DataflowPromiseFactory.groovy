package org.softwood.promise.core.dataflow

import groovy.util.logging.Slf4j
import org.softwood.dataflow.DataflowFactory
import org.softwood.promise.Promise
import org.softwood.promise.PromiseFactory

/**
 * Dataflow-based implementation of PromiseFactory
 */
@Slf4j
class DataflowPromiseFactory implements PromiseFactory {
    private final DataflowFactory dataflowFactory

    DataflowPromiseFactory(DataflowFactory dataflowFactory) {
        this.dataflowFactory = dataflowFactory
    }

    @Override
    <T> Promise<T> createPromise() {
        return new DataflowPromise<T>(dataflowFactory.variable())
    }

    @Override
    <T> Promise<T> createPromise(T value) {
        return new DataflowPromise<T>(dataflowFactory.variable(value))
    }

    @Override
    <T> Promise<T> executeAsync(Closure<T> task) {
        return new DataflowPromise<T>(DataflowFactory.task(task))
    }
}
