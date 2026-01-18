package org.softwood.dag.task

import groovy.transform.ToString
import groovy.util.logging.Slf4j
import org.softwood.promise.Promise
import org.softwood.promise.Promises

import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.softwood.dag.resilience.RateLimiter
import org.softwood.dag.resilience.RateLimitExceededException
import org.softwood.dag.resilience.TimeoutPolicy
import org.softwood.dag.resilience.TaskTimeoutException
import org.softwood.dag.resilience.DeadLetterQueuePolicy
import org.softwood.dag.resilience.DeadLetterQueue
import org.softwood.dag.resilience.IdempotencyPolicy
import org.softwood.dag.resilience.IdempotencyKey
import org.softwood.dag.resilience.IdempotencyCacheEntry

/**
 * Abstract base class for all DAG tasks.
 *
 * Implements ITask interface and provides:
 *  - retryPolicy (maxRetries, backoff, delay)
 *  - timeoutMillis
 *  - state handling
 *  - promise-based async execution
 */
@Slf4j
@ToString(includeNames = true, includeFields = true, excludes = ['ctx', 'eventDispatcher'])
abstract class TaskBase<T> implements ITask<T> {

    final String id
    final String name
    volatile TaskState state = TaskState.SCHEDULED
    TaskEventDispatch eventDispatcher

    // enable duck typing on context
    def ctx

    /**
     * Canonical DAG topology:
     * All DSLs and tests use only these.
     */
    final Set<String> predecessors = [] as Set
    final Set<String> successors   = [] as Set

    /** -------------------------------
     * Task runtime behaviour
     */
    protected RetryPolicy retryPolicy = new RetryPolicy()
    protected RateLimitPolicy rateLimitPolicy = new RateLimitPolicy()
    protected TimeoutPolicy timeoutPolicy = new TimeoutPolicy()
    protected DeadLetterQueuePolicy dlqPolicy = new DeadLetterQueuePolicy()
    protected IdempotencyPolicy idempotencyPolicy = new IdempotencyPolicy()
    private Long taskTimeoutMillis = null  // Legacy - kept for backwards compatibility
    protected Throwable lastError = null

    Promise<?> completionPromise = null
    private Optional<?> injectedInput = Optional.empty()

    // ----------------------------------------------------
    // constructor - expects id and a name and a graph ctx
    // ----------------------------------------------------
    TaskBase(String id, String name, ctx) {
        this.id = id
        this.name = name
        this.ctx = ctx
    }

    // ----------------------------------------------------
    // Canonical graph wiring
    // ----------------------------------------------------

    void dependsOn(String taskId) {
        predecessors << taskId
    }
    
    void dependsOn(ITask task) {
        predecessors << task.id
    }

    void addSuccessor(String taskId) {
        successors << taskId
    }
    
    void addSuccessor(ITask task) {
        successors << task.id
    }
    
    void addPredecessor(String taskId) {
        predecessors << taskId
    }
    
    void addPredecessor(ITask task) {
        predecessors << task.id
    }

    // ----------------------------------------------------
    // Synthetic properties
    // ----------------------------------------------------
    Integer getMaxRetries() { retryPolicy.maxAttempts }
    void setMaxRetries(Integer v) { retryPolicy.maxAttempts = v }

    Long getTimeoutMillis() { taskTimeoutMillis }
    void setTimeoutMillis(Long v) { taskTimeoutMillis = v }

    Throwable getError() { lastError }

    boolean getHasStarted() { state != TaskState.SCHEDULED }

    boolean isCompleted() { state == TaskState.COMPLETED }
    boolean isSkipped() { state == TaskState.SKIPPED }
    boolean isFailed() { state == TaskState.FAILED }

    void markScheduled() {
        state = TaskState.SCHEDULED
        emitEvent(TaskState.SCHEDULED)
    }

    void markCompleted() {
        state = TaskState.COMPLETED
        emitEvent(TaskState.COMPLETED)
    }

    void markFailed(Throwable error) {
        lastError = error
        state = TaskState.FAILED
        emitErrorEvent(error)
    }

    void markSkipped() {
        state = TaskState.SKIPPED
        emitEvent(TaskState.SKIPPED)
    }

    void setInjectedInput(Object data) {
        this.injectedInput = Optional.ofNullable(data)
    }

    // ----------------------------------------------------
    // Resolver Support
    // ----------------------------------------------------
    
    /**
     * Create a resolver for this task execution.
     * Provides DSL access to prev and ctx during task configuration.
     * 
     * @param prevValue Result from previous task
     * @param ctx Task context
     * @return TaskResolver instance
     */
    protected TaskResolver createResolver(Object prevValue, TaskContext ctx) {
        return new TaskResolver(prevValue, ctx)
    }
    
