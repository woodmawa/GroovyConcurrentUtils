package org.softwood.dag.task.objectstorage

/**
 * Exception thrown by object storage operations
 */
class ObjectStoreException extends RuntimeException {
    
    /** Optional object reference */
    ObjectRef objectRef
    
    /** Optional container name */
    String container
    
    /** Provider ID where error occurred */
    String providerId
    
    ObjectStoreException(String message) {
        super(message)
    }
    
    ObjectStoreException(String message, Throwable cause) {
        super(message, cause)
    }
    
    ObjectStoreException(String message, ObjectRef ref) {
        super(message)
        this.objectRef = ref
    }
    
    ObjectStoreException(String message, ObjectRef ref, Throwable cause) {
        super(message, cause)
        this.objectRef = ref
    }
    
    @Override
    String getMessage() {
        def msg = super.getMessage()
        if (objectRef) {
            msg += " [object: ${objectRef}]"
        }
        if (container) {
            msg += " [container: ${container}]"
        }
        if (providerId) {
            msg += " [provider: ${providerId}]"
        }
        return msg
    }
}
