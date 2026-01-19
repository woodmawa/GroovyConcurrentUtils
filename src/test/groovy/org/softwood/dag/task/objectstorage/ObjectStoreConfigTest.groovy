package org.softwood.dag.task.objectstorage

import org.softwood.dag.task.objectstorage.providers.*
import spock.lang.Ignore
import spock.lang.Specification

/**
 * Test configuration for all Object Storage providers.
 * 
 * These tests verify configuration validation WITHOUT requiring SDK dependencies.
 * Tests that call initialize() are @Ignore'd since they require actual SDKs.
 */
class ObjectStoreConfigTest extends Specification {
    
    // =========================================================================
    // MinIO Configuration Tests (No SDK Required)
    // =========================================================================
    
    def "MinIO provider should accept valid config"() {
        given:
        def provider = new MinIOProvider()
        
        when:
        provider.configure([
            endpoint: 'http://localhost:9000',
            accessKey: 'minioadmin',
            secretKey: 'minioadmin',
            region: 'us-east-1'
        ])
        
        then:
        provider.providerId == 'minio'
        provider.displayName == 'MinIO'
        !provider.initialized  // Not initialized, just configured
        provider.getConfigValue('endpoint', null) == 'http://localhost:9000'
        provider.getConfigValue('accessKey', null) == 'minioadmin'
        provider.getConfigValue('region', null) == 'us-east-1'
    }
    
    def "MinIO provider should fail without required endpoint"() {
        given:
        def provider = new MinIOProvider()
        
        when:
        provider.configure([
            accessKey: 'minioadmin',
            secretKey: 'minioadmin'
        ])
        
        then:
        def ex = thrown(Exception)
        ex.message.contains('endpoint')
    }
    
    def "MinIO provider should fail without credentials"() {
        given:
        def provider = new MinIOProvider()
        
        when:
        provider.configure([
            endpoint: 'http://localhost:9000'
        ])
        
        then:
        def ex = thrown(Exception)
        ex.message.contains('accessKey') || ex.message.contains('secretKey')
    }
    
    @Ignore("Requires io.minio:minio:8.5+ dependency for initialization")
    def "MinIO provider should initialize and set capabilities"() {
        given:
        def provider = new MinIOProvider()
        provider.configure([
            endpoint: 'http://localhost:9000',
            accessKey: 'minioadmin',
            secretKey: 'minioadmin'
        ])
        
        when:
        provider.initialize()
        
        then:
        provider.initialized
        provider.capabilities.supportsConditionalPut
        provider.capabilities.supportsVersionToken
        provider.capabilities.supportsObjectTags
        provider.capabilities.supportsRangeReads
    }
    
    // =========================================================================
    // Garage Configuration Tests (No SDK Required)
    // =========================================================================
    
    def "Garage provider should accept valid config"() {
        given:
        def provider = new GarageProvider()
        
        when:
        provider.configure([
            endpoint: 'http://localhost:3900',
            accessKey: 'GK1234567890',
            secretKey: 'secret1234567890',
            region: 'garage'
        ])
        
        then:
        provider.providerId == 'garage'
        provider.displayName == 'Garage'
        !provider.initialized
        provider.getConfigValue('endpoint', null) == 'http://localhost:3900'
    }
    
    def "Garage provider should use default region when not specified"() {
        given:
        def provider = new GarageProvider()
        
        when:
        provider.configure([
            endpoint: 'http://localhost:3900',
            accessKey: 'GK1234567890',
            secretKey: 'secret1234567890'
        ])
        
        then:
        notThrown(Exception)
        provider.getConfigValue('region', 'garage') == 'garage'
    }
    
    @Ignore("Requires io.minio:minio:8.5+ dependency for initialization")
    def "Garage provider should initialize with limited capabilities"() {
        given:
        def provider = new GarageProvider()
        provider.configure([
            endpoint: 'http://localhost:3900',
            accessKey: 'test',
            secretKey: 'test'
        ])
        
        when:
        provider.initialize()
        
        then:
        provider.initialized
        !provider.capabilities.supportsObjectTags
        !provider.capabilities.supportsServerSideEncryption
    }
    
    // =========================================================================
    // AWS S3 Configuration Tests (No SDK Required)
    // =========================================================================
    
    def "AWS S3 provider should accept config with credentials"() {
        given:
        def provider = new AwsS3Provider()
        
        when:
        provider.configure([
            region: 'us-east-1',
            accessKey: 'AKIAIOSFODNN7EXAMPLE',
            secretKey: 'wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY'
        ])
        
        then:
        provider.providerId == 'aws-s3'
        provider.displayName == 'AWS S3'
        !provider.initialized
        provider.getConfigValue('region', null) == 'us-east-1'
        provider.getConfigValue('accessKey', null) == 'AKIAIOSFODNN7EXAMPLE'
    }
    
    def "AWS S3 provider should accept config without credentials for default chain"() {
        given:
        def provider = new AwsS3Provider()
        
        when:
        provider.configure([
            region: 'us-west-2'
        ])
        
        then:
        notThrown(Exception)
        provider.getConfigValue('region', null) == 'us-west-2'
    }
    
