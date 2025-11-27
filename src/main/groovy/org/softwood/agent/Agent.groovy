package org.softwood.agent

import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import groovyjarjarantlr4.v4.runtime.misc.NotNull
import org.softwood.pool.ConcurrentPool

import java.lang.reflect.Modifier
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Supplier

/**
 * Agent<T> – single-threaded, message-driven access to mutable state.
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
 *   <li>Convenient DSL builder ({@code #agent(Object)})</li>
 * </ul>
 *
 * @param <T> type of underlying state
 */
@Slf4j
@CompileStatic
class Agent<T> {

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

    /** Executor for running the worker loop */
    private final ExecutorService executor

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
     * @param wrappedObject object whose state will be updated sequentially
     * @param copy strategy for defensive snapshot cloning; defaults to reflection-based deep copy
     * @param exec optional executor; defaults to ConcurrentPool
     */
    Agent(@NotNull T wrappedObject, Supplier<T> copy = null, ExecutorService exec = null) {
        this.wrappedObject = wrappedObject
        this.immutableCopySupplier = copy ?: { deepCopy(wrappedObject) } as Supplier<T>
        this.executor = exec ?: new ConcurrentPool().executor
    }

    /**
     * Creates a builder for constructing an Agent with DSL-style options.
     */
    static <T> AgentBuilder<T> agent(T value) { new AgentBuilder<>(value) }

    /** Builder class for Agent instantiation */
    static class AgentBuilder<T> {
        final T value
        Supplier<T> copySupplier
        ExecutorService exec

        AgentBuilder(T v) { value = v }

        /** Sets custom snapshot copy strategy */
        AgentBuilder<T> immutableCopyBy(Supplier<T> s) { copySupplier = s; this }

        /** Sets custom executor */
        AgentBuilder<T> usingExecutor(ExecutorService e) { exec = e; this }

        /** Creates the Agent */
        Agent<T> build() { new Agent<>(value, copySupplier, exec) }
    }

    // ----------------------------------------------------------------------
    // DEEP COPY ENGINE
    // ----------------------------------------------------------------------

    /** Deep-copies arbitrary values including nested Maps/Lists */
    @CompileDynamic
    private Object deepCopyValue(Object v) {
        if (v == null) return null
        if (v instanceof String || v instanceof Number || v instanceof Boolean || v instanceof Character || v instanceof Enum)
            return v
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
    Agent<T> send(Closure action) {
        if (shuttingDown.get()) throw new IllegalStateException("Agent is shutting down")
        taskQueue.offer(new ClosureTask(action))
        processQueue()
        this
    }

    /** Alias for {@link #send} */
    Agent<T> async(Closure c) { send(c) }

    /** Synchronous update with optional timeout */
    def <R> R sync(Closure<R> c, long t = 0) { sendAndGet(c, t) }

    /** Operator: agent >> closure */
    def rightShift(Closure c) { async(c) }

    /** Operator: agent << closure returning R */
    def <R> R leftShift(Closure<R> c) { sync(c) }

    /**
     * Submits a closure and blocks until it completes.
     *
     * @param action closure returning a result
     * @param timeoutSeconds optional timeout
     * @return closure return value
     */
    def <R> R sendAndGet(Closure<R> action, long timeoutSeconds = 0L) {
        if (shuttingDown.get()) throw new IllegalStateException("Agent is shutting down")
        def task = new SendAndGetTask<R>(action)
        def latch = new CountDownLatch(1)

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
    T getVal() { immutableCopySupplier?.get() ?: wrappedObject }

    /**
     * Initiates shutdown: prevents new tasks, drains queue, waits until finished.
     */
    void shutdown() {
        shuttingDown.set(true)
        processQueue()
        drained.await()
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
            executor.execute({ ->
                try {
                    Task t
                    while ((t = taskQueue.poll()) != null) {
                        try {
                            t.execute(wrappedObject)
                        } catch (Throwable e) {
                            log.error("Agent task failed", e)
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
