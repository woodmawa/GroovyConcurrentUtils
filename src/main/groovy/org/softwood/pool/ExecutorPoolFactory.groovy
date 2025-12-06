package org.softwood.pool

import groovy.util.logging.Slf4j

import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService

/**
 * Factory and builder for creating ExecutorPool instances.
 *
 * This allows client code to provide custom executors, names,
 * and scheduled executors without tightly coupling to ConcurrentPool.
 */
@Slf4j
class ExecutorPoolFactory {

    /**
     * Wrap an existing ExecutorService in a ConcurrentPool.
     * The resulting pool does NOT own the executor.
     */
    static ExecutorPool wrap(ExecutorService executor) {
        assert executor != null : "ExecutorService cannot be null"
        return new ConcurrentPool(executor)
    }

    /**
     * Create a builder for constructing a custom ExecutorPool.
     */
    static Builder builder() {
        return new Builder()
    }

    // ----------------------------------------------------------------------
    // Builder
    // ----------------------------------------------------------------------

    static class Builder {

        private ExecutorService executor
        private ScheduledExecutorService scheduler
        private String name
        private boolean ownsExecutor = false
        private boolean ownsScheduler = false

        /**
         * Use a specific ExecutorService.
         * If provided, the pool will NOT own the executor.
         */
        Builder executor(ExecutorService executor) {
            this.executor = executor
            this.ownsExecutor = false
            return this
        }

        /**
         * Use a specific ScheduledExecutorService.
         * If provided, the pool will NOT own the scheduler.
         */
        Builder scheduler(ScheduledExecutorService scheduler) {
            this.scheduler = scheduler
            this.ownsScheduler = false
            return this
        }

        /**
         * Optional logical name for logging / debugging.
         */
        Builder name(String name) {
            this.name = name
            return this
        }

        /**
         * Build the ExecutorPool.
         *
         * Rules:
         * - If custom executor is given → wrap it
         * - If no executor is given → use ConcurrentPool defaults
         */
        ExecutorPool build() {
            ExecutorPool pool

            if (executor != null) {
                // Wrap user-provided executor
                pool = new ConcurrentPool(executor, scheduler)
                if (name) {
                    pool.name = name
                }
                return pool
            }

            // Use default ConcurrentPool behavior
            pool = new ConcurrentPool(name)

            // Custom scheduler if provided
            if (scheduler != null) {
                pool.setScheduledExecutor(scheduler)
            }

            return pool
        }
    }
}