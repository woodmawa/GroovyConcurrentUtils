package org.softwood.dag.task

import groovy.util.logging.Slf4j

import java.nio.file.Files
import java.nio.file.Path

/**
 * Security validator for FileTask operations.
 * 
 * Enforces security policies including path traversal prevention,
 * symlink validation, and resource limit enforcement.
 */
@Slf4j
class FileTaskSecurityValidator {
    
    private final FileTaskSecurityConfig config
    
    FileTaskSecurityValidator(FileTaskSecurityConfig config) {
        this.config = config
        config.validate()  // Validate config on construction
    }
    
    // =========================================================================
    // Path Validation
    // =========================================================================
    
    /**
     * Validates that a file is within allowed directories.
     * 
     * @param file File to validate
     * @throws SecurityException if file is outside allowed directories in strict mode
     * @return true if file is valid, false if should be skipped
     */
    boolean validateFilePath(File file) {
        if (!config.strictMode) {
            return true  // No validation in permissive mode
        }
        
        if (config.allowedDirectories.isEmpty()) {
            // This should never happen due to config.validate()
            logSecurityEvent(
                'CONFIGURATION_ERROR',
                'strictMode enabled but no allowedDirectories configured'
            )
            return false
        }
        
        try {
            def canonical = file.canonicalPath
            
            def allowed = config.allowedDirectories.any { allowedDir ->
                def allowedCanonical = allowedDir.canonicalPath
                
                // File must be within or equal to allowed directory
                canonical == allowedCanonical || 
                canonical.startsWith(allowedCanonical + File.separator)
            }
            
            if (!allowed) {
                logSecurityEvent(
                    'PATH_TRAVERSAL_ATTEMPT',
                    "Rejected file outside allowed directories: ${sanitizePath(file)}",
                    [requestedPath: canonical, allowedDirs: config.allowedDirectories*.canonicalPath]
                )
                
                throw new SecurityException(
                    "Access denied: file is outside allowed directories. " +
                    "Path: ${sanitizePath(file)}"
                )
            }
            
            return true
            
        } catch (IOException e) {
            log.error("Error validating file path: ${sanitizePath(file)}", e)
            return false
        }
    }
    
    /**
     * Validates a list of files, filtering out invalid ones.
     * 
     * @param files Files to validate
     * @return List of valid files
     */
    List<File> validateFiles(List<File> files) {
        return files.findAll { file ->
            try {
                return validateFilePath(file)
            } catch (SecurityException e) {
                if (config.strictMode) {
                    throw e  // Re-throw in strict mode
                } else {
                    log.warn("Skipping invalid file: ${sanitizePath(file)}")
                    return false
                }
            }
        }
    }
    
    // =========================================================================
    // Symlink Validation
    // =========================================================================
    
    /**
     * Checks if a file is a symbolic link.
     * 
     * @param file File to check
     * @return true if file is a symlink
     */
    boolean isSymlink(File file) {
        try {
            return Files.isSymbolicLink(file.toPath())
        } catch (Exception e) {
            log.warn("Error checking symlink status: ${sanitizePath(file)}", e)
            return false
        }
    }
    
    /**
     * Validates a symbolic link according to security policy.
     * 
     * @param file Symlink file
     * @return true if symlink is valid/allowed, false if should be skipped
     * @throws SecurityException if symlink target is outside allowed directories
     */
    boolean validateSymlink(File file) {
        if (!isSymlink(file)) {
            return true  // Not a symlink, validation passes
        }
        
        if (!config.followSymlinks) {
            log.debug("Skipping symlink (followSymlinks=false): ${sanitizePath(file)}")
            return false
        }
        
        if (!config.validateSymlinkTargets) {
            return true  // Following symlinks without target validation
        }
        
        try {
            Path linkPath = file.toPath()
            Path targetPath = Files.readSymbolicLink(linkPath)
            
            // Resolve to absolute path
            Path absoluteTarget = linkPath.getParent().resolve(targetPath).normalize()
            File targetFile = absoluteTarget.toFile()
            
            // Validate target is within allowed directories
            if (!validateFilePath(targetFile)) {
                logSecurityEvent(
                    'SYMLINK_TRAVERSAL_ATTEMPT',
                    "Rejected symlink with target outside allowed directories",
                    [
                        symlinkPath: sanitizePath(file),
                        targetPath: sanitizePath(targetFile),
                        allowedDirs: config.allowedDirectories*.canonicalPath
                    ]
                )
                
                throw new SecurityException(
                    "Symlink target outside allowed directories. " +
                    "Link: ${sanitizePath(file)}, Target: ${sanitizePath(targetFile)}"
                )
            }
            
            return true
            
        } catch (IOException e) {
            log.error("Error validating symlink: ${sanitizePath(file)}", e)
            return false
        }
    }
    
