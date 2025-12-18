package org.softwood.actor

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.softwood.pool.ExecutorPool
import org.softwood.pool.ExecutorPoolFactory

/**
 * Factory for creating Actor instances.
 *
 * <p><b>⚠️ IMPORTANT:</b> For most production use cases, create actors via {@link ActorSystem}
 * instead of using ActorFactory directly. ActorFactory is intended for:
 * <ul>
 *   <li><b>Testing:</b> Creating standalone mock actors</li>
 *   <li><b>Advanced configuration:</b> Custom pool setup before system registration</li>
 *   <li><b>Standalone actors:</b> Actors that don't need system features</li>
 * </ul>
 *
 * <p><b>⚠️ Standalone actors created with ActorFactory.create() cannot use:</b>
 * <ul>
 *   <li>{@code ctx.spawn()} - requires ActorSystem</li>
 *   <li>{@code ctx.watch()} - requires DeathWatch registry</li>
 *   <li>{@code ctx.scheduleOnce()} - requires Scheduler</li>
 *   <li>{@code ctx.spawnForEach()} - requires ActorSystem</li>
 *   <li>Parent-child hierarchies</li>
 *   <li>System-wide shutdown coordination</li>
 * </ul>
 *
 * <h2>✅ Recommended: Use ActorSystem (Production Code)</h2>
 * <pre>
 * def system = new ActorSystem("my-app")
 * 
 * // Create actors through the system
 * def actor = system.actor {
 *     name 'Counter'
 *     onMessage { msg, ctx -&gt;
 *         ctx.state.count = (ctx.state.count ?: 0) + 1
 *         ctx.reply(ctx.state.count)
 *     }
 * }
 * 
 * system.shutdown()  // Coordinated shutdown
 * </pre>
 *
 * <h2>⚠️ Advanced: Standalone Actor (Limited Features)</h2>
 * <pre>
 * // Only for testing or advanced use cases
 * def actor = ActorFactory.create("test-actor") { msg, ctx -&gt;
 *     ctx.reply("mocked response")
 * }
 * 
 * // Or with custom configuration
 * def actor = ActorFactory.builder("worker", handler)
 *     .poolOwned(customPool)
 *     .maxMailboxSize(10000)
 *     .build()
 * </pre>
 *
 * @see ActorSystem Primary entry point for production actor creation
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
    // DSL Entry Point (merged from ActorDSL)
    // ----------------------------------------------------------------------

    /**
     * Create an actor using builder DSL.
     * 
     * <p>This is the main entry point for the actor DSL. Import statically for cleanest syntax:</p>
     * <pre>
     * import static org.softwood.actor.ActorFactory.actor
     * 
     * def myActor = actor(system) {
     *     name "Printer"
     *     onMessage { msg, ctx -&gt;
     *         println "[\$ctx.actorName] \$msg"
     *     }
     * }
     * </pre>
     * 
     * @param system the actor system
     * @param spec DSL configuration closure
     * @return configured actor
     */
    static Actor actor(
            ActorSystem system,
            @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = ActorBuilder)
                    Closure<?> spec) {

        def builder = new ActorBuilder(system)
        spec.delegate = builder
        spec.resolveStrategy = Closure.DELEGATE_FIRST
        spec.call()

        return builder.build()
    }

    // ----------------------------------------------------------------------
    // Factory Methods
    // ----------------------------------------------------------------------

    /**
     * Creates an actor with default settings.
     * 
     * <p><b>⚠️ WARNING:</b> This creates a standalone actor that is NOT registered with an ActorSystem.
     * The actor will not have access to:
     * <ul>
     *   <li>{@code ctx.spawn()} - for creating child actors</li>
     *   <li>{@code ctx.watch()} - for lifecycle monitoring</li>
     *   <li>{@code ctx.scheduleOnce()} - for delayed messages</li>
     *   <li>{@code ctx.spawnForEach()} - for bulk child creation</li>
     * </ul>
     * 
     * <p><b>✅ Recommended:</b> Use {@code ActorSystem.actor()} instead for production code.</p>
     * 
     * <p>This method is primarily for:
     * <ul>
     *   <li>Unit testing (creating mock actors)</li>
     *   <li>Standalone actors that don't need system features</li>
     * </ul>
     *
     * @param name actor name
     * @param handler message handler closure
     * @param initialState initial state map (optional)
     * @return configured actor (standalone, not registered)
     */
    static Actor create(String name, Closure handler, Map initialState = [:]) {
        log.debug("Creating standalone actor '{}' via ActorFactory.create(). " +
                  "Consider using ActorSystem.actor() for full feature support.", name)
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
