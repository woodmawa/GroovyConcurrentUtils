package org.softwood.agent

import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import groovyjarjarantlr4.v4.runtime.misc.NotNull
import org.softwood.pool.ExecutorPool

import java.lang.reflect.Modifier
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Supplier

/**
 * GroovyAgent<T> – single-threaded, message-driven access to mutable state.
 *
 * <p>This class wraps a mutable object and guarantees all updates and reads occur
 * sequentially on a dedicated worker thread. Multiple threads may submit work concurrently;
 * the Agent ensures serialized execution.</p>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Serial execution of closures on a wrapped object</li>
 *   <li>Fire-and-forget ({@link #send}) and synchronous ({@link #sendAndGet}) operations</li>
 *   <li>Immutable snapshot copying via a pluggable copy strategy</li>
 *   <li>Graceful draining shutdown ({@link #shutdown})</li>
 *   <li>Health and metrics endpoints for monitoring</li>
 *   <li>Error tracking and custom error handlers</li>
 *   <li>Optional queue size limits with backpressure</li>
 * </ul>
 *
 * <p><strong>Note:</strong> Use {@link AgentFactory} to create instances rather than
 * directly constructing GroovyAgent.</p>
 *
 * @param <T> type of underlying state
 */
@Slf4j
@CompileStatic
class GroovyAgent<T> implements Agent<T> {

    /** Wrapped mutable state */
    private final T wrappedObject

    /** FIFO queue of tasks to run sequentially */
    private final ConcurrentLinkedQueue<Task> taskQueue = new ConcurrentLinkedQueue<>()

    /** Flag controlling entry into processing loop */
    private final AtomicBoolean processing = new AtomicBoolean(false)

