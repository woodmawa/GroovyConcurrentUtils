package org.softwood.dag.task.objectstorage

import org.softwood.dag.task.objectstorage.providers.*
import spock.lang.Specification

/**
 * Unit tests for Object Storage Providers WITHOUT requiring external SDKs.
 * 
 * These tests verify provider behavior, configuration, validation, and error handling
 * without actually initializing the providers (which would require SDK dependencies).
 * 
 * Tests cover:
 * - Provider identity and metadata
 * - Configuration validation
 * - Error messages
 * - Configuration helpers
 * - State management
 * - Capability flags
 */
class ObjectStoreProviderUnitTest extends Specification {
    
    // =========================================================================
    // Provider Identity Tests (No SDKs Required)
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
        ids.toSet().size() == 5  // All unique
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
    
    def "all providers should start uninitialized"() {
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
    
    // =========================================================================
    // MinIO Provider Unit Tests
    // =========================================================================
    
    def "MinIO should reject configuration without endpoint"() {
        given:
        def provider = new MinIOProvider()
        
        when:
        provider.configure([
            accessKey: 'test',
            secretKey: 'test'
        ])
        
        then:
        def ex = thrown(ObjectStoreException)
        ex.message.contains('endpoint')
        ex.message.contains('missing')
    }
    
    def "MinIO should reject configuration without accessKey"() {
        given:
        def provider = new MinIOProvider()
        
        when:
        provider.configure([
            endpoint: 'http://localhost:9000',
            secretKey: 'test'
        ])
        
        then:
        def ex = thrown(ObjectStoreException)
        ex.message.contains('accessKey')
    }
    
    def "MinIO should reject configuration without secretKey"() {
        given:
        def provider = new MinIOProvider()
        
        when:
        provider.configure([
            endpoint: 'http://localhost:9000',
            accessKey: 'test'
        ])
        
        then:
        def ex = thrown(ObjectStoreException)
        ex.message.contains('secretKey')
    }
    
    def "MinIO should accept valid configuration without initialization"() {
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
        notThrown(Exception)
        !provider.initialized  // Not initialized yet
        provider.providerId == 'minio'
    }
    
    def "MinIO should provide helpful error when SDK missing"() {
        given:
        def provider = new MinIOProvider()
        provider.configure([
            endpoint: 'http://localhost:9000',
            accessKey: 'test',
            secretKey: 'test'
        ])
        
        when:
        provider.initialize()
        
        then:
        def ex = thrown(ObjectStoreException)
        ex.message.contains('MinIO client library not found')
        ex.message.contains('io.minio:minio:8.5+')
        ex.cause instanceof ClassNotFoundException
    }
    
    def "MinIO should support optional region config"() {
        given:
        def provider = new MinIOProvider()
        provider.configure([
            endpoint: 'http://localhost:9000',
            accessKey: 'test',
            secretKey: 'test',
            region: 'custom-region'
        ])
        
        expect:
        provider.getConfigValue('region', 'us-east-1') == 'custom-region'
    }
    
    // =========================================================================
    // Garage Provider Unit Tests
    // =========================================================================
    
    def "Garage should reject configuration without endpoint"() {
        given:
        def provider = new GarageProvider()
        
        when:
        provider.configure([
            accessKey: 'GK123',
            secretKey: 'secret'
        ])
        
        then:
        def ex = thrown(ObjectStoreException)
        ex.message.contains('endpoint')
    }
    
    def "Garage should accept valid configuration"() {
        given:
        def provider = new GarageProvider()
        
        when:
        provider.configure([
            endpoint: 'http://localhost:3900',
            accessKey: 'GK123',
            secretKey: 'secret'
        ])
        
        then:
        notThrown(Exception)
        !provider.initialized
        provider.providerId == 'garage'
    }
    
    def "Garage should provide helpful error mentioning S3 compatibility"() {
        given:
        def provider = new GarageProvider()
        provider.configure([
            endpoint: 'http://localhost:3900',
            accessKey: 'GK123',
            secretKey: 'secret'
        ])
        
        when:
        provider.initialize()
        
        then:
        def ex = thrown(ObjectStoreException)
        ex.message.contains('MinIO client library not found')
        ex.message.contains('required for Garage S3 compatibility')
        ex.message.contains('io.minio:minio:8.5+')
    }
    
    // =========================================================================
    // AWS S3 Provider Unit Tests
    // =========================================================================
    
    def "AWS S3 should reject configuration without region"() {
        given:
        def provider = new AwsS3Provider()
        
        when:
        provider.configure([:])
        
        then:
        def ex = thrown(ObjectStoreException)
        ex.message.contains('region')
        ex.message.contains('missing')
    }
    
    def "AWS S3 should accept configuration with just region"() {
        given:
        def provider = new AwsS3Provider()
        
        when:
        provider.configure([
            region: 'us-east-1'
        ])
        
        then:
        notThrown(Exception)
        !provider.initialized
        provider.providerId == 'aws-s3'
    }
    
    def "AWS S3 should accept configuration with credentials"() {
        given:
        def provider = new AwsS3Provider()
        
        when:
        provider.configure([
            region: 'us-west-2',
            accessKey: 'AKIAIOSFODNN7EXAMPLE',
            secretKey: 'wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY'
        ])
        
        then:
        notThrown(Exception)
        provider.getConfigValue('region', null) == 'us-west-2'
    }
    
    def "AWS S3 should provide helpful error when SDK missing"() {
        given:
        def provider = new AwsS3Provider()
        provider.configure([region: 'us-east-1'])
        
        when:
        provider.initialize()
        
        then:
        def ex = thrown(ObjectStoreException)
        ex.message.contains('AWS SDK S3 client not found')
        ex.message.contains('software.amazon.awssdk:s3:2.20+')
    }
    
    // =========================================================================
    // Azure Blob Provider Unit Tests
    // =========================================================================
    
    def "Azure should reject configuration without credentials"() {
        given:
        def provider = new AzureBlobProvider()
        
        when:
        provider.configure([:])
        provider.validateConfig()
        
        then:
        thrown(ObjectStoreException)
    }
    
    def "Azure should accept connectionString"() {
        given:
        def provider = new AzureBlobProvider()
        
        when:
        provider.configure([
            connectionString: 'DefaultEndpointsProtocol=https;AccountName=test;AccountKey=key;EndpointSuffix=core.windows.net'
        ])
        
        then:
        notThrown(Exception)
        provider.providerId == 'azure-blob'
    }
    
    def "Azure should accept accountName and accountKey"() {
        given:
        def provider = new AzureBlobProvider()
        
        when:
        provider.configure([
            accountName: 'myaccount',
            accountKey: 'bXlrZXk='
        ])
        
        then:
        notThrown(Exception)
    }
    
    def "Azure should provide helpful error when SDK missing"() {
        given:
        def provider = new AzureBlobProvider()
        provider.configure([
            connectionString: 'test'
        ])
        
        when:
        provider.initialize()
        
        then:
        def ex = thrown(ObjectStoreException)
        ex.message.contains('Azure Blob Storage SDK not found')
        ex.message.contains('com.azure:azure-storage-blob:12.20+')
    }
    
    // =========================================================================
    // Google Cloud Storage Provider Unit Tests
    // =========================================================================
    
    def "GCS should reject configuration without projectId"() {
        given:
        def provider = new GoogleCloudStorageProvider()
        
        when:
        provider.configure([:])
        provider.validateConfig()
        
        then:
        thrown(ObjectStoreException)
    }
    
    def "GCS should accept configuration with just projectId"() {
        given:
        def provider = new GoogleCloudStorageProvider()
        
        when:
        provider.configure([
            projectId: 'my-gcp-project'
        ])
        
        then:
        notThrown(Exception)
        provider.providerId == 'gcs'
    }
    
    def "GCS should accept credentialsJson path"() {
        given:
        def provider = new GoogleCloudStorageProvider()
        
        when:
        provider.configure([
            projectId: 'my-project',
            credentialsJson: '/path/to/credentials.json'
        ])
        
        then:
        notThrown(Exception)
    }
    
    def "GCS should provide helpful error when SDK missing"() {
        given:
        def provider = new GoogleCloudStorageProvider()
        provider.configure([
            projectId: 'test-project'
        ])
        
        when:
        provider.initialize()
        
        then:
        def ex = thrown(ObjectStoreException)
        ex.message.contains('Google Cloud Storage SDK not found')
        ex.message.contains('com.google.cloud:google-cloud-storage:2.15+')
    }
    
    // =========================================================================
    // Configuration Helper Tests
    // =========================================================================
    
    def "providers should support getConfigValue with default"() {
        given:
        def provider = new MinIOProvider()
        provider.configure([
            endpoint: 'http://localhost:9000',
            accessKey: 'test',
            secretKey: 'test',
            customOption: 'custom-value'
        ])
        
        expect:
        provider.getConfigValue('endpoint', 'default') == 'http://localhost:9000'
        provider.getConfigValue('customOption', 'default') == 'custom-value'
        provider.getConfigValue('nonexistent', 'default-val') == 'default-val'
    }
    
    def "providers should support getRequiredConfigValue"() {
        given:
        def provider = new MinIOProvider()
        provider.configure([
            endpoint: 'http://localhost:9000',
            accessKey: 'test',
            secretKey: 'test'
        ])
        
        expect:
        provider.getRequiredConfigValue('endpoint') == 'http://localhost:9000'
        provider.getRequiredConfigValue('accessKey') == 'test'
    }
    
    def "providers should throw on missing required config"() {
        given:
        def provider = new MinIOProvider()
        provider.configure([
            endpoint: 'http://localhost:9000',
            accessKey: 'test',
            secretKey: 'test'
        ])
        
        when:
        provider.getRequiredConfigValue('nonexistent')
        
        then:
        def ex = thrown(ObjectStoreException)
        ex.message.contains('nonexistent')
        ex.message.contains('missing')
    }
    
    // =========================================================================
    // State Management Tests
    // =========================================================================
    
    def "providers should track initialized state"() {
        given:
        def provider = new MinIOProvider()
        
        expect:
        !provider.initialized
        
        when:
        provider.configure([
            endpoint: 'http://localhost:9000',
            accessKey: 'test',
            secretKey: 'test'
        ])
        
        then:
        !provider.initialized  // Still not initialized
    }
    
    def "close should reset initialized state"() {
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
        !provider.initialized
    }
    
    def "close should be idempotent"() {
        given:
        def provider = new MinIOProvider()
        provider.configure([
            endpoint: 'http://localhost:9000',
            accessKey: 'test',
            secretKey: 'test'
        ])
        
        when:
        provider.close()
        provider.close()
        provider.close()
        
        then:
        notThrown(Exception)
    }
    
    // =========================================================================
    // Error Message Quality Tests
    // =========================================================================
    
    def "all providers should give helpful SDK-missing errors"() {
        given:
        def testCases = [
            [provider: new MinIOProvider(), config: [endpoint: 'http://test', accessKey: 'k', secretKey: 's'], expectedLib: 'io.minio:minio'],
            [provider: new GarageProvider(), config: [endpoint: 'http://test', accessKey: 'k', secretKey: 's'], expectedLib: 'io.minio:minio'],
            [provider: new AwsS3Provider(), config: [region: 'us-east-1'], expectedLib: 'software.amazon.awssdk:s3'],
            [provider: new AzureBlobProvider(), config: [connectionString: 'test'], expectedLib: 'com.azure:azure-storage-blob'],
            [provider: new GoogleCloudStorageProvider(), config: [projectId: 'test'], expectedLib: 'com.google.cloud:google-cloud-storage']
        ]
        
        expect:
        testCases.each { testCase ->
            testCase.provider.configure(testCase.config)
            try {
                testCase.provider.initialize()
                assert false, "Should have thrown exception for ${testCase.provider.providerId}"
            } catch (ObjectStoreException ex) {
                assert ex.message.toLowerCase().contains('not found') || ex.message.toLowerCase().contains('sdk')
                assert ex.message.contains(testCase.expectedLib)
                assert ex.cause instanceof ClassNotFoundException
            }
        }
    }
    
    def "validation errors should mention the provider"() {
        given:
        def provider = new MinIOProvider()
        
        when:
        provider.configure([accessKey: 'test', secretKey: 'test'])
        
        then:
        def ex = thrown(ObjectStoreException)
        ex.message.contains('minio')
    }
    
    // =========================================================================
    // Multiple Configuration Tests
    // =========================================================================
    
    def "providers should allow reconfiguration"() {
        given:
        def provider = new MinIOProvider()
        
        when:
        provider.configure([
            endpoint: 'http://localhost:9000',
            accessKey: 'test1',
            secretKey: 'secret1'
        ])
        
        then:
        provider.getConfigValue('accessKey', null) == 'test1'
        
        when:
        provider.configure([
            endpoint: 'http://localhost:9001',
            accessKey: 'test2',
            secretKey: 'secret2'
        ])
        
        then:
        provider.getConfigValue('accessKey', null) == 'test2'
        provider.getConfigValue('endpoint', null) == 'http://localhost:9001'
    }
    
    // =========================================================================
    // Provider-Specific Configuration Tests
    // =========================================================================
    
    def "MinIO should support region configuration"() {
        given:
        def provider = new MinIOProvider()
        
        when:
        provider.configure([
            endpoint: 'http://localhost:9000',
            accessKey: 'test',
            secretKey: 'test',
            region: 'eu-west-1'
        ])
        
        then:
        provider.getConfigValue('region', 'us-east-1') == 'eu-west-1'
    }
    
    def "AWS S3 should support optional credentials"() {
        given:
        def provider = new AwsS3Provider()
        
        when: "configure without credentials (uses default chain)"
        provider.configure([region: 'us-east-1'])
        
        then:
        notThrown(Exception)
        
        when: "configure with explicit credentials"
        provider.configure([
            region: 'us-east-1',
            accessKey: 'AKIATEST',
            secretKey: 'secret'
        ])
        
        then:
        notThrown(Exception)
        provider.getConfigValue('accessKey', null) == 'AKIATEST'
    }
    
    def "Azure should support both connectionString and account credentials"() {
        given:
        def provider = new AzureBlobProvider()
        
        when: "configure with connection string"
        provider.configure([
            connectionString: 'DefaultEndpointsProtocol=https;AccountName=test;AccountKey=key'
        ])
        
        then:
        notThrown(Exception)
        
        when: "configure with account credentials"
        def provider2 = new AzureBlobProvider()
        provider2.configure([
            accountName: 'testaccount',
            accountKey: 'testkey'
        ])
        
        then:
        notThrown(Exception)
    }
    
    def "GCS should support optional credentials"() {
        given:
        def provider = new GoogleCloudStorageProvider()
        
        when: "configure without credentials (uses ADC)"
        provider.configure([projectId: 'my-project'])
        
        then:
        notThrown(Exception)
        
        when: "configure with credentials file"
        provider.configure([
            projectId: 'my-project',
            credentialsJson: '/path/to/creds.json'
        ])
        
        then:
        notThrown(Exception)
        provider.getConfigValue('credentialsJson', null) == '/path/to/creds.json'
    }
}
