package org.softwood.dag.task.nosql

import spock.lang.Specification

/**
 * Tests for DynamoDbProvider configuration loading.
 */
class DynamoDbProviderConfigTest extends Specification {
    
    def "should load from config with defaults"() {
        when:
        def provider = DynamoDbProvider.fromConfig()
        
        then:
        provider != null
        provider.region == 'us-east-1'
        provider.connectionTimeout == 10000
        provider.requestTimeout == 30000
        provider.@initialized  // Auto-initialized
    }
    
    def "should allow config overrides for region"() {
        when:
        def provider = DynamoDbProvider.fromConfig([
            'database.dynamodb.region': 'us-west-2'
        ])
        
        then:
        provider.region == 'us-west-2'
        provider.@initialized
    }
    
    def "should allow config overrides for credentials"() {
        when:
        def provider = DynamoDbProvider.fromConfig([
            'database.dynamodb.accessKeyId': 'AKIATEST123',
            'database.dynamodb.secretAccessKey': 'secret123'
        ])
        
        then:
        provider.accessKeyId == 'AKIATEST123'
        provider.secretAccessKey == 'secret123'
        provider.@initialized
    }
    
    def "should allow config override for endpoint URL (DynamoDB Local)"() {
        when:
        def provider = DynamoDbProvider.fromConfig([
            'database.dynamodb.endpointUrl': 'http://localhost:8000'
        ])
        
        then:
        provider.endpointUrl == 'http://localhost:8000'
        provider.@initialized
    }
    
    def "should allow config overrides for timeouts"() {
        when:
        def provider = DynamoDbProvider.fromConfig([
            'database.dynamodb.connectionTimeout': 5000,
            'database.dynamodb.requestTimeout': 60000
        ])
        
        then:
        provider.connectionTimeout == 5000
        provider.requestTimeout == 60000
        provider.@initialized
    }
    
    def "should handle multiple config overrides"() {
        when:
        def provider = DynamoDbProvider.fromConfig([
            'database.dynamodb.region': 'eu-west-1',
            'database.dynamodb.endpointUrl': 'http://localhost:8000',
            'database.dynamodb.connectionTimeout': 15000
        ])
        
        then:
        provider.region == 'eu-west-1'
        provider.endpointUrl == 'http://localhost:8000'
        provider.connectionTimeout == 15000
        provider.@initialized
    }
    
    def "should work in stub mode after config loading"() {
        given:
        def provider = DynamoDbProvider.fromConfig()
        
        when:
        def result = provider.find("test-table", [:], null, null)
        
        then:
        result != null
        result.isEmpty()
    }
}
