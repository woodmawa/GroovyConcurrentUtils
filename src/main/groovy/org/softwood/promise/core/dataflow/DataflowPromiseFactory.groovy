package org.softwood.promise.core.dataflow

import groovy.util.logging.Slf4j
import org.softwood.dataflow.DataflowFactory
import org.softwood.dataflow.DataflowVariable
import org.softwood.promise.Promise
import org.softwood.promise.PromiseFactory

import java.util.concurrent.CompletableFuture

/**
 * Dataflow-based implementation of {@link PromiseFactory}.
 *
 * <p>This factory produces {@link DataflowPromise} instances backed by
 * {@link DataflowVariable} primitives.</p>
 *
 * <h3>Responsibilities</h3>
 * <ul>
 *   <li>Create new empty promises</li>
 *   <li>Create already completed promises</li>
 *   <li>Run async tasks and return promises</li>
 *   <li>Adapt CompletableFuture and Promise into new DataflowPromises</li>
 * </ul>
 */
@Slf4j
class DataflowPromiseFactory implements PromiseFactory {

    private final DataflowFactory dataflowFactory

    /**
     * @param dataflowFactory backend factory for core dataflow primitives
     */
    DataflowPromiseFactory(DataflowFactory dataflowFactory) {
        this.dataflowFactory = dataflowFactory
    }

    /** {@inheritDoc} */
    @Override
    <T> Promise<T> createPromise() {
        return new DataflowPromise<T>(dataflowFactory.createDataflowVariable())
    }

    /** {@inheritDoc} */
    @Override
    <T> Promise<T> createPromise(T value) {
        return new DataflowPromise<T>(dataflowFactory.createDataflowVariable(value))
    }

    /** {@inheritDoc} */
    @Override
    <T> Promise<T> executeAsync(Closure<T> task) {
        return new DataflowPromise<T>(DataflowFactory.task(task))
    }

    /** {@inheritDoc} */
    @Override
    <T> Promise<T> from(CompletableFuture<T> future) {
        DataflowVariable<T> variable = dataflowFactory.createDataflowVariable()
        def promise = new DataflowPromise<T>(variable)

        future.whenComplete { value, error ->
            if (error != null) variable.bindError(error)
            else variable.bind(value)
        }

        return promise
    }

    /** {@inheritDoc} */
    @Override
    <T> Promise<T> from(Promise<T> otherPromise) {
        DataflowVariable<T> variable = dataflowFactory.createDataflowVariable()
        def promise = new DataflowPromise<T>(variable)

        otherPromise.onComplete { val -> variable.bind(val) }
        otherPromise.onError { err -> variable.bindError(err) }

        return promise
    }
}