    /**
     * Execute a closure with resolver support.
     * 
     * This allows DSL closures to receive a resolver parameter for
     * accessing previous task results, globals, and credentials.
     * 
     * <h3>Example:</h3>
     * <pre>
     * task("example") { r ->
     *     // r is a TaskResolver
     *     def userId = r.prev.userId
     *     def apiKey = r.credential('api.key')
     *     r.setGlobal('processed', true)
     * }
     * </pre>
     * 
     * @param closure DSL closure (may or may not accept resolver parameter)
     * @param prevValue Previous task result
     * @param ctx Task context
     * @return Result from closure
     */
    protected Object executeWithResolver(Closure closure, Object prevValue, TaskContext ctx) {
        if (!closure) {
            return null
        }
        
        def resolver = createResolver(prevValue, ctx)
        
        // Check if closure accepts parameters
        if (closure.maximumNumberOfParameters > 0) {
            // Closure expects resolver parameter
            closure.delegate = resolver
            closure.resolveStrategy = Closure.DELEGATE_FIRST
            return closure.call(resolver)
        } else {
            // Closure doesn't expect parameters (legacy support)
            closure.delegate = this
            closure.resolveStrategy = Closure.DELEGATE_FIRST
            return closure.call()
        }
    }
    
    /**
     * Evaluate a value that may be static or a closure requiring resolver.
     * 
     * This is the key helper that makes DSL methods resolver-aware:
     * - If value is a Closure → execute with resolver
     * - If value is static → return as-is
     * 
     * <h3>Usage in Task DSL Methods:</h3>
     * <pre>
     * void url(Object urlValue) {
     *     this.urlValue = urlValue  // Store as-is
     * }
     * 
     * // Later in runTask():
     * String actualUrl = evaluateValue(urlValue, ctx, prevValue)
     * </pre>
     * 
     * <h3>Supports both patterns:</h3>
     * <pre>
     * url "https://api.example.com/data"  // Static
     * url { r -> r.global('api.url') }     // Dynamic with resolver
     * </pre>
     * 
     * @param value Static value or Closure
     * @param ctx Task context
     * @param prevValue Previous task result  
     * @return Evaluated value
     */
    protected Object evaluateValue(Object value, TaskContext ctx, Object prevValue) {
        if (value instanceof Closure) {
            return executeWithResolver((Closure) value, prevValue, ctx)
        }
        return value
    }
    
    /**
     * Configure a DSL delegate with resolver-aware closure execution.
     * 
     * This helper handles the common pattern of:
     * 1. Create a DSL object
     * 2. Set it as closure delegate
     * 3. Execute closure with resolver if needed
     * 
     * <h3>Usage Pattern:</h3>
     * <pre>
     * void config(@DelegatesTo(ConfigDsl) Closure configClosure) {
     *     def dsl = new ConfigDsl()
     *     configureDsl(dsl, configClosure, null, null)  // null = defer to runTask
     *     // OR during runTask:
     *     configureDsl(dsl, configClosure, ctx, prevValue)
     * }
     * </pre>
     * 
     * @param delegate DSL object to configure
     * @param config Configuration closure
     * @param ctx Task context (null = not executing yet)
     * @param prevValue Previous task result (null = not executing yet)
     * @return true if executed immediately, false if should be deferred
     */
    protected boolean configureDsl(Object delegate, Closure config, TaskContext ctx, Object prevValue) {
        if (!config) {
            return true
        }
        
        // Check if closure needs resolver (has parameters)
        if (config.maximumNumberOfParameters > 0) {
            // Needs resolver - check if we have context
            if (ctx == null) {
                // No context yet - cannot execute, caller should defer
                return false
            }
            
            // Execute with resolver
            def resolver = createResolver(prevValue, ctx)
            config.delegate = delegate
            config.resolveStrategy = Closure.DELEGATE_FIRST
            config.call(resolver)
            return true
            
        } else {
            // No parameters - execute immediately
            config.delegate = delegate
            config.resolveStrategy = Closure.DELEGATE_FIRST
            config.call()
            return true
        }
    }
    
    /**
     * Store a closure for deferred execution if it needs resolver.
     * 
     * This is a convenience method for the common pattern:
     * <pre>
     * if (closure.maximumNumberOfParameters > 0) {
     *     deferredClosure = closure
     *     return true
     * } else {
     *     // execute now
     *     return false
     * }
     * </pre>
     * 
     * @param closure Closure to check
     * @return true if closure needs resolver (should be deferred)
     */
    protected boolean needsResolver(Closure closure) {
        return closure != null && closure.maximumNumberOfParameters > 0
    }
    
