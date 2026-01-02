package org.softwood.dag.task.nosql

import groovy.transform.CompileStatic

/**
 * Update options.
 * 
 * @since 2.2.0
 */
@CompileStatic
class UpdateOptions {
    /** If true, insert document if it doesn't exist */
    boolean upsert = false
    
    /** If true, update multiple documents (default: update only first match) */
    boolean multi = false
}
