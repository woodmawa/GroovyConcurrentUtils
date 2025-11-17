package org.softwood.agent

import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import groovyjarjarantlr4.v4.runtime.misc.NotNull
import org.softwood.pool.ConcurrentPool

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Supplier
import java.util.concurrent.CountDownLatch

/**
 * A thread-safe agent that wraps a non-thread-safe object.
 *
 * - All actions on the wrapped object are executed sequentially.
 * - Execution is performed on an ExecutorService (virtual-thread pool by default via ConcurrentPool).
 * - Closures are executed with delegate = wrappedObject (DELEGATE_FIRST).
 * - getVal() returns a defensive copy when possible, or the wrapped object as a fallback.
 *
 * API:
 *   Agent<T> agent = Agent.agent([count: 0]).build()
 *
 *   agent >> { count++ }            // async, fire-and-forget
 *   def v = agent << { count }      // sync, returns result
 *
 *   agent.send { count++ }          // async
 *   def res = agent.sendAndGet { count }  // sync
 */
@Slf4j
@CompileStatic
class Agent<T> {

    /** Wrapped, non-thread-safe object. Only touched from the worker. */
    private final T wrappedObject

    /** FIFO of tasks to run on the wrapped object. */
    private final ConcurrentLinkedQueue<Task> taskQueue = new ConcurrentLinkedQueue<>()

    /** Guards single consumer: true while worker is draining the queue. */
    private final AtomicBoolean processing = new AtomicBoolean(false)

    /** Strategy to produce a defensive copy of the wrapped object. */
    private final Supplier<T> immutableCopySupplier

    /** Executor used to run the single worker. */
    private final ExecutorService executor

    /** If true, this Agent owns the executor and will shut it down on close. */
    private final boolean ownsExecutor

    // ─────────────────────────────────────────────────────────────
    // Internal Task abstraction
    // ─────────────────────────────────────────────────────────────

    private static interface Task {
        void execute(Object target)
    }

    private static class ClosureTask implements Task {
        private final Closure closure

        ClosureTask(Closure closure) {
            this.closure = closure
        }

        @Override
        void execute(Object target) {
            Closure cloned = closure.clone() as Closure
            cloned.delegate = target
            cloned.resolveStrategy = Closure.DELEGATE_FIRST
            cloned.call()
        }
    }

    private static class SendAndGetTask<R> implements Task {
        private final Closure<R> action
        private R result
        private Throwable error

        SendAndGetTask(Closure<R> action) {
            this.action = action
        }

        @Override
        void execute(Object target) {
            try {
                Closure<R> cloned = action.clone() as Closure
                cloned.delegate = target
                cloned.resolveStrategy = Closure.DELEGATE_FIRST
                result = cloned.call() as R
            } catch (Throwable t) {
                error = t
            }
        }