    /**
     * Execute a deferred configuration closure with resolver.
     * 
     * Typical pattern in runTask():
     * <pre>
     * if (deferredConfig) {
     *     def dsl = new ConfigDsl()
     *     executeDeferredConfig(deferredConfig, dsl, ctx, prevValue)
     *     // Use configured dsl...
     * }
     * </pre>
     * 
     * @param config Deferred configuration closure
     * @param delegate DSL object to configure
     * @param ctx Task context
     * @param prevValue Previous task result
     */
    protected void executeDeferredConfig(Closure config, Object delegate, TaskContext ctx, Object prevValue) {
        if (config) {
            def resolver = createResolver(prevValue, ctx)
            config.delegate = delegate
            config.resolveStrategy = Closure.DELEGATE_FIRST
            config.call(resolver)
        }
    }
    
    // ----------------------------------------------------
    // Retry DSL Configuration
    // ----------------------------------------------------
    
    /**
     * Configure retry behavior using a DSL block.
     * 
     * <h3>Example:</h3>
     * <pre>
     * serviceTask("api-call") {
     *     retry {
     *         maxAttempts 5
     *         initialDelay Duration.ofMillis(100)
     *         backoffMultiplier 2.0
     *         circuitBreaker Duration.ofMinutes(5)
     *     }
     *     action { ctx, prev -> ... }
     * }
     * </pre>
     * 
     * @param config DSL configuration closure
     */
    void retry(@DelegatesTo(RetryDsl) Closure config) {
        def dsl = new RetryDsl(this.retryPolicy)
        config.delegate = dsl
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.call()
    }
    
    /**
     * Apply a retry preset by name.
     * 
     * Available presets:
     * - "aggressive": 5 attempts, 100ms delay, 2.0x backoff
     * - "moderate": 3 attempts, 500ms delay, 1.5x backoff
     * - "conservative": 2 attempts, 1s delay, no backoff
     * - "none": disable retries
     * 
     * <h3>Example:</h3>
     * <pre>
     * serviceTask("api-call") {
     *     retry "aggressive"
     *     action { ctx, prev -> ... }
     * }
     * </pre>
     * 
     * @param preset name of the preset (case-insensitive)
     */
    void retry(String preset) {
        def dsl = new RetryDsl(this.retryPolicy)
        dsl.preset(preset)
    }
    
    // ----------------------------------------------------
    // Rate Limit DSL Configuration
    // ----------------------------------------------------
    
    /**
     * Configure rate limiting behavior using a DSL block.
     * 
     * <h3>Example:</h3>
     * <pre>
     * serviceTask("api-call") {
     *     rateLimit {
     *         name "api-limiter"
     *         enabled true
     *         onLimitExceeded { ctx -> 
     *             log.warn("Rate limited: \${ctx.taskId}")
     *         }
     *     }
     *     action { ctx, prev -> ... }
     * }
     * </pre>
     * 
     * @param config DSL configuration closure
     */
    void rateLimit(@DelegatesTo(RateLimitDsl) Closure config) {
        def dsl = new RateLimitDsl(this.rateLimitPolicy)
        config.delegate = dsl
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.call()
    }
    
    /**
     * Configure rate limiting with just a limiter name (simple form).
     * Automatically enables rate limiting.
     * 
     * <h3>Example:</h3>
     * <pre>
     * serviceTask("api-call") {
     *     rateLimit "api-limiter"
     *     action { ctx, prev -> ... }
     * }
     * </pre>
     * 
     * @param limiterName name of the rate limiter to use
     */
    void rateLimit(String limiterName) {
        rateLimitPolicy.name = limiterName
        rateLimitPolicy.enabled = true
    }
    
    // ----------------------------------------------------
    // Timeout DSL Configuration
    // ----------------------------------------------------
    
    /**
     * Configure timeout behavior using a DSL block.
     * 
     * <h3>Example:</h3>
     * <pre>
     * serviceTask("slow-task") {
     *     timeout {
     *         duration Duration.ofSeconds(30)
     *         warningThreshold Duration.ofSeconds(25)
     *         onWarning { ctx -> log.warn("Task taking too long") }
     *         onTimeout { ctx -> log.error("Task timed out") }
     *     }
     *     action { ctx, prev -> ... }
     * }
     * </pre>
     * 
     * @param config DSL configuration closure
     */
    void timeout(@DelegatesTo(org.softwood.dag.resilience.TimeoutDsl) Closure config) {
        def dsl = new org.softwood.dag.resilience.TimeoutDsl(this.timeoutPolicy)
        config.delegate = dsl
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.call()
    }
    
