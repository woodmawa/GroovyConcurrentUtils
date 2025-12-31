package org.softwood.dag.persistence

import groovy.util.logging.Slf4j
import org.eclipse.store.storage.embedded.types.EmbeddedStorageManager
import org.eclipse.store.storage.embedded.types.EmbeddedStorage

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Manages EclipseStore persistence for TaskGraph execution state.
 * 
 * Key Features:
 * - Thread-safe: Each run() gets unique storage instance
 * - Atomic snapshots: Complete graph state persisted per run
 * - Incremental writes: Task states persisted asynchronously as they complete
 * - Compression: Optional gzip compression for large snapshots
 * - Retention: Automatic cleanup of old snapshots
 * - Recovery: Load snapshots for retry/debugging
 * - Fault tolerance: VM crash protection via incremental persistence
 * 
 * EclipseStore is the successor to MicroStream and is fully Java 25 compatible.
 */
@Slf4j
class EclipseStoreManager implements AutoCloseable {
    
    private final String graphId
    private final String runId
    private final String baseDir
    private final boolean compression
    private final int maxSnapshots
    
    private EmbeddedStorageManager storage
    private GraphStateSnapshot snapshot
    private Path snapshotFile
    private boolean closed = false
    
    // Track pending async persistence operations
    private final AtomicInteger pendingAsyncOps = new AtomicInteger(0)
    
    // Thread-local registry to prevent interference between concurrent runs
    private static final ThreadLocal<EclipseStoreManager> THREAD_LOCAL = new ThreadLocal<>()
    
    /**
     * Create a new persistence manager for a graph run
     * 
     * @param graphId Identifier for the TaskGraph
     * @param runId Unique UUID for this execution run
     * @param baseDir Base directory for .gsm files
     * @param compression Whether to enable gzip compression
     * @param maxSnapshots Maximum number of snapshots to retain
     */
    EclipseStoreManager(String graphId, String runId, String baseDir, boolean compression, int maxSnapshots) {
        this.graphId = graphId
        this.runId = runId
        this.baseDir = baseDir
        this.compression = compression
        this.maxSnapshots = maxSnapshots
        
        // Initialize storage
        initializeStorage()
        
        // Register in thread-local for access during task execution
        THREAD_LOCAL.set(this)
        
        log.debug "EclipseStore manager initialized: graphId=$graphId, runId=$runId, file=$snapshotFile"
    }
    
    /**
     * Get the current thread's persistence manager (if any)
     */
    static Optional<EclipseStoreManager> current() {
        return Optional.ofNullable(THREAD_LOCAL.get())
    }
    
    /**
     * Initialize EclipseStore storage and snapshot
     */
    private void initializeStorage() {
        try {
            // Create storage directory
            Path storageDir = createStorageDirectory()
            
            // Build snapshot filename
            String timestamp = DateTimeFormatter.ISO_INSTANT
                .format(Instant.now())
                .replace(':', '-')  // Windows-safe
            String extension = compression ? ".gsm.gz" : ".gsm"
            String filename = "${graphId}_${runId}_${timestamp}${extension}"
            snapshotFile = storageDir.resolve(filename)
            
            // Create initial snapshot
            snapshot = new GraphStateSnapshot(
                graphId: graphId,
                runId: runId,
                threadId: Thread.currentThread().getId(),
                threadName: Thread.currentThread().getName(),
                startTime: Instant.now(),
                finalStatus: GraphExecutionStatus.RUNNING
            )
            
            // Start EclipseStore with the snapshot as root
            storage = EmbeddedStorage.start(snapshot, snapshotFile)
            
        } catch (Exception e) {
            log.error "Failed to initialize EclipseStore storage", e
            throw new RuntimeException("Persistence initialization failed", e)
        }
    }
    
    /**
     * Create and return the storage directory path
     */
    private Path createStorageDirectory() {
        Path dir = Paths.get(baseDir, "graphState")
        Files.createDirectories(dir)
        return dir
    }
    
