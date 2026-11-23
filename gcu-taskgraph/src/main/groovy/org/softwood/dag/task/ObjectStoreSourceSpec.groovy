package org.softwood.dag.task

import groovy.transform.ToString

/**
 * Specification for an object storage source (container/bucket with filters)
 */
@ToString(includeNames = true)
class ObjectStoreSourceSpec {
    
    /** Container/bucket name */
    String container
    
    /** Prefix filter (e.g., "logs/2024/") */
    String prefix = ""
    
    /** Recursive listing (ignore delimiter) */
    boolean recursive = false
    
    /** Maximum objects to retrieve (null = unlimited) */
    Integer maxObjects
    
    /** Page size for listing operations */
    int pageSize = 1000
    
    /** Include metadata in listing (may require extra API calls) */
    boolean includeMetadata = false
    
    // =========================================================================
    // DSL Configuration Methods
    // =========================================================================
    
    /**
     * Set the prefix filter
     */
    void prefix(String prefix) {
        this.prefix = prefix
    }
    
    /**
     * Enable recursive listing
     */
    void recursive(boolean recursive) {
        this.recursive = recursive
    }
    
    /**
     * Set maximum objects to retrieve
     */
    void maxObjects(int max) {
        this.maxObjects = max
    }
    
    /**
     * Set page size for listing
     */
    void pageSize(int size) {
        this.pageSize = size
    }
    
    /**
     * Include metadata in listing
     */
    void includeMetadata(boolean include) {
        this.includeMetadata = include
    }
}