    /**
     * Configure timeout with just a duration (simple form).
     * Automatically enables timeout.
     * 
     * <h3>Example:</h3>
     * <pre>
     * serviceTask("api-call") {
     *     timeout Duration.ofSeconds(30)
     *     action { ctx, prev -> ... }
     * }
     * </pre>
     * 
     * @param duration timeout duration
     */
    void timeout(java.time.Duration duration) {
        timeoutPolicy.timeout(duration)
    }
    
    /**
     * Configure timeout with a preset name.
     * 
     * Available presets: quick, standard, long, very-long, none
     * 
     * <h3>Example:</h3>
     * <pre>
     * serviceTask("api-call") {
     *     timeout "standard"  // 30 seconds
     *     action { ctx, prev -> ... }
     * }
     * </pre>
     * 
     * @param preset preset name
     */
    void timeout(String preset) {
        def dsl = new org.softwood.dag.resilience.TimeoutDsl(this.timeoutPolicy)
        dsl.preset(preset)
    }
    
    // ----------------------------------------------------
    // Dead Letter Queue DSL Configuration
    // ----------------------------------------------------
    
    /**
     * Configure Dead Letter Queue behavior using a DSL block.
     * 
     * Enables automatic capture of task failures to the DLQ for
     * post-mortem analysis, retry, and debugging.
     * 
     * <h3>Example:</h3>
     * <pre>
     * serviceTask("api-call") {
     *     deadLetterQueue {
     *         maxSize 1000
     *         maxAge 24.hours
     *         alwaysCapture IOException
     *         neverCapture IllegalArgumentException
     *         onEntryAdded { entry ->
     *             log.warn "Task failed: \${entry.taskId}"
     *         }
     *     }
     *     action { ctx, prev -> ... }
     * }
     * </pre>
     * 
     * @param config DSL configuration closure
     */
    void deadLetterQueue(@DelegatesTo(org.softwood.dag.resilience.DeadLetterQueueDsl) Closure config) {
        def dsl = new org.softwood.dag.resilience.DeadLetterQueueDsl(this.dlqPolicy)
        config.delegate = dsl
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.call()
    }
    
    /**
     * Configure Dead Letter Queue with a preset.
     * 
     * Available presets:
     * - "permissive": unlimited size, no expiration (for debugging)
     * - "strict": limited size (100), short retention (1 hour)
     * - "autoretry": enables automatic retry with sensible defaults
     * - "debugging": large storage (unlimited) for long-term analysis
     * 
     * <h3>Example:</h3>
     * <pre>
     * serviceTask("api-call") {
     *     deadLetterQueue "strict"
     *     action { ctx, prev -> ... }
     * }
     * </pre>
     * 
     * @param preset preset name
     */
    void deadLetterQueue(String preset) {
        def dsl = new org.softwood.dag.resilience.DeadLetterQueueDsl(this.dlqPolicy)
        dsl.preset(preset)
    }
    
    // ----------------------------------------------------
    // Idempotency DSL Configuration
    // ----------------------------------------------------
    
    /**
     * Configure idempotency behavior using a DSL block.
     * 
     * Enables caching of task results to prevent duplicate execution
     * with the same input.
     * 
     * <h3>Example:</h3>
     * <pre>
     * serviceTask("api-call") {
     *     idempotent {
     *         ttl Duration.ofMinutes(30)
     *         keyFrom { input -> input.requestId }
     *         onCacheHit { key, result ->
     *             log.info "Returning cached result for \${key}"
     *         }
     *     }
     *     action { ctx, prev -> callApi() }
     * }
     * </pre>
     * 
     * @param config DSL configuration closure
     */
    void idempotent(@DelegatesTo(org.softwood.dag.resilience.IdempotencyDsl) Closure config) {
        def dsl = new org.softwood.dag.resilience.IdempotencyDsl(this.idempotencyPolicy)
        config.delegate = dsl
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.call()
    }
    
    /**
     * Configure idempotency with a preset.
     * 
     * Available presets:
     * - "short": 5 minutes TTL
     * - "standard": 30 minutes TTL (default)
     * - "long": 2 hours TTL
     * - "permanent": No expiration
     * 
     * <h3>Example:</h3>
     * <pre>
     * serviceTask("api-call") {
     *     idempotent "standard"
     *     action { ctx, prev -> ... }
     * }
     * </pre>
     * 
     * @param preset preset name
     */
    void idempotent(String preset) {
        def dsl = new org.softwood.dag.resilience.IdempotencyDsl(this.idempotencyPolicy)
        dsl.preset(preset)
    }


