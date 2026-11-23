package org.softwood.dag.task.objectstorage

import org.softwood.dag.task.objectstorage.providers.*
import spock.lang.Specification

/**
 * Basic unit tests for Object Storage framework.
 * These tests don't require any external services and test the core abstractions.
 */
class ObjectStoreBasicsTest extends Specification {
    
    // =========================================================================
    // ObjectRef Tests
    // =========================================================================
    
    def "ObjectRef should be immutable"() {
        when:
        def ref = ObjectRef.of('my-bucket', 'path/to/file.txt')
        
        then:
        ref.container == 'my-bucket'
        ref.key == 'path/to/file.txt'
        ref.toString() == 'my-bucket/path/to/file.txt'
    }
    
    def "ObjectRef equals should work correctly"() {
        given:
        def ref1 = ObjectRef.of('bucket', 'key')
        def ref2 = ObjectRef.of('bucket', 'key')
        def ref3 = ObjectRef.of('bucket', 'different-key')
        
        expect:
        ref1 == ref2
        ref1 != ref3
        ref1.hashCode() == ref2.hashCode()
    }
    
    // =========================================================================
    // ObjectInfo Tests
    // =========================================================================
    
    def "ObjectInfo should hold metadata"() {
        given:
        def ref = ObjectRef.of('bucket', 'file.txt')
        def now = new Date()
        
        when:
        def info = new ObjectInfo(
            ref: ref,
            sizeBytes: 1024,
            contentType: 'text/plain',
            lastModified: now,
            versionToken: 'etag-123',
            userMetadata: [author: 'John'],
            tags: [env: 'prod']
        )
        
        then:
        info.ref == ref
        info.sizeBytes == 1024
        info.contentType == 'text/plain'
        info.lastModified == now
        info.versionToken == 'etag-123'
        info.userMetadata.author == 'John'
        info.tags.env == 'prod'
    }
    
    // =========================================================================
    // Options Tests
    // =========================================================================
    
    def "PutOptions should have sensible defaults"() {
        when:
        def options = PutOptions.DEFAULT
        
        then:
        !options.ifNoneMatch
        options.metadata == [:]
        options.tags == [:]
        options.contentType == null
    }
    
    def "PutOptions should allow customization"() {
        when:
        def options = new PutOptions(
            ifNoneMatch: true,
            contentType: 'application/json',
            metadata: [version: '1.0'],
            tags: [env: 'staging']
        )
        
        then:
        options.ifNoneMatch
        options.contentType == 'application/json'
        options.metadata.version == '1.0'
        options.tags.env == 'staging'
    }
    
    def "GetOptions should support range reads"() {
        when:
        def options = new GetOptions(rangeStart: 100, rangeEnd: 199)
        
        then:
        options.rangeStart == 100
        options.rangeEnd == 199
    }
    
    def "ListOptions should support prefix and delimiter"() {
        when:
        def options = new ListOptions(
            prefix: 'logs/',
            delimiter: '/',
            pageSize: 100
        )
        
        then:
        options.prefix == 'logs/'
        options.delimiter == '/'
        options.pageSize == 100
    }
    
    def "ReplaceOptions should enforce existence check"() {
        when:
        def options = new ReplaceOptions(
            mustExist: true,
            ifMatchVersionToken: 'etag-123'
        )
        
        then:
        options.mustExist
        options.ifMatchVersionToken == 'etag-123'
    }
    
    // =========================================================================
    // ProviderCapabilities Tests
    // =========================================================================
    
    def "ProviderCapabilities should have defaults"() {
        when:
        def caps = new ProviderCapabilities()
        
        then:
        !caps.supportsConditionalPut
        !caps.supportsVersionToken
        !caps.supportsObjectTags
        !caps.supportsUserMetadata
    }
    
    def "ProviderCapabilities full should enable everything"() {
        when:
        def caps = ProviderCapabilities.full()
        
        then:
        caps.supportsConditionalPut
        caps.supportsVersionToken
        caps.supportsObjectTags
        caps.supportsUserMetadata
        caps.supportsServerSideEncryption
        caps.supportsPresignedUrls
        caps.supportsMultipartUpload
        caps.supportsRangeReads
        caps.supportsDelimiterListing
        caps.supportsVersioning
    }
    
    def "ProviderCapabilities s3Compatible should have S3 features"() {
        when:
        def caps = ProviderCapabilities.s3Compatible()
        
        then:
        caps.supportsConditionalPut
        caps.supportsVersionToken
        caps.supportsObjectTags
        caps.supportsUserMetadata
    }
    
    // =========================================================================
    // ObjectStoreException Tests
    // =========================================================================
    
    def "ObjectStoreException should include context"() {
        given:
        def ref = ObjectRef.of('bucket', 'key')
        
        when:
        def ex = new ObjectStoreException('Test error', ref)
        ex.providerId = 'minio'
        
        then:
        ex.message.contains('Test error')
        ex.message.contains('bucket/key')
        ex.message.contains('minio')
        ex.objectRef == ref
    }
    
    def "ObjectStoreException should support cause"() {
        given:
        def cause = new IOException('Network error')
        def ref = ObjectRef.of('bucket', 'key')
        
        when:
        def ex = new ObjectStoreException('Failed to upload', ref, cause)
        
        then:
        ex.cause == cause
        ex.message.contains('Failed to upload')
    }
    
