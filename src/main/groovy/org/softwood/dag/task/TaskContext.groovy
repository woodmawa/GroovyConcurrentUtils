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
import org.softwood.dag.resilience.MemoCache

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
    
    // GCU 1.2.2 (M4): the three lazy services below are created at most once. The getters used to be an
    // unguarded check-then-create, so tasks starting together each got their own cache or queue.
    private final Object lazyLock = new Object()

    // Dead Letter Queue (optional - set by TaskGraph or manually)
    volatile DeadLetterQueue deadLetterQueue = null
    
    // Idempotency Cache (optional - set by TaskGraph or manually)
    volatile IdempotencyCache idempotencyCache = null

    // Memo Cache (optional - session-scoped performance cache, set on demand)
    volatile MemoCache memoCache = null

    /**
     * GCU 1.2.2 (M1): optional redaction hook, off by default (null).
     *
     * <p>When set, the library passes a task result through it before writing the result to a log line,
     * to graph persistence, and to {@link TaskEvent#getRedactedResult()}. It never changes what tasks
     * receive from their predecessors, and it never changes {@link TaskEvent#result}.</p>
     *
     * <p>The closure takes {@code (value)} or {@code (taskId, value)} and returns what may be shown.</p>
     */
    volatile Closure resultRedactor = null

    /** What {@link #redact} returns when the redactor itself fails: the value is hidden, not let through. */
    static final String REDACTED = '[REDACTED]'

    /**
     * GCU 1.2.2 (M1): the form of a task result that may be logged, persisted or shown.
     * With no redactor this is the value itself.
     */
    Object redact(String taskId, Object value) {
        Closure r = resultRedactor
        if (r == null) return value
        try {
            return r.maximumNumberOfParameters >= 2 ? r.call(taskId, value) : r.call(value)
        } catch (Throwable t) {
            log.warn("TaskContext.redact(): redactor failed for task '{}' ({}); value hidden", taskId, t.toString())
            return REDACTED
        }
    }
    
    /**
     * GCU 1.3.0 (D3): local services every HttpTask on this context may call, as {@code host:port}
     * entries (lower case). Empty by default: a local address is then refused unless the task itself
     * names it. Filled by {@code allowLocal 'localhost:8082'} in the graph DSL.
     */
    final Set<String> httpAllowLocal = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * GCU 1.3.0 (D9): the graph's working directory. A FileTask with no security configuration is
     * confined to it; null (the default) means the JVM's working directory.
     */
    volatile File workingDirectory = null

    /**
     * GCU 1.3.0 (D9): further directories every unconfigured FileTask on this context may reach.
     * Filled by {@code allowedDirectories ...} in the graph DSL.
     */
    final List<File> fileAllowedDirectories = new java.util.concurrent.CopyOnWriteArrayList<File>()

    /**
     * GCU 1.4.0 (D2, level 2): the capabilities this graph grants its restricted scripts, set with
     * {@code allow '...'} in the graph DSL. 'files' binds {@code files} (bounded by the FileTask
     * directory rules above); 'http' binds {@code http} (bounded by the HttpTask guard and
     * {@link #httpAllowLocal}). 1.4.1: 'sql' binds {@code sql} (the datasources below); 'process'
     * binds {@code process} and is what lets a ShellTask run at all. Empty by default: a restricted
     * script reaches nothing outside itself, and no shell runs.
     */
    final Set<String> scriptCapabilities = java.util.concurrent.ConcurrentHashMap.<String>newKeySet()

    /** The capability names {@code allow} accepts in this version. */
    static final Set<String> SCRIPT_CAPABILITIES = Collections.unmodifiableSet(new LinkedHashSet<String>(['files', 'http', 'sql', 'process']))

    /**
     * GCU 1.4.1 (D2): the interpreters this graph's shell runs may use (powershell, cmd, bash,
     * python), set with {@code interpreters ...}. Empty by default: {@code allow 'process'} alone
     * runs nothing.
     */
    final Set<String> processInterpreters = java.util.concurrent.ConcurrentHashMap.<String>newKeySet()

    /** GCU 1.4.1: an executable for an interpreter, set with {@code interpreterPath 'python', '...'}. */
    final Map<String, String> interpreterPaths = new java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * GCU 1.4.1: environment variables of this JVM that a shell run may see, beyond the short list an
     * interpreter needs to start. Set with {@code passEnvironment ...}.
     */
    final Set<String> passEnvironment = java.util.concurrent.ConcurrentHashMap.<String>newKeySet()

    /**
     * GCU 1.4.1: the runner for this graph's shell runs; null means a local child process. AW sets one
     * that hands the command to FS execute. GCU's checks run before any runner.
     */
    volatile org.softwood.dag.task.shell.ShellRunner shellRunner = null

    /**
     * GCU 1.4.1 (D2, allow 'sql'): the datasources this graph declares by name, for restricted scripts
     * to use through {@code sql}. Set with {@code datasource 'name', provider} in the graph DSL.
     */
    final Map<String, org.softwood.dag.task.sql.ScriptDatasource> datasources =
            new java.util.concurrent.ConcurrentHashMap<String, org.softwood.dag.task.sql.ScriptDatasource>()

    /**
     * GCU 1.4.1: told of every shell run and every {@code sql} call, with a map of what was done
     * ({@code kind} 'shell' or 'sql', the task, and for a shell the interpreter, working directory,
     * script hash and exit code; for sql the datasource, statement hash and rows). Null by default:
     * the record is then only logged, on the logger {@code org.softwood.dag.audit}.
     */
    volatile Closure auditListener = null

    private static final org.slf4j.Logger AUDIT_LOG = org.slf4j.LoggerFactory.getLogger('org.softwood.dag.audit')

    /**
     * GCU 1.4.2 (keepBackground): background processes a shell task left running on purpose, each
     * with the graph that was running when they were kept. Ended when that graph ends.
     */
    private final List<Map<String, Object>> keptProcesses = new java.util.concurrent.CopyOnWriteArrayList<Map<String, Object>>()

    /** GCU 1.4.2: keep a shell run's background processes until the graph running now ends. */
    void keepUntilGraphEnds(AutoCloseable keeper) {
        if (keeper == null) return
        keptProcesses.add([graph: graph, keeper: keeper] as Map<String, Object>)
    }

    /**
     * GCU 1.4.2: end the kept processes of the given graph, and of any graph that has already
     * ended (a sub-graph sharing this context may have been the one running when they were kept).
     * Null ends them all. Called by TaskGraph when a run ends, and by {@link #close()}.
     */
    void endKeptProcesses(Object endedGraph) {
        for (Map<String, Object> entry : keptProcesses) {
            def owner = entry.graph
            boolean ownerDone = owner == null || owner.is(endedGraph) || endedGraph == null || graphHasEnded(owner)
            if (!ownerDone) continue
            if (keptProcesses.remove(entry)) {
                try {
                    ((AutoCloseable) entry.keeper).close()
                } catch (Exception e) {
                    log.warn("TaskContext: could not stop kept background processes ({})", e.toString())
                }
            }
        }
    }

    private static boolean graphHasEnded(Object g) {
        try {
            def p = g.graphCompletionPromise
            return p != null && p.isDone()
        } catch (Exception ignored) {
            return false    // in doubt the graph is running: close() ends them at the latest
        }
    }

    /** GCU 1.4.1: log an audit record and pass it to the listener. A failing listener is logged, never thrown. */
    void audit(Map<String, Object> record) {
        AUDIT_LOG.info('{}', record)
        Closure listener = auditListener
        if (listener == null) return
        try {
            listener.call(record)
        } catch (Throwable t) {
            log.warn("TaskContext.audit(): the audit listener failed ({})", t.toString())
        }
    }

    // Resource monitoring (set by TaskGraph if configured)
    def resourceMonitor = null

    // Reference to the owning TaskGraph (set by TaskGraph when execution starts)
    def graph = null

    /**
     * 1.2.0 (H2): cancellation signal for everything running on this context.
     * Set by TaskGraph.abortFlow() and by a graph timeout. TaskBase checks it before each attempt and
     * after each retry wait, so a cancelled graph starts no new work. It does not interrupt work that
     * is already running. A context shared by several graphs (sub-graphs) shares the signal.
     */
    private final java.util.concurrent.atomic.AtomicBoolean cancelFlag = new java.util.concurrent.atomic.AtomicBoolean(false)

    void requestCancel() { cancelFlag.set(true) }

    boolean isCancelRequested() { cancelFlag.get() }

    /** Clear the signal so the context can run another graph (TaskGraph.reset()). */
    void clearCancel() { cancelFlag.set(false) }

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
     * GCU 1.2.2 (M3): pool, factory and config all supplied. Used by the builder, whose config(...)
     * was read and then dropped. A null or empty config loads the default.
     */
    TaskContext(ExecutorPool pool, PromiseFactory promiseFactory, Map config) {
        this.config = config ?: ConfigLoader.loadConfig()
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
            // GCU 1.2.2 (M3): the config given to the builder is the context's config. It used to be
            // read here and then dropped. Null or empty still loads the default, as TaskContext(Map) does.
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

            return new TaskContext(pool, promiseFactory, cfg)
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
        DeadLetterQueue q = this.@deadLetterQueue
        if (q == null) {
            synchronized (lazyLock) {
                q = this.@deadLetterQueue
                if (q == null) {
                    q = new DeadLetterQueue()
                    this.@deadLetterQueue = q
                }
            }
        }
        return q
    }
    
    /**
     * Get or create the Idempotency Cache for this context.
     */
    IdempotencyCache getIdempotencyCache() {
        IdempotencyCache c = this.@idempotencyCache
        if (c == null) {
            synchronized (lazyLock) {
                c = this.@idempotencyCache
                if (c == null) {
                    c = new IdempotencyCache()
                    this.@idempotencyCache = c
                }
            }
        }
        return c
    }

    /**
     * Get or create the Memo Cache for this context.
     */
    MemoCache getMemoCache() {
        MemoCache c = this.@memoCache
        if (c == null) {
            synchronized (lazyLock) {
                c = this.@memoCache
                if (c == null) {
                    c = new MemoCache()
                    this.@memoCache = c
                }
            }
        }
        return c
    }

    /**
     * FIX C4: Shut down the pool when the context is done.
     * Call this after graph completion to release threads.
     * Idempotent - safe to call multiple times.
     */
    @Override
    void close() {
        endKeptProcesses(null)
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