    // ------------
    // helper method
    // -------------
    Promise<?> buildPrevPromise(Map<String, ? extends ITask> tasks) {
        if (injectedInput.isPresent()) {
            return ctx.promiseFactory.createPromise(injectedInput.get())
        }

        if (predecessors.isEmpty()) {
            return ctx.promiseFactory.createPromise (null)
        }

        if (predecessors.size() == 1) {
            String predId = predecessors.first()
            ITask pred = tasks[predId]
            return pred?.completionPromise ?: ctx.promiseFactory.createPromise(null)
        }

        // Multiple predecessors - collect their completion promises
        log.debug "Task ${id}: building combined promise from ${predecessors.size()} predecessors: ${predecessors}"
        List<Promise<?>> predPromises = predecessors.collect {
            ITask pred = tasks[it]
            log.debug "Task ${id}: predecessor ${it} -> completionPromise: ${pred?.completionPromise}"
            pred?.completionPromise
        }.findAll { it != null }

        log.debug "Task ${id}: collected ${predPromises.size()} non-null completion promises"
        return Promises.all(predPromises)
    }

    // ----------------------------------------------------
    // Subclasses implement the core promise-returning action
    // This receives the UNWRAPPED value (Optional<?>), not the promise
    // ----------------------------------------------------
    protected abstract Promise<T> runTask(TaskContext ctx, Object prevValue)

    // ----------------------------------------------------
    // Main entrypoint called by TaskGraph
    // Receives Optional<Promise<?>> and unwraps it for runTask
    // ----------------------------------------------------
    Promise execute(Promise<?> previousPromise) {

        state = TaskState.RUNNING
        emitEvent(TaskState.RUNNING)

        log.debug "Task ${id}: execute() called with prevPromise present: $previousPromise"

        Promise<T> attemptPromise = runAttempt(previousPromise)
        completionPromise = attemptPromise

        log.debug "Task ${id}: execute() got attemptPromise"

        return attemptPromise.then { T result ->
            log.debug "Task ${id}: completed with result: $result"
            state = TaskState.COMPLETED
            emitEvent(TaskState.COMPLETED)
            result
        }.recover { Throwable err ->
            // Only handle actual errors - skip if err is null
            if (err != null) {
                log.error "Task ${id}: failed with error: ${err.message}"
                lastError = err
                state = TaskState.FAILED
                emitErrorEvent(err)
                throw err
            }
            // If err is null, this is likely the success path - do nothing
            log.debug "Task ${id}: recover called with null error, ignoring"
        }
    }

    // ----------------------------------------------------
    // Determine if an exception should trigger retries
    // Configuration/validation errors should fail fast
    // ----------------------------------------------------
    protected boolean isRetriable(Throwable err) {
        // Don't retry configuration/validation errors - these indicate bugs or misconfiguration
        if (err instanceof IllegalStateException) return false
        if (err instanceof IllegalArgumentException) return false
        if (err instanceof NullPointerException) return false
        
        // Don't retry timeout errors (already handled separately)
        if (err instanceof TimeoutException) return false
        if (err instanceof TaskTimeoutException) return false
        
        // Don't retry circuit breaker exceptions - these represent deliberate circuit state
        if (err instanceof CircuitBreakerOpenException) return false
        
        // Don't retry rate limit exceptions - these are protective limits, not transient failures
        if (err instanceof RateLimitExceededException) return false
        
        // Don't retry receive timeout errors - check by class name to avoid circular dependency
        def className = err.class.name
        if (className.endsWith('ReceiveTimeoutException')) return false
        if (className.endsWith('ManualTaskFailedException')) return false
        
        // Retry everything else (network errors, transient failures, etc.)
        return true
    }

    // ----------------------------------------------------
    // Retry + timeout wrapper
    // Unwraps Optional<Promise<?>> to Optional<?> for runTask
    // ----------------------------------------------------
    private Promise<T> runAttempt(Promise<?> previousPromise) {

        log.debug "Task ${id}: runAttempt() starting"
        def factory = ctx.promiseFactory

        return factory.executeAsync {
            
            // Acquire resource slot if resource monitoring is enabled
            def resourceMonitor = ctx.resourceMonitor
            if (resourceMonitor != null) {
                log.debug "Task ${id}: acquiring resource slot"
                resourceMonitor.acquireTaskSlot()
            }
            
            try {
                return executeTaskWithRetry(previousPromise)
            } finally {
                // Always release resource slot
                if (resourceMonitor != null) {
                    log.debug "Task ${id}: releasing resource slot"
                    resourceMonitor.releaseTaskSlot()
                }
            }
        }
    }
    