    // =========================================================================
    // Resource Limits
    // =========================================================================
    
    /**
     * Validates file count is within limits.
     * 
     * @param fileCount Number of files
     * @throws SecurityException if count exceeds maxFiles in enforce mode
     */
    void validateFileCount(int fileCount) {
        if (fileCount > config.maxFiles) {
            logSecurityEvent(
                'FILE_COUNT_LIMIT_EXCEEDED',
                "File count ${fileCount} exceeds limit ${config.maxFiles}"
            )
            
            if (config.enforceLimits) {
                throw new SecurityException(
                    "File count ${fileCount} exceeds maximum allowed ${config.maxFiles}"
                )
            } else {
                log.warn("File count ${fileCount} exceeds limit ${config.maxFiles} (not enforced)")
            }
        }
    }
    
    /**
     * Validates file size is within limits.
     * 
     * @param file File to validate
     * @return true if file size is acceptable, false if should be skipped
     */
    boolean validateFileSize(File file) {
        if (!file.exists() || !file.isFile()) {
            return true
        }
        
        def size = file.size()
        
        if (size > config.maxFileSizeBytes) {
            logSecurityEvent(
                'FILE_SIZE_LIMIT_EXCEEDED',
                "File size ${size} exceeds limit ${config.maxFileSizeBytes}",
                [filePath: sanitizePath(file), fileSize: size]
            )
            
            if (config.enforceLimits) {
                log.warn("Skipping oversized file: ${sanitizePath(file)} (${formatBytes(size)})")
                return false
            } else {
                log.warn("File ${sanitizePath(file)} size ${formatBytes(size)} exceeds limit (not enforced)")
                return true
            }
        }
        
        return true
    }
    
    /**
     * Validates total size of all files is within limits.
     * 
     * @param files Files to validate
     * @throws SecurityException if total size exceeds maxTotalSize in enforce mode
     */
    void validateTotalSize(List<File> files) {
        def totalSize = files.sum { it.size() } as Long ?: 0L
        
        if (totalSize > config.maxTotalSizeBytes) {
            logSecurityEvent(
                'TOTAL_SIZE_LIMIT_EXCEEDED',
                "Total size ${totalSize} exceeds limit ${config.maxTotalSizeBytes}",
                [totalSize: totalSize, fileCount: files.size()]
            )
            
            if (config.enforceLimits) {
                throw new SecurityException(
                    "Total file size ${formatBytes(totalSize)} exceeds maximum allowed " +
                    "${formatBytes(config.maxTotalSizeBytes)}"
                )
            } else {
                log.warn("Total size ${formatBytes(totalSize)} exceeds limit (not enforced)")
            }
        }
    }
    
    // =========================================================================
    // Utility Methods
    // =========================================================================
    
    /**
     * Sanitizes file path for logging based on verboseErrors setting.
     * 
     * @param file File to sanitize
     * @return Sanitized path string
     */
    private String sanitizePath(File file) {
        if (config.verboseErrors) {
            return file.canonicalPath
        } else {
            // Return just the filename without path
            return file.name
        }
    }
    
    /**
     * Formats bytes in human-readable format.
     */
    private String formatBytes(long bytes) {
        if (bytes < 1024) return "${bytes} B"
        if (bytes < 1024 * 1024) return "${bytes / 1024} KB"
        if (bytes < 1024 * 1024 * 1024) return "${bytes / (1024 * 1024)} MB"
        return "${bytes / (1024 * 1024 * 1024)} GB"
    }
    
    /**
     * Logs a security event.
     */
    private void logSecurityEvent(String eventType, String message, Map context = [:]) {
        if (!config.logSecurityEvents) {
            return
        }
        
        def event = [
            timestamp: new Date(),
            eventType: eventType,
            message: message,
            context: context,
            threadId: Thread.currentThread().id,
            threadName: Thread.currentThread().name
        ]
        
        log.warn("SECURITY EVENT [${eventType}]: ${message} ${context}")
    }
}
