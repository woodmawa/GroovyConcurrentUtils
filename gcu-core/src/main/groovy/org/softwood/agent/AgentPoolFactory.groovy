package org.softwood.agent

import groovy.transform.CompileStatic
import org.softwood.pool.ExecutorPool
import org.softwood.pool.ExecutorPoolFactory

import java.util.function.Supplier

/**
 * Factory for creating AgentPool instances.
 *
 * <h2>Usage Examples</h2>
 * <pre>
 * // Create pool with 4 agents, each managing [count: 0]
 * def pool = AgentPoolFactory.create(4) { [count: 0] }
 *
 * // Create pool with shared executor
 * def execPool = ExecutorPoolFactory.builder().name("shared").build()
 * def pool = AgentPoolFactory.builder(4) { [count: 0] }
 *     .sharedPool(execPool)
 *     .build()
 *
 * // Create pool with per-agent configuration
 * def pool = AgentPoolFactory.builder(4) { [count: 0] }
 *     .maxQueueSize(1000)
 *     .onError { e -> log.error("Task failed", e) }
 *     .build()
 * </pre>
 */
@CompileStatic
class AgentPoolFactory {

    /**
     * Creates an agent pool with default settings.
     *
     * @param size number of agents in the pool
     * @param stateFactory closure that creates initial state for each agent
     * @return new AgentPool instance
     */
    static <T> AgentPool<T> create(int size, Closure<T> stateFactory) {
        if (size <= 0) {
            throw new IllegalArgumentException("Pool size must be positive")
        }
        
        List<Agent<T>> agents = new ArrayList<>(size)
        for (int i = 0; i < size; i++) {
            T initialState = stateFactory.call()
            agents.add(AgentFactory.create(initialState))
        }
        
        return new AgentPool<T>(agents)
    }

    /**
     * Creates a builder for constructing an agent pool with custom settings.
     *
     * @param size number of agents in the pool
     * @param stateFactory closure that creates initial state for each agent
     * @return builder instance
     */
    static <T> Builder<T> builder(int size, Closure<T> stateFactory) {
        return new Builder<>(size, stateFactory)
    }

    // ----------------------------------------------------------------------
    // Builder
    // ----------------------------------------------------------------------

    /**
     * Builder for constructing agent pools with custom configuration.
     */
    static class Builder<T> {
        private final int size
        private final Closure<T> stateFactory
        private ExecutorPool sharedPool
        private Supplier<T> copyStrategy
        private Integer maxQueueSize
        private Closure<Void> errorHandler
        private boolean ownsPools = false

        Builder(int size, Closure<T> stateFactory) {
            if (size <= 0) {
                throw new IllegalArgumentException("Pool size must be positive")
            }
            this.size = size
            this.stateFactory = stateFactory
        }

        /**
         * Use a shared ExecutorPool for all agents.
         *
         * @param pool shared executor pool
         * @return this builder
         */
        Builder<T> sharedPool(ExecutorPool pool) {
            this.sharedPool = pool
            this.ownsPools = false
            return this
        }

        /**
         * Sets a custom copy strategy for all agents.
         *
         * @param strategy supplier that returns a defensive copy
         * @return this builder
         */
        Builder<T> copyStrategy(Supplier<T> strategy) {
            this.copyStrategy = strategy
            return this
        }

        /**
         * Sets max queue size for all agents.
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
         * Sets an error handler for all agents.
         *
         * @param handler closure to handle errors
         * @return this builder
         */
        Builder<T> onError(Closure<Void> handler) {
            this.errorHandler = handler
            return this
        }

        /**
         * Builds the AgentPool instance.
         *
         * @return configured AgentPool
         */
        AgentPool<T> build() {
            List<Agent<T>> agents = new ArrayList<>(size)
            
            for (int i = 0; i < size; i++) {
                T initialState = stateFactory.call()
                
                def agentBuilder = AgentFactory.builder(initialState)
                
                if (sharedPool != null) {
                    agentBuilder.pool(sharedPool)
                }
                
                if (copyStrategy != null) {
                    agentBuilder.copyStrategy(copyStrategy)
                }
                
                if (maxQueueSize != null) {
                    agentBuilder.maxQueueSize(maxQueueSize)
                }
                
                if (errorHandler != null) {
                    agentBuilder.onError(errorHandler)
                }
                
                agents.add(agentBuilder.build())
            }
            
            return new AgentPool<T>(agents)
        }
    }

    // ----------------------------------------------------------------------
    // Convenience Methods
    // ----------------------------------------------------------------------

    /**
     * Creates an agent pool where each agent has its own dedicated pool.
     *
     * @param size number of agents
     * @param stateFactory closure creating initial state
     * @param poolNamePrefix prefix for executor pool names
     * @return new AgentPool instance
     */
    static <T> AgentPool<T> createWithDedicatedPools(int size, Closure<T> stateFactory, String poolNamePrefix) {
        if (size <= 0) {
            throw new IllegalArgumentException("Pool size must be positive")
        }
        
        List<Agent<T>> agents = new ArrayList<>(size)
        for (int i = 0; i < size; i++) {
            T initialState = stateFactory.call()
            String poolName = "${poolNamePrefix}-${i}"
            agents.add(AgentFactory.createWithPool(initialState, poolName))
        }
        
        return new AgentPool<T>(agents)
    }

    /**
     * Creates an agent pool with queue size limits on all agents.
     *
     * @param size number of agents
     * @param stateFactory closure creating initial state
     * @param maxQueueSize queue size limit per agent
     * @return new AgentPool instance
     */
    static <T> AgentPool<T> createWithQueueLimit(int size, Closure<T> stateFactory, int maxQueueSize) {
        return builder(size, stateFactory)
                .maxQueueSize(maxQueueSize)
                .build()
    }
}
