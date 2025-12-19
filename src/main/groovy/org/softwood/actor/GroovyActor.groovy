package org.softwood.actor

import groovy.transform.CompileStatic
import groovy.transform.TypeCheckingMode
import groovy.transform.TypeChecked
import groovy.util.logging.Slf4j
import org.softwood.pool.ExecutorPool
import org.softwood.actor.supervision.SupervisionStrategy
import org.softwood.actor.supervision.SupervisorDirective
import org.softwood.actor.supervision.RestartStatistics
import org.softwood.actor.lifecycle.Terminated

import java.time.Duration
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * GroovyActor – Core actor implementation with enhanced observability.
 *
 * <p>Provides message-driven concurrency with isolated state, sequential
 * message processing, and comprehensive monitoring capabilities.</p>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Sequential message processing on dedicated executor</li>
 *   <li>Context-aware message handlers with sender tracking</li>
 *   <li>Health status and operational metrics</li>
 *   <li>Error tracking with configurable retention</li>
 *   <li>Mailbox size limits with backpressure</li>
 *   <li>Graceful shutdown with timeout support</li>
 * </ul>
 *
 * <p><strong>Note:</strong> Use {@link ActorFactory} to create instances.</p>
 *
 * @since 1.0.0
 */
@Slf4j
@CompileStatic
class GroovyActor implements Actor {

    /** Actor name for identification */
    final String name

    /** Actor's mutable state (isolated) */
    final Map state = [:]

    /** Message handler closure */
    private final Closure handler

    /** Mailbox for incoming messages */
    private BlockingQueue<MessageEnvelope> mailbox

    /** Executor pool for mailbox loop */
    private final ExecutorPool pool

    /** True if this actor owns the pool */
    private final boolean ownsPool

    /** Running state flag */
    private final AtomicBoolean running = new AtomicBoolean(true)

    /** Currently processing message */
    private final AtomicBoolean processing = new AtomicBoolean(false)

    /** Latch signaling termination complete */
    private final CountDownLatch terminated = new CountDownLatch(1)

    // Reference to actor system for context operations
    private ActorSystem system

    // Metrics tracking
    private final AtomicLong messagesReceived = new AtomicLong(0)
    private final AtomicLong messagesProcessed = new AtomicLong(0)
    private final AtomicLong messagesErrored = new AtomicLong(0)
    private final AtomicLong mailboxRejections = new AtomicLong(0)
    private final long createdAt = System.currentTimeMillis()
    private volatile long lastMessageProcessedAt = 0

    // Configuration
    private volatile int maxMailboxSize = 0
    private final int maxErrorsRetained

    // Error management
    private volatile Closure<Void> errorHandler
    private final ConcurrentLinkedQueue<Map<String, Object>> recentErrors = new ConcurrentLinkedQueue<>()
    
    // Supervision
    private volatile SupervisionStrategy supervisionStrategy
    private volatile RestartStatistics restartStats

    // ─────────────────────────────────────────────────────────────
    // Construction
    // ─────────────────────────────────────────────────────────────

    /**
     * Package-private constructor - use ActorFactory.
     */
    GroovyActor(String name, Map initialState, Closure handler, ExecutorPool pool, boolean ownsPool, int maxErrorsRetained = 100, int maxMailboxSize = 0) {
        validateActorName(name)
        this.name = name
        this.handler = handler
        this.pool = pool
        this.ownsPool = ownsPool
        this.maxErrorsRetained = maxErrorsRetained
        this.maxMailboxSize = maxMailboxSize
        
        // Initialize mailbox with correct capacity from the start
        if (maxMailboxSize > 0) {
            this.mailbox = new LinkedBlockingQueue<>(maxMailboxSize)
        } else {
            this.mailbox = new LinkedBlockingQueue<>()
        }

        if (initialState) {
            this.state.putAll(initialState)
        }

        startMailboxLoop()
    }

