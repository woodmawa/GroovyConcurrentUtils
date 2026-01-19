package org.softwood.dag.task.objectstorage.providers

import groovy.util.logging.Slf4j
import org.softwood.dag.task.objectstorage.*

/**
 * Garage Object Storage Provider (S3-compatible, lightweight)
 *
 * <p><strong>Dependencies:</strong> Requires io.minio:minio:8.5+ to be available at runtime
 * (uses MinIO client for S3 compatibility). The provider will fail initialization if MinIO
 * library is not available.</p>
 *
 * Configuration:
 * - endpoint: Garage server endpoint (required) e.g., "http://localhost:3900"
 * - accessKey: Access key (required)
 * - secretKey: Secret key (required)
 * - region: Region (optional, default: "garage")
 */
@Slf4j
class GarageProvider extends AbstractObjectStoreProvider {
    
    private Object client  // MinioClient (Garage is S3-compatible)
    private boolean clientAvailable = false
    
    @Override
    String getProviderId() {
        return "garage"
    }
    
    @Override
    String getDisplayName() {
        return "Garage"
    }
    
    @Override
    protected void validateConfig() {
        getRequiredConfigValue('endpoint')
        getRequiredConfigValue('accessKey')
        getRequiredConfigValue('secretKey')
    }
    
    @Override
    void initialize() {
        if (initialized) return
        
        log.info "Initializing Garage provider"
        
        try {
            // Check if MinIO client is available (Garage is S3-compatible)
            Class.forName("io.minio.MinioClient")
            clientAvailable = true
            
            def endpoint = getRequiredConfigValue('endpoint')
            def accessKey = getRequiredConfigValue('accessKey')
            def secretKey = getRequiredConfigValue('secretKey')
            def region = getConfigValue('region', 'garage')
            
            // Use reflection to create MinIO client
            def minioClientClass = Class.forName("io.minio.MinioClient")
            def builderMethod = minioClientClass.getMethod("builder")
            def builder = builderMethod.invoke(null)
            
            builder.getClass().getMethod("endpoint", String).invoke(builder, endpoint)
            builder.getClass().getMethod("credentials", String, String).invoke(builder, accessKey, secretKey)
            builder.getClass().getMethod("region", String).invoke(builder, region)
            
            client = builder.getClass().getMethod("build").invoke(builder)
            
            // Garage has limited capabilities
            capabilities = new ProviderCapabilities(
                supportsConditionalPut: true,
                supportsVersionToken: true,
                supportsObjectTags: false,
                supportsUserMetadata: true,
                supportsServerSideEncryption: false,
                supportsPresignedUrls: true,
                supportsMultipartUpload: true,
                supportsRangeReads: true,
                supportsDelimiterListing: true,
                supportsVersioning: false
            )
            
            initialized = true
            log.info "Garage provider initialized for endpoint: ${endpoint}"
            
        } catch (ClassNotFoundException e) {
            throw new ObjectStoreException(
                "MinIO client library not found (required for Garage S3 compatibility). " +
                "Add io.minio:minio:8.5+ to classpath.", e)
        } catch (Exception e) {
            throw new ObjectStoreException("Failed to initialize Garage provider", e)
        }
    }
    
    @Override
    void close() {
        super.close()
        client = null
        clientAvailable = false
    }
    
    // =========================================================================
    // Stub Implementations - TODO: Complete with reflection like MinIO
    // =========================================================================
    
    @Override
    void createContainer(String container, ContainerOptions options) throws ObjectStoreException {
        ensureAvailable()
        throw new UnsupportedOperationException("Garage provider operations not yet fully implemented with reflection")
    }
    
    @Override
    void deleteContainer(String container, boolean force) throws ObjectStoreException {
        ensureAvailable()
        throw new UnsupportedOperationException("Garage provider operations not yet fully implemented with reflection")
    }
    
    @Override
    List<ContainerInfo> listContainers() throws ObjectStoreException {
        ensureAvailable()
        throw new UnsupportedOperationException("Garage provider operations not yet fully implemented with reflection")
    }
    
    @Override
    ObjectInfo stat(ObjectRef ref) throws ObjectStoreException {
        ensureAvailable()
        throw new UnsupportedOperationException("Garage provider operations not yet fully implemented with reflection")
    }
    
    @Override
    ObjectInfo put(ObjectRef ref, InputStream data, long contentLength, PutOptions options) throws ObjectStoreException {
        ensureAvailable()
        throw new UnsupportedOperationException("Garage provider operations not yet fully implemented with reflection")
    }
    
    @Override
    ObjectInfo replace(ObjectRef ref, InputStream data, long contentLength, ReplaceOptions options) throws ObjectStoreException {
        ensureAvailable()
        throw new UnsupportedOperationException("Garage provider operations not yet fully implemented with reflection")
    }
    
    @Override
    ObjectRead get(ObjectRef ref, GetOptions options) throws ObjectStoreException {
        ensureAvailable()
        throw new UnsupportedOperationException("Garage provider operations not yet fully implemented with reflection")
    }
    
    @Override
    void delete(ObjectRef ref, DeleteOptions options) throws ObjectStoreException {
        ensureAvailable()
        throw new UnsupportedOperationException("Garage provider operations not yet fully implemented with reflection")
    }
    
    @Override
    ObjectInfo copy(ObjectRef source, ObjectRef target, CopyOptions options) throws ObjectStoreException {
        ensureAvailable()
        throw new UnsupportedOperationException("Garage provider operations not yet fully implemented with reflection")
    }
    
    @Override
    PagedResult<ObjectInfo> list(String container, ListOptions options) throws ObjectStoreException {
        ensureAvailable()
        throw new UnsupportedOperationException("Garage provider operations not yet fully implemented with reflection")
    }
    
    private void ensureAvailable() {
        if (!initialized) {
            throw new ObjectStoreException("Garage provider not initialized")
        }
        if (!clientAvailable) {
            throw new ObjectStoreException("MinIO client library not available (required for Garage)")
        }
    }
}
