package org.softwood.actor

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.softwood.pool.ExecutorPool
import org.softwood.pool.ExecutorPoolFactory

/**
 * Factory for creating Actor instances.
 *
 * <p>Provides convenient factory methods and a builder pattern for
 * configuring actors with custom pools, error handlers, and mailbox limits.</p>
 *
 * <h2>Usage Examples</h2>
 * <pre>
 * // Simple creation
 * def actor = ActorFactory.create("counter") { msg, ctx -&gt;
 *     ctx.state.count = (ctx.state.count ?: 0) + 1
 *     ctx.reply(ctx.state.count)
 * }
 *
 * // With configuration
 * def actor = ActorFactory.builder("processor") { msg, ctx -&gt;
 *     // handler logic
 * }
 *     .initialState([processed: 0])
 *     .maxMailboxSize(1000)
 *     .maxErrorsRetained(50)
 *     .onError({ e -&gt; log.error("Error", e) })
 *     .build()
 * </pre>
 */
@Slf4j
@CompileStatic
class ActorFactory {

    private static volatile ExecutorPool defaultPool

    /**
     * Returns the default executor pool for actors.
     * Lazily initialized on first access.
     */
    static synchronized ExecutorPool defaultPool() {
        if (defaultPool == null) {
            defaultPool = ExecutorPoolFactory.builder()
                    .name("default-actor-pool")
                    .build()
        }
        return defaultPool
    }

    /**
     * Sets a custom default pool for all actors.
     * Must be called before any actors are created.
     *
     * @param pool the pool to use as default
     */
    static synchronized void setDefaultPool(ExecutorPool pool) {
        if (pool == null) {
            throw new IllegalArgumentException("Pool cannot be null")
        }
        defaultPool = pool
    }

    // ----------------------------------------------------------------------
    // Factory Methods
    // ----------------------------------------------------------------------

    /**
     * Creates an actor with default settings.
     *
     * @param name actor name
     * @param handler message handler closure
     * @param initialState initial state map (optional)
     * @return configured actor
     */
    static Actor create(String name, Closure handler, Map initialState = [:]) {
        new GroovyActor(name, initialState, handler, defaultPool(), false, 100, 0)
    }

    /**
     * Returns a builder for custom actor configuration.
     *
     * @param name actor name
     * @param handler message handler closure
     * @return builder instance
     */
    static Builder builder(String name, Closure handler) {
        new Builder(name, handler)
    }

    // ----------------------------------------------------------------------
    // Convenience Methods
    // ----------------------------------------------------------------------

    /**
     * Creates an actor with a named dedicated pool.
     * The actor owns the pool and will shut it down on stop.
     *
     * @param name actor name
     * @param handler message handler
     * @param poolName name for the dedicated pool
     * @return configured actor
     */
    static Actor createWithPool(String name, Closure handler, String poolName) {
        def pool = ExecutorPoolFactory.builder().name(poolName).build()
        new GroovyActor(name, [:], handler, pool, true, 100, 0)
    }

    /**
     * Creates an actor with a shared pool.
     * The actor does NOT own the pool and will not shut it down.
     *
     * @param name actor name
     * @param handler message handler
     * @param pool shared executor pool
     * @return configured actor
     */
    static Actor createWithSharedPool(String name, Closure handler, ExecutorPool pool) {
        new GroovyActor(name, [:], handler, pool, false, 100, 0)
    }

    /**
     * Creates an actor with a mailbox size limit.
     *
     * @param name actor name
     * @param handler message handler
     * @param maxMailboxSize maximum pending messages
     * @return configured actor
     */
    static Actor createWithMailboxLimit(String name, Closure handler, int maxMailboxSize) {
        builder(name, handler)
                .maxMailboxSize(maxMailboxSize)
                .build()
    }

    // ----------------------------------------------------------------------
    // Builder
    // ----------------------------------------------------------------------

    /**
     * Builder for configuring actor creation.
     */
    @CompileStatic
    static class Builder {
        private final String name
        private final Closure handler
        private Map initialState = [:]
        private ExecutorPool pool
        private boolean ownsPool = false
        private Integer maxMailboxSize
        private Integer maxErrorsRetained
        private Closure<Void> errorHandler

        Builder(String name, Closure handler) {
            this.name = name
            this.handler = handler
        }

        /**
         * Sets the initial state for the actor.
         *
         * @param state initial state map
         * @return this builder
         */
        Builder initialState(Map state) {
            this.initialState = state ?: [:]
            return this
        }

        /**
         * Uses a specific executor pool (shared, not owned).
         *
         * @param pool executor pool to use
         * @return this builder
         */
        Builder pool(ExecutorPool pool) {
            this.pool = pool
            this.ownsPool = false
            return this
        }

        /**
         * Uses a specific executor pool and takes ownership.
         * Actor will shut down the pool when stopped.
         *
         * @param pool executor pool to own
         * @return this builder
         */
        Builder poolOwned(ExecutorPool pool) {
            this.pool = pool
            this.ownsPool = true
            return this
        }

        /**
         * Sets the maximum mailbox size.
         * When full, new messages are rejected.
         *
         * @param max maximum size (0 = unbounded)
         * @return this builder
         */
        Builder maxMailboxSize(int max) {
            if (max < 0) {
                throw new IllegalArgumentException("Max mailbox size cannot be negative")
            }
            this.maxMailboxSize = max
            return this
        }

        /**
         * Sets the maximum number of errors to retain.
         *
         * @param max maximum errors (default: 100)
         * @return this builder
         */
        Builder maxErrorsRetained(int max) {
            if (max < 1) {
                throw new IllegalArgumentException("Max errors retained must be positive")
            }
            this.maxErrorsRetained = max
            return this
        }

        /**
         * Sets an error handler for message processing failures.
         *
         * @param handler error handler closure
         * @return this builder
         */
        Builder onError(Closure<Void> handler) {
            this.errorHandler = handler
            return this
        }

        /**
         * Builds the configured actor.
         *
         * @return configured actor instance
         */
        Actor build() {
            ExecutorPool execPool = pool ?: defaultPool()

            Actor actor = new GroovyActor(
                    name,
                    initialState,
                    handler,
                    execPool,
                    ownsPool,
                    maxErrorsRetained ?: 100,
                    maxMailboxSize ?: 0  // Pass to constructor
            )

            if (errorHandler != null) {
                actor.onError(errorHandler)
            }

            return actor
        }
    }
}
