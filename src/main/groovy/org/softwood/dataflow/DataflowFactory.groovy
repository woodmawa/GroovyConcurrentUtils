package org.softwood.dataflow

import groovy.util.logging.Slf4j
import org.softwood.pool.ConcurrentPool

import java.util.concurrent.ExecutorService

/**
 * Factory for creating dataflow tasks and variables with a configurable executor
 */
@Slf4j
class DataflowFactory {
    private static ConcurrentPool defaultPool

    /**
    * Maps threads/tasks to parallel groups they belong to
    */
    public static final ThreadLocal<ConcurrentPool> activePool = new ThreadLocal<ConcurrentPool>()

    static ConcurrentPool getActiveConcurrentPool () {
        if (defaultPool == null) {
            def newPool = new ConcurrentPool ()
            activePool.set(newPool)
            defaultPool = newPool
            log.debug "default pool was null - just created new active pool"
        }
        activePool.get()
    }

    /*static {
        // Initialize with virtual threads by default if running on Java 21+
        defaultPool = new ConcurrentPool()
    }*/

    private final ConcurrentPool pool

    DataflowFactory() {
        this.pool = getActiveConcurrentPool()
    }

    /**
     * if given new pool just use the one provided
     *
     * @param pool
     */
    DataflowFactory(ConcurrentPool pool) {
        this.pool = pool
        activePool.set (pool)
    }

    /**
     * using passed in ExecutorService creat a Pool with that
     *
     * @param executor
     */
    DataflowFactory(ExecutorService executor ) {
        this.pool = new ConcurrentPool (executor)
        activePool.set (pool)
    }


    /**
     * Sets the default executor to use for new DataFlow instances
     * @param executor The executor to use
     */
    static void setDefaultPool(ConcurrentPool pool) {
        defaultPool = pool
        activePool.set (pool)
    }

    /**
     * Creates a new DataFlowVariable
     * @return A new DataFlowVariable
     */
    <T> DataflowVariable<T> variable() {
        return new DataflowVariable<T>()
    }

    /**
     * Creates a new DataFlowVariable with an initial value
     * @param initialValue The initial value for the variable
     * @return A new DataFlowVariable with the given initial value
     */
    <T> DataflowVariable<T> variable(T initialValue) {
        def variable = new DataflowVariable<T>()
        variable.set(initialValue)
        return variable
    }


    /**
     * Executes the given task asynchronously using the configured executor
     * @param task The task to execute
     * @return A DataFlowVariable that will contain the result of the task
     */
    static <T> DataflowVariable<T> task(Closure<T> task) {
        DataflowVariable variable = new DataflowVariable<T>()
        getActiveConcurrentPool().executor.submit {
            try {
                //todo if call throws execption outside of the variable,
                // then the value is never set to initialised and get will block ()
                T result = task.call()
                variable.set(result)
            } catch (Throwable e) {

                variable.setError(e)

                // Make sure the future is also completed exceptionally
                if (!variable.isDone()) {
                    variable.setError (e)
                }

                // Ensure the state is set to INITIALIZED
                variable.state.set(DataFlowExpression.DataFlowState.INITIALIZED)

                log.error("Task(): Task execution failed", e)
            }
        }
        return variable
    }

    /**
     * Returns the executor used by this DataFlow instance
     * @return The executor
     */
    ExecutorService getExecutor() {
        return getActiveConcurrentPool().executor
    }
}
