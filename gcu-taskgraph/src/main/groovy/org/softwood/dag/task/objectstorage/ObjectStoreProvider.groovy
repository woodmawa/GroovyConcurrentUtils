package org.softwood.dag.task.objectstorage

import groovy.transform.Immutable

/**
 * Provider interface for object storage systems.
 *
 * <p>Supports: AWS S3, Azure Blob Storage, Google Cloud Storage, MinIO, Garage</p>
 *
 * <p><strong>Design Philosophy:</strong></p>
 * <ul>
 *   <li>Treat all as object stores (not filesystems)</li>
 *   <li>Objects live in containers/buckets with keys/names</li>
 *   <li>Stream-first for efficiency</li>
 *   <li>Conditional operations via version tokens (ETags/generations)</li>
 *   <li>Provider capabilities expose differences</li>
 * </ul>
 *
 * @since 2.4.0
 */
interface ObjectStoreProvider {

    /**
     * Unique provider identifier: "aws-s3", "azure-blob", "gcs", "minio", "garage"
     */
    String getProviderId()

    /**
     * Human-friendly display name
     */
    String getDisplayName()

    /**
     * Provider capabilities (what operations are supported)
     */
    ProviderCapabilities getCapabilities()

    /**
     * Initialize the provider (establish connections, validate config, etc.)
     */
    void initialize()

    /**
     * Clean up resources
     */
    void close()

    // =========================================================================
    // Container Operations
    // =========================================================================

    /**
     * Check if container/bucket exists
     */
    boolean containerExists(String container) throws ObjectStoreException

    /**
     * Create a container/bucket
     */
    void createContainer(String container, ContainerOptions options) throws ObjectStoreException

    /**
     * Delete a container/bucket
     * @param force If true, delete even if not empty (deletes all objects first)
     */
    void deleteContainer(String container, boolean force) throws ObjectStoreException

    /**
     * List all containers/buckets
     */
    List<ContainerInfo> listContainers() throws ObjectStoreException

    // =========================================================================
    // Object Operations
    // =========================================================================

    /**
     * Get object metadata (HEAD operation - no body download)
     * @return ObjectInfo or null if not found
     */
    ObjectInfo stat(ObjectRef ref) throws ObjectStoreException

    /**
     * Put object (upsert by default, create-only if options.ifNoneMatch = true)
     * @return ObjectInfo including version token
     */
    ObjectInfo put(ObjectRef ref, InputStream data, long contentLength, PutOptions options) throws ObjectStoreException

    /**
     * Replace object (must exist, optionally with version token check)
     */
    ObjectInfo replace(ObjectRef ref, InputStream data, long contentLength, ReplaceOptions options) throws ObjectStoreException

    /**
     * Get object as stream
     * @return ObjectRead handle (must be closed by caller)
     */
    ObjectRead get(ObjectRef ref, GetOptions options) throws ObjectStoreException

    /**
     * Delete object (idempotent - deleting missing object is OK)
     */
    void delete(ObjectRef ref, DeleteOptions options) throws ObjectStoreException

    /**
     * Copy object within store
     */
    ObjectInfo copy(ObjectRef source, ObjectRef target, CopyOptions options) throws ObjectStoreException

    /**
     * List objects in container
     */
    PagedResult<ObjectInfo> list(String container, ListOptions options) throws ObjectStoreException

    // =========================================================================
    // Convenience Methods
    // =========================================================================

    /**
     * Check if object exists
     */
    default boolean exists(ObjectRef ref) {
        return stat(ref) != null
    }

    /**
     * Put from byte array
     */
    default ObjectInfo put(ObjectRef ref, byte[] data, PutOptions options) {
        return put(ref, new ByteArrayInputStream(data), data.length, options)
    }

    /**
     * Put from String
     */
    default ObjectInfo put(ObjectRef ref, String text, PutOptions options) {
        def bytes = text.getBytes(options.charset ?: 'UTF-8')
        return put(ref, bytes, options)
    }

    /**
     * Get as byte array (for small objects only!)
     */
    default byte[] getBytes(ObjectRef ref, GetOptions options) {
        def read = get(ref, options)
        try {
            return read.stream().bytes
        } finally {
            read.close()
        }
    }

    /**
     * Get as String
     */
    default String getText(ObjectRef ref, GetOptions options) {
        def bytes = getBytes(ref, options)
        return new String(bytes, options.charset ?: 'UTF-8')
    }
}
