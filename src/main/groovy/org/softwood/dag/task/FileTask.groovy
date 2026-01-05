package org.softwood.dag.task

import groovy.util.logging.Slf4j
import org.softwood.promise.Promise

/**
 * FileTask - Process Files with Rich DSL Support
 *
 * Provides specialized task type for file processing operations with:
 * - File discovery from directories with patterns
 * - Promise-based file list injection from upstream tasks
 * - GDK method delegation for natural file operations
 * - Pipeline visibility via tap() points
 * - Parallel processing using virtual threads
 * - Rich execution summaries
 *
 * <h3>Key Features:</h3>
 * <ul>
 *   <li>Multiple file sources (directory discovery, explicit files, upstream promises)</li>
 *   <li>File-level filtering before processing</li>
 *   <li>Closure delegation to File for GDK method access</li>
 *   <li>tap() points for pipeline visibility</li>
 *   <li>Built-in statistics tracking</li>
 *   <li>Automatic parallel processing with virtual threads</li>
 *   <li>Data emission to downstream tasks</li>
 *   <li>Collection and aggregation within task</li>
 * </ul>
 *
 * <h3>DSL Example - Simple File Processing:</h3>
 * <pre>
 * graph.fileTask {
 *     name 'ProcessLogs'
 *     
 *     sources {
 *         directory('/var/logs') {
 *             pattern '*.log'
 *             recursive true
 *         }
 *     }
 *     
 *     filter { file ->
 *         file.size() > 1.KB && file.canRead()
 *     }
 *     
 *     tap { files, ctx ->
 *         println "Processing ${files.size()} log files"
 *     }
 *     
 *     eachFile { ctx ->
 *         // delegate is File - GDK methods available!
 *         def errorCount = 0
 *         eachLine { line ->
 *             if (line.contains('ERROR')) {
 *                 errorCount++
 *             }
 *         }
 *         
 *         ctx.emit([
 *             file: name,
 *             size: size(),
 *             errors: errorCount
 *         ])
 *     }
 *     
 *     aggregate { ctx ->
 *         [
 *             filesProcessed: ctx.filesProcessed(),
 *             totalErrors: ctx.emitted().sum { it.errors }
 *         ]
 *     }
 * }
 * </pre>
 *
 * <h3>DSL Example - Chaining from Previous Task:</h3>
 * <pre>
 * graph.scriptTask {
 *     name 'FindFiles'
 *     execute { ctx ->
 *         new File('/data').listFiles().findAll { it.name.endsWith('.csv') }
 *     }
 * }
 * 
 * graph.fileTask {
 *     name 'ProcessCSVs'
 *     dependsOn 'FindFiles'  // Gets List<File> from previous promise
 *     
 *     eachFile { ctx ->
 *         def records = readLines().drop(1)  // Skip header via GDK
 *         ctx.emit([file: name, records: records.size()])
 *     }
 * }
 * </pre>
 *
 * <h3>DSL Example - Advanced with Statistics:</h3>
 * <pre>
 * graph.fileTask {
 *     name 'AnalyzeDataset'
 *     
 *     sources { directory('/data/csvs') { pattern '*.csv' } }
 *     
 *     eachFile { ctx ->
 *         def records = readLines().size()
 *         def bytes = size()
 *         
 *         ctx.track('recordCount', records)
 *         ctx.track('fileSize', bytes)
 *         ctx.emit([file: name, records: records])
 *     }
 *     
 *     tap { results, ctx ->
 *         println "Progress: ${ctx.filesProcessed()}/${ctx.filesTotal()}"
 *     }
 *     
 *     aggregate { ctx ->
 *         [
 *             totalFiles: ctx.filesProcessed(),
 *             totalRecords: ctx.stats('recordCount').sum,
 *             avgRecords: ctx.stats('recordCount').average,
 *             totalSize: ctx.stats('fileSize').sum
 *         ]
 *     }
 * }
 * </pre>
 */
@Slf4j
class FileTask extends TaskBase<Object> {

    // =========================================================================
    // Configuration
    // =========================================================================
    
    /** File sources (directories with patterns, explicit files) */
    List<FileSourceSpec> fileSources = []
    
