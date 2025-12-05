package org.softwood.dataflow

import groovy.util.logging.Slf4j
import org.softwood.pool.ConcurrentPool
import org.softwood.pool.ExecutorPool
import org.softwood.pool.ExecutorPoolsFactory
import org.softwood.promise.core.PromisePoolContext

import java.util.concurrent.ExecutorService

/**
 * Factory for creating dataflow tasks and variables with a configurable executor
 */
@Slf4j
class DataflowFactory {

    private final ExecutorPool pool

    /*DataflowFactory() {
        this.pool = new ConcurrentPool("dataflow")
    }*/

    /**
     * Create factory using current context pool.
     */
    DataflowFactory() {
        this.pool = PromisePoolContext.getCurrentPool()
    }

    DataflowFactory(ExecutorPool  pool) {
        this.pool = pool
    }

    //wrap a user provided executor service if really required.  better to use the default virtual threads model though
    DataflowFactory(ExecutorService executor) {
        this.pool = ExecutorPoolsFactory.wrap (executor)
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

    /**
     * Get the pool used by this factory.
     */
    ExecutorPool getPool() {
        return pool
    }
}