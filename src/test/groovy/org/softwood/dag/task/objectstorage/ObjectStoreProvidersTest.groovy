package org.softwood.dag.task.objectstorage

import org.softwood.dag.task.objectstorage.providers.*
import spock.lang.Ignore
import spock.lang.Specification

/**
 * Tests for Object Storage Provider operations.
 * 
 * These tests verify the provider implementations work correctly.
 * Most tests are marked @Ignore as they require actual storage services running.
 * 
 * To run these tests:
 * 1. Start the required storage service (MinIO, Garage, etc.)
 * 2. Update the configuration in each test
 * 3. Remove the @Ignore annotation
 * 4. Run the test
 */
class ObjectStoreProvidersTest extends Specification {
    
    // =========================================================================
    // MinIO Provider Tests
    // =========================================================================
    
    @Ignore("Requires MinIO running on localhost:9000")
    def "MinIO should create and delete bucket"() {
        given:
        def provider = new MinIOProvider()
        provider.configure([
            endpoint: 'http://localhost:9000',
            accessKey: 'minioadmin',
            secretKey: 'minioadmin'
        ])
        provider.initialize()
        def bucketName = "test-bucket-${System.currentTimeMillis()}"
        
        when: "create bucket"
        provider.createContainer(bucketName, ContainerOptions.DEFAULT)
        
        then: "bucket exists"
        provider.containerExists(bucketName)
        
        when: "list buckets"
        def buckets = provider.listContainers()
        
        then: "our bucket is in the list"
        buckets.any { it.name == bucketName }
        
        cleanup:
        if (provider.containerExists(bucketName)) {
            provider.deleteContainer(bucketName, false)
        }
        provider.close()
    }
    
    @Ignore("Requires MinIO running on localhost:9000")
    def "MinIO should put and get object"() {
        given:
        def provider = new MinIOProvider()
        provider.configure([
            endpoint: 'http://localhost:9000',
            accessKey: 'minioadmin',
            secretKey: 'minioadmin'
        ])
        provider.initialize()
        def bucketName = "test-bucket-${System.currentTimeMillis()}"
        provider.createContainer(bucketName, ContainerOptions.DEFAULT)
        
        def ref = ObjectRef.of(bucketName, 'test-file.txt')
        def content = 'Hello MinIO!'
        
        when: "put object"
        def putOptions = new PutOptions(contentType: 'text/plain')
        def info = provider.put(ref, content, putOptions)
        
        then: "object info is returned"
        info != null
        info.ref == ref
        info.contentType == 'text/plain'
        info.versionToken != null
        
        when: "get object"
        def text = provider.getText(ref, GetOptions.DEFAULT)
        
        then: "content matches"
        text == content
        
        when: "stat object"
        def stat = provider.stat(ref)
        
        then: "metadata is correct"
        stat.ref == ref
        stat.sizeBytes == content.bytes.length
        stat.contentType == 'text/plain'
        
        cleanup:
        if (provider.exists(ref)) {
            provider.delete(ref, DeleteOptions.DEFAULT)
        }
        provider.deleteContainer(bucketName, true)
        provider.close()
    }
    
    @Ignore("Requires MinIO running on localhost:9000")
    def "MinIO should list objects with prefix"() {
        given:
        def provider = new MinIOProvider()
        provider.configure([
            endpoint: 'http://localhost:9000',
            accessKey: 'minioadmin',
            secretKey: 'minioadmin'
        ])
        provider.initialize()
        def bucketName = "test-bucket-${System.currentTimeMillis()}"
        provider.createContainer(bucketName, ContainerOptions.DEFAULT)
        
        // Create test objects
        ['file1.txt', 'file2.txt', 'docs/readme.md'].each { key ->
            def ref = ObjectRef.of(bucketName, key)
            provider.put(ref, "content of ${key}", PutOptions.DEFAULT)
        }
        
        when: "list all objects"
        def allObjects = provider.list(bucketName, ListOptions.DEFAULT)
        
        then: "all objects returned"
        allObjects.results.size() == 3
        
        when: "list with prefix"
        def listOptions = new ListOptions(prefix: 'file')
        def filteredObjects = provider.list(bucketName, listOptions)
        
        then: "only matching objects returned"
        filteredObjects.results.size() == 2
        filteredObjects.results.every { it.ref.key.startsWith('file') }
        
        cleanup:
        provider.deleteContainer(bucketName, true)
        provider.close()
    }
    
