package org.softwood.promise.core

import groovy.util.logging.Slf4j
import org.softwood.pool.ConcurrentPool
import org.softwood.pool.ExecutorPool

/**
 * Thread-local context for managing ExecutorPool instances used by Promises.
 *
 * This allows client code to inject custom pools (including mocks) that will be
 * used by all Promise operations within a scope.
 *
 * Usage:
 * <pre>
 * def customPool = new ConcurrentPool(4)
 * PromisePoolContext.withPool(customPool) {
 *     def p = Promises.async { ... }  // uses customPool
 * }
 * </pre>
 */
@Slf4j
class PromisePoolContext {

    private static final ThreadLocal<ExecutorPool> poolHolder = new ThreadLocal<>()
    private static ExecutorPool defaultPool = new ConcurrentPool("default-promise-pool")

    /**
     * Get the current pool for this thread/context.
     * Falls back to default pool if none is set.
     */
    static ExecutorPool getCurrentPool() {
        ExecutorPool pool = poolHolder.get()
        if (pool != null) {
            return pool
        }

        // Lazy init default pool
        if (defaultPool == null) {
            synchronized (PromisePoolContext) {
                if (defaultPool == null) {
                    defaultPool = new ConcurrentPool("default-promise-pool")
                }
            }
        }

        return defaultPool
    }

    /**
     * Set the default pool used when no thread-local pool is set.
     * This affects all threads that haven't explicitly set a pool.
     */
    static void setDefaultPool(ExecutorPool pool) {
        defaultPool = pool
    }

    /**
     * Execute a closure with a specific pool active for this thread.
     * The pool is automatically cleared after the closure completes.
     *
     * @param pool the pool to use within the closure scope
     * @param closure the code to execute with the pool active
     * @return the result of the closure
     */
    static <T> T withPool(ExecutorPool pool, Closure<T> closure) {
        ExecutorPool previous = poolHolder.get()
        try {
            poolHolder.set(pool)
            return closure.call()
        } finally {
            if (previous == null) {
                poolHolder.remove()
            } else {
                poolHolder.set(previous)
            }
        }
    }

    /**
     * Execute a closure with a specific pool active for this thread.
     * Variant that doesn't return a value.
     */
    static void withPoolVoid(ExecutorPool pool, Closure closure) {
        withPool(pool, closure)
    }

    /**
     * Explicitly set the pool for the current thread.
     * Remember to call clearPool() when done.
     */
    static void setPool(ExecutorPool pool) {
        poolHolder.set(pool)
    }

    /**
     * Clear the thread-local pool, reverting to default.
     */
    static void clearPool() {
        poolHolder.remove()
    }

    /**
     * Check if a thread-local pool is currently set.
     */
    static boolean hasThreadLocalPool() {
        return poolHolder.get() != null
    }
}