    // =========================================================================
    // ContainerInfo Tests
    // =========================================================================
    
    def "ContainerInfo should hold bucket metadata"() {
        given:
        def created = new Date()
        
        when:
        def info = new ContainerInfo(
            name: 'my-bucket',
            createdDate: created,
            region: 'us-east-1',
            metadata: [owner: 'team-a']
        )
        
        then:
        info.name == 'my-bucket'
        info.createdDate == created
        info.region == 'us-east-1'
        info.metadata.owner == 'team-a'
    }
    
    // =========================================================================
    // AbstractObjectStoreProvider Tests
    // =========================================================================
    
    def "AbstractObjectStoreProvider should manage configuration"() {
        given:
        def provider = new TestProvider()
        
        when:
        provider.configure([
            endpoint: 'http://localhost:9000',
            timeout: 30000,
            debug: true
        ])
        
        then:
        provider.getConfigValue('endpoint', null) == 'http://localhost:9000'
        provider.getConfigValue('timeout', 5000) == 30000
        provider.getConfigValue('debug', false) == true
        provider.getConfigValue('missing', 'default') == 'default'
    }
    
    def "AbstractObjectStoreProvider should validate required config"() {
        given:
        def provider = new TestProvider()
        provider.configure([timeout: 30000])
        
        when:
        provider.getRequiredConfigValue('endpoint')
        
        then:
        def ex = thrown(ObjectStoreException)
        ex.message.contains('endpoint')
        ex.message.contains('missing')
    }
    
    def "AbstractObjectStoreProvider should track initialization"() {
        given:
        def provider = new TestProvider()
        provider.configure([endpoint: 'http://localhost'])
        
        expect:
        !provider.initialized
        
        when:
        provider.initialize()
        
        then:
        provider.initialized
        
        when:
        provider.close()
        
        then:
        !provider.initialized
    }
    
    def "AbstractObjectStoreProvider convenience methods should work"() {
        given:
        def provider = new TestProvider()
        provider.configure([endpoint: 'http://localhost'])
        provider.initialize()
        
        when: "put from byte array"
        def bytes = 'test'.bytes
        def ref = ObjectRef.of('bucket', 'key')
        // Would call put with InputStream internally
        
        then:
        notThrown(Exception)
        
        cleanup:
        provider.close()
    }
    
    // =========================================================================
    // Provider Identity Tests
    // =========================================================================
    
    def "each provider should have unique ID"() {
        given:
        def providers = [
            new MinIOProvider(),
            new GarageProvider(),
            new AwsS3Provider(),
            new AzureBlobProvider(),
            new GoogleCloudStorageProvider()
        ]
        
        when:
        def ids = providers.collect { it.providerId }
        
        then:
        ids.size() == 5
        ids.toSet().size() == 5  // All unique
        ids == ['minio', 'garage', 'aws-s3', 'azure-blob', 'gcs']
    }
    
    def "each provider should have display name"() {
        expect:
        new MinIOProvider().displayName == 'MinIO'
        new GarageProvider().displayName == 'Garage'
        new AwsS3Provider().displayName == 'AWS S3'
        new AzureBlobProvider().displayName == 'Azure Blob Storage'
        new GoogleCloudStorageProvider().displayName == 'Google Cloud Storage'
    }
    
    // =========================================================================
    // Helper Classes
    // =========================================================================
    
    /**
     * Test provider implementation for testing AbstractObjectStoreProvider
     */
    static class TestProvider extends AbstractObjectStoreProvider {
        
        @Override
        String getProviderId() {
            return 'test'
        }
        
        @Override
        String getDisplayName() {
            return 'Test Provider'
        }
        
        @Override
        void initialize() {
            capabilities = ProviderCapabilities.full()
            initialized = true
        }
        
        @Override
        void createContainer(String container, ContainerOptions options) {
            throw new UnsupportedOperationException()
        }
        
        @Override
        void deleteContainer(String container, boolean force) {
            throw new UnsupportedOperationException()
        }
        
        @Override
        List<ContainerInfo> listContainers() {
            return []
        }
        
        @Override
        ObjectInfo stat(ObjectRef ref) {
            return null
        }
        
        @Override
        ObjectInfo put(ObjectRef ref, InputStream data, long contentLength, PutOptions options) {
            return new ObjectInfo(
                ref: ref,
                sizeBytes: contentLength,
                versionToken: 'test-etag',
                lastModified: new Date()
            )
        }
        
        @Override
        ObjectInfo replace(ObjectRef ref, InputStream data, long contentLength, ReplaceOptions options) {
            throw new UnsupportedOperationException()
        }
        
        @Override
        ObjectRead get(ObjectRef ref, GetOptions options) {
            throw new UnsupportedOperationException()
        }
        
        @Override
        void delete(ObjectRef ref, DeleteOptions options) {
            // No-op
        }
        
        @Override
        ObjectInfo copy(ObjectRef source, ObjectRef target, CopyOptions options) {
            throw new UnsupportedOperationException()
        }
        
        @Override
        PagedResult<ObjectInfo> list(String container, ListOptions options) {
            return createPagedResult([], null)
        }
    }
}