    /**
     * Set the actor system reference (called by ActorSystem after creation).
     * Package-private, only ActorSystem should call this.
     */
    void setSystem(ActorSystem system) {
        this.system = system
    }

    // ─────────────────────────────────────────────────────────────
    // Messaging API
    // ─────────────────────────────────────────────────────────────

    @Override
    void tell(Object msg) {
        tell(msg, null)
    }

    @Override
    void tell(Object msg, Actor sender) {
        enqueueMessage(msg, null, sender)
    }

    @Override
    void send(Object msg) {
        tell(msg)
    }

    @Override
    Object ask(Object msg, Duration timeout = Duration.ofSeconds(5)) {
        return askSync(msg, timeout)
    }

    @Override
    Object askSync(Object msg, Duration timeout = Duration.ofSeconds(5)) {
        def future = new CompletableFuture<Object>()
        enqueueMessage(msg, future, null)

        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (TimeoutException te) {
            future.cancel(true)
            throw te
        }
    }

    @Override
    void sendAndContinue(Object msg, Closure continuation, Duration timeout = Duration.ofSeconds(5)) {
        def future = new CompletableFuture<Object>()
        enqueueMessage(msg, future, null)

        future.thenAccept { value ->
            continuation.call(value)
        }.exceptionally { throwable ->
            log.error("[$name] Continuation failed", throwable)
            return null
        }
    }

    @Override
    Object sendAndWait(Object msg, Duration timeout = Duration.ofSeconds(5)) {
        return askSync(msg, timeout)
    }
    
    /**
     * Groovy left-shift operator for synchronous message sending.
     * Provides GPars-compatible syntax: actor << message
     * 
     * <p>Delegates to askSync() with default 5 second timeout.</p>
     */
    @Override
    Object leftShift(Object msg) {
        return askSync(msg, Duration.ofSeconds(5))
    }

    // ─────────────────────────────────────────────────────────────
    // Identification
    // ─────────────────────────────────────────────────────────────

    @Override
    String getName() {
        return name
    }

    // ─────────────────────────────────────────────────────────────
    // State Access
    // ─────────────────────────────────────────────────────────────

    @Override
    Map getState() {
        // Return deep defensive copy to prevent external modification
        // of mutable objects in state
        return deepCopy(state)
    }
    
    /**
     * Creates a deep copy of a map to prevent external modification.
     * Handles nested maps, lists, and cloneable objects.
     * 
     * @param original the map to copy
     * @return a deep copy of the map
     */
    @TypeChecked(TypeCheckingMode.SKIP)
    private Map deepCopy(Map original) {
        if (original == null) {
            return [:]
        }
        
        def copy = [:]
        original.each { key, value ->
            if (value instanceof Map) {
                copy[key] = deepCopy(value as Map)
            } else if (value instanceof List) {
                copy[key] = deepCopyList(value as List)
            } else if (value instanceof Set) {
                copy[key] = new HashSet(value as Set)
            } else if (value instanceof Cloneable) {
                try {
                    copy[key] = value.clone()
                } catch (Exception e) {
                    // Cloning failed, use reference (primitives/immutables safe)
                    copy[key] = value
                }
            } else {
                // Primitives, strings, and immutable objects are safe
                copy[key] = value
            }
        }
        return copy
    }
    
    /**
     * Creates a deep copy of a list.
     */
    @TypeChecked(TypeCheckingMode.SKIP)
    private List deepCopyList(List original) {
        if (original == null) {
            return []
        }
        
        def copy = []
        original.each { value ->
            if (value instanceof Map) {
                copy << deepCopy(value as Map)
            } else if (value instanceof List) {
                copy << deepCopyList(value as List)
            } else if (value instanceof Set) {
                copy << new HashSet(value as Set)
            } else if (value instanceof Cloneable) {
                try {
                    copy << value.clone()
                } catch (Exception e) {
                    copy << value
                }
            } else {
                copy << value
            }
        }
        return copy
    }

