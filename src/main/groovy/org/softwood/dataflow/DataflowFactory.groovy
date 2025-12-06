package org.softwood.dataflow

import groovy.util.logging.Slf4j
import org.softwood.pool.ExecutorPool
import org.softwood.pool.ExecutorPoolFactory
import org.softwood.promise.core.PromisePoolContext

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.function.Function
import java.util.function.Supplier

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
        this.pool = ExecutorPoolFactory.wrap (executor)
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

    // -------------------------------------------------------------------------
    // New Java Lambda overloads
    // -------------------------------------------------------------------------

    /**
     * Execute a Callable asynchronously.
     */
    def <T> DataflowVariable<T> task(Callable<T> callable) {
        DataflowVariable<T> variable = new DataflowVariable<T>(pool)
        pool.executor.submit {
            try {
                T result = callable.call()
                variable.setValue(result)
            } catch (Throwable e) {
                variable.bindError(e)
                log.error("DataflowFactory.task(Callable): execution failed", e)
            }
        }
        return variable
    }

    /**
     * Execute a Supplier asynchronously.
     */
    def <T> DataflowVariable<T> task(Supplier<T> supplier) {
        DataflowVariable<T> variable = new DataflowVariable<T>(pool)
        pool.executor.submit {
            try {
                T result = supplier.get()
                variable.setValue(result)
            } catch (Throwable e) {
                variable.bindError(e)
                log.error("DataflowFactory.task(Supplier): execution failed", e)
            }
        }
        return variable
    }

    /**
     * Execute a Runnable asynchronously.
     * Produces a DataflowVariable<Void>.
     */
    DataflowVariable<Void> task(Runnable runnable) {
        DataflowVariable<Void> variable = new DataflowVariable<Void>(pool)
        pool.executor.submit {
            try {
                runnable.run()
                variable.setValue(null)
            } catch (Throwable e) {
                variable.bindError(e)
                log.error("DataflowFactory.task(Runnable): execution failed", e)
            }
        }
        return variable
    }

    /**
     * Execute a Function<T,R> asynchronously with a provided input.
     */
    def <T, R> DataflowVariable<R> task(Function<T, R> fn, T input) {
        DataflowVariable<R> variable = new DataflowVariable<R>(pool)
        pool.executor.submit {
            try {
                R result = fn.apply(input)
                variable.setValue(result)
            } catch (Throwable e) {
                variable.bindError(e)
                log.error("DataflowFactory.task(Function,input): execution failed", e)
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