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

@Slf4j
class TaskContext {

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

    //default empty constructor  - wont have a graph set, create a real ConcurrentPool
    TaskContext() {
        this.promiseFactory = new DataflowPromiseFactory()
        this.config = ConfigLoader.loadConfig()
        this.pool   = ExecutorPoolFactory.builder()
                .name("dag-pool")
                .build()
    }

    //for testing
    TaskContext(ExecutorPool pool, PromiseFactory promiseFactory) {
        this.config = ConfigLoader.loadConfig()
        this.pool   = pool
        this.promiseFactory = promiseFactory
    }

    // Explicit: inject a custom pool (FakePool or ConcurrentPool)
    TaskContext (ExecutorPool pool) {
        this.promiseFactory = new DataflowPromiseFactory()
        this.config = ConfigLoader.loadConfig()
        this.pool   = pool
    }


    TaskContext(Map config) {
        this.promiseFactory = new DataflowPromiseFactory()
        this.config = config ?: ConfigLoader.loadConfig()      // uses  ConfigLoader as fallback
        this.pool   = ExecutorPoolFactory.builder()
                .name("dag-pool")
                .build()    // uses virtual threads if available
    }


    // -----------------------
    // 💥 ADD BUILDER HERE
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
            // Default config if user doesn't supply one
            Map cfg = config ?: ConfigLoader.loadConfig()

            if (!pool) {
                // Normal default behaviour
                pool = ExecutorPoolFactory.builder()
                        .name("dag-pool")
                        .build()
            }
            if (!promiseFactory) {
                promiseFactory = new DataflowPromiseFactory()
            }

            // Reuse your existing constructor
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
     * <h3>Example:</h3>
     * <pre>
     * ctx.rateLimiter("api-limiter") {
     *     maxRequests 100
     *     timeWindow Duration.ofMinutes(1)
     *     strategy "sliding-window"
     * }
     * </pre>
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
     * Creates a default DLQ if one doesn't exist.
     * 
     * @return the dead letter queue
     */
    DeadLetterQueue getDeadLetterQueue() {
        if (deadLetterQueue == null) {
            deadLetterQueue = new DeadLetterQueue()
        }
        return deadLetterQueue
    }
    
    /**
     * Get or create the Idempotency Cache for this context.
     * Creates a default cache if one doesn't exist.
     * 
     * @return the idempotency cache
     */
    IdempotencyCache getIdempotencyCache() {
        if (idempotencyCache == null) {
            idempotencyCache = new IdempotencyCache()
        }
        return idempotencyCache
    }
}