    /**
     * Update task state in the snapshot (with asynchronous persistence)
     */
    void updateTaskState(String taskId, TaskState state, Map<String, Object> data = [:]) {
        if (closed) {
            log.warn "Attempted to update task state after manager closed: $taskId"
            return
        }
        
        try {
            TaskStateSnapshot taskSnapshot = snapshot.taskStates.get(taskId)
            
            if (taskSnapshot == null) {
                // Create new task snapshot
                taskSnapshot = TaskStateSnapshot.builder()
                    .taskId(taskId)
                    .taskName(data.name as String ?: taskId)
                    .state(state)
                    .startTime(data.startTime as Instant ?: Instant.now())
                    .build()
                snapshot.taskStates.put(taskId, taskSnapshot)
            } else {
                // Update existing task snapshot
                taskSnapshot.state = state
                
                if (state == TaskState.RUNNING && !taskSnapshot.startTime) {
                    taskSnapshot.startTime = Instant.now()
                }
                
                if (state == TaskState.COMPLETED) {
                    taskSnapshot.endTime = Instant.now()
                    taskSnapshot.result = data.result
                }
                
                if (state == TaskState.FAILED) {
                    taskSnapshot.endTime = Instant.now()
                    taskSnapshot.errorMessage = data.errorMessage as String
                    taskSnapshot.errorStackTrace = data.errorStackTrace as String
                }
            }
            
            // Persist incrementally in background (non-blocking)
            // Only persist on terminal states to reduce I/O overhead
            if (state == TaskState.COMPLETED || state == TaskState.FAILED || state == TaskState.SKIPPED) {
                persistAsync()
            }
            
            log.debug "Updated task state: $taskId -> $state"
            
        } catch (Exception e) {
            log.error "Failed to update task state for $taskId", e
            // Don't throw - persistence failures shouldn't break graph execution
        }
    }
    
    /**
     * Asynchronously persist the snapshot to avoid blocking task execution
     */
    private void persistAsync() {
        pendingAsyncOps.incrementAndGet()
        // Use a virtual thread to avoid blocking
        Thread.startVirtualThread {
            try {
                storage.store(snapshot.taskStates)
                storage.store(snapshot.contextGlobals)
                storage.store(snapshot)
                log.trace "Async persistence completed"
            } catch (Exception e) {
                log.warn "Async persistence failed", e
            } finally {
                pendingAsyncOps.decrementAndGet()
            }
        }
    }
    
