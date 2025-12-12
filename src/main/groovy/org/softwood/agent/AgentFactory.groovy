package org.softwood.agent

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.softwood.pool.ExecutorPool
import org.softwood.pool.ExecutorPoolFactory

import java.util.function.Supplier

/**
 * Factory for creating Agent instances.
 *
 * <p>Provides convenient methods for agent construction with optional customization
 * of executors, copy strategies, and other settings.</p>
 *
 * <h2>Usage Examples</h2>
 * <pre>
 * // Simple agent with default settings
 * def agent = AgentFactory.create([count: 0])
 *
 * // Agent with custom pool
 * def pool = ExecutorPoolFactory.builder().name("my-pool").build()
 * def agent = AgentFactory.builder([count: 0])
 *     .pool(pool)
 *     .build()
 *
 * // Agent with custom copy strategy
 * def agent = AgentFactory.builder(myObject)
 *     .copyStrategy({ -> myObject.clone() })
 *     .build()
 *
 * // Agent with queue size limit
 * def agent = AgentFactory.builder([count: 0])
 *     .maxQueueSize(1000)
 *     .build()
 * </pre>
 */
@Slf4j
@CompileStatic
class AgentFactory {

    /** Shared default pool for agents that don't specify their own */
    private static volatile ExecutorPool defaultPoolInstance

    /**
     * Returns the shared default pool, creating it lazily if needed.
     * Uses virtual threads if available (Java 21+).
     */
    static ExecutorPool defaultPool() {
        if (defaultPoolInstance == null) {
            synchronized (AgentFactory) {
                if (defaultPoolInstance == null) {
                    defaultPoolInstance = ExecutorPoolFactory.builder()
                            .name("agent-default-pool")
                            .build()
                }
            }
        }
        return defaultPoolInstance
    }

    /**
     * Creates an agent with default settings.
     *
     * @param initialValue initial state to wrap
     * @return new Agent instance
     */
    static <T> Agent<T> create(T initialValue) {
        return new GroovyAgent<T>(initialValue, null, null, false)
    }

    /**
     * Creates a builder for constructing an agent with custom settings.
     *
     * @param initialValue initial state to wrap
     * @return builder instance
     */
    static <T> Builder<T> builder(T initialValue) {
        return new Builder<>(initialValue)
    }

    // ----------------------------------------------------------------------
    // Builder
    // ----------------------------------------------------------------------

    /**
     * Builder for constructing agents with custom configuration.
     */
    static class Builder<T> {
        private final T initialValue
        private ExecutorPool pool
        private Supplier<T> copyStrategy
        private boolean ownsPool = false
        private Integer maxQueueSize
        private Closure<Void> errorHandler

        Builder(T value) {
            this.initialValue = value
        }

        /**
         * Sets a custom ExecutorPool for this agent.
         *
         * @param pool executor pool to use
         * @return this builder
         */
        Builder<T> pool(ExecutorPool pool) {
            this.pool = pool
            this.ownsPool = false  // External pool
            return this
        }

        /**
         * Sets a custom ExecutorPool that this agent will own and shutdown.
         *
         * @param pool executor pool to use and own
         * @return this builder
         */
        Builder<T> poolOwned(ExecutorPool pool) {
            this.pool = pool
            this.ownsPool = true
            return this
        }

        /**
         * Sets a custom copy strategy for creating immutable snapshots.
         *
         * @param strategy supplier that returns a defensive copy
         * @return this builder
         */
        Builder<T> copyStrategy(Supplier<T> strategy) {
            this.copyStrategy = strategy
            return this
        }

        /**
         * Sets the maximum queue size (0 = unbounded).
         *
         * @param max maximum queue size
         * @return this builder
         */
        Builder<T> maxQueueSize(int max) {
            if (max < 0) {
                throw new IllegalArgumentException("Max queue size cannot be negative")
            }
            this.maxQueueSize = max
            return this
        }

        /**
         * Sets an error handler for task failures.
         *
         * @param handler closure to handle errors
         * @return this builder
         */
        Builder<T> onError(Closure<Void> handler) {
            this.errorHandler = handler
            return this
        }

        /**
         * Builds the Agent instance.
         *
         * @return configured Agent
         */
        Agent<T> build() {
            Agent<T> agent = new GroovyAgent<T>(initialValue, copyStrategy, pool, ownsPool)
            
            if (maxQueueSize != null) {
                agent.setMaxQueueSize(maxQueueSize)
            }
            
            if (errorHandler != null) {
                agent.onError(errorHandler)
            }
            
            return agent
        }
    }

    // ----------------------------------------------------------------------
    // Convenience Methods
    // ----------------------------------------------------------------------

    /**
     * Creates an agent with a specific named pool.
     *
     * @param initialValue initial state
     * @param poolName name for the executor pool
     * @return new Agent instance
     */
    static <T> Agent<T> createWithPool(T initialValue, String poolName) {
        ExecutorPool pool = ExecutorPoolFactory.builder()
                .name(poolName)
                .build()
        return new GroovyAgent<T>(initialValue, null, pool, true)  // Owns pool
    }

    /**
     * Creates an agent using a shared pool instance.
     *
     * @param initialValue initial state
     * @param pool shared pool to use
     * @return new Agent instance
     */
    static <T> Agent<T> createWithSharedPool(T initialValue, ExecutorPool pool) {
        return new GroovyAgent<T>(initialValue, null, pool, false)  // Doesn't own pool
    }

    /**
     * Creates an agent with a queue size limit.
     *
     * @param initialValue initial state
     * @param maxQueueSize maximum queue size
     * @return new Agent instance
     */
    static <T> Agent<T> createWithQueueLimit(T initialValue, int maxQueueSize) {
        Agent<T> agent = new GroovyAgent<T>(initialValue, null, null, false)
        agent.setMaxQueueSize(maxQueueSize)
        return agent
    }
}