    /** Explicit file list */
    List<File> explicitFiles = []
    
    /** Deferred file list builder (with resolver) */
    private Closure deferredFilesBuilder
    
    /** File filter closure */
    Closure fileFilter
    
    /** Per-file processing closure (delegate = File) */
    Closure eachFileClosure
    
    /** Aggregation closure (after all files processed) */
    Closure aggregationClosure
    
    /** Custom execution closure (full control) */
    Closure customExecute
    
    /** Tap points for pipeline visibility */
    List<TapPoint> tapPoints = []
    
    /** Security configuration (optional) */
    FileTaskSecurityConfig securityConfig
    
    /** Security validator (lazy-initialized) */
    private FileTaskSecurityValidator validator
    
    // =========================================================================
    // Constructor
    // =========================================================================
    
    FileTask(String id, String name, TaskContext ctx) {
        super(id, name, ctx)
    }
    
    // =========================================================================
    // Security Configuration DSL
    // =========================================================================
    
    /**
     * Configure security settings for file operations.
     * 
     * <pre>
     * fileTask {
     *     securityConfig FileTaskSecurityConfig.strict([new File('/var/app/data')])
     *     // or
     *     securityConfig {
     *         strictMode true
     *         allowedDirectories = [new File('/var/logs')]
     *         maxFiles 5000
     *     }
     * }
     * </pre>
     */
    void securityConfig(FileTaskSecurityConfig config) {
        this.securityConfig = config
        this.validator = null  // Reset validator to be re-initialized
    }
    
    /**
     * Configure security using DSL.
     */
    void securityConfig(@DelegatesTo(strategy = Closure.DELEGATE_FIRST) Closure config) {
        def builder = FileTaskSecurityConfig.builder()
        config.delegate = builder
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.call()
        this.securityConfig = builder.build()
        this.validator = null  // Reset validator
    }
    
    /**
     * Get or create security validator.
     */
    private FileTaskSecurityValidator getValidator() {
        if (validator == null) {
            // Create default permissive config if none specified
            if (securityConfig == null) {
                log.debug("No security config specified, using permissive defaults")
                securityConfig = FileTaskSecurityConfig.permissive()
            }
            validator = new FileTaskSecurityValidator(securityConfig)
        }
        return validator
    }

    // =========================================================================
    // DSL Methods - File Sources
    // =========================================================================
    
    /**
     * Define file sources using DSL.
     * 
     * <h3>Static paths:</h3>
     * <pre>
     * sources {
     *     directory('/var/logs') {
     *         pattern '*.log'
     *         recursive true
     *     }
     * }
     * </pre>
     * 
     * <h3>Dynamic paths (with resolver):</h3>
     * <pre>
     * sources { r ->
     *     directory(r.global('log.directory', '/var/logs')) {
     *         pattern r.global('log.pattern', '*.log')
     *         recursive true
     *     }
     * }
     * </pre>
     */
    void sources(@DelegatesTo(FileSourcesBuilder) Closure spec) {
        // Note: FileSourcesBuilder doesn't need resolver - directory() accepts path strings
        // So we just execute normally
        def builder = new FileSourcesBuilder()
        spec.delegate = builder
        spec.resolveStrategy = Closure.DELEGATE_FIRST
        spec.call()
        
        fileSources.addAll(builder.sources)
    }
    
    /**
     * Explicitly specify files to process.
     * 
     * <h3>Static file list:</h3>
     * <pre>
     * files([new File('/data/file1.txt'), new File('/data/file2.txt')])
     * </pre>
     * 
     * <h3>Dynamic file list (with resolver):</h3>
     * <pre>
     * files { r ->
     *     def dir = new File(r.global('data.directory'))
     *     dir.listFiles().findAll { it.name.contains(r.prev.filter) }
     * }
     * </pre>
     */
    void files(Object value) {
        if (value instanceof List) {
            explicitFiles.addAll(value as List<File>)
        } else if (value instanceof Closure) {
            deferredFilesBuilder = value as Closure
        }
    }
    
    // =========================================================================
    // DSL Methods - Processing
    // =========================================================================
    