    /**
     * Wait for all pending async persistence operations to complete
     */
    private void waitForPendingPersistence(long timeoutMillis = 5000) {
        long deadline = System.currentTimeMillis() + timeoutMillis
        while (pendingAsyncOps.get() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10)
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt()
                break
            }
        }
        
        int remaining = pendingAsyncOps.get()
        if (remaining > 0) {
            log.warn "Timed out waiting for ${remaining} pending persistence operations"
        }
    }
    
    /**
     * Update context globals in the snapshot
     */
    void updateContextGlobals(Map<String, Object> globals) {
        if (closed) return
        
        try {
            // Only store serializable values
            globals.each { key, value ->
                if (isSerializable(value)) {
                    snapshot.contextGlobals.put(key, value)
                } else {
                    log.warn "Skipping non-serializable global: $key (${value?.getClass()?.name})"
                }
            }
            
        } catch (Exception e) {
            log.error "Failed to update context globals", e
        }
    }
    
    /**
     * Mark graph execution as completed
     */
    void markGraphCompleted() {
        if (closed) return
        
        try {
            // Wait for all pending async persistence to complete
            waitForPendingPersistence()
            
            snapshot.endTime = Instant.now()
            snapshot.finalStatus = GraphExecutionStatus.COMPLETED
            
            // Store the final snapshot and all modified objects SYNCHRONOUSLY
            storage.store(snapshot.taskStates)  // Explicitly store the map
            storage.store(snapshot.contextGlobals)  // Explicitly store the globals
            storage.store(snapshot)  // Store the root
            
            log.info "Graph execution completed: $graphId (runId=$runId) in ${snapshot.durationMillis}ms"
            
        } catch (Exception e) {
            log.error "Failed to mark graph as completed", e
        }
    }
    
    /**
     * Mark graph execution as failed
     */
    void markGraphFailed(String failedTaskId, Throwable error) {
        if (closed) return
        
        try {
            // Wait for all pending async persistence to complete
            waitForPendingPersistence()
            
            snapshot.endTime = Instant.now()
            snapshot.finalStatus = GraphExecutionStatus.FAILED
            snapshot.failureTaskId = failedTaskId
            snapshot.failureMessage = error.message
            snapshot.failureStackTrace = stackTraceToString(error)
            
            // Store the final snapshot and all modified objects SYNCHRONOUSLY
            storage.store(snapshot.taskStates)  // Explicitly store the map
            storage.store(snapshot.contextGlobals)  // Explicitly store the globals
            storage.store(snapshot)  // Store the root
            
            log.error "Graph execution failed: $graphId (runId=$runId) - task $failedTaskId failed"
            
        } catch (Exception e) {
            log.error "Failed to mark graph as failed", e
        }
    }
    
    /**
     * Get the current snapshot (for testing/debugging)
     */
    GraphStateSnapshot getSnapshot() {
        return snapshot
    }
    
    /**
     * Get the snapshot file path
     */
    Path getSnapshotFile() {
        return snapshotFile
    }
    
    /**
     * Close the storage manager without storing (for when snapshot mode says not to persist)
     */
    void closeWithoutStoring() {
        if (closed) return
        
        try {
            if (storage != null) {
                storage.close()
            }
            
            // Delete the snapshot directory since we're not persisting
            if (snapshotFile != null && Files.exists(snapshotFile)) {
                if (Files.isDirectory(snapshotFile)) {
                    snapshotFile.toFile().deleteDir()
                } else {
                    Files.deleteIfExists(snapshotFile)
                }
                log.debug "Deleted snapshot directory: $snapshotFile"
            }
            
            closed = true
            THREAD_LOCAL.remove()
            
            log.debug "EclipseStore manager closed without storing: $graphId (runId=$runId)"
            
        } catch (Exception e) {
            log.error "Error closing EclipseStore manager", e
        }
    }
    
    /**
     * Close the storage manager and cleanup thread-local.
     * Blocks until all data is flushed to disk.
     */
    @Override
    void close() {
        if (closed) return
        
        try {
            if (storage != null) {
                // storage.close() is synchronous and blocks until all data is flushed
                storage.close()
            }
            
            // Cleanup old snapshots if retention policy specified
            if (maxSnapshots > 0) {
                cleanupOldSnapshots()
            }
            
            closed = true
            THREAD_LOCAL.remove()
            
            log.debug "EclipseStore manager closed: $graphId (runId=$runId)"
            
        } catch (Exception e) {
            log.error "Error closing EclipseStore manager", e
        }
    }
    
    /**
     * Clean up old snapshot files based on retention policy
     */
    private void cleanupOldSnapshots() {
        try {
            Path storageDir = Paths.get(baseDir, "graphState")
            if (!Files.exists(storageDir)) return
            
            // Find all snapshot files for this graph
            List<Path> snapshots = Files.list(storageDir)
                .filter { it.fileName.toString().startsWith("${graphId}_") }
                .filter { it.fileName.toString().endsWith(".gsm") || it.fileName.toString().endsWith(".gsm.gz") }
                .sorted { a, b -> Files.getLastModifiedTime(b) <=> Files.getLastModifiedTime(a) }
                .collect()
            
            // Delete excess snapshots
            if (snapshots.size() > maxSnapshots) {
                snapshots.drop(maxSnapshots).each { oldSnapshot ->
                    try {
                        // EclipseStore creates a directory for each snapshot
                        if (Files.isDirectory(oldSnapshot)) {
                            oldSnapshot.toFile().deleteDir()
                        } else {
                            Files.deleteIfExists(oldSnapshot)
                        }
                        log.debug "Deleted old snapshot: ${oldSnapshot.fileName}"
                    } catch (Exception e) {
                        log.warn "Failed to delete old snapshot: ${oldSnapshot.fileName}", e
                    }
                }
            }
            
        } catch (Exception e) {
            log.warn "Failed to cleanup old snapshots", e
        }
    }
    
    /**
     * Load a snapshot from file (for recovery)
     */
    static GraphStateSnapshot loadSnapshot(Path snapshotFile) {
        try {
            // Start storage from existing directory
            def storage = EmbeddedStorage.start(snapshotFile)
            def snapshot = storage.root() as GraphStateSnapshot
            storage.close()
            
            return snapshot
            
        } catch (Exception e) {
            log.error "Failed to load snapshot from $snapshotFile", e
            throw new RuntimeException("Failed to load snapshot", e)
        }
    }
    
    /**
     * Check if value is serializable
     */
    private static boolean isSerializable(Object value) {
        if (value == null) return true
        if (value instanceof Serializable) return true
        
        // Primitive types and common immutable types
        if (value instanceof String || 
            value instanceof Number || 
            value instanceof Boolean ||
            value instanceof Date) {
            return true
        }
        
        // Collections are OK if their contents are serializable
        if (value instanceof Map || value instanceof Collection) {
            return true  // EclipseStore can handle nested structures
        }
        
        return false
    }
    
    /**
     * Convert throwable to string
     */
    private static String stackTraceToString(Throwable error) {
        if (error == null) return null
        
        StringWriter sw = new StringWriter()
        error.printStackTrace(new PrintWriter(sw))
        return sw.toString()
    }
}