        R getResult() {
            if (error != null) {
                if (error instanceof RuntimeException) {
                    throw (RuntimeException) error
                }
                throw new RuntimeException("sendAndGet: Exception during task execution", error)
            }
            return result
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Construction
    // ─────────────────────────────────────────────────────────────

    /**
     * Primary constructor (used by builder / factory).
     */
    Agent(@NotNull T wrappedObject,
          Supplier<T> immutableCopySupplier = null,
          ExecutorService executor = null) {

        this.wrappedObject = wrappedObject

        if (immutableCopySupplier != null) {
            this.immutableCopySupplier = immutableCopySupplier
        } else {
            this.immutableCopySupplier = useDeepCopySupplier()
        }

        if (executor != null) {
            this.executor = executor
            this.ownsExecutor = false
        } else {
            def pool = new ConcurrentPool()              // virtual-thread per task
            this.executor = pool.getExecutor()
            this.ownsExecutor = true
        }

        log.debug("Agent created with object: {}", wrappedObject)
    }

    /**
     * Factory entry point for DSL-style creation:
     *   def a = Agent.agent(myObj).build()
     *   def a = Agent.agent(myObj).immutableCopyBy { ... }.usingExecutor(custom).build()
     */
    static <T> AgentBuilder<T> agent(T value) {
        new AgentBuilder<>(value)
    }

    static class AgentBuilder<T> {
        private final T value
        private Supplier<T> copySupplier
        private ExecutorService executor

        AgentBuilder(T value) {
            this.value = value
        }

        AgentBuilder<T> immutableCopyBy(Supplier<T> supplier) {
            this.copySupplier = supplier
            this
        }

        AgentBuilder<T> usingExecutor(ExecutorService executor) {
            this.executor = executor
            this
        }

        Agent<T> build() {
            new Agent<>(value, copySupplier, executor)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Deep copy support (best-effort)
    // ─────────────────────────────────────────────────────────────

    /**
     * Returns a supplier that performs a deep-ish copy of the wrapped object.
     */
    private Supplier<T> useDeepCopySupplier() {
        return { deepCopy(wrappedObject) } as Supplier<T>
    }


    /**
     * Deep copy entry point for the wrapped object.
     * Delegates to deepCopyValue for recursive copying.
     */
    @CompileDynamic
    @NotNull
    T deepCopy(Object object) {
        (T) deepCopyValue(object)
    }

/**
 * Recursively deep-copies arbitrary objects:
 *  - null → null
 *  - primitives / wrappers / String / Number / Boolean / Character → same instance
 *  - Collection → new collection, deep-copy each element
 *  - Map → new map, deep-copy each value (and keys if they are complex)
 *  - Cloneable with clone() → use clone()
 *  - Other objects → reflectively copy fields, deep-copying each field value
 */
    @CompileDynamic
    private Object deepCopyValue(Object value) {
        if (value == null) {
            return null
        }

        // Immutable / primitive-like values: safe to share
        if (value instanceof String ||
                value instanceof Number ||
                value instanceof Boolean ||
                value instanceof Character ||
                value instanceof Enum) {
            return value
        }

        // Collections
        if (value instanceof Collection) {
            Collection original = (Collection) value
            Collection copy
            try {
                copy = (Collection) value.getClass().getDeclaredConstructor().newInstance()
            } catch (Exception ignored) {
                // Fallback to most general types
                copy = (value instanceof Set) ? (Collection) new LinkedHashSet<>() : new ArrayList<>()
            }
            original.each { elem ->
                copy.add(deepCopyValue(elem))
            }
            return copy
        }

        // Maps
        if (value instanceof Map) {
            Map original = (Map) value
            Map copy
            try {
                copy = (Map) value.getClass().getDeclaredConstructor().newInstance()
            } catch (Exception ignored) {
                copy = new LinkedHashMap<>()
            }
            original.each { k, v ->
                def newKey = deepCopyValue(k)
                def newVal = deepCopyValue(v)
                copy[newKey] = newVal
            }
            return copy
        }

        // Cloneable objects with clone()
        if (value instanceof Cloneable && value.metaClass.respondsTo(value, 'clone')) {
            return value.clone()
        }

        // Fallback: reflectively copy fields of a "bean"/POJO
        return deepCopyObject(value)
    }

    /**
     * Deep-copy a non-collection, non-map object by:
     *  - constructing a new instance (default or Map constructor)
     *  - walking its class hierarchy
     *  - deepCopyValue() on each non-static, non-final, non-synthetic field
     */
    @CompileDynamic
    private Object deepCopyObject(Object original) {
        def originalClass = original.getClass()
        def copyInstance

        // Try default constructor or Map constructor
        try {
            copyInstance = originalClass.getDeclaredConstructor().newInstance()
        } catch (Exception e) {
            def constructors = originalClass.declaredConstructors
            def ctor = constructors.find { c ->
                c.parameterTypes.length == 0 ||
                        (c.parameterTypes.length == 1 && c.parameterTypes[0] == Map)
            }
            if (ctor) {
                ctor.accessible = true
                copyInstance = ctor.parameterTypes.length == 0 ? ctor.newInstance() : ctor.newInstance([:])
            } else {
                throw new RuntimeException("Cannot find suitable constructor for class ${originalClass.name}")
            }
        }

        // Copy fields up the hierarchy
        def classesToCopy = []
        def currentClass = originalClass
        while (currentClass != Object) {
            classesToCopy.add(0, currentClass)
            currentClass = currentClass.superclass
        }

        classesToCopy.each { Class<?> clazz ->
            copyPropertiesFromClass(clazz, original, copyInstance)
        }

        return copyInstance
    }

    /**
     * Copy all non-static, non-final, non-synthetic fields from original to copy,
     * using deepCopyValue() for the field value.
     */
    @CompileDynamic
    private void copyPropertiesFromClass(Class clazz, Object original, Object copy) {
        Field[] fields = clazz.declaredFields
        fields.each { Field field ->
            if (Modifier.isStatic(field.modifiers) ||
                    Modifier.isFinal(field.modifiers) ||
                    field.synthetic) {
                return
            }

            if (field.name == 'size') {
                return
            }

            if (!field.canAccess(original)) {
                try {
                    field.accessible = true
                } catch (SecurityException se) {
                    log.trace("Field ${field.name} inaccessible, skipping")
                    return
                }
            }

            def originalValue = field.get(original)
            def copiedValue = deepCopyValue(originalValue)
            field.set(copy, copiedValue)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────

    /**
     * Fire-and-forget: enqueue a closure to run against the wrapped object.
     * Closure delegate = wrappedObject, DELEGATE_FIRST.
     */
    Agent<T> send(Closure action) {
        log.trace("Queuing action for execution")
        taskQueue.offer(new ClosureTask(action))
        processQueue()
        return this
    }

    /** Alias for send() for readability. */
    Agent<T> async(Closure action) {
        send(action)
    }

    /**
     * Execute closure on wrapped object and return its result.
     * Blocks the caller until completion (cheap with virtual threads).
     *
     * Optional timeout in seconds; 0 or negative means "no timeout".
     */
    def <R> R sendAndGet(Closure<R> action, long timeoutSeconds = 0L) {
        def task = new SendAndGetTask<R>(action)
        def latch = new CountDownLatch(1)

        send {
            try {
                task.execute(delegate)
            } finally {
                latch.countDown()
            }
        }

        boolean completed
        if (timeoutSeconds > 0) {
            completed = latch.await(timeoutSeconds, TimeUnit.SECONDS)
        } else {
            latch.await()
            completed = true
        }

        if (!completed) {
            throw new RuntimeException("sendAndGet timed out after ${timeoutSeconds}s")
        }

        return task.getResult()
    }

    /** Alias for sendAndGet() for readability. */
    def <R> R sync(Closure<R> action, long timeoutSeconds = 0L) {
        sendAndGet(action, timeoutSeconds)
    }

    /** Operator overloads: >> = async, << = sync. */
    def rightShift(Closure action) {
        async(action)
    }

    def <R> R leftShift(Closure<R> action) {
        sync(action)
    }

    /**
     * Returns a defensive copy of the wrapped object when possible.
     * If no copy strategy is available, returns the wrapped object itself.
     */
    T getVal() {
        log.trace("Getting immutable/defensive copy of wrapped object")
        return immutableCopySupplier != null ? immutableCopySupplier.get() : wrappedObject
    }

    /**
     * Cleanup method. If this Agent owns its executor, it will be shut down.
     */
    void shutdown() {
        if (ownsExecutor) {
            executor.shutdown()
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Internal: worker loop
    // ─────────────────────────────────────────────────────────────

    private void processQueue() {
        if (processing.compareAndSet(false, true)) {
            try {
                executor.execute(new Runnable() {
                    @Override
                    void run() {
                        try {
                            Task task
                            while ((task = taskQueue.poll()) != null) {
                                log.trace("Executing queued task")
                                try {
                                    task.execute(wrappedObject)
                                } catch (Throwable t) {
                                    log.error("Error executing agent action", t)
                                }
                            }
                        } finally {
                            processing.set(false)
                            if (!taskQueue.isEmpty()) {
                                processQueue()
                            }
                        }
                    }
                })
            } catch (Throwable t) {
                // Ensure we don't get stuck with processing=true
                processing.set(false)
                log.error("Failed to schedule agent worker on executor", t)
            }
        }
    }
}