    /**
     * Filter files before processing.
     * 
     * <h3>Simple filter:</h3>
     * <pre>
     * filter { file -> file.size() > 1024 }
     * </pre>
     * 
     * <h3>Filter with resolver (for dynamic criteria):</h3>
     * <pre>
     * filter { r, file ->
     *     file.size() > r.global('min.file.size', 1024) &&
     *     file.name.contains(r.prev.searchTerm)
     * }
     * </pre>
     */
    void filter(Closure filterClosure) {
        this.fileFilter = filterClosure
    }
    
    /**
     * Process each file individually.
     * Closure delegate is set to the File being processed.
     */
    void eachFile(@DelegatesTo(File) Closure processClosure) {
        this.eachFileClosure = processClosure
    }
    
    /**
     * Aggregate results after all files processed.
     */
    void aggregate(Closure aggClosure) {
        this.aggregationClosure = aggClosure
    }
    
    /**
     * Custom execution logic (full control).
     */
    void execute(Closure execClosure) {
        this.customExecute = execClosure
    }
    
    /**
     * Add a tap point for visibility.
     * 
     * <pre>
     * tap { data, ctx ->
     *     println "Current state: ${data}"
     * }
     * </pre>
     */
    void tap(Closure tapClosure) {
        tapPoints << new TapPoint(closure: tapClosure)
    }
    
    // =========================================================================
    // Execution Implementation
    // =========================================================================
    
    @Override
    protected Promise<Object> runTask(TaskContext taskCtx, Object prevResult) {
        log.debug("FileTask[${name}] starting execution")
        
        return taskCtx.promiseFactory.executeAsync {
            // Create file-specific context
            FileTaskContext fileCtx = new FileTaskContext(
                baseContext: taskCtx,
                taskName: name
            )
            
            long startTime = System.currentTimeMillis()
            
            try {
                // 1. Discover files
                List<File> files = discoverFiles(prevResult, fileCtx, taskCtx)
                executeTaps('discovery', files, fileCtx)
                
                // 2. Filter files
                if (fileFilter) {
                    files = files.findAll { file ->
                        try {
                            // Check if filter expects resolver parameter
                            if (fileFilter.maximumNumberOfParameters > 1) {
                                def resolver = createResolver(prevResult, taskCtx)
                                return fileFilter.call(resolver, file)
                            } else {
                                return fileFilter.call(file)
                            }
                        } catch (Exception e) {
                            log.warn("Filter failed for ${file}: ${e.message}")
                            false
                        }
                    }
                    executeTaps('filter', files, fileCtx)
                }
                
                fileCtx.setTotalFiles(files.size())
                log.debug("FileTask[${name}] processing ${files.size()} files")
                
                // 3. Execute processing logic
                Object result = null
                
                if (customExecute) {
                    // Custom execution
                    fileCtx.files = files
                    result = customExecute.call(fileCtx)
                    
                } else if (eachFileClosure) {
                    // Standard per-file processing
                    result = processFiles(files, fileCtx)
                    
                } else {
                    // No processing closure - just return files
                    result = files
                }
                
                // 4. Aggregation
                if (aggregationClosure && !customExecute) {
                    result = aggregationClosure.call(fileCtx)
                    executeTaps('aggregate', result, fileCtx)
                }
                
                // 5. Build execution summary
                long duration = System.currentTimeMillis() - startTime
                fileCtx.summary.totalDuration = duration
                fileCtx.summary.filesProcessed = fileCtx.filesProcessed()
                fileCtx.summary.filesSkipped = files.size() - fileCtx.filesProcessed()
                
                log.debug("FileTask[${name}] completed: ${fileCtx.filesProcessed()} files in ${duration}ms")
                
                // Return result wrapped with summary
                return new FileTaskResult(
                    data: result,
                    summary: fileCtx.summary
                )
                
            } catch (Exception e) {
                log.error("FileTask[${name}] failed", e)
                fileCtx.summary.errorsEncountered++
                throw e
            }
        }
    }
    
