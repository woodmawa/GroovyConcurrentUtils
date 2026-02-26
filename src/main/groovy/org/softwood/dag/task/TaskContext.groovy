package org.softwood.dag.task

import org.softwood.config.ConfigLoader
import groovy.util.logging.Slf4j
import org.softwood.pool.ExecutorPool
import org.softwood.promise.PromiseFactory
import org.softwood.pool.ExecutorPoolFactory
import org.softwood.promise.core.dataflow.DataflowPromiseFactory
import org.softwood.dag.IBinding
import org.softwood.dag.TaskBinding
import org.softwood.dag.resilience.RateLimiter
import org.softwood.dag.resilience.RateLimiterRegistry
import org.softwood.dag.resilience.DeadLetterQueue
import org.softwood.dag.resilience.IdempotencyCache

/**
 * Shared context for a TaskGraph execution.
 *
 * <p>Owns the ExecutorPool and PromiseFactory used by all tasks in the graph.
 * The PromiseFactory is wired to use the same pool so that the hardened
 * ConcurrentPool backs all async task execution.</p>
 *
 * <p>Implements {@link Closeable}: call {@link #close()} when the graph is done
 * to shut down the underlying pool and release threads. The Tasks utility
 * methods do this automatically via try-with-resources.</p>
 */
@Slf4j
class TaskContext implements Closeable {

    final IBinding globals = new TaskBinding()
    final IBinding credentials = new TaskBinding()
    final Map config
    final ExecutorPool pool
    final PromiseFactory promiseFactory
    
    // Resilience service registries
    final RateLimiterRegistry rateLimiters = new RateLimiterRegistry()
    
    // Dead Letter Queue (optional - set by TaskGraph or manually)
    DeadLetterQueue deadLetterQueue = null
    
    // Idempotency Cache (optional - set by TaskGraph or manually)
    IdempotencyCache idempotencyCache = null
    
    // Resource monitoring (set by TaskGraph if configured)
    def resourceMonitor = null

    // Reference to the owning TaskGraph (set by TaskGraph when execution starts)
    def graph = null

    /**
     * Default constructor - creates a dag-pool and wires it into the PromiseFactory.
     * This ensures the hardened ConcurrentPool backs all async task execution.
     */
    TaskContext() {
        this.config = ConfigLoader.loadConfig()
        this.pool   = ExecutorPoolFactory.builder()
                .name("dag-pool")
                .build()
        // FIX C2: pass pool into factory so all executeAsync uses the hardened pool
        this.promiseFactory = new DataflowPromiseFactory(pool)
    }

    /**
     * For testing - inject both pool and promiseFactory explicitly.
     */
    TaskContext(ExecutorPool pool, PromiseFactory promiseFactory) {
        this.config = ConfigLoader.loadConfig()
        this.pool   = pool
        this.promiseFactory = promiseFactory
    }

    /**
     * Inject a custom pool; factory is wired to use it.
     */
    TaskContext(ExecutorPool pool) {
        this.config = ConfigLoader.loadConfig()
        this.pool   = pool
        // FIX C2: factory uses the injected pool
        this.promiseFactory = new DataflowPromiseFactory(pool)
    }

    TaskContext(Map config) {
        this.config = config ?: ConfigLoader.loadConfig()
        this.pool   = ExecutorPoolFactory.builder()
                .name("dag-pool")
                .build()
        // FIX C2: factory uses the built pool
        this.promiseFactory = new DataflowPromiseFactory(pool)
    }

    // -----------------------
    //  BUILDER
    // -----------------------
    static Builder builder() { new Builder() }

    static class Builder {
        private ExecutorPool pool
        private PromiseFactory promiseFactory
        private Map config

        Builder pool(ExecutorPool p) {
            this.pool = p
            return this
        }

        Builder promiseFactory(PromiseFactory pf) {
            this.promiseFactory = pf
            return this
        }

        Builder config(Map c) {
            this.config = c
            return this
        }

        TaskContext build() {
            Map cfg = config ?: ConfigLoader.loadConfig()

            if (!pool) {
                pool = ExecutorPoolFactory.builder()
                        .name("dag-pool")
                        .build()
            }
            if (!promiseFactory) {
                // FIX C2: default factory always wired to the pool
                promiseFactory = new DataflowPromiseFactory(pool)
            }

            return new TaskContext(pool, promiseFactory)
        }
    }

    Object get(String key) {
        globals.get(key)
    }

    void set(String key, Object value) {
        globals.set(key, value)
    }
    
    /**
     * Create or get a rate limiter using DSL configuration.
     *
     * @param name unique name for the rate limiter
     * @param config DSL configuration closure
     * @return the rate limiter
     */
    RateLimiter rateLimiter(String name, @DelegatesTo(org.softwood.dag.resilience.RateLimiterConfigDsl) Closure config) {
        return rateLimiters.create(name, config)
    }
    
    /**
     * Get or create the Dead Letter Queue for this context.
     */
    DeadLetterQueue getDeadLetterQueue() {
        if (deadLetterQueue == null) {
            deadLetterQueue = new DeadLetterQueue()
        }
        return deadLetterQueue
    }
    
    /**
     * Get or create the Idempotency Cache for this context.
     */
    IdempotencyCache getIdempotencyCache() {
        if (idempotencyCache == null) {
            idempotencyCache = new IdempotencyCache()
        }
        return idempotencyCache
    }

    /**
     * FIX C4: Shut down the pool when the context is done.
     * Call this after graph completion to release threads.
     * Idempotent - safe to call multiple times.
     */
    @Override
    void close() {
        try {
            if (pool != null) {
                log.debug "TaskContext.close(): shutting down pool '${pool.name ?: 'dag-pool'}'"
                pool.shutdown()
            }
        } catch (Exception e) {
            log.warn "TaskContext.close(): error shutting down pool", e
        }
    }
}
