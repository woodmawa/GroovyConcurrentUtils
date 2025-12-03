package org.softwood.dataflow

import groovy.util.logging.Slf4j
import org.softwood.pool.ConcurrentPool

import java.util.concurrent.ExecutorService

/**
 * Factory for creating dataflow tasks and variables with a configurable executor
 */
@Slf4j
class DataflowFactory {

    private final ConcurrentPool pool

    DataflowFactory() {
        this.pool = new ConcurrentPool("dataflow")
    }

    DataflowFactory(ConcurrentPool pool) {
        this.pool = pool
    }

    DataflowFactory(ExecutorService executor) {
        this.pool = new ConcurrentPool(executor)
    }

    <T> DataflowVariable<T> createDataflowVariable() {
        // Use the same pool for DFV so its async listeners share this pool
        return new DataflowVariable<T>(pool)
    }

    <T> DataflowVariable<T> createDataflowVariable(T initialValue) {
        def variable = new DataflowVariable<T>(pool)
        variable.set(initialValue)
        return variable
    }

    <T> DataflowVariable<T> task(Closure<T> task) {
        DataflowVariable<T> variable = new DataflowVariable<T>(pool)
        pool.executor.submit {
            try {
                T result = task.call()
                variable.setValue(result)
            } catch (Throwable e) {
                variable.bindError(e)
                log.error("Task(): Task execution failed", e)
            }
        }
        return variable
    }

    ExecutorService getExecutor() {
        return pool.executor
    }
}