    def "AWS S3 provider should accept custom endpoint config"() {
        given:
        def provider = new AwsS3Provider()
        
        when:
        provider.configure([
            region: 'us-east-1',
            endpoint: 'http://localhost:9000',
            accessKey: 'test',
            secretKey: 'test',
            pathStyleAccess: true
        ])
        
        then:
        notThrown(Exception)
        provider.getConfigValue('endpoint', null) == 'http://localhost:9000'
        provider.getConfigValue('pathStyleAccess', false) == true
    }
    
    def "AWS S3 provider should fail without region"() {
        given:
        def provider = new AwsS3Provider()
        
        when:
        provider.configure([:])
        
        then:
        def ex = thrown(Exception)
        ex.message.contains('region')
    }
    
    @Ignore("Requires software.amazon.awssdk:s3 dependency for initialization")
    def "AWS S3 provider should initialize with full capabilities"() {
        given:
        def provider = new AwsS3Provider()
        provider.configure([region: 'us-east-1'])
        
        when:
        provider.initialize()
        
        then:
        provider.initialized
        provider.capabilities.supportsConditionalPut
        provider.capabilities.supportsServerSideEncryption
    }
    
    // =========================================================================
    // Azure Blob Configuration Tests (No SDK Required)
    // =========================================================================
    
    def "Azure Blob provider should accept connection string"() {
        given:
        def provider = new AzureBlobProvider()
        
        when:
        provider.configure([
            connectionString: 'DefaultEndpointsProtocol=https;AccountName=myaccount;AccountKey=mykey;EndpointSuffix=core.windows.net'
        ])
        
        then:
        provider.providerId == 'azure-blob'
        provider.displayName == 'Azure Blob Storage'
        !provider.initialized
        provider.getConfigValue('connectionString', null) != null
    }
    
    def "Azure Blob provider should accept account credentials"() {
        given:
        def provider = new AzureBlobProvider()
        
        when:
        provider.configure([
            accountName: 'myaccount',
            accountKey: 'bXlrZXk='  // base64 encoded key
        ])
        
        then:
        notThrown(Exception)
        provider.getConfigValue('accountName', null) == 'myaccount'
        provider.getConfigValue('accountKey', null) == 'bXlrZXk='
    }
    
    def "Azure Blob provider should fail without credentials"() {
        given:
        def provider = new AzureBlobProvider()
        
        when:
        provider.configure([:])
        provider.validateConfig()
        
        then:
        thrown(Exception)
    }
    
    @Ignore("Requires com.azure:azure-storage-blob dependency for initialization")
    def "Azure Blob provider should initialize with versioning support"() {
        given:
        def provider = new AzureBlobProvider()
        provider.configure([
            accountName: 'test',
            accountKey: 'test'
        ])
        
        when:
        provider.initialize()
        
        then:
        provider.initialized
        provider.capabilities.supportsVersioning
    }
    
    // =========================================================================
    // Google Cloud Storage Configuration Tests (No SDK Required)
    // =========================================================================
    
    def "GCS provider should have correct identity"() {
        given:
        def provider = new GoogleCloudStorageProvider()
        
        expect:
        provider.providerId == 'gcs'
        provider.displayName == 'Google Cloud Storage'
    }
    
    def "GCS provider should accept config with credentials file"() {
        given:
        def provider = new GoogleCloudStorageProvider()
        
        when:
        provider.configure([
            projectId: 'my-gcp-project',
            credentialsJson: '/path/to/service-account.json'
        ])
        
        then:
        notThrown(Exception)
        provider.getConfigValue('projectId', null) == 'my-gcp-project'
        provider.getConfigValue('credentialsJson', null) == '/path/to/service-account.json'
    }
    
    def "GCS provider should accept config with inline credentials"() {
        given:
        def provider = new GoogleCloudStorageProvider()
        
        when:
        provider.configure([
            projectId: 'my-project',
            credentialsContent: '{ "type": "service_account" }'
        ])
        
        then:
        notThrown(Exception)
        provider.getConfigValue('credentialsContent', null) != null
    }
    
    def "GCS provider should accept config without explicit credentials for ADC"() {
        given:
        def provider = new GoogleCloudStorageProvider()
        
        when:
        provider.configure([
            projectId: 'my-project'
        ])
        
        then:
        notThrown(Exception)
    }
    
    def "GCS provider should fail without project ID"() {
        given:
        def provider = new GoogleCloudStorageProvider()
        
        when:
        provider.configure([:])
        provider.validateConfig()
        
        then:
        thrown(Exception)
    }
    
    @Ignore("Requires com.google.cloud:google-cloud-storage dependency for initialization")
    def "GCS provider should initialize with generation-based versioning"() {
        given:
        def provider = new GoogleCloudStorageProvider()
        provider.configure([projectId: 'test-project'])
        
        when:
        provider.initialize()
        
        then:
        provider.initialized
        provider.capabilities.supportsVersioning
        provider.capabilities.supportsVersionToken
    }
    