    // ─────────────────────────────────────────────────────────────
    // Lifecycle Management
    // ─────────────────────────────────────────────────────────────

    @Override
    void stop() {
        if (!running.compareAndSet(true, false)) {
            return  // Already stopping
        }

        log.debug("[$name] Initiating graceful stop")

        // Send stop signal
        try {
            mailbox.put(new MessageEnvelope("__STOP__"))
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
        }

        // Wait for termination
        try {
            terminated.await()
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
        }

        // Notify watchers and cleanup death watch
        notifyWatchersAndCleanup(null)

        // Shutdown owned pool
        if (ownsPool && pool != null) {
            pool.shutdown()
        }

        log.debug("[$name] Stopped")
    }

    @Override
    boolean stop(Duration timeout) {
        if (!running.compareAndSet(true, false)) {
            return terminated.getCount() == 0  // Already stopping/stopped
        }

        log.debug("[$name] Initiating graceful stop with timeout")

        // Send stop signal
        try {
            mailbox.put(new MessageEnvelope("__STOP__"))
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
            return false
        }

        // Wait for termination with timeout
        boolean completed
        try {
            completed = terminated.await(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
            return false
        }

        // Shutdown owned pool
        if (ownsPool && pool != null) {
            pool.shutdown()
        }

        if (completed) {
            log.debug("[$name] Stopped within timeout")
        } else {
            log.warn("[$name] Stop timeout expired, ${mailbox.size()} messages remaining")
        }

        return completed
    }

    @Override
    void stopNow() {
        if (!running.compareAndSet(true, false)) {
            return  // Already stopping
        }

        int discarded = mailbox.size()
        mailbox.clear()

        // Use debug level to avoid information disclosure
        log.debug("[$name] Force stopped, discarded $discarded messages")

        // Send stop signal
        try {
            mailbox.put(new MessageEnvelope("__STOP__"))
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
        }

        // Don't wait - force termination
        terminated.countDown()

        // Shutdown owned pool
        if (ownsPool && pool != null) {
            pool.shutdown()
        }
    }

    @Override
    boolean isStopped() {
        return !running.get()
    }

    @Override
    boolean isTerminated() {
        return !running.get() && terminated.getCount() == 0
    }

    // ─────────────────────────────────────────────────────────────
    // Error Management
    // ─────────────────────────────────────────────────────────────

    @Override
    Actor onError(Closure<Void> errorHandler) {
        this.errorHandler = errorHandler
        return this
    }

    @Override
    List<Map<String, Object>> getErrors(int maxCount = Integer.MAX_VALUE) {
        Map<String, Object>[] errors = recentErrors.toArray() as Map<String, Object>[]
        return Arrays.asList(errors).take(Math.min(maxCount, errors.length))
    }

    @Override
    void clearErrors() {
        recentErrors.clear()
    }

    /**
     * Handles a message processing error.
     */
    @TypeChecked(TypeCheckingMode.SKIP)
    private void handleMessageError(Throwable e) {
        messagesErrored.incrementAndGet()

        try {
            // Store error details (sanitized for security)
            def errorInfo = [
                    timestamp    : System.currentTimeMillis(),
                    errorType    : e.class.simpleName,  // Hide package structure
                    message      : sanitizeErrorMessage(e.message),
                    stackTrace   : sanitizeStackTrace(e)
            ] as Map<String, Object>

            // Fix race condition: ensure size limit BEFORE adding
            while (recentErrors.size() >= maxErrorsRetained) {
                recentErrors.poll()
            }
            recentErrors.offer(errorInfo)
        } catch (Throwable sanitizationError) {
            // If sanitization fails, store basic error info
            log.error("[$name] Error sanitization failed", sanitizationError)
            def basicErrorInfo = [
                    timestamp    : System.currentTimeMillis(),
                    errorType    : e.class.simpleName,
                    message      : "Error occurred",
                    stackTrace   : []
            ] as Map<String, Object>
            recentErrors.offer(basicErrorInfo)
        }

        // Call custom error handler if set
        if (errorHandler) {
            try {
                errorHandler.call(e)
            } catch (Throwable handlerError) {
                log.error("[$name] Error handler failed", handlerError)
            }
        }

        // Apply supervision directive if strategy is set
        if (supervisionStrategy) {
            applySupervisionDirective(e)
        } else {
            // Default: log without stack trace if it's a known error type
            if (e instanceof IllegalArgumentException || e instanceof IllegalStateException) {
                log.warn("[$name] Message processing failed: ${e.class.simpleName}: ${e.message}")
            } else {
                log.error("[$name] Message processing failed: ${e.message}", e)
            }
        }
    }
    
    /**
     * Apply supervision directive based on the strategy's decision.
     */
    private void applySupervisionDirective(Throwable e) {
        def directive = supervisionStrategy.decide(e, this)
        
        switch(directive) {
            case SupervisorDirective.RESTART:
                handleRestart(e)
                break
            case SupervisorDirective.RESUME:
                handleResume(e)
                break
            case SupervisorDirective.STOP:
                handleStop(e)
                break
            case SupervisorDirective.ESCALATE:
                handleEscalate(e)
                break
            default:
                log.error("[$name] Unknown supervision directive: $directive", e)
        }
    }
    
    /**
     * Handle RESTART directive - clear state and restart actor.
     */
    private void handleRestart(Throwable e) {
        if (!restartStats.recordRestart()) {
            log.warn("[$name] Max restarts (${supervisionStrategy.maxRestarts}) exceeded within ${supervisionStrategy.withinDuration} - stopping actor")
            stop()
            return
        }
        
        // Calculate backoff if enabled
        if (supervisionStrategy.useExponentialBackoff) {
            def backoff = restartStats.calculateBackoff(
                supervisionStrategy.initialBackoff,
                supervisionStrategy.maxBackoff
            )
            log.debug("[$name] Backing off for ${backoff.toMillis()}ms before restart")
            try {
                Thread.sleep(backoff.toMillis())
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt()
                log.warn("[$name] Backoff interrupted")
            }
        }
        
        // Clear state and notify strategy
        log.debug("[$name] Restarting actor due to ${e.class.simpleName}: ${e.message}")
        state.clear()
        supervisionStrategy.onRestart(this, e)
    }
    
    /**
     * Handle RESUME directive - ignore the error and continue.
     */
    private void handleResume(Throwable e) {
        log.debug("[$name] Resuming after error (ignored)", e)
        // Do nothing - just continue processing next message
    }
    
    /**
     * Handle STOP directive - terminate the actor.
     */
    private void handleStop(Throwable e) {
        log.info("[$name] Stopping actor due to supervision directive: ${e.class.simpleName}: ${e.message}")
        supervisionStrategy.onStop(this, e)
        stop()
    }
    
    /**
     * Handle ESCALATE directive - propagate to parent/supervisor.
     */
    private void handleEscalate(Throwable e) {
        if (system == null) {
            log.warn("[$name] Cannot escalate - no system reference")
            stop()
            return
        }
        
        // Get parent from hierarchy
        String parentName = system.hierarchy.getParent(this)
        
        if (parentName == null) {
            log.warn("[$name] Cannot escalate - no parent actor, stopping instead", e)
            stop()
            return
        }
        
        def parent = system.getActor(parentName)
        if (parent == null || parent.isStopped()) {
            log.warn("[$name] Cannot escalate - parent [$parentName] not found or stopped, stopping instead", e)
            stop()
            return
        }
        
        log.info("[$name] Escalating error to parent [$parentName]", e)
        
        // Send error to parent as a special message
        // Parent's supervision strategy will handle it
        try {
            def escalationMsg = [
                type: 'child-error',
                childName: name,
                errorType: e.class.simpleName,
                errorMessage: sanitizeErrorMessage(e.message),
                timestamp: System.currentTimeMillis()
                // Note: Do NOT include actor reference to maintain encapsulation
            ]
            parent.tell(escalationMsg)
        } catch (Exception escalationError) {
            log.error("[$name] Failed to escalate to parent, stopping", escalationError)
            stop()
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Security & Sanitization
    // ─────────────────────────────────────────────────────────────
    
    /**
     * Sanitize error messages to prevent information disclosure.
     * Removes file paths, credentials, and other sensitive data.
     */
    @TypeChecked(TypeCheckingMode.SKIP)
    private String sanitizeErrorMessage(String msg) {
        if (msg == null) {
            return "Error occurred"
        }
        
        // Remove absolute file paths (Windows and Unix)
        def sanitized = msg.replaceAll(/[A-Za-z]:\\[\\\w\.\-]+/, '<path>')
        sanitized = sanitized.replaceAll(/\/[\w\/\.\-]+/, '<path>')
        
        // Remove potential credentials (use (?i) inline flag for case-insensitive)
        sanitized = sanitized.replaceAll(/(?i)password\s*[=:]\s*\w+/, 'password=***')
        sanitized = sanitized.replaceAll(/(?i)token\s*[=:]\s*\w+/, 'token=***')
        sanitized = sanitized.replaceAll(/(?i)key\s*[=:]\s*\w+/, 'key=***')
        
        // Truncate very long messages
        if (sanitized.length() > 500) {
            sanitized = sanitized.substring(0, 497) + "..."
        }
        
        return sanitized
    }
    
    /**
     * Sanitize stack traces based on environment.
     * In production, stack traces should be minimal or absent.
     */
    @TypeChecked(TypeCheckingMode.SKIP)
    private List<String> sanitizeStackTrace(Throwable e) {
        // Check if we're in production mode (no stack traces)
        def productionMode = System.getProperty("actor.productionMode", "false").toBoolean()
        
        if (productionMode) {
            return []  // No stack traces in production
        }
        
        // Development: Include limited stack trace
        return Arrays.asList(e.stackTrace)
            .take(5)
            .collect { it.toString() }
            .collect { line ->
                // Remove absolute paths from stack trace
                line.replaceAll(/[A-Za-z]:\\[\\\w\.\-]+/, '<path>')
                    .replaceAll(/\/[\w\/\.\-]+/, '<path>')
            }
    }
    
    /**
     * Validate actor name for security.
     */
    private static void validateActorName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Actor name cannot be null or empty")
        }
        if (name.length() > 256) {
            throw new IllegalArgumentException("Actor name too long (max 256 characters)")
        }
        if (name.contains("..") || name.contains("/") || name.contains("\\")) {
            throw new IllegalArgumentException("Actor name contains illegal path characters")
        }
        // Allow: letters, numbers, dash, underscore, dot
        if (!name.matches(/^[a-zA-Z0-9._-]+$/)) {
            throw new IllegalArgumentException("Actor name contains invalid characters (allowed: a-z, A-Z, 0-9, ., _, -)")
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Configuration
    // ─────────────────────────────────────────────────────────────

    @Override
    void setMaxMailboxSize(int max) {
        if (max < 0) {
            throw new IllegalArgumentException("Max mailbox size cannot be negative")
        }
        
        // Note: Changing mailbox size on a running actor is not recommended
        // as it requires draining and recreating the queue.
        // This is mainly provided for testing/debugging.
        log.warn("[$name] Changing mailbox size on running actor is not recommended")
        
        this.maxMailboxSize = max
        
        // Recreate mailbox with new size (bounded or unbounded)
        BlockingQueue<MessageEnvelope> newMailbox
        if (max > 0) {
            newMailbox = new LinkedBlockingQueue<>(max)
        } else {
            newMailbox = new LinkedBlockingQueue<>()
        }
        
        // Transfer existing messages
        if (mailbox != null) {
            List<MessageEnvelope> existingMessages = []
            mailbox.drainTo(existingMessages)
            newMailbox.addAll(existingMessages)
        }
        
        this.mailbox = newMailbox
    }

    @Override
    int getMaxMailboxSize() {
        return maxMailboxSize
    }
    
    // ─────────────────────────────────────────────────────────────
    // Supervision
    // ─────────────────────────────────────────────────────────────
    
    /**
     * Set the supervision strategy for this actor.
     * This determines how the actor handles failures during message processing.
     */
    Actor setSupervisionStrategy(SupervisionStrategy strategy) {
        this.supervisionStrategy = strategy
        if (strategy) {
            this.restartStats = new RestartStatistics(
                strategy.maxRestarts,
                strategy.withinDuration
            )
        }
        return this
    }
    
    /**
     * Get the current supervision strategy, if any.
     */
    SupervisionStrategy getSupervisionStrategy() {
        return supervisionStrategy
    }
    
    /**
     * Get restart statistics for this actor.
     */
    RestartStatistics getRestartStats() {
        return restartStats
    }

    // ─────────────────────────────────────────────────────────────
    // Observability
    // ─────────────────────────────────────────────────────────────

    @Override
    Map<String, Object> health() {
        int currentMailboxSize = mailbox.size()
        boolean isRunning = running.get()
        boolean isProcessing = processing.get()

        String status
        if (!isRunning) {
            status = "STOPPING"
        } else if (currentMailboxSize > (maxMailboxSize * 0.8) && maxMailboxSize > 0) {
            status = "DEGRADED"
        } else {
            status = "HEALTHY"
        }

        return [
                name               : name,
                status             : status,
                running            : isRunning,
                terminated         : isTerminated(),
                processing         : isProcessing,
                mailboxSize        : currentMailboxSize,
                maxMailboxSize     : maxMailboxSize,
                mailboxUtilization : maxMailboxSize > 0 ? (currentMailboxSize * 100.0 / maxMailboxSize) : 0.0,
                recentErrorCount   : recentErrors.size(),
                timestamp          : System.currentTimeMillis()
        ] as Map<String, Object>
    }

    @Override
    Map<String, Object> metrics() {
        // Snapshot all values atomically
        long received = messagesReceived.get()
        long processed = messagesProcessed.get()
        long errored = messagesErrored.get()
        long rejections = mailboxRejections.get()
        int mailboxDepth = mailbox.size()
        boolean isProcessing = processing.get()
        boolean isRunning = running.get()
        long lastProcessed = lastMessageProcessedAt

        long now = System.currentTimeMillis()
        long uptime = now - createdAt

        // Calculate derived metrics
        long pending = received - processed
        double throughput = uptime > 0 ? (processed * 1000.0 / uptime) : 0.0
        double errorRate = processed > 0 ? (errored * 100.0 / processed) : 0.0

        return [
                name                    : name,
                messagesReceived        : received,
                messagesProcessed       : processed,
                messagesPending         : pending,
                messagesErrored         : errored,
                mailboxRejections       : rejections,
                mailboxDepth            : mailboxDepth,
                maxMailboxSize          : maxMailboxSize,
                processing              : isProcessing,
                running                 : isRunning,
                terminated              : isTerminated(),
                uptimeMs                : uptime,
                throughputPerSec        : throughput,
                errorRatePercent        : errorRate,
                lastMessageProcessedAt  : lastProcessed,
                createdAt               : createdAt,
                timestamp               : now
        ] as Map<String, Object>
    }

    // ─────────────────────────────────────────────────────────────
    // Internal: Mailbox Loop
    // ─────────────────────────────────────────────────────────────

    private void startMailboxLoop() {
        // fix the ide warning type handling failure in the closure
        final CountDownLatch terminatedLatch = this.terminated

        pool.execute({
            log.debug("[$name] Mailbox loop started")

            try {
                while (running.get()) {
                    MessageEnvelope envelope = mailbox.take()

                    if (!running.get() || envelope.payload == "__STOP__") {
                        log.debug("[$name] Stop signal received")
                        break
                    }

                    processing.set(true)
                    try {
                        processMessage(envelope)
                    } finally {
                        processing.set(false)
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt()
                log.debug("[$name] Mailbox loop interrupted")
            } finally {
                // groovy type fix for the IDE
                terminatedLatch.countDown()
                log.debug("[$name] Mailbox loop terminated")
            }
        } as Runnable)
    }

    private void processMessage(MessageEnvelope envelope) {
        messagesProcessed.incrementAndGet()

        def ctx = new ActorContext(
                name,
                envelope.payload,
                state,
                system,
                envelope.replyFuture,
                envelope.sender as Actor  // Cast to interface
        )

        try {
            def result = handler.call(envelope.payload, ctx)

            // Auto-reply if ask and handler didn't explicitly reply AND didn't defer
            if (envelope.isAsk() && !envelope.replyFuture.isDone() && !ctx.isReplyDeferred()) {
                ctx.reply(result)
            }

            lastMessageProcessedAt = System.currentTimeMillis()

        } catch (Throwable t) {
            handleMessageError(t)

            if (envelope.isAsk() && !envelope.replyFuture.isDone()) {
                envelope.replyFuture.completeExceptionally(t)
            }
        }
    }

    private void enqueueMessage(Object msg, CompletableFuture<Object> future, Actor sender) {
        if (!running.get()) {
            throw new IllegalStateException("Actor [$name] is not running")
        }

        try {
            // Use offer without timeout for immediate non-blocking attempt
            boolean added = mailbox.offer(new MessageEnvelope(msg, future, sender))
            
            if (!added) {
                // Queue is full - reject
                mailboxRejections.incrementAndGet()
                // Log details internally, but don't expose in exception
                log.debug("[$name] Mailbox full: size=${mailbox.size()}, max=$maxMailboxSize")
                throw new RejectedExecutionException(
                        "Actor [$name] mailbox full - message rejected")
            }
            
            // Only increment if successfully added
            messagesReceived.incrementAndGet()
        } catch (RejectedExecutionException e) {
            // Re-throw rejection exceptions
            throw e
        } catch (Exception e) {
            throw new IllegalStateException("Error enqueuing message", e)
        }
    }
    
    // ─────────────────────────────────────────────────────────────
    // Death Watch Support
    // ─────────────────────────────────────────────────────────────
    
    /**
     * Notify all watching actors that this actor has terminated.
     * Also cleanup death watch registry.
     */
    private void notifyWatchersAndCleanup(Throwable cause) {
        if (system == null) {
            return // No system reference, can't notify
        }
        
        try {
            // Get all watchers before cleanup
            Set<String> watcherNames = system.deathWatch.getWatchers(this)
            
            // Send Terminated messages to all watchers FIRST
            def terminatedMsg = cause != null 
                ? new Terminated(this, cause) 
                : new Terminated(this)
            
            for (String watcherName : watcherNames) {
                try {
                    def watcher = system.getActor(watcherName)
                    if (watcher != null && !watcher.isStopped()) {
                        watcher.tell(terminatedMsg)
                        log.debug("[$name] Sent Terminated message to watcher [$watcherName]")
                    }
                } catch (Exception e) {
                    log.warn("[$name] Failed to notify watcher [$watcherName]", e)
                }
            }
            
            // THEN remove this actor from death watch registry
            system.deathWatch.removeActor(this)
            
            // Also cleanup hierarchy relationships
            system.hierarchy.removeActor(this)
            
        } catch (Exception e) {
            log.error("[$name] Error in notifyWatchersAndCleanup", e)
        }
    }
}
