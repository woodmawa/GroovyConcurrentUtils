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
import org.softwood.dag.resilience.MemoPolicy
import org.softwood.dag.resilience.MemoDsl
import org.softwood.dag.resilience.MemoCache

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
abstract class TaskBase<T> implements IGraphNode<T> {

    final String id
    final String name
    // H5 fix: initial state is CREATED (not SCHEDULED - that happens when graph picks it up)
    // H8 fix: use AtomicReference for guarded, race-free transitions
    private final java.util.concurrent.atomic.AtomicReference<TaskState> _state =
            new java.util.concurrent.atomic.AtomicReference<>(TaskState.CREATED)

    TaskState getState() { _state.get() }
    // Legacy setter kept so execute() / emitEvent() bare assignments still compile;
    // prefer the transitionState() guard below for all new code.
    //
    // 1.2.0 (H3): the setter is guarded. It used to be a raw set, and executeTaskWithRetry() assigns
    // `state = ...` throughout, so a task cancelled or skipped by the graph was overwritten with
    // RUNNING / COMPLETED / FAILED by its own worker. Now an assignment is a transitionState() call and
    // an illegal one (anything out of a terminal state) is ignored.
    // The one exception is an explicit reset to CREATED or SCHEDULED, which callers use to re-run a
    // standalone task; that stays unconditional.
    void setState(TaskState s) {
        if (s == null) return
        if (s == TaskState.CREATED || s == TaskState.SCHEDULED) {
            _state.set(s)
            return
        }
        transitionState(s)
    }
    TaskEventDispatch eventDispatcher

    // L16 fix: typed as TaskContext (was 'def' - duck typing not needed here)
    TaskContext ctx

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
    protected MemoPolicy memoPolicy = new MemoPolicy()
    private Long taskTimeoutMillis = null  // Legacy - kept for backwards compatibility

    /**
     * 1.2.0 (D6): opt-in. When true, a task timeout also cancels the promise returned by runTask()
     * with mayInterruptIfRunning=true, so work started through promiseFactory.executeAsync is interrupted.
     * Default false: the task is reported failed and its work is left to finish, as in 1.1.x.
     */
    boolean interruptOnTimeout = false

    /** 1.2.0 (D6): the promise of the attempt currently running, for TaskGraph.interruptOnCancel. */
    private volatile Promise<?> inFlight = null

    /** DSL form: {@code interruptOnTimeout true}. */
    void interruptOnTimeout(boolean enabled) { this.interruptOnTimeout = enabled }
    protected Throwable lastError = null

    Promise<?> completionPromise = null
    private Optional<?> injectedInput = Optional.empty()

