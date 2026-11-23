package org.softwood.dag.task

import groovy.transform.Canonical
import org.softwood.dag.task.objectstorage.ObjectRef

/**
 * Result from ObjectStoreTask execution
 * 
 * Note: Not @Immutable because it contains Object fields that may vary.
 * Use @Canonical instead for value equality and toString.
 */
@Canonical
class ObjectStoreTaskResult {
    
    /** Success flag */
    boolean success
    
    /** Aggregated or custom result data */
    Object data
    
    /** Execution context (for detailed inspection) */
    ObjectStoreTaskContext context
    
    /** Number of objects processed */
    int objectsProcessed = 0
    
    /** Emitted data (for downstream tasks) */
    List<Object> emittedData = []
    
    /** Errors that occurred (if any) */
    Map<ObjectRef, Exception> errors = [:]
    
    /**
     * Get summary map
     */
    Map<String, Object> summary() {
        return [
            success: success,
            objectsProcessed: objectsProcessed,
            emittedCount: emittedData.size(),
            errorCount: errors.size(),
            hasErrors: !errors.isEmpty(),
            data: data
        ]
    }
    
    @Override
    String toString() {
        "ObjectStoreTaskResult[success=${success}, processed=${objectsProcessed}, emitted=${emittedData.size()}, errors=${errors.size()}]"
    }
}
