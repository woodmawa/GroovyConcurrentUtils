package org.softwood.promise.core.dataflow

import groovy.util.logging.Slf4j
import org.softwood.dataflow.DataflowFactory
import org.softwood.dataflow.DataflowVariable
import org.softwood.pool.ExecutorPool
import org.softwood.promise.Promise
import org.softwood.promise.PromiseFactory
import org.softwood.promise.core.PromisePoolContext

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

    /**
     * Create factory using current context pool.
     */
    DataflowPromiseFactory() {
        this(new DataflowFactory(PromisePoolContext.getCurrentPool()))
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
    <T> Promise<T> createFailedPromise(Throwable cause) {
        DataflowVariable<T> dv = dataflowFactory.createDataflowVariable()
        dv.bindError(cause)
        return new DataflowPromise<T>(dv)
    }

    /**
     * {@inheritDoc}
     *
     * <p>The supplied closure is executed asynchronously on the {@link DataflowFactory}'s worker pool
     * via {@link DataflowFactory#task(groovy.lang.Closure)}, and the resulting {@link DataflowVariable}
     * is wrapped in a {@link DataflowPromise}.</p>
     */
    @Override
    <T> Promise<T> executeAsync(Closure<T> task) {
        return new DataflowPromise<T>(dataflowFactory.task(task))
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

    /**
     * Wrap an existing DataflowVariable into a DataflowPromise.
     *
     * This is used when user code accidentally returns a DataflowVariable
     * instead of a Promise.  This method gives Promises.ensurePromise()
     * a canonical way to convert DFVs into Promises without duplicating logic.
     */
    <T> Promise<T> wrapDataflowVariable(DataflowVariable<T> variable) {
        return new DataflowPromise<T>(variable)
    }

    /**
     * Get the pool used by this factory.
     */
    ExecutorPool getPool() {
        return dataflowFactory.getPool()
    }
}