    @Ignore("Requires MinIO running on localhost:9000")
    def "MinIO should copy object"() {
        given:
        def provider = new MinIOProvider()
        provider.configure([
            endpoint: 'http://localhost:9000',
            accessKey: 'minioadmin',
            secretKey: 'minioadmin'
        ])
        provider.initialize()
        def bucketName = "test-bucket-${System.currentTimeMillis()}"
        provider.createContainer(bucketName, ContainerOptions.DEFAULT)
        
        def source = ObjectRef.of(bucketName, 'source.txt')
        def target = ObjectRef.of(bucketName, 'target.txt')
        def content = 'Copy me!'
        
        provider.put(source, content, PutOptions.DEFAULT)
        
        when: "copy object"
        def copiedInfo = provider.copy(source, target, CopyOptions.DEFAULT)
        
        then: "target exists"
        provider.exists(target)
        copiedInfo.ref == target
        
        when: "verify content"
        def targetContent = provider.getText(target, GetOptions.DEFAULT)
        
        then: "content matches"
        targetContent == content
        
        cleanup:
        provider.deleteContainer(bucketName, true)
        provider.close()
    }
    
    @Ignore("Requires MinIO running on localhost:9000")
    def "MinIO should support range reads"() {
        given:
        def provider = new MinIOProvider()
        provider.configure([
            endpoint: 'http://localhost:9000',
            accessKey: 'minioadmin',
            secretKey: 'minioadmin'
        ])
        provider.initialize()
        def bucketName = "test-bucket-${System.currentTimeMillis()}"
        provider.createContainer(bucketName, ContainerOptions.DEFAULT)
        
        def ref = ObjectRef.of(bucketName, 'test.txt')
        def content = '0123456789ABCDEF'
        provider.put(ref, content, PutOptions.DEFAULT)
        
        when: "read range"
        def getOptions = new GetOptions(rangeStart: 5, rangeEnd: 9)
        def objectRead = provider.get(ref, getOptions)
        def rangeContent = new String(objectRead.stream.bytes)
        objectRead.close()
        
        then: "only range is returned"
        rangeContent == '56789'
        
        cleanup:
        provider.deleteContainer(bucketName, true)
        provider.close()
    }
    
    @Ignore("Requires MinIO running on localhost:9000")
    def "MinIO should handle metadata"() {
        given:
        def provider = new MinIOProvider()
        provider.configure([
            endpoint: 'http://localhost:9000',
            accessKey: 'minioadmin',
            secretKey: 'minioadmin'
        ])
        provider.initialize()
        def bucketName = "test-bucket-${System.currentTimeMillis()}"
        provider.createContainer(bucketName, ContainerOptions.DEFAULT)
        
        def ref = ObjectRef.of(bucketName, 'metadata-test.txt')
        def metadata = [author: 'John Doe', version: '1.0']
        def putOptions = new PutOptions(metadata: metadata, contentType: 'text/plain')
        
        when: "put with metadata"
        provider.put(ref, 'test content', putOptions)
        
        and: "stat object"
        def stat = provider.stat(ref)
        
        then: "metadata is preserved"
        stat.userMetadata.author == 'John Doe'
        stat.userMetadata.version == '1.0'
        
        cleanup:
        provider.deleteContainer(bucketName, true)
        provider.close()
    }
    
    // =========================================================================
    // Garage Provider Tests
    // =========================================================================
    
    @Ignore("Requires Garage running on localhost:3900")
    def "Garage should create and delete bucket"() {
        given:
        def provider = new GarageProvider()
        provider.configure([
            endpoint: 'http://localhost:3900',
            accessKey: 'GK1234567890',
            secretKey: 'secret1234567890'
        ])
        provider.initialize()
        def bucketName = "test-bucket-${System.currentTimeMillis()}"
        
        when:
        provider.createContainer(bucketName, ContainerOptions.DEFAULT)
        
        then:
        provider.containerExists(bucketName)
        
        cleanup:
        provider.deleteContainer(bucketName, false)
        provider.close()
    }
    
    // =========================================================================
    // AWS S3 Provider Tests
    // =========================================================================
    