    /**
     * Execute task with retry logic.
     * Separated from runAttempt() to ensure resource cleanup happens properly.
     */
    protected T executeTaskWithRetry(Promise<?> previousPromise) {

            int attempt = 1
            long delay = retryPolicy.initialDelay.toMillis()
            Object prevValue = null  // Declare outside try block so it's accessible in catch

            while (true) {
                try {
                    state = TaskState.RUNNING
                    emitEvent(TaskState.RUNNING)

                    // Unwrap the promise to get the actual value
                    if (previousPromise != null) {
                        log.debug "Task ${id}: unwrapping predecessor promise"
                        prevValue = previousPromise.get()  // Block and get the value

                        log.debug "Task ${id}: raw unwrapped value: $prevValue (${prevValue?.getClass()?.simpleName})"

                        // If the value is itself a Promise, unwrap it again (this can happen with nested promises)
                        while (prevValue instanceof Promise) {
                            log.debug "Task ${id}: value is still a Promise, unwrapping again"
                            prevValue = ((Promise) prevValue).get()
                        }

                        // Handle List results from multiple predecessors
                        if (prevValue instanceof List && ((List) prevValue).size() == 1) {
                            prevValue = ((List) prevValue)[0]
                            log.debug "Task ${id}: unwrapped single-element list to: $prevValue"
                        }

                        log.debug "Task ${id}: final unwrapped prevValue: $prevValue"
                    }

                    // Check rate limit BEFORE calling runTask()
                    if (rateLimitPolicy.isActive()) {
                        log.debug "Task ${id}: checking rate limit '${rateLimitPolicy.name}'"
                        
                        // Get the rate limiter from context
                        RateLimiter limiter = ctx.rateLimiters.get(rateLimitPolicy.name)
                        if (limiter == null) {
                            throw new IllegalStateException("Task ${id}: rate limiter '${rateLimitPolicy.name}' not found in context. " +
                                "Create it first using ctx.rateLimiter('${rateLimitPolicy.name}') { ... }")
                        }
                        
                        // Try to acquire permission
                        if (!limiter.tryAcquire()) {
                            // Rate limit exceeded - invoke callback if provided
                            if (rateLimitPolicy.onLimitExceeded) {
                                try {
                                    rateLimitPolicy.onLimitExceeded.call(ctx)
                                } catch (Exception cbErr) {
                                    log.warn "Task ${id}: error in onLimitExceeded callback: ${cbErr.message}"
                                }
                            }
                            
                            // Throw rate limit exception
                            def retryAfter = limiter.getRetryAfter()
                            throw new RateLimitExceededException(
                                "Task ${id}: rate limit exceeded (${limiter.getCurrentCount()}/${limiter.maxRequests} requests in ${limiter.timeWindow})",
                                rateLimitPolicy.name,
                                id,
                                limiter.getCurrentCount(),
                                limiter.maxRequests,
                                limiter.timeWindow,
                                retryAfter
                            )
                        }
                        
                        log.debug "Task ${id}: rate limit check passed (${limiter.getCurrentCount()}/${limiter.maxRequests})"
                    }
                    
                    // Check idempotency cache BEFORE calling runTask()
                    if (idempotencyPolicy.enabled) {
                        def cacheResult = checkIdempotencyCache(prevValue)
                        if (cacheResult != null) {
                            // Cache hit - return cached result
                            log.debug "Task ${id}: returning cached result"
                            state = TaskState.COMPLETED
                            emitEvent(TaskState.COMPLETED)
                            return (T) cacheResult
                        }
                    }

                    log.debug "Task ${id}: calling runTask() with prevValue"
                    
                    // Determine effective timeout (timeoutPolicy takes precedence over legacy taskTimeoutMillis)
                    Long effectiveTimeoutMillis = null
                    if (timeoutPolicy.isActive() && timeoutPolicy.timeout != null) {
                        effectiveTimeoutMillis = timeoutPolicy.timeout.toMillis()
                    } else if (taskTimeoutMillis != null) {
                        effectiveTimeoutMillis = taskTimeoutMillis
                    }
                    
                    Promise<T> promise = runTask(ctx, prevValue)  // Pass raw value (may be null)

                    log.debug "Task ${id}: runTask() returned promise, waiting for result"
                    
                    T result
                    if (effectiveTimeoutMillis != null) {
                        def startTime = System.currentTimeMillis()
                        
                        try {
                            result = promise.get(effectiveTimeoutMillis, TimeUnit.MILLISECONDS)
                        } catch (java.lang.IllegalStateException raceErr) {
                            // DataflowPromise race condition: error state detected but error value not yet set
                            // Wait a moment and try again to get the actual error
                            if (raceErr.message?.contains("Error state detected but error value is null")) {
                                Thread.sleep(10)  // Brief delay to let error propagate
                                try {
                                    result = promise.get(effectiveTimeoutMillis, TimeUnit.MILLISECONDS)
                                } catch (Throwable actualErr) {
                                    // Now we should get the real error
                                    throw actualErr
                                }
                            } else {
                                throw raceErr
                            }
                        } catch (TimeoutException timeoutEx) {
                            def elapsed = System.currentTimeMillis() - startTime
                            
                            // Invoke timeout callback if provided
                            if (timeoutPolicy.onTimeout) {
                                try {
                                    timeoutPolicy.onTimeout.call(ctx)
                                } catch (Exception cbErr) {
                                    log.warn "Task ${id}: error in onTimeout callback: ${cbErr.message}"
                                }
                            }
                            
                            // Throw custom timeout exception
                            throw new TaskTimeoutException(
                                id,
                                java.time.Duration.ofMillis(effectiveTimeoutMillis),
                                java.time.Duration.ofMillis(elapsed)
                            )
                        }
                    } else {
                        try {
                            result = promise.get()
                        } catch (java.lang.IllegalStateException raceErr) {
                            // DataflowPromise race condition: error state detected but error value not yet set
                            // Wait a moment and try again to get the actual error
                            if (raceErr.message?.contains("Error state detected but error value is null")) {
                                Thread.sleep(10)  // Brief delay to let error propagate
                                result = promise.get()
                            } else {
                                throw raceErr
                            }
                        }
                    }

                    log.debug "Task ${id}: got result: $result"
                    
                    // Store result in idempotency cache if enabled
                    if (idempotencyPolicy.enabled) {
                        storeInIdempotencyCache(prevValue, result)
                    }

                    state = TaskState.COMPLETED
                    emitEvent(TaskState.COMPLETED)
                    return result

                } catch (Throwable err) {
                    lastError = err
                    log.error "Task ${id}: attempt $attempt failed: ${err.message}"
                    
                    // Capture to Dead Letter Queue if configured and policy allows
                    captureToDeadLetterQueue(err, prevValue, attempt)

                    // Check if this error should be retried
                    if (!isRetriable(err)) {
                        log.debug "Task ${id}: non-retriable error (${err.class.simpleName}), failing immediately"
                        state = TaskState.FAILED
                        emitErrorEvent(err)
                        throw err  // Throw original exception directly - no wrapping
                    }

                    // Check if we've exhausted retries for retriable errors
                    // Only wrap if retries were actually configured (maxAttempts > 1)
                    if (retryPolicy.maxAttempts <= 1) {
                        // No retries configured - throw original exception
                        state = TaskState.FAILED
                        emitErrorEvent(err)
                        throw err
                    }
                    
                    if (attempt >= retryPolicy.maxAttempts) {
                        // Retries exhausted - wrap to indicate retry exhaustion
                        state = TaskState.FAILED
                        def exceeded = new RuntimeException("Task ${id}: exceeded retry attempts", err)
                        emitErrorEvent(exceeded)
                        throw exceeded
                    }

                    // Retry with backoff
                    log.debug "Task ${id}: retrying after ${delay}ms (attempt $attempt)"
                    Thread.sleep(delay)
                    delay = (long) (delay * retryPolicy.backoffMultiplier)
                    attempt++
                }
            }
        // End of executeTaskWithRetry
    }

    // ----------------------------------------------------
    // Task Event helpers
    // ----------------------------------------------------

    private void emitEvent(TaskState newState) {
        state = newState
        if (eventDispatcher) {
            eventDispatcher.emit(new TaskEvent(id, newState))
        }
    }

    private void emitErrorEvent(Throwable err) {
        state = TaskState.FAILED
        if (eventDispatcher) {
            eventDispatcher.emit(new TaskEvent(id, TaskState.FAILED, err))
        }
    }
    
    // ----------------------------------------------------
    // Dead Letter Queue Capture
    // ----------------------------------------------------
    
    /**
     * Capture task failure to Dead Letter Queue if configured.
     * 
     * Only captures if:
     * 1. DLQ is available in context
     * 2. DLQ policy is enabled (configured via DSL)
     * 3. Policy allows capture for this task/exception combination
     * 
     * @param error the exception that caused the failure
     * @param inputValue the input value passed to this task
     * @param attemptCount the number of attempts made before failure
     */
    protected void captureToDeadLetterQueue(Throwable error, Object inputValue, int attemptCount) {
        try {
            // Check if DLQ is available in context
            def dlq = ctx?.deadLetterQueue
            if (dlq == null) {
                // No DLQ configured - skip capture
                return
            }
            
            // Check if DLQ policy is enabled (i.e., was explicitly configured)
            if (!dlqPolicy.enabled) {
                log.debug "Task ${id}: DLQ not enabled, skipping capture"
                return
            }
            
            // Check if policy allows capture for this task/exception
            if (!dlqPolicy.shouldCapture(id, error)) {
                log.debug "Task ${id}: DLQ policy rejected capture for ${error.class.simpleName}"
                return
            }
            
            // Transfer policy callbacks to DLQ (only if not already set)
            if (dlqPolicy.onEntryAdded && !dlq.onEntryAdded) {
                dlq.onEntryAdded = dlqPolicy.onEntryAdded
            }
            if (dlqPolicy.onQueueFull && !dlq.onQueueFull) {
                dlq.onQueueFull = dlqPolicy.onQueueFull
            }
            
            // Get run ID if available (from TaskGraph context)
            String runId = ctx.globals?.get('runId')
            
            // Build metadata
            Map<String, Object> metadata = [:]
            metadata.taskType = this.class.simpleName
            metadata.hasRetryPolicy = retryPolicy.maxAttempts > 1
            metadata.hasTimeout = timeoutPolicy.isActive()
            metadata.hasRateLimit = rateLimitPolicy.isActive()
            
            // Capture to DLQ
            def entryId = dlq.add(
                this,
                inputValue,
                error,
                attemptCount,
                runId,
                metadata
            )
            
            log.debug "Task ${id}: captured failure to DLQ as entry ${entryId}"
            
        } catch (Exception dlqErr) {
            // Never let DLQ capture failures break the task execution flow
            log.error "Task ${id}: failed to capture to DLQ: ${dlqErr.message}", dlqErr
        }
    }
    
