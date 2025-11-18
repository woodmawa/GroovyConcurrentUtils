package org.softwood.promise.core.dataflow

import groovy.util.logging.Slf4j
import org.softwood.promise.Promise

import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.Consumer
import java.util.function.Function

/**
 * DataFlowVariable-based implementation of Promise
 */
@Slf4j
class DataFlowPromise<T> implements Promise<T> {
    private final DataflowVariable<T> variable

    DataFlowPromise(DataflowVariable<T> variable) {
        this.variable = variable
    }

    @Override
    T get() throws Exception {
        return variable.get()
    }

    @Override
    T get(long timeout, TimeUnit unit) throws TimeoutException {
        return variable.get(timeout, unit)
    }

    @Override
    boolean isDone() {
        return variable.isDone()
    }

    @Override
    Promise<T> onComplete(Consumer<T> callback) {
        variable.whenAvailable(callback)
        return this
    }

    @Override
    <R> Promise<R> then(Function<T, R> function) {
        DataflowVariable<R> result = variable.then({ T value ->
            return function.apply(value)
        } as Closure)
        return new DataFlowPromise<R>(result)
    }

    @Override
    Promise<T> onError(Consumer<Throwable> errorHandler) {
        variable.whenAvailable({ T value ->
            if (variable.hasError()) {
                errorHandler.accept(variable.getError())
            }
        } as Consumer)
        return this
    }

    @Override
    <R> Promise<R> recover(Function<Throwable, R> recovery) {
        return new DataFlowPromise<R>(variable.recover(recovery))
    }
}
