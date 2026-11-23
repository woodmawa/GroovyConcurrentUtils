package org.softwood.dag.task.objectstorage

import groovy.transform.Immutable

/**
 * Object storage capabilities - what the provider supports
 */
@Immutable
class ProviderCapabilities {

    /** Supports conditional put (if-none-match) */
    boolean supportsConditionalPut = false

    /** Supports version tokens (ETag/generation) */
    boolean supportsVersionToken = false

    /** Supports object tags */
    boolean supportsObjectTags = false

    /** Supports user metadata */
    boolean supportsUserMetadata = false

    /** Supports server-side encryption */
    boolean supportsServerSideEncryption = false

    /** Supports presigned URLs */
    boolean supportsPresignedUrls = false

    /** Supports multipart upload */
    boolean supportsMultipartUpload = false

    /** Supports byte range reads */
    boolean supportsRangeReads = false

    /** Supports delimiter-based listing (folder emulation) */
    boolean supportsDelimiterListing = false

    /** Supports object versioning */
    boolean supportsVersioning = false

    static ProviderCapabilities full() {
        new ProviderCapabilities(
                supportsConditionalPut: true,
                supportsVersionToken: true,
                supportsObjectTags: true,
                supportsUserMetadata: true,
                supportsServerSideEncryption: true,
                supportsPresignedUrls: true,
                supportsMultipartUpload: true,
                supportsRangeReads: true,
                supportsDelimiterListing: true,
                supportsVersioning: true
        )
    }

    static ProviderCapabilities s3Compatible() {
        new ProviderCapabilities(
                supportsConditionalPut: true,
                supportsVersionToken: true,
                supportsObjectTags: true,
                supportsUserMetadata: true,
                supportsServerSideEncryption: true,
                supportsPresignedUrls: true,
                supportsMultipartUpload: true,
                supportsRangeReads: true,
                supportsDelimiterListing: true,
                supportsVersioning: true
        )
    }
}