    // ----------------------------------------------------
    // Idempotency Cache Operations
    // ----------------------------------------------------
    
    /**
     * Check idempotency cache for a cached result.
     * 
     * @param inputValue the task input
     * @return the cached result, or null if not found
     */
    protected Object checkIdempotencyCache(Object inputValue) {
        try {
            // Check if cache is available
            def cache = ctx?.idempotencyCache
            if (cache == null) {
                log.debug "Task ${id}: no idempotency cache available"
                return null
            }
            
            // Generate idempotency key
            IdempotencyKey key = idempotencyPolicy.generateKey(id, inputValue)
            
            // Check cache
            IdempotencyCacheEntry entry = cache.get(key)
            if (entry != null) {
                log.debug "Task ${id}: cache hit for key ${key}"
                
                // Invoke callback if provided
                if (idempotencyPolicy.onCacheHit) {
                    try {
                        idempotencyPolicy.onCacheHit.call(key, entry.result)
                    } catch (Exception cbErr) {
                        log.warn "Task ${id}: error in onCacheHit callback: ${cbErr.message}"
                    }
                }
                
                return entry.result
            }
            
            // Cache miss
            log.debug "Task ${id}: cache miss for key ${key}"
            
            // Invoke callback if provided
            if (idempotencyPolicy.onCacheMiss) {
                try {
                    idempotencyPolicy.onCacheMiss.call(key)
                } catch (Exception cbErr) {
                    log.warn "Task ${id}: error in onCacheMiss callback: ${cbErr.message}"
                }
            }
            
            return null
            
        } catch (Exception cacheErr) {
            // Never let cache errors break task execution
            log.error "Task ${id}: error checking idempotency cache: ${cacheErr.message}", cacheErr
            return null
        }
    }
    
    /**
     * Store result in idempotency cache.
     * 
     * @param inputValue the task input
     * @param result the task result
     */
    protected void storeInIdempotencyCache(Object inputValue, Object result) {
        try {
            // Check if cache is available
            def cache = ctx?.idempotencyCache
            if (cache == null) {
                log.debug "Task ${id}: no idempotency cache available"
                return
            }
            
            // Generate idempotency key
            IdempotencyKey key = idempotencyPolicy.generateKey(id, inputValue)
            
            // Store in cache
            cache.put(key, result, idempotencyPolicy.ttl)
            
            log.debug "Task ${id}: cached result for key ${key}"
            
            // Invoke callback if provided
            if (idempotencyPolicy.onResultCached) {
                try {
                    idempotencyPolicy.onResultCached.call(key, result)
                } catch (Exception cbErr) {
                    log.warn "Task ${id}: error in onResultCached callback: ${cbErr.message}"
                }
            }
            
        } catch (Exception cacheErr) {
            // Never let cache errors break task execution
            log.error "Task ${id}: failed to store in idempotency cache: ${cacheErr.message}", cacheErr
        }
    }
}
