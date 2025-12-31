package org.softwood.dag.task

/**
 * Security configuration for FileTask operations.
 * 
 * Controls file system access boundaries, resource limits, and security policies
 * to prevent path traversal, resource exhaustion, and information leakage.
 * 
 * <h3>Usage Example:</h3>
 * <pre>
 * def securityConfig = FileTaskSecurityConfig.builder()
 *     .strictMode(true)
 *     .allowedDirectories([new File("/var/app/data"), new File("/tmp/uploads")])
 *     .followSymlinks(false)
 *     .maxFiles(10000)
 *     .maxFileSizeBytes(100 * 1024 * 1024)  // 100MB
 *     .verboseErrors(false)
 *     .build()
 * 
 * fileTask {
 *     name 'secure-processing'
 *     securityConfig securityConfig
 *     sources { directory('/var/app/data') { pattern '*.log' } }
 * }
 * </pre>
 */
class FileTaskSecurityConfig {
    
    // =========================================================================
    // Path Security
    // =========================================================================
    
    /**
     * When true, strictly enforces allowedDirectories whitelist.
     * Any file access outside these directories throws SecurityException.
     * 
     * Default: true (secure by default)
     */
    boolean strictMode = true
    
    /**
     * Whitelist of directories that FileTask is allowed to access.
     * When strictMode=true, all file operations are validated against this list.
     * 
     * Default: empty list (requires explicit configuration in strict mode)
     */
    List<File> allowedDirectories = []
    
    /**
     * When true, follows symbolic links during file discovery.
     * When false, symlinks are skipped for security.
     * 
     * Default: false (secure by default)
     */
    boolean followSymlinks = false
    
    /**
     * When true AND followSymlinks=true, validates that symlink targets
     * are within allowedDirectories.
     * 
     * Default: true
     */
    boolean validateSymlinkTargets = true
    
    // =========================================================================
    // Resource Limits
    // =========================================================================
    
    /**
     * Maximum number of files that can be discovered/processed.
     * Prevents memory exhaustion from large directory trees.
     * 
     * Default: 10,000 files
     */
    int maxFiles = 10_000
    
    /**
     * Maximum size in bytes for any single file to be processed.
     * Files exceeding this are skipped with a warning.
     * 
     * Default: 100MB
     */
    long maxFileSizeBytes = 100 * 1024 * 1024
    
    /**
     * Maximum total size in bytes for all files combined.
     * Prevents processing excessively large datasets.
     * 
     * Default: 1GB
     */
    long maxTotalSizeBytes = 1024 * 1024 * 1024
    
    /**
     * When true, enforces all resource limits (maxFiles, maxFileSize, maxTotalSize).
     * When false, limits are logged as warnings but not enforced.
     * 
     * Default: true
     */
    boolean enforceLimits = true
    
    /**
     * Maximum depth for recursive directory traversal.
     * Prevents infinite loops from circular symlinks or deep hierarchies.
     * 
     * Default: 50 levels
     */
    int maxRecursionDepth = 50
    
    // =========================================================================
    // Error Handling
    // =========================================================================
    
    /**
     * When true, error messages include full file paths.
     * When false, error messages are sanitized to prevent information leakage.
     * 
     * Default: false (secure by default)
     */
    boolean verboseErrors = false
    
    /**
     * When true, logs security violations (path traversal attempts, limit violations).
     * When false, security events are silently rejected.
     * 
     * Default: true
     */
    boolean logSecurityEvents = true
    
    // =========================================================================
    // Validation
    // =========================================================================
    
    /**
     * Validates configuration for security issues.
     * 
     * @throws IllegalStateException if configuration is invalid
     */
    void validate() {
        if (strictMode && allowedDirectories.isEmpty()) {
            throw new IllegalStateException(
                "strictMode=true requires at least one allowedDirectory"
            )
        }
        
        if (maxFiles < 1) {
            throw new IllegalArgumentException("maxFiles must be >= 1")
        }
        
        if (maxFileSizeBytes < 1) {
            throw new IllegalArgumentException("maxFileSizeBytes must be >= 1")
        }
        
        if (maxTotalSizeBytes < maxFileSizeBytes) {
            throw new IllegalArgumentException(
                "maxTotalSizeBytes (${maxTotalSizeBytes}) must be >= maxFileSizeBytes (${maxFileSizeBytes})"
            )
        }
        
        if (maxRecursionDepth < 1) {
            throw new IllegalArgumentException("maxRecursionDepth must be >= 1")
        }
        
        // Validate allowed directories exist and are readable
        allowedDirectories.each { dir ->
            if (!dir.exists()) {
                throw new IllegalStateException(
                    "allowedDirectory does not exist: ${dir.absolutePath}"
                )
            }
            if (!dir.isDirectory()) {
                throw new IllegalStateException(
                    "allowedDirectory is not a directory: ${dir.absolutePath}"
                )
            }
            if (!dir.canRead()) {
                throw new IllegalStateException(
                    "allowedDirectory is not readable: ${dir.absolutePath}"
                )
            }
        }
    }
    
    // =========================================================================
    // Builder Pattern
    // =========================================================================
    
    static Builder builder() {
        new Builder()
    }
    
    static class Builder {
        private FileTaskSecurityConfig instance = new FileTaskSecurityConfig()
        
        Builder strictMode(boolean val) { instance.strictMode = val; this }
        Builder allowedDirectories(List<File> val) { instance.allowedDirectories = val; this }
        Builder followSymlinks(boolean val) { instance.followSymlinks = val; this }
        Builder validateSymlinkTargets(boolean val) { instance.validateSymlinkTargets = val; this }
        Builder maxFiles(int val) { instance.maxFiles = val; this }
        Builder maxFileSizeBytes(long val) { instance.maxFileSizeBytes = val; this }
        Builder maxTotalSizeBytes(long val) { instance.maxTotalSizeBytes = val; this }
        Builder enforceLimits(boolean val) { instance.enforceLimits = val; this }
        Builder maxRecursionDepth(int val) { instance.maxRecursionDepth = val; this }
        Builder verboseErrors(boolean val) { instance.verboseErrors = val; this }
        Builder logSecurityEvents(boolean val) { instance.logSecurityEvents = val; this }
        
        FileTaskSecurityConfig build() { instance }
    }
    
    // =========================================================================
    // Factory Methods
    // =========================================================================
    
    /**
     * Creates a permissive configuration suitable for development/testing.
     * NOT RECOMMENDED FOR PRODUCTION.
     */
    static FileTaskSecurityConfig permissive() {
        return builder()
            .strictMode(false)
            .followSymlinks(true)
            .validateSymlinkTargets(false)
            .maxFiles(Integer.MAX_VALUE)
            .maxFileSizeBytes(Long.MAX_VALUE)
            .maxTotalSizeBytes(Long.MAX_VALUE)
            .enforceLimits(false)
            .verboseErrors(true)
            .logSecurityEvents(false)
            .build()
    }
    
    /**
     * Creates a strict configuration suitable for production.
     * Requires explicit allowedDirectories.
     */
    static FileTaskSecurityConfig strict(List<File> allowedDirectories) {
        return builder()
            .strictMode(true)
            .allowedDirectories(allowedDirectories)
            .followSymlinks(false)
            .validateSymlinkTargets(true)
            .maxFiles(10_000)
            .maxFileSizeBytes(100 * 1024 * 1024)
            .maxTotalSizeBytes(1024 * 1024 * 1024)
            .enforceLimits(true)
            .maxRecursionDepth(50)
            .verboseErrors(false)
            .logSecurityEvents(true)
            .build()
    }
}