    /**
     * Discover files from all configured sources.
     * Applies security validation to all discovered files.
     */
    private List<File> discoverFiles(Object prevResult, FileTaskContext fileCtx, TaskContext taskCtx) {
        List<File> allFiles = []
        
        // 1. From previous task's promise (if List<File>)
        if (prevResult instanceof List && prevResult.every { it instanceof File }) {
            log.debug("FileTask[${name}] using ${prevResult.size()} files from previous task")
            allFiles.addAll(prevResult as List<File>)
        }
        
        // 2. From explicit files
        if (explicitFiles) {
            log.debug("FileTask[${name}] adding ${explicitFiles.size()} explicit files")
            allFiles.addAll(explicitFiles)
        }
        
        // 3. From deferred file builder (with resolver)
        if (deferredFilesBuilder) {
            def files = executeWithResolver(deferredFilesBuilder, prevResult, taskCtx)
            if (files instanceof List) {
                log.debug("FileTask[${name}] adding ${files.size()} files from builder")
                allFiles.addAll(files as List<File>)
            }
        }
        
        // 4. From directory sources (security validation happens in FileSourceSpec)
        fileSources.each { source ->
            // Pass validator to source for integrated security
            source.validator = getValidator()
            def discovered = source.discover()
            log.debug("FileTask[${name}] discovered ${discovered.size()} files from ${source.directory}")
            allFiles.addAll(discovered)
        }
        
        // Remove duplicates
        allFiles = allFiles.unique { it.canonicalPath }
        
        // 4. Apply security validation
        log.debug("FileTask[${name}] validating ${allFiles.size()} files")
        allFiles = getValidator().validateFiles(allFiles)
        
        // 5. Filter by size
        allFiles = allFiles.findAll { file ->
            getValidator().validateFileSize(file)
        }
        
        // 6. Validate file count
        getValidator().validateFileCount(allFiles.size())
        
        // 7. Validate total size
        getValidator().validateTotalSize(allFiles)
        
        log.debug("FileTask[${name}] ${allFiles.size()} files passed security validation")
        
        return allFiles
    }
    
    /**
     * Process all files using eachFile closure.
     */
    private Object processFiles(List<File> files, FileTaskContext ctx) {
        List<Promise> filePromises = []
        
        files.each { file ->
            // Process each file in its own virtual thread
            Promise filePromise = ctx.baseContext.promiseFactory.executeAsync {
                try {
                    ctx.currentFile = file
                    
                    // Set closure delegate to File for GDK access
                    eachFileClosure.delegate = file
                    eachFileClosure.resolveStrategy = Closure.DELEGATE_FIRST
                    
                    // Call closure with context
                    def result = eachFileClosure.call(ctx)
                    
                    ctx.incrementFilesProcessed()
                    executeTaps('file', file, ctx)
                    
                    return result
                    
                } catch (Exception e) {
                    log.error("Error processing file ${file}: ${e.message}", e)
                    ctx.summary.errorsEncountered++
                    ctx.logError(file, e)
                    return null
                }
            }
            
            filePromises << filePromise
        }
        
        // Wait for all files to complete
        Promise.all(filePromises).get()
        
        // Return emitted results
        return ctx.emitted()
    }
    
    /**
     * Execute tap points for visibility.
     */
    private void executeTaps(String phase, Object data, FileTaskContext ctx) {
        tapPoints.each { tap ->
            try {
                tap.closure.call(data, ctx)
            } catch (Exception e) {
                log.warn("Tap failed at phase '${phase}': ${e.message}")
            }
        }
    }
    
    // =========================================================================
    // Helper Classes
    // =========================================================================
    
    /**
     * Builder for file sources DSL.
     */
    static class FileSourcesBuilder {
        List<FileSourceSpec> sources = []
        
        void directory(String path, @DelegatesTo(FileSourceSpec) Closure config = null) {
            def spec = new FileSourceSpec(directory: new File(path))
            if (config) {
                config.delegate = spec
                config.resolveStrategy = Closure.DELEGATE_FIRST
                config.call()
            }
            sources << spec
        }
    }
    
    /**
     * Tap point holder.
     */
    static class TapPoint {
        Closure closure
    }
}
