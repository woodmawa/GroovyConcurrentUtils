package org.softwood.dag.task

import groovy.io.FileType
import groovy.util.logging.Slf4j

import java.util.regex.Pattern

/**
 * Specification for discovering files from a directory.
 *
 * Supports:
 * - Pattern matching (glob-style: *.log, error-*.txt)
 * - Recursive directory traversal
 * - File age filtering
 * - Size filtering
 * - Custom filters
 */
@Slf4j
class FileSourceSpec {
    
    /** Base directory to scan */
    File directory
    
    /** File name pattern (glob-style: *.log, data-*.csv) */
    String pattern
    
    /** Scan subdirectories recursively */
    boolean recursive = false
    
    /** Only files older than this many milliseconds */
    Long olderThanMillis
    
    /** Only files newer than this many milliseconds */
    Long newerThanMillis
    
    /** Minimum file size in bytes */
    Long minSize
    
    /** Maximum file size in bytes */
    Long maxSize
    
    /** Custom filter closure */
    Closure<Boolean> customFilter
    
    /** Security validator (injected from FileTask) */
    FileTaskSecurityValidator validator
    
    // =========================================================================
    // DSL Methods
    // =========================================================================
    
    /**
     * Set file pattern (glob-style).
     * 
     * Examples:
     * - "*.log" matches all .log files
     * - "error-*.txt" matches error-001.txt, error-abc.txt, etc.
     * - "data-[0-9]*.csv" matches data-1.csv, data-123.csv, etc.
     */
    void pattern(String pat) {
        this.pattern = pat
    }
    
    /**
     * Enable recursive directory scanning.
     */
    void recursive(boolean rec) {
        this.recursive = rec
    }
    
    /**
     * Only include files older than specified duration.
     * 
     * <pre>
     * olderThan 24.hours
     * olderThan 7.days
     * </pre>
     */
    void olderThan(Number hours) {
        this.olderThanMillis = (long)(hours * 3600000)
    }
    
    /**
     * Only include files newer than specified duration.
     */
    void newerThan(Number hours) {
        this.newerThanMillis = (long)(hours * 3600000)
    }
    
    /**
     * Minimum file size.
     */
    void minSize(long bytes) {
        this.minSize = bytes
    }
    
    /**
     * Maximum file size.
     */
    void maxSize(long bytes) {
        this.maxSize = bytes
    }
    
    /**
     * Custom filter.
     */
    void filter(Closure<Boolean> filterClosure) {
        this.customFilter = filterClosure
    }
    
    // =========================================================================
    // File Discovery
    // =========================================================================
    
    /**
     * Discover files matching this specification.
     */
    List<File> discover() {
        if (!directory.exists()) {
            log.warn("Directory does not exist: ${directory}")
            return []
        }
        
        if (!directory.isDirectory()) {
            log.warn("Not a directory: ${directory}")
            return []
        }
        
        List<File> matchedFiles = []
        long currentTime = System.currentTimeMillis()
        
        // Convert glob pattern to regex
        Pattern patternRegex = pattern ? globToRegex(pattern) : null
        
        // Scan directory
        def fileType = recursive ? FileType.FILES : FileType.FILES
        
        if (recursive) {
            directory.eachFileRecurse(fileType) { file ->
                // Security: validate symlink before processing
                if (validator && !validator.validateSymlink(file)) {
                    return  // Skip this file
                }
                
                if (matches(file, patternRegex, currentTime)) {
                    matchedFiles << file
                }
            }
        } else {
            directory.eachFile(fileType) { file ->
                // Security: validate symlink before processing
                if (validator && !validator.validateSymlink(file)) {
                    return  // Skip this file
                }
                
                if (matches(file, patternRegex, currentTime)) {
                    matchedFiles << file
                }
            }
        }
        
        log.debug("Discovered ${matchedFiles.size()} files from ${directory}")
        return matchedFiles
    }
    
    /**
     * Check if file matches all criteria.
     */
    private boolean matches(File file, Pattern patternRegex, long currentTime) {
        // Pattern matching
        if (patternRegex && !patternRegex.matcher(file.name).matches()) {
            return false
        }
        
        // Age filtering
        if (olderThanMillis) {
            long fileAge = currentTime - file.lastModified()
            if (fileAge < olderThanMillis) {
                return false
            }
        }
        
        if (newerThanMillis) {
            long fileAge = currentTime - file.lastModified()
            if (fileAge > newerThanMillis) {
                return false
            }
        }
        
        // Size filtering
        if (minSize && file.size() < minSize) {
            return false
        }
        
        if (maxSize && file.size() > maxSize) {
            return false
        }
        
        // Custom filter
        if (customFilter) {
            try {
                if (!customFilter.call(file)) {
                    return false
                }
            } catch (Exception e) {
                log.warn("Custom filter failed for ${file}: ${e.message}")
                return false
            }
        }
        
        return true
    }
    
    /**
     * Convert glob pattern to regex.
     * 
     * Supports:
     * - * matches any characters
     * - ? matches single character
     * - [abc] matches a, b, or c
     * - [a-z] matches a through z
     */
    private Pattern globToRegex(String glob) {
        StringBuilder regex = new StringBuilder("^")
        
        glob.each { c ->  // Don't type as 'char' - Groovy passes String
            switch (c) {
                case '*':
                    regex.append(".*")
                    break
                case '?':
                    regex.append(".")
                    break
                case '.':
                    regex.append("\\.")
                    break
                case '[':
                case ']':
                    regex.append(c)
                    break
                default:
                    char ch = c as char
                    if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '-') {
                        regex.append(c)
                    } else {
                        regex.append("\\").append(c)
                    }
            }
        }
        
        regex.append('$')
        return Pattern.compile(regex.toString())
    }
}