    @Ignore("Requires AWS credentials and S3 access")
    def "AWS S3 should create and delete bucket"() {
        given:
        def provider = new AwsS3Provider()
        provider.configure([
            region: 'us-east-1',
            accessKey: System.getenv('AWS_ACCESS_KEY_ID'),
            secretKey: System.getenv('AWS_SECRET_ACCESS_KEY')
        ])
        provider.initialize()
        def bucketName = "test-bucket-${System.currentTimeMillis()}"
        
        when:
        provider.createContainer(bucketName, ContainerOptions.DEFAULT)
        
        then:
        provider.containerExists(bucketName)
        
        cleanup:
        provider.deleteContainer(bucketName, false)
        provider.close()
    }
    
    @Ignore("Requires AWS credentials and S3 access")
    def "AWS S3 should support pagination"() {
        given:
        def provider = new AwsS3Provider()
        provider.configure([
            region: 'us-east-1'
        ])
        provider.initialize()
        def bucketName = "existing-test-bucket"
        
        // Create many objects
        20.times { i ->
            def ref = ObjectRef.of(bucketName, "file-${i}.txt")
            provider.put(ref, "content ${i}", PutOptions.DEFAULT)
        }
        
        when: "list with page size"
        def listOptions = new ListOptions(pageSize: 10)
        def page1 = provider.list(bucketName, listOptions)
        
        then: "first page has 10 items"
        page1.results.size() == 10
        page1.hasMore()
        page1.nextPageToken != null
        
        when: "get next page"
        def nextOptions = new ListOptions(pageSize: 10, pageToken: page1.nextPageToken)
        def page2 = provider.list(bucketName, nextOptions)
        
        then: "second page has remaining items"
        page2.results.size() == 10
        
        cleanup:
        provider.deleteContainer(bucketName, true)
        provider.close()
    }
    
    // =========================================================================
    // Azure Blob Provider Tests
    // =========================================================================
    
    @Ignore("Requires Azure storage account")
    def "Azure should create and delete container"() {
        given:
        def provider = new AzureBlobProvider()
        provider.configure([
            connectionString: System.getenv('AZURE_STORAGE_CONNECTION_STRING')
        ])
        provider.initialize()
        def containerName = "test-container-${System.currentTimeMillis()}"
        
        when:
        provider.createContainer(containerName, ContainerOptions.DEFAULT)
        
        then:
        provider.containerExists(containerName)
        
        cleanup:
        provider.deleteContainer(containerName, false)
        provider.close()
    }
    
    @Ignore("Requires Azure storage account")
    def "Azure should handle blob metadata"() {
        given:
        def provider = new AzureBlobProvider()
        provider.configure([
            accountName: System.getenv('AZURE_STORAGE_ACCOUNT'),
            accountKey: System.getenv('AZURE_STORAGE_KEY')
        ])
        provider.initialize()
        def containerName = "test-container"
        def ref = ObjectRef.of(containerName, 'metadata-test.txt')
        
        when:
        def metadata = [department: 'engineering', project: 'storage']
        def putOptions = new PutOptions(metadata: metadata)
        provider.put(ref, 'test content', putOptions)
        
        and:
        def stat = provider.stat(ref)
        
        then:
        stat.userMetadata.department == 'engineering'
        stat.userMetadata.project == 'storage'
        
        cleanup:
        provider.delete(ref, DeleteOptions.DEFAULT)
        provider.close()
    }
    
    // =========================================================================
    // Google Cloud Storage Provider Tests
    // =========================================================================
    
    @Ignore("Requires GCP project and credentials")
    def "GCS should create and delete bucket"() {
        given:
        def provider = new GoogleCloudStorageProvider()
        provider.configure([
            projectId: 'my-gcp-project',
            credentialsJson: '/path/to/service-account.json'
        ])
        provider.initialize()
        def bucketName = "test-bucket-${System.currentTimeMillis()}"
        
        when:
        provider.createContainer(bucketName, ContainerOptions.DEFAULT)
        
        then:
        provider.containerExists(bucketName)
        
        cleanup:
        provider.deleteContainer(bucketName, false)
        provider.close()
    }
    
    @Ignore("Requires GCP project and credentials")
    def "GCS should support generation-based versioning"() {
        given:
        def provider = new GoogleCloudStorageProvider()
        provider.configure([
            projectId: 'my-gcp-project'
        ])
        provider.initialize()
        def bucketName = "test-bucket"
        def ref = ObjectRef.of(bucketName, 'versioned-file.txt')
        
        when: "put initial version"
        def info1 = provider.put(ref, 'version 1', PutOptions.DEFAULT)
        
        then: "has generation number"
        info1.generation != null
        
        when: "update object"
        def info2 = provider.put(ref, 'version 2', PutOptions.DEFAULT)
        
        then: "generation changed"
        info2.generation != info1.generation
        info2.generation > info1.generation
        
        cleanup:
        provider.delete(ref, DeleteOptions.DEFAULT)
        provider.close()
    }
    