    // =========================================================================
    // Configuration Helper Tests (No SDK Required)
    // =========================================================================
    
    def "should get required config value"() {
        given:
        def provider = new MinIOProvider()
        provider.configure([
            endpoint: 'http://localhost:9000',
            accessKey: 'test',
            secretKey: 'test'
        ])
        
        expect:
        provider.getRequiredConfigValue('endpoint') == 'http://localhost:9000'
    }
    
    def "should get optional config value with default"() {
        given:
        def provider = new MinIOProvider()
        provider.configure([
            endpoint: 'http://localhost:9000',
            accessKey: 'test',
            secretKey: 'test'
        ])
        
        expect:
        provider.getConfigValue('region', 'us-east-1') == 'us-east-1'
        provider.getConfigValue('endpoint', 'default') == 'http://localhost:9000'
    }
    
    def "should throw exception for missing required config"() {
        given:
        def provider = new MinIOProvider()
        
        when:
        provider.configure([
            accessKey: 'test',
            secretKey: 'test'
            // Missing endpoint - will throw during configure()
        ])
        
        then:
        def ex = thrown(ObjectStoreException)
        ex.message.contains('endpoint')
        ex.message.contains('missing')
    }
    
    def "should handle custom config values"() {
        given:
        def provider = new MinIOProvider()
        provider.configure([
            endpoint: 'http://localhost:9000',
            accessKey: 'test',
            secretKey: 'test',
            customParam: 'custom-value',
            anotherParam: 12345
        ])
        
        expect:
        provider.getConfigValue('customParam', null) == 'custom-value'
        provider.getConfigValue('anotherParam', 0) == 12345
    }
    
    // =========================================================================
    // Provider State Tests (No SDK Required)
    // =========================================================================
    
    def "providers should start uninitialized"() {
        given:
        def providers = [
            new MinIOProvider(),
            new GarageProvider(),
            new AwsS3Provider(),
            new AzureBlobProvider(),
            new GoogleCloudStorageProvider()
        ]
        
        expect:
        providers.every { !it.initialized }
    }
    
    def "configuration should not change initialized state"() {
        given:
        def provider = new MinIOProvider()
        
        when:
        provider.configure([
            endpoint: 'http://localhost:9000',
            accessKey: 'test',
            secretKey: 'test'
        ])
        
        then:
        !provider.initialized
    }
    
    def "close should be safe on unconfigured provider"() {
        given:
        def provider = new MinIOProvider()
        
        when:
        provider.close()
        
        then:
        notThrown(Exception)
        !provider.initialized
    }
    
    def "close should be safe on configured but uninitialized provider"() {
        given:
        def provider = new MinIOProvider()
        provider.configure([
            endpoint: 'http://localhost:9000',
            accessKey: 'test',
            secretKey: 'test'
        ])
        
        when:
        provider.close()
        
        then:
        notThrown(Exception)
        !provider.initialized
    }
    
    def "providers should allow reconfiguration"() {
        given:
        def provider = new MinIOProvider()
        
        when:
        provider.configure([
            endpoint: 'http://localhost:9000',
            accessKey: 'key1',
            secretKey: 'secret1'
        ])
        
        then:
        provider.getConfigValue('accessKey', null) == 'key1'
        
        when:
        provider.configure([
            endpoint: 'http://localhost:9001',
            accessKey: 'key2',
            secretKey: 'secret2'
        ])
        
        then:
        provider.getConfigValue('accessKey', null) == 'key2'
        provider.getConfigValue('endpoint', null) == 'http://localhost:9001'
    }
    
    // =========================================================================
    // Cross-Provider Configuration Tests (No SDK Required)
    // =========================================================================
    
    def "all providers should have unique IDs"() {
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
        ids.toSet().size() == 5
        ids == ['minio', 'garage', 'aws-s3', 'azure-blob', 'gcs']
    }
    
    def "all providers should have display names"() {
        expect:
        new MinIOProvider().displayName == 'MinIO'
        new GarageProvider().displayName == 'Garage'
        new AwsS3Provider().displayName == 'AWS S3'
        new AzureBlobProvider().displayName == 'Azure Blob Storage'
        new GoogleCloudStorageProvider().displayName == 'Google Cloud Storage'
    }
    
    def "all providers should accept configuration without initializing"() {
        given:
        def testCases = [
            [provider: new MinIOProvider(), config: [endpoint: 'http://test', accessKey: 'k', secretKey: 's']],
            [provider: new GarageProvider(), config: [endpoint: 'http://test', accessKey: 'k', secretKey: 's']],
            [provider: new AwsS3Provider(), config: [region: 'us-east-1']],
            [provider: new AzureBlobProvider(), config: [connectionString: 'test']],
            [provider: new GoogleCloudStorageProvider(), config: [projectId: 'test']]
        ]
        
        expect:
        testCases.each { testCase ->
            testCase.provider.configure(testCase.config)
            assert !testCase.provider.initialized, "${testCase.provider.providerId} should not be initialized after configure()"
        }
    }
}
