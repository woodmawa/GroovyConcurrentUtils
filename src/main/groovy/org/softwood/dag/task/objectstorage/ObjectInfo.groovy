package org.softwood.dag.task.objectstorage

import groovy.transform.Immutable

/**
 * Object metadata and stat information
 */
@Immutable
class ObjectInfo {
    ObjectRef ref
    long sizeBytes = 0
    String contentType
    String contentEncoding
    Date lastModified

    /** Version token (ETag for S3/Azure, generation for GCS) */
    String versionToken

    /** GCS-specific generation number */
    Long generation

    /** User-defined metadata */
    Map<String, String> userMetadata = [:]

    /** Object tags (S3/compatible) */
    Map<String, String> tags = [:]

    /** Storage class (STANDARD, INFREQUENT_ACCESS, GLACIER, etc.) */
    String storageClass
}