    // =========================================================================
    // Cross-Provider Tests
    // =========================================================================
    
    @Ignore("Requires all providers configured")
    def "all providers should handle basic operations consistently"() {
        given:
        def providers = [
            minioProvider(),
            garageProvider(),
            awsProvider(),
            azureProvider(),
            gcsProvider()
        ]
        
        expect:
        providers.each { provider ->
            def bucketName = "test-${provider.providerId}-${System.currentTimeMillis()}"
            def ref = ObjectRef.of(bucketName, 'test.txt')
            
            // Create bucket
            provider.createContainer(bucketName, ContainerOptions.DEFAULT)
            assert provider.containerExists(bucketName)
            
            // Put object
            def info = provider.put(ref, 'test content', PutOptions.DEFAULT)
            assert info != null
            assert provider.exists(ref)
            
            // Get object
            def content = provider.getText(ref, GetOptions.DEFAULT)
            assert content == 'test content'
            
            // Delete object
            provider.delete(ref, DeleteOptions.DEFAULT)
            assert !provider.exists(ref)
            
            // Delete bucket
            provider.deleteContainer(bucketName, false)
            assert !provider.containerExists(bucketName)
            
            provider.close()
        }
    }
    
    // =========================================================================
    // Error Handling Tests
    // =========================================================================
    
    @Ignore("Requires MinIO running - providers need actual SDK dependencies")
    def "providers should handle missing objects gracefully"() {
        given:
        def provider = new MinIOProvider()
        provider.configure([
            endpoint: 'http://localhost:9000',
            accessKey: 'minioadmin',
            secretKey: 'minioadmin'
        ])
        provider.initialize()
        def ref = ObjectRef.of('nonexistent-bucket', 'nonexistent-key')
        
        expect:
        provider.stat(ref) == null
        !provider.exists(ref)
        
        cleanup:
        provider.close()
    }
    
    @Ignore("Requires MinIO running - providers need actual SDK dependencies")
    def "delete should be idempotent"() {
        given:
        def provider = new MinIOProvider()
        provider.configure([
            endpoint: 'http://localhost:9000',
            accessKey: 'minioadmin',
            secretKey: 'minioadmin'
        ])
        provider.initialize()
        def bucketName = "test-bucket-${System.currentTimeMillis()}"
        provider.createContainer(bucketName, ContainerOptions.DEFAULT)
        def ref = ObjectRef.of(bucketName, 'test.txt')
        
        when: "delete non-existent object"
        provider.delete(ref, DeleteOptions.DEFAULT)
        
        then: "no exception thrown"
        notThrown(Exception)
        
        cleanup:
        provider.deleteContainer(bucketName, false)
        provider.close()
    }
    
    // =========================================================================
    // Helper Methods
    // =========================================================================
    
    private MinIOProvider minioProvider() {
        def provider = new MinIOProvider()
        provider.configure([
            endpoint: 'http://localhost:9000',
            accessKey: 'minioadmin',
            secretKey: 'minioadmin'
        ])
        provider.initialize()
        return provider
    }
    
    private GarageProvider garageProvider() {
        def provider = new GarageProvider()
        provider.configure([
            endpoint: 'http://localhost:3900',
            accessKey: 'GK123',
            secretKey: 'secret'
        ])
        provider.initialize()
        return provider
    }
    
    private AwsS3Provider awsProvider() {
        def provider = new AwsS3Provider()
        provider.configure([
            region: 'us-east-1'
        ])
        provider.initialize()
        return provider
    }
    
    private AzureBlobProvider azureProvider() {
        def provider = new AzureBlobProvider()
        provider.configure([
            connectionString: System.getenv('AZURE_STORAGE_CONNECTION_STRING')
        ])
        provider.initialize()
        return provider
    }
    
    private GoogleCloudStorageProvider gcsProvider() {
        def provider = new GoogleCloudStorageProvider()
        provider.configure([
            projectId: 'my-project'
        ])
        provider.initialize()
        return provider
    }
}
