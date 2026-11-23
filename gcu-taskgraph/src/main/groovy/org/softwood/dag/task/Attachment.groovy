package org.softwood.dag.task

import groovy.transform.ToString
import java.time.LocalDateTime

/**
 * Represents an attachment associated with a ManualTask.
 * Attachments can be uploaded when completing a manual task.
 */
@ToString(includeNames = true)
class Attachment {
    
    /** File name */
    String name
    
    /** MIME type (e.g., "application/pdf", "image/png") */
    String contentType
    
    /** File size in bytes */
    long size
    
    /** Binary content */
    byte[] data
    
    /** Timestamp when attachment was uploaded */
    LocalDateTime uploadedAt = LocalDateTime.now()
    
    /** Optional description */
    String description
    
    /**
     * Get metadata without the binary content (for logging/serialization)
     */
    Map<String, Object> getMetadata() {
        return [
            name: name,
            contentType: contentType,
            size: size,
            uploadedAt: uploadedAt,
            description: description
        ]
    }
    
    /**
     * Create an attachment from a file
     */
    static Attachment fromFile(File file, String contentType = null) {
        return new Attachment(
            name: file.name,
            contentType: contentType ?: guessContentType(file.name),
            size: file.length(),
            data: file.bytes
        )
    }
    
    /**
     * Simple content type guessing based on file extension
     */
    private static String guessContentType(String filename) {
        def ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase()
        
        switch (ext) {
            case 'pdf': return 'application/pdf'
            case 'doc':
            case 'docx': return 'application/msword'
            case 'xls':
            case 'xlsx': return 'application/vnd.ms-excel'
            case 'txt': return 'text/plain'
            case 'jpg':
            case 'jpeg': return 'image/jpeg'
            case 'png': return 'image/png'
            case 'gif': return 'image/gif'
            case 'zip': return 'application/zip'
            default: return 'application/octet-stream'
        }
    }
}