    /** True once shutdown has been initiated */
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false)

    /** Signals when queue has fully drained after shutdown */
    private final CountDownLatch drained = new CountDownLatch(1)

    /** Supplier returning an immutable snapshot of wrappedObject */
    private final Supplier<T> immutableCopySupplier

    /** ExecutorPool for running the worker loop */
    private final ExecutorPool pool

    /** True if this agent owns the pool and should shut it down */
    private final boolean ownsPool

    /** Tracks total tasks submitted */
    private final AtomicLong tasksSubmitted = new AtomicLong(0)

    /** Tracks total tasks completed */
    private final AtomicLong tasksCompleted = new AtomicLong(0)

    /** Tracks total task errors */
    private final AtomicLong tasksErrored = new AtomicLong(0)

    /** Tracks queue rejections due to size limits */
    private final AtomicLong queueRejections = new AtomicLong(0)

    /** Agent creation timestamp */
    private final long createdAt = System.currentTimeMillis()

    /** Timestamp of last completed task */
    private volatile long lastTaskCompletedAt = 0

    /** Maximum queue size (0 = unbounded) */
    private volatile int maxQueueSize = 0

    /** Custom error handler */
    private volatile Closure<Void> errorHandler

    /** Recent errors (limited queue) */
    private final ConcurrentLinkedQueue<Map<String, Object>> recentErrors = new ConcurrentLinkedQueue<>()

    /** Maximum errors to retain */
    private static final int MAX_ERRORS_RETAINED = 100

    // ----------------------------------------------------------------------
    // TASK TYPES
    // ----------------------------------------------------------------------

    /**
     * A unit of work executed sequentially by the Agent.
     */
    private static interface Task {
        void execute(Object target)
    }

    /**
     * Fire-and-forget closure task.
     */
    private static class ClosureTask implements Task {
        final Closure closure
        ClosureTask(Closure c) { this.closure = c }

        /** Executes the closure with delegate=target */
        void execute(Object target) {
            Closure cc = closure.clone() as Closure
            cc.delegate = target
            cc.resolveStrategy = Closure.DELEGATE_FIRST
            cc.call()
        }
    }

    /**
     * Synchronous task that captures a return value.
     */
    private static class SendAndGetTask<R> implements Task {
        final Closure<R> action
        R result
        Throwable error

        SendAndGetTask(Closure<R> a) { action = a }

        /** Executes closure and captures result or error */
        void execute(Object target) {
            try {
                Closure<R> c = action.clone() as Closure
                c.delegate = target
                c.resolveStrategy = Closure.DELEGATE_FIRST
                result = (R) c.call()
            } catch (Throwable t) {
                error = t
            }
        }

        /** Returns result or rethrows error */
        R getResult() {
            if (error != null)
                throw (error instanceof RuntimeException ? error : new RuntimeException(error))
            result
        }
    }

    // ----------------------------------------------------------------------
    // CONSTRUCTION
    // ----------------------------------------------------------------------

    /**
     * Creates a new Agent wrapping the given object.
     * 
     * Package-private constructor - use AgentFactory for construction.
     *
     * @param wrappedObject object whose state will be updated sequentially
     * @param copy strategy for defensive snapshot cloning; defaults to reflection-based deep copy
     * @param pool ExecutorPool for task execution
     * @param ownsPool whether this agent owns the pool and should shut it down
     */
    GroovyAgent(@NotNull T wrappedObject, Supplier<T> copy = null, ExecutorPool pool = null, boolean ownsPool = false) {
        this.wrappedObject = wrappedObject
        this.immutableCopySupplier = copy ?: { deepCopy(wrappedObject) } as Supplier<T>
        this.pool = pool ?: AgentFactory.defaultPool()
        this.ownsPool = ownsPool
    }

    // ----------------------------------------------------------------------
    // DEEP COPY ENGINE
    // ----------------------------------------------------------------------

    /** Deep-copies arbitrary values including nested Maps/Lists */
    @CompileDynamic
    private Object deepCopyValue(Object v) {
        if (v == null) return null
        // Immutable types - return as-is
        if (v instanceof String || v instanceof Number || v instanceof Boolean || 
            v instanceof Character || v instanceof Enum || v instanceof groovy.lang.GString)
            return v
        
        // Fast path for Cloneable
        if (v instanceof Cloneable && !(v instanceof Collection) && !(v instanceof Map)) {
            try {
                return v.clone()
            } catch (CloneNotSupportedException ignored) {
                // Fall through to reflection
            }
        }
        
        if (v instanceof Collection) {
            Collection src = (Collection) v
            Collection copy = (v instanceof Set) ? new LinkedHashSet<>() : new ArrayList<>()
            src.each { copy.add(deepCopyValue(it)) }
            return copy
        }
        if (v instanceof Map) {
            Map m = (Map) v
            Map copy = new LinkedHashMap<>()
            m.each { k, val -> copy[deepCopyValue(k)] = deepCopyValue(val) }
            return copy
        }
        return deepCopyObject(v)
    }

    /** Deep-copies arbitrary POJOs using reflection */
    @CompileDynamic
    private Object deepCopyObject(Object o) {
        def cls = o.class
        def inst = cls.getDeclaredConstructor().newInstance()

        def classes = []
        for (def c = cls; c != Object; c = c.superclass) classes.add(0, c)

        classes.each { c ->
            c.declaredFields.each { f ->
                if (Modifier.isStatic(f.modifiers) || Modifier.isFinal(f.modifiers) || f.synthetic) return
                if (!f.canAccess(o)) f.accessible = true
                f.set(inst, deepCopyValue(f.get(o)))
            }
        }
        inst
    }

    /** Generic deep copy entry point */
    T deepCopy(Object o) { (T) deepCopyValue(o) }

    // ----------------------------------------------------------------------
    // PUBLIC OPERATIONS
    // ----------------------------------------------------------------------

    /**
     * Submits a fire-and-forget update task.
     *
     * @param action closure to execute on underlying state
     * @return this Agent
     */
    @Override
    GroovyAgent<T> send(Closure action) {
        if (shuttingDown.get()) 
            throw new IllegalStateException("Agent is shutting down")
        
        // Check queue size limit
        if (maxQueueSize > 0 && taskQueue.size() >= maxQueueSize) {
            queueRejections.incrementAndGet()
            throw new RejectedExecutionException(
                "Agent queue full (size=${taskQueue.size()}, max=$maxQueueSize)")
        }
        
        tasksSubmitted.incrementAndGet()
        taskQueue.offer(new ClosureTask(action))
        processQueue()
        this
    }

    /** Alias for {@link #send} */
    @Override
    GroovyAgent<T> async(Closure c) { send(c) }

    /** Synchronous update with optional timeout */
    @Override
    def <R> R sync(Closure<R> c, long t = 0) { sendAndGet(c, t) }

    /** Operator: agent >> closure */
    @Override
    def rightShift(Closure c) { async(c) }

    /** Operator: agent << closure returning R */
    @Override
    def <R> R leftShift(Closure<R> c) { sync(c) }

    /**
     * Submits a closure and blocks until it completes.
     *
     * @param action closure returning a result
     * @param timeoutSeconds optional timeout
     * @return closure return value
     */
    @Override
    def <R> R sendAndGet(Closure<R> action, long timeoutSeconds = 0L) {
        if (shuttingDown.get()) throw new IllegalStateException("Agent is shutting down")
        def task = new SendAndGetTask<R>(action)
        def latch = new CountDownLatch(1)

        // Use send which will increment tasksSubmitted
        send {
            try { task.execute(delegate) } finally { latch.countDown() }
        }

        boolean ok
        if (timeoutSeconds > 0) {
            ok = latch.await(timeoutSeconds, TimeUnit.SECONDS)
        } else {
            latch.await()
            ok = true
        }

        if (!ok) throw new RuntimeException("sendAndGet timed out after ${timeoutSeconds}s")
        task.getResult()
    }

    /** Returns an immutable defensive snapshot of state */
    @Override
    T getValue() { immutableCopySupplier?.get() ?: wrappedObject }

    // ----------------------------------------------------------------------
    // LIFECYCLE MANAGEMENT
    // ----------------------------------------------------------------------

    /**
     * Initiates shutdown: prevents new tasks, drains queue, waits until finished.
     */
    @Override
    void shutdown() {
        shuttingDown.set(true)
        processQueue()
        try {
            drained.await()
        } finally {
            if (ownsPool && pool != null) {
                pool.shutdown()
            }
        }
    }

    /**
     * Initiates shutdown with timeout.
     */
    @Override
    boolean shutdown(long timeout, TimeUnit unit) {
        shuttingDown.set(true)
        processQueue()
        try {
            return drained.await(timeout, unit)
        } finally {
            if (ownsPool && pool != null) {
                pool.shutdown()
            }
        }
    }

    /**
     * Forcefully shuts down, discarding pending tasks.
     */
    @Override
    void shutdownNow() {
        shuttingDown.set(true)
        int discarded = taskQueue.size()
        taskQueue.clear()
        drained.countDown()
        
        if (ownsPool && pool != null) {
            pool.shutdown()
        }
        
        log.info("Agent forcefully shutdown, discarded {} pending tasks", discarded)
    }

    /**
     * Returns true if shutdown has been initiated.
     */
    @Override
    boolean isShutdown() {
        return shuttingDown.get()
    }

    /**
     * Returns true if shutdown is complete.
     */
    @Override
    boolean isTerminated() {
        return shuttingDown.get() && taskQueue.isEmpty() && !processing.get()
    }

    // ----------------------------------------------------------------------
    // ERROR MANAGEMENT
    // ----------------------------------------------------------------------

    /**
     * Sets an error handler for task failures.
     */
    @Override
    Agent<T> onError(Closure<Void> handler) {
        this.errorHandler = handler
        return this
    }

    /**
     * Returns recent errors.
     */
    @Override
    List<Map<String, Object>> getErrors(int maxCount = Integer.MAX_VALUE) {
        def errors = recentErrors.toArray() as Map<String, Object>[]
        return errors.take(Math.min(maxCount, errors.length)).toList()
    }

    /**
     * Clears error history.
     */
    @Override
    void clearErrors() {
        recentErrors.clear()
    }

    /**
     * Handles a task error.
     */
    private void handleTaskError(Throwable e) {
        tasksErrored.incrementAndGet()
        
        // Store error details
        def errorInfo = [
            timestamp: System.currentTimeMillis(),
            errorType: e.class.name,
            message: e.message ?: "No message",
            stackTrace: e.stackTrace.take(5)*.toString()
        ]
        
        recentErrors.offer(errorInfo)
        while (recentErrors.size() > MAX_ERRORS_RETAINED) {
            recentErrors.poll()
        }
        
        // Call custom error handler if set
        if (errorHandler) {
            try {
                errorHandler.call(e)
            } catch (Throwable handlerError) {
                log.error("Error handler failed", handlerError)
            }
        }
        
        log.error("Agent task failed", e)
    }

    // ----------------------------------------------------------------------
    // CONFIGURATION
    // ----------------------------------------------------------------------

    /**
     * Returns maximum queue size.
     */
    @Override
    int getMaxQueueSize() {
        return maxQueueSize
    }

    /**
     * Sets maximum queue size.
     */
    @Override
    void setMaxQueueSize(int max) {
        if (max < 0) {
            throw new IllegalArgumentException("Max queue size cannot be negative")
        }
        this.maxQueueSize = max
    }

    // ----------------------------------------------------------------------
    // HEALTH & METRICS
    // ----------------------------------------------------------------------

    /**
     * Returns health status of this agent.
     *
     * @return map with keys: status, shuttingDown, processing, queueSize
     */
    @Override
    Map<String, Object> health() {
        int queueSize = taskQueue.size()
        boolean isProcessing = processing.get()
        boolean isShuttingDown = shuttingDown.get()
        
        String status
        if (isShuttingDown) {
            status = "SHUTTING_DOWN"
        } else if (queueSize > (maxQueueSize * 0.8) && maxQueueSize > 0) {
            status = "DEGRADED"
        } else {
            status = "HEALTHY"
        }
        
        return [
                status: status,
                shuttingDown: isShuttingDown,
                terminated: isTerminated(),
                processing: isProcessing,
                queueSize: queueSize,
                maxQueueSize: maxQueueSize,
                queueUtilization: maxQueueSize > 0 ? (queueSize * 100.0 / maxQueueSize) : 0.0,
                recentErrorCount: recentErrors.size(),
                timestamp: System.currentTimeMillis()
        ]
    }

    /**
     * Returns operational metrics for this agent.
     *
     * @return map with task counts, queue depth, and timing information
     */
    @Override
    Map<String, Object> metrics() {
        // Snapshot all values atomically
        long submitted = tasksSubmitted.get()
        long completed = tasksCompleted.get()
        long errored = tasksErrored.get()
        long rejections = queueRejections.get()
        int qDepth = taskQueue.size()
        boolean proc = processing.get()
        boolean shutdown = shuttingDown.get()
        long lastCompleted = lastTaskCompletedAt
        
        long now = System.currentTimeMillis()
        long uptime = now - createdAt
        
        // Calculate derived metrics
        long pending = submitted - completed
        double throughput = uptime > 0 ? (completed * 1000.0 / uptime) : 0.0
        double errorRate = completed > 0 ? (errored * 100.0 / completed) : 0.0
        
        // Return consistent snapshot
        return [
                tasksSubmitted: submitted,
                tasksCompleted: completed,
                tasksPending: pending,
                tasksErrored: errored,
                queueRejections: rejections,
                queueDepth: qDepth,
                maxQueueSize: maxQueueSize,
                processing: proc,
                shuttingDown: shutdown,
                terminated: isTerminated(),
                uptimeMs: uptime,
                throughputPerSec: throughput,
                errorRatePercent: errorRate,
                lastTaskCompletedAt: lastCompleted,
                createdAt: createdAt,
                timestamp: now
        ]
    }

    // ----------------------------------------------------------------------
    // INTERNAL EXECUTION LOOP
    // ----------------------------------------------------------------------

    /**
     * Processes tasks in FIFO order. Uses executor to run a worker loop
     * that drains the queue until empty. Ensures only one processor runs
     * at a time via CAS on {@link #processing}.
     */
    private void processQueue() {
        if (!processing.compareAndSet(false, true)) return

        try {
            pool.execute({ ->
                try {
                    Task t
                    while ((t = taskQueue.poll()) != null) {
                        try {
                            t.execute(wrappedObject)
                            tasksCompleted.incrementAndGet()
                            lastTaskCompletedAt = System.currentTimeMillis()
                        } catch (Throwable e) {
                            handleTaskError(e)
                        }
                    }
                } finally {
                    processing.set(false)

                    if (!taskQueue.isEmpty()) processQueue()
                    else if (shuttingDown.get()) drained.countDown()
                }
            } as Runnable)
        } catch (Throwable e) {
            processing.set(false)
            log.error("Failed to schedule agent worker", e)
        }
    }
}