    // ----------------------------------------------------
    // constructor - expects id and a name and a graph ctx
    // ----------------------------------------------------
    TaskBase(String id, String name, TaskContext ctx) {
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

    // H5 fix: hasStarted means we've moved beyond CREATED/SCHEDULED - i.e. at least RUNNING
    boolean getHasStarted() { state.ordinal() >= TaskState.RUNNING.ordinal() }

    boolean isCompleted() { state == TaskState.COMPLETED }
    boolean isSkipped()   { state == TaskState.SKIPPED }
    boolean isFailed()    { state == TaskState.FAILED }
    boolean isCancelled() { state == TaskState.CANCELLED }

    /**
     * H8 fix: Guarded state transition - only moves to `next` if current state
     * is a valid predecessor according to TaskState.canTransitionTo().
     * Returns true if transition succeeded, false if it was rejected (e.g. already terminal).
     * Thread-safe via AtomicReference CAS loop.
     */
    boolean transitionState(TaskState next) {
        while (true) {
            TaskState current = _state.get()
            if (current == next) return false   // already there: not a transition, not worth a log line
            if (!current.canTransitionTo(next)) {
                log.debug "Task ${id}: rejected transition ${current} -> ${next} (illegal)"
                return false
            }
            if (_state.compareAndSet(current, next)) {
                log.debug "Task ${id}: state ${current} -> ${next}"
                return true
            }
            // CAS failed - another thread changed state concurrently, retry
        }
    }

    void markScheduled() {
        if (transitionState(TaskState.SCHEDULED)) {
            emitEvent(TaskState.SCHEDULED)
        }
    }

    void markCompleted() {
        if (transitionState(TaskState.COMPLETED)) {
            emitEvent(TaskState.COMPLETED)
        }
    }

    void markFailed(Throwable error) {
        if (transitionState(TaskState.FAILED)) {
            lastError = error
            emitErrorEvent(error)
        }
    }

    void markSkipped() {
        if (transitionState(TaskState.SKIPPED)) {
            emitEvent(TaskState.SKIPPED)
        }
    }

    void markCancelled() {
        if (transitionState(TaskState.CANCELLED)) {
            emitEvent(TaskState.CANCELLED)
        }
    }

    // ----------------------------------------------------
    // 1.2.0 (H2/H4): cancellation
    // ----------------------------------------------------

    /** True once this task was cancelled, or its graph was aborted or timed out. */
    protected boolean isCancelRequested() {
        return _state.get() == TaskState.CANCELLED || (ctx != null && ctx.isCancelRequested())
    }

    private void throwIfCancelled() {
        if (isCancelRequested()) {
            throw new java.util.concurrent.CancellationException("Task ${id}: cancelled before the attempt started")
        }
    }

    /**
     * An interrupt or a cancellation, directly or as a cause. DataflowPromise.get() wraps
     * InterruptedException in a RuntimeException, so the cause chain is checked (bounded).
     */
    protected static boolean isCancellation(Throwable err) {
        Throwable t = err
        int depth = 0
        while (t != null && depth < 6) {
            if (t instanceof InterruptedException || t instanceof java.util.concurrent.CancellationException) return true
            t = t.cause
            depth++
        }
        return false
    }

    /** Mark this task CANCELLED and return the exception that ends executeTaskWithRetry(). Never retried. */
    private java.util.concurrent.CancellationException cancelledBy(Throwable err) {
        if (err instanceof InterruptedException) Thread.currentThread().interrupt()
        lastError = err
        markCancelled()
        if (err instanceof java.util.concurrent.CancellationException) {
            return (java.util.concurrent.CancellationException) err
        }
        def ce = new java.util.concurrent.CancellationException(
                "Task ${id}: cancelled (${err.class.simpleName}: ${err.message})".toString())
        ce.initCause(err)
        return ce
    }

    /**
     * 1.2.0 (D6): cancel the attempt that is running now, with an interrupt. Used by TaskGraph when
     * interruptOnCancel is set. A no-op when nothing is in flight.
     */
    void interruptInFlight() {
        Promise<?> p = inFlight
        if (p != null && !p.isDone()) {
            try {
                p.cancel(true)
            } catch (Exception e) {
                log.debug "Task ${id}: could not interrupt in-flight work: ${e.class.simpleName}: ${e.message}"
            }
        }
    }

    void setInjectedInput(Object data) {
        this.injectedInput = Optional.ofNullable(data)
    }

    /**
     * Reset this task so the graph can be executed again via {@link TaskGraph#reset()}.
     * Restores state to CREATED, clears the completion promise and injected input.
     * Does NOT clear memo/idempotency caches - those are held on the shared TaskContext.
     */
    void resetForReuse() {
        _state.set(TaskState.CREATED)
        completionPromise = null
        injectedInput = Optional.empty()
        lastError = null
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

    // ----------------------------------------------------
    // Memoisation DSL Configuration
    // ----------------------------------------------------

    /**
     * Configure memoisation for this task using a DSL block.
     *
     * <p>Memo sits in the execution chain BEFORE the idempotency check:
     * memo (performance) → idempotency (correctness) → rate limit → runTask()</p>
     *
     * <pre>
     * task("lookup") {
     *     memo {
     *         keyFrom { ctx, input -> "user:${input.id}" }
     *         ttl Duration.ofMinutes(10)
     *     }
     *     action { ctx, prev -> db.find(prev.id) }
     * }
     * </pre>
     */
    void memo(@DelegatesTo(MemoDsl) Closure config) {
        def dsl = new MemoDsl(this.memoPolicy)
        config.delegate = dsl
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.call()
    }

    /**
     * Configure memoisation with a named preset.
     * Valid: "session", "short", "long", "persistent"
     */
    void memo(String preset) {
        new MemoDsl(this.memoPolicy).preset(preset)
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
    /**
     * Execute the core task logic and return a Promise containing the result.
     *
     * <p><strong>⚠️ CRITICAL THREADING WARNING:</strong></p>
     * <p>This method is already executing inside an async context created by
     * {@link #runAttempt(Promise)}. The calling thread is managed by the
     * promise factory's executor pool.</p>
     *
     * <p><strong>DO NOT</strong> wrap your entire implementation in
     * {@code ctx.promiseFactory.executeAsync{}} — doing so creates a redundant
     * nested async layer. Instead, either:</p>
     *
     * <p><strong>Pattern A — Return a pending promise from user code:</strong></p>
     * <pre>{@code
     * protected Promise<MyResult> runTask(TaskContext ctx, Object prevValue) {
     *     // The action/work itself uses executeAsync — return that promise directly.
     *     // This allows executeTaskWithRetry() to apply timeouts via promise.get(timeout).
     *     return ctx.promiseFactory.executeAsync { doExpensiveWork(prevValue) }
     * }
     * }</pre>
     *
     * <p><strong>Pattern B — Synchronous work, return resolved promise:</strong></p>
     * <pre>{@code
     * protected Promise<MyResult> runTask(TaskContext ctx, Object prevValue) {
     *     def result = doQuickWork(prevValue)
     *     return Promises.newPromise(result)
     * }
     * }</pre>
     *
     * <p><strong>WRONG pattern (double-wrapping — will waste threads):</strong></p>
     * <pre>{@code
     * protected Promise<MyResult> runTask(TaskContext ctx, Object prevValue) {
     *     return ctx.promiseFactory.executeAsync {    // ❌ outer wrapper
     *         def p = someAction.call(ctx, prevValue) // returns a Promise
     *         return p.get()                          // ❌ blocks thread waiting for inner
     *     }
     * }
     * }</pre>
     *
     * <p><strong>Note on timeouts:</strong> If the task has a timeout configured,
     * {@code executeTaskWithRetry()} calls {@code promise.get(timeout)} on the
     * returned promise. For timeouts to work, the returned promise must still be
     * <em>pending</em> (Pattern A). If you return an already-resolved promise
     * (Pattern B), timeouts cannot fire because {@code get()} returns immediately.</p>
     *
     * <p><strong>Note:</strong> {@link ServiceTask} legitimately uses a single
     * {@code executeAsync} because it wraps arbitrary user closures that may block
     * (e.g. {@code Thread.sleep}). This is the correct pattern for task types
     * that need timeout support for blocking work. The anti-pattern is
     * <em>double-wrapping</em>: using {@code executeAsync} and then calling
     * {@code innerPromise.get()} inside it.</p>
     *
     * @param ctx       the shared task context
     * @param prevValue the unwrapped result from predecessor tasks (may be null)
     * @return a Promise containing the task result
     */
    protected abstract Promise<T> runTask(TaskContext ctx, Object prevValue)

    // ----------------------------------------------------
    // Main entrypoint called by TaskGraph
    // Receives Optional<Promise<?>> and unwraps it for runTask
    // ----------------------------------------------------
    Promise execute(Promise<?> previousPromise) {

        // 1.2.0 (H3): execute() on a task that already finished is an explicit re-run (standalone
        // Tasks.* use, loops). Restart it from CREATED; a CANCELLED or SKIPPED task stays as it is.
        TaskState before = _state.get()
        if (before == TaskState.COMPLETED || before == TaskState.FAILED) {
            _state.compareAndSet(before, TaskState.CREATED)
        }
        transitionState(TaskState.RUNNING)
        // NOTE: RUNNING event emitted by executeTaskWithRetry() inside runAttempt().
        // Do NOT emit here — would cause duplicate TASK_STARTED per node.

        log.debug "Task ${id}: execute() called with prevPromise present: $previousPromise"

        // C1 fix: pre-create completionPromise and assign it BEFORE runAttempt fires.
        // If the task completes synchronously, successor tasks reading completionPromise
        // via buildPrevPromise() will find a non-null promise.
        Promise<T> resultHolder = ctx.promiseFactory.createPromise()
        completionPromise = resultHolder

        log.debug "Task ${id}: execute() starting runAttempt (completionPromise pre-assigned)"
        Promise<T> attemptPromise = runAttempt(previousPromise)

        // Wire completion/failure callbacks into the pre-assigned holder.
        // Use onComplete/onError (registered on the underlying CompletableFuture) rather
        // than then/recover (which return a NEW derived CompletableFuture that nobody
        // holds a reference to and can be GC'd before it fires, silently dropping the
        // callback and leaving resultHolder unresolved forever).
        attemptPromise.onComplete { T result ->
            log.debug "Task ${id}: completed with result: ${redacted(result)}"
            transitionState(TaskState.COMPLETED)
            // NOTE: COMPLETED event emitted by executeTaskWithRetry() at return point.
            // Do NOT emit here — would cause duplicate TASK_COMPLETED per node.
            resultHolder.accept(result)
        }
        attemptPromise.onError { Throwable err ->
            log.error "Task ${id}: failed with error: ${err.message}"
            lastError = err
            // 1.2.0 (H3): a cancelled or skipped task is not re-reported as FAILED.
            if (transitionState(TaskState.FAILED) || isFailed()) {
                emitErrorEvent(err)
            }
            resultHolder.reject(err)   // signal failure to waiting successors
        }

        return resultHolder
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
            long taskStartTime = System.currentTimeMillis()  // Track task execution start

            while (true) {
                long attemptStartTime = System.currentTimeMillis()  // Track individual attempt start

                try {
                    // 1.2.0 (H2): a cancelled task, or a task in an aborted graph, does not start another attempt.
                    throwIfCancelled()
                    state = TaskState.RUNNING

                    // Emit event with attempt number for retries
                    if (attempt > 1) {
                        // This is a retry - emit enhanced event
                        def retryEvent = TaskEvent.builder(id, TaskState.RUNNING)
                            .taskName(name)
                            .attemptNumber(attempt)
                            .graphId(ctx?.globals?.get('graphId'))
                            .runId(ctx?.globals?.get('runId'))
                            .build()
                        if (eventDispatcher) {
                            eventDispatcher.emit(retryEvent)
                        }
                    } else {
                        emitEvent(TaskState.RUNNING)
                    }

                    // Unwrap the promise to get the actual value
                    if (previousPromise != null) {
                        log.debug "Task ${id}: unwrapping predecessor promise"
                        prevValue = previousPromise.get()  // Block and get the value

                        log.debug "Task ${id}: raw unwrapped value: ${redacted(prevValue)} (${prevValue?.getClass()?.simpleName})"

                        // If the value is itself a Promise, unwrap it again (this can happen with nested promises)
                        while (prevValue instanceof Promise) {
                            log.debug "Task ${id}: value is still a Promise, unwrapping again"
                            prevValue = ((Promise) prevValue).get()
                        }

                        // Handle List results from multiple predecessors
                        if (prevValue instanceof List && ((List) prevValue).size() == 1) {
                            prevValue = ((List) prevValue)[0]
                            log.debug "Task ${id}: unwrapped single-element list to: ${redacted(prevValue)}"
                        }

                        log.debug "Task ${id}: final unwrapped prevValue: ${redacted(prevValue)}"
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
                    
                    // Check memo cache FIRST (performance shortcut — before idempotency)
                    if (memoPolicy.enabled) {
                        String memoKey = memoPolicy.deriveKey(id, ctx, prevValue)
                        MemoCache mc = ctx?.memoCache
                        if (mc == null) { mc = ctx?.getMemoCache() }
                        if (mc != null) {
                            Object memo = mc.get(memoKey)
                            if (memo != null) {
                                log.debug "Task ${id}: memo cache hit for key='${memoKey}'"
                                state = TaskState.COMPLETED
                                emitEvent(TaskState.COMPLETED)
                                return (T) memo
                            }
                        }
                    }
                    
                    // Check idempotency cache BEFORE calling runTask()
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
                    inFlight = promise

                    log.debug "Task ${id}: runTask() returned promise, waiting for result"
                    
                    T result
                    if (effectiveTimeoutMillis != null) {
                        def startTime = System.currentTimeMillis()
                        
                        try {
                            result = promise.get(effectiveTimeoutMillis, TimeUnit.MILLISECONDS)
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
                            
                            // 1.2.0 (D6): opt-in interrupt of the timed-out work. Off by default.
                            if (interruptOnTimeout) {
                                try {
                                    promise.cancel(true)
                                } catch (Exception cancelErr) {
                                    log.debug "Task ${id}: could not cancel timed-out work: ${cancelErr.class.simpleName}: ${cancelErr.message}"
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
                        // No explicit timeout configured — use a safety timeout to detect
                        // potential nested-promise deadlocks (see runTask() javadoc warning).
                        // 5 minutes is generous; most tasks should complete well within this.
                        long safetyTimeoutMs = 300_000L
                        try {
                            result = promise.get(safetyTimeoutMs, TimeUnit.MILLISECONDS)
                        } catch (TimeoutException safetyTimeout) {
                            // Distinguish: did the SAFETY net time out, or did the task's own
                            // timeout mechanism fail the promise with a TimeoutException?
                            // If the promise is done (failed), it's the task's own error — re-throw it.
                            if (promise.isDone()) {
                                throw safetyTimeout  // Task's own TimeoutException, propagate as-is
                            }
                            // Otherwise this is genuinely the safety net firing — likely a deadlock
                            throw new RuntimeException(
                                "Task '${id}' timed out after ${(long)(safetyTimeoutMs/1000)}s waiting for runTask() promise. " +
                                "This is often caused by using ctx.promiseFactory.executeAsync{} inside runTask(), " +
                                "which creates a nested-promise deadlock. See TaskBase.runTask() javadoc for the correct pattern.",
                                safetyTimeout
                            )
                        }
                    }

                    log.debug "Task ${id}: got result: ${redacted(result)}"

                    // Store result in idempotency cache if enabled
                    if (idempotencyPolicy.enabled) {
                        storeInIdempotencyCache(prevValue, result)
                    }

                    // Store result in memo cache if enabled
                    if (memoPolicy.enabled) {
                        String memoKey = memoPolicy.deriveKey(id, ctx, prevValue)
                        MemoCache mc = ctx?.memoCache
                        if (mc == null) { mc = ctx?.getMemoCache() }
                        if (mc != null) {
                            mc.put(memoKey, result, memoPolicy.ttl)
                            log.debug "Task ${id}: memo stored key='${memoKey}'"
                        }
                    }

                    // Calculate execution time
                    long executionTimeMs = System.currentTimeMillis() - attemptStartTime
                    long totalTimeMs = System.currentTimeMillis() - taskStartTime

                    state = TaskState.COMPLETED

                    // Emit enhanced completion event with timing
                    def completionEvent = TaskEvent.builder(id, TaskState.COMPLETED)
                        .taskName(name)
                        .attemptNumber(attempt)
                        .executionTimeMs(executionTimeMs)
                        .graphId(ctx?.globals?.get('graphId'))
                        .runId(ctx?.globals?.get('runId'))
                        .result(result)
                        .redactedResult(redacted(result))   // 1.2.2 (M1): result itself is never altered
                        .addMetadata('totalTimeMs', totalTimeMs)
                        .addMetadata('retriesUsed', attempt - 1)
                        .build()
                    if (eventDispatcher) {
                        eventDispatcher.emit(completionEvent)
                    }

                    return result

                } catch (Throwable err) {
                    // 1.2.0 (H4): an interrupt or a cancellation ends the task as CANCELLED. It is never retried
                    // and never sent to the dead letter queue.
                    if (isCancellation(err)) {
                        log.debug "Task ${id}: attempt $attempt cancelled: ${err.class.simpleName}: ${err.message}"
                        throw cancelledBy(err)
                    }
                    // 1.2.0 (H4): a JVM-level error is not a transient failure.
                    if (err instanceof VirtualMachineError || err instanceof LinkageError) {
                        lastError = err
                        state = TaskState.FAILED
                        emitErrorEvent(err)
                        throw err
                    }
                    lastError = err
                    log.error "Task ${id}: attempt $attempt failed: ${err.message}"
                    
                    // Capture to Dead Letter Queue if configured and policy allows
                    captureToDeadLetterQueue(err, prevValue, attempt)

                    // Check if this error should be retried
                    if (!isRetriable(err)) {
                        log.debug "Task ${id}: non-retriable error (${err.class.simpleName}), failing immediately"
                        state = TaskState.FAILED

                        // Emit enhanced failure event
                        long executionTimeMs = System.currentTimeMillis() - attemptStartTime
                        def failureEvent = TaskEvent.builder(id, TaskState.FAILED)
                            .taskName(name)
                            .attemptNumber(attempt)
                            .executionTimeMs(executionTimeMs)
                            .error(err)
                            // 1.4.0: say that this failure is final, and why it was not retried
                            .addMetadata(TaskEvent.WILL_RETRY, false)
                            .addMetadata(TaskEvent.NOT_RETRIED_BECAUSE, TaskEvent.NOT_RETRIABLE)
                            .graphId(ctx?.globals?.get('graphId'))
                            .runId(ctx?.globals?.get('runId'))
                            .build()
                        if (eventDispatcher) {
                            eventDispatcher.emit(failureEvent)
                        }

                        // 1.4.0: the same signal on the exception, for a caller that listens to no events
                        TaskFailureSignal.attach(err, id, TaskEvent.NOT_RETRIABLE, attempt)
                        throw err  // Throw original exception directly - no wrapping
                    }

                    // Check if we've exhausted retries for retriable errors
                    // Only wrap if retries were actually configured (maxAttempts > 1)
                    if (retryPolicy.maxAttempts <= 1) {
                        // No retries configured - throw original exception
                        state = TaskState.FAILED

                        long executionTimeMs = System.currentTimeMillis() - attemptStartTime
                        def failureEvent = TaskEvent.builder(id, TaskState.FAILED)
                            .taskName(name)
                            .attemptNumber(attempt)
                            .executionTimeMs(executionTimeMs)
                            .error(err)
                            .addMetadata(TaskEvent.WILL_RETRY, false)
                            .addMetadata(TaskEvent.NOT_RETRIED_BECAUSE, TaskEvent.NO_RETRIES_CONFIGURED)
                            .graphId(ctx?.globals?.get('graphId'))
                            .runId(ctx?.globals?.get('runId'))
                            .build()
                        if (eventDispatcher) {
                            eventDispatcher.emit(failureEvent)
                        }

                        TaskFailureSignal.attach(err, id, TaskEvent.NO_RETRIES_CONFIGURED, attempt)
                        throw err
                    }

                    if (attempt >= retryPolicy.maxAttempts) {
                        // Retries exhausted - wrap to indicate retry exhaustion
                        state = TaskState.FAILED
                        def exceeded = new RuntimeException("Task ${id}: exceeded retry attempts", err)

                        long totalTimeMs = System.currentTimeMillis() - taskStartTime
                        def failureEvent = TaskEvent.builder(id, TaskState.FAILED)
                            .taskName(name)
                            .attemptNumber(attempt)
                            .error(exceeded)
                            .graphId(ctx?.globals?.get('graphId'))
                            .runId(ctx?.globals?.get('runId'))
                            .addMetadata('totalTimeMs', totalTimeMs)
                            .addMetadata('retriesExhausted', true)
                            .addMetadata(TaskEvent.WILL_RETRY, false)
                            .addMetadata(TaskEvent.NOT_RETRIED_BECAUSE, TaskEvent.RETRIES_EXHAUSTED)
                            .build()
                        if (eventDispatcher) {
                            eventDispatcher.emit(failureEvent)
                        }

                        TaskFailureSignal.attach(exceeded, id, TaskEvent.RETRIES_EXHAUSTED, attempt)
                        throw exceeded
                    }

                    // Will retry - emit TASK_RETRYING event
                    log.debug "Task ${id}: retrying after ${delay}ms (attempt $attempt)"

                    // Map to custom TaskState for retry or use metadata
                    def retryingEvent = TaskEvent.builder(id, TaskState.RUNNING)  // Use RUNNING as base state
                        .taskName(name)
                        .attemptNumber(attempt + 1)  // Next attempt number
                        .graphId(ctx?.globals?.get('graphId'))
                        .runId(ctx?.globals?.get('runId'))
                        .addMetadata('isRetrying', true)
                        .addMetadata(TaskEvent.WILL_RETRY, true)
                        .addMetadata('retryDelayMs', delay)
                        .addMetadata('previousAttemptFailed', err.class.simpleName)
                        .build()
                    if (eventDispatcher) {
                        eventDispatcher.emit(retryingEvent)
                    }

                    // 1.2.0 (H2/H4): the wait between attempts ends on cancel or interrupt instead of retrying.
                    try {
                        Thread.sleep(delay)
                    } catch (InterruptedException ie) {
                        throw cancelledBy(ie)
                    }
                    if (isCancelRequested()) {
                        throw cancelledBy(new java.util.concurrent.CancellationException(
                                "Task ${id}: cancelled while waiting to retry".toString()))
                    }
                    delay = (long) (delay * retryPolicy.backoffMultiplier)
                    attempt++
                }
            }
        // End of executeTaskWithRetry
    }

    // ----------------------------------------------------
    // Task Event helpers
    // ----------------------------------------------------

    /**
     * GCU 1.2.2 (M1): a task value as it may be written to a log line or shown on an event.
     * The value itself when the context has no redactor (the default). See TaskContext.redact.
     */
    protected Object redacted(Object value) {
        TaskContext c = ctx
        return c != null ? c.redact(id, value) : value
    }

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
