package org.softwood.dag.task

import groovy.transform.Canonical

/**
 * Represents an error that occurred during file processing.
 */
@Canonical
class FileError {
    File file
    Exception exception
    Date timestamp
    
    String getMessage() {
        exception?.message ?: 'Unknown error'
    }
    
    @Override
    String toString() {
        return "FileError[file=${file?.name}, error=${message}, at=${timestamp}]"
    }
}
