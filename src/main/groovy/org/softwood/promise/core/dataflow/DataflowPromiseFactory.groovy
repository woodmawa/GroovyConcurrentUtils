package org.softwood.promise.core.dataflow

import groovy.util.logging.Slf4j
import org.softwood.promise.Promise
import org.softwood.promise.PromiseFactory

/**
 * DataFlow-based implementation of PromiseFactory
 */
@Slf4j
class DataflowPromiseFactory implements PromiseFactory {
    private final DataflowFactory dataflow

    DataFlowPromiseFactory(DataflowFactory dataFlow) {
        this.dataflow = dataFlow
    }

    @Override
    <T> Promise<T> createPromise() {
        return new DataflowPromise<T>(dataFlow.variable())
    }

    @Override
    <T> Promise<T> createPromise(T value) {
        return new DataflowPromise<T>(dataflow.variable(value))
    }

    @Override
    <T> Promise<T> executeAsync(Closure<T> task) {
        return new DataflowPromise<T>(DataflowFactory.task(task))
    }
}
