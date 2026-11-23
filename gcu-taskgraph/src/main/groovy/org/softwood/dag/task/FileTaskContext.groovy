package org.softwood.dag.task

import groovy.util.logging.Slf4j
import org.softwood.promise.Promise

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * FileTaskContext - Extended context for file processing tasks.
 *
 * Provides file-specific capabilities:
 * - File list access
 * - Current file tracking
 * - Data emission to downstream tasks
 * - Collection for aggregation
 * - Statistics tracking (min, max, avg, sum)
 * - Progress tracking
 * - Error logging
 *
 * Wraps the base TaskContext to provide access to ExecutorPool, globals, etc.
 */
@Slf4j
class FileTaskContext {
    
    // =========================================================================
    // Base Context (delegation target)
    // =========================================================================
    
    /** Base task context (contains pool, globals, config) */
    TaskContext baseContext
    
    /** Task name for logging */
    String taskName
    
    // =========================================================================
    // File Tracking
    // =========================================================================
    
    /** All files to process */
    List<File> files = []
    
    /** Current file being processed (set by FileTask) */
    File currentFile
    
    /** Total files to process */
    private int totalFiles = 0
    
    /** Files processed so far */
    private AtomicInteger processedCount = new AtomicInteger(0)
    
    // =========================================================================
    // Data Emission & Collection
    // =========================================================================
    
    /** Results emitted to downstream tasks */
    private List emittedResults = [].asSynchronized()
    
    /** Items collected for aggregation */
    private List collectedItems = [].asSynchronized()
    
    // =========================================================================
    // Statistics Tracking
    // =========================================================================
    
    /** Statistics by metric name */
    private Map<String, StatsCollector> statsCollectors = new ConcurrentHashMap<>()
    
    // =========================================================================
    // Execution Summary
    // =========================================================================
    
    /** Execution summary (populated during execution) */
    ExecutionSummary summary = new ExecutionSummary()
    
    /** Error tracking */
    private List<FileError> errors = [].asSynchronized()
    
    // =========================================================================
    // Delegation to Base Context
    // =========================================================================
    
    /**
     * Get global variables map.
     */
    Map getGlobals() {
        baseContext.globals
    }
    
    /**
     * Get configuration map.
     */
    Map getConfig() {
        baseContext.config
    }
    
    /**
     * Get executor pool (for custom async operations).
     */
    def getPool() {
        baseContext.pool
    }
    
    // =========================================================================
    // File Access
    // =========================================================================
    
    void setTotalFiles(int total) {
        this.totalFiles = total
    }
    
    int filesTotal() {
        totalFiles
    }
    
    int filesProcessed() {
        processedCount.get()
    }
    
    void incrementFilesProcessed() {
        processedCount.incrementAndGet()
    }
    
    double progressPercent() {
        totalFiles > 0 ? (filesProcessed() / totalFiles * 100.0) : 0.0
    }
    
    // =========================================================================
    // Data Emission (to downstream tasks)
    // =========================================================================
    
    /**
     * Emit a single result to downstream tasks.
     */
    void emit(Object result) {
        emittedResults << result
    }
    
    /**
     * Emit multiple results to downstream tasks.
     */
    void emitAll(Collection results) {
        emittedResults.addAll(results)
    }
    
    /**
     * Get all emitted results.
     */
    List emitted() {
        return emittedResults.asImmutable()
    }
    
    // =========================================================================
    // Collection (for aggregation within task)
    // =========================================================================
    
    /**
     * Collect an item for aggregation.
     */
    void collect(Object item) {
        collectedItems << item
    }
    
    /**
     * Collect multiple items for aggregation.
     */
    void collectAll(Collection items) {
        collectedItems.addAll(items)
    }
    
    /**
     * Get all collected items.
     */
    List collected() {
        return collectedItems.asImmutable()
    }
    
    // =========================================================================
    // Statistics Tracking
    // =========================================================================
    
    /**
     * Track a metric value.
     * 
     * <pre>
     * ctx.track('fileSize', file.size())
     * ctx.track('recordCount', records.size())
     * </pre>
     */
    void track(String metricName, Number value) {
        statsCollectors.computeIfAbsent(metricName) { new StatsCollector(name: it) }
                      .add(value)
    }
    
    /**
     * Get statistics for a metric.
     * 
     * <pre>
     * def stats = ctx.stats('fileSize')
     * println "Average: ${stats.average}"
     * println "Total: ${stats.sum}"
     * </pre>
     */
    Stats stats(String metricName) {
        def collector = statsCollectors.get(metricName)
        return collector ? collector.getStats() : new Stats(name: metricName)
    }
    
    // =========================================================================
    // Error Handling
    // =========================================================================
    
    /**
     * Log an error for a file.
     */
    void logError(File file, Exception exception) {
        errors << new FileError(
            file: file,
            exception: exception,
            timestamp: new Date()
        )
        
        log.error("Error processing file ${file.name}: ${exception.message}")
    }
    
    /**
     * Get all logged errors.
     */
    List<FileError> getErrors() {
        return errors.asImmutable()
    }
    
    // =========================================================================
    // Global Variable Shortcuts
    // =========================================================================
    
    /**
     * Set a global variable.
     */
    void setGlobal(String key, Object value) {
        baseContext.globals[key] = value
    }
    
    /**
     * Get a global variable.
     */
    Object getGlobal(String key) {
        baseContext.globals[key]
    }
    
    // =========================================================================
    // Async Processing Support
    // =========================================================================
    
    /**
     * Process files asynchronously using virtual threads.
     * 
     * <pre>
     * def promises = ctx.processAsync { fileCtx ->
     *     // 'this' is the File (via delegation)
     *     def content = text
     *     fileCtx.emit(process(content))
     * }
     * 
     * Promise.all(promises).get()
     * </pre>
     */
    List<Promise> processAsync(Closure fileProcessor) {
        files.collect { file ->
            baseContext.promiseFactory.executeAsync {
                fileProcessor.delegate = file
                fileProcessor.resolveStrategy = Closure.DELEGATE_FIRST
                fileProcessor.call(this)
            }
        }
    }
    
    // =========================================================================
    // Progress Reporting
    // =========================================================================
    
    /**
     * Report progress (logs and updates summary).
     */
    void reportProgress(String message) {
        log.info("[${taskName}] ${message} (${filesProcessed()}/${filesTotal()})")
    }
}

/**
 * Statistics collector for tracking numeric metrics.
 */
class StatsCollector {
    String name
    private List<Number> values = [].asSynchronized()
    
    void add(Number value) {
        values << value
    }
    
    Stats getStats() {
        if (values.isEmpty()) {
            return new Stats(name: name)
        }
        
        def sorted = values.sort()
        return new Stats(
            name: name,
            count: values.size(),
            sum: values.sum() as Double,
            min: sorted.first() as Double,
            max: sorted.last() as Double,
            average: (values.sum() / values.size()) as Double,
            median: calculateMedian(sorted)
        )
    }
    
    private double calculateMedian(List<Number> sorted) {
        int size = sorted.size()
        if (size % 2 == 0) {
            return ((sorted[size / 2 - 1] + sorted[size / 2]) / 2.0) as Double
        } else {
            return sorted[size / 2] as Double
        }
    }
}
