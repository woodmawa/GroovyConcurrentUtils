package org.softwood.dag.task.nosql

import spock.lang.Specification

/**
 * Tests for CosmosDbProvider configuration loading.
 */
class CosmosDbProviderConfigTest extends Specification {
    
    def "should load from config with defaults"() {
        when:
        def provider = CosmosDbProvider.fromConfig([
            'database.cosmosdb.endpoint': 'https://test.documents.azure.com:443/',
            'database.cosmosdb.key': 'test-key',
            'database.cosmosdb.database': 'testdb'
        ])
        
        then:
        provider != null
        provider.endpoint == 'https://test.documents.azure.com:443/'
        provider.key == 'test-key'
        provider.database == 'testdb'
        provider.consistencyLevel == 'SESSION'
        provider.connectionMode == 'DIRECT'
        provider.maxRetryAttempts == 3
        provider.requestTimeout == 60
        provider.@initialized  // Auto-initialized
    }
    
    def "should allow config overrides for consistency level"() {
        when:
        def provider = CosmosDbProvider.fromConfig([
            'database.cosmosdb.endpoint': 'https://test.documents.azure.com:443/',
            'database.cosmosdb.key': 'test-key',
            'database.cosmosdb.database': 'testdb',
            'database.cosmosdb.consistencyLevel': 'STRONG'
        ])
        
        then:
        provider.consistencyLevel == 'STRONG'
        provider.@initialized
    }
    
    def "should allow config overrides for connection mode"() {
        when:
        def provider = CosmosDbProvider.fromConfig([
            'database.cosmosdb.endpoint': 'https://test.documents.azure.com:443/',
            'database.cosmosdb.key': 'test-key',
            'database.cosmosdb.database': 'testdb',
            'database.cosmosdb.connectionMode': 'GATEWAY'
        ])
        
        then:
        provider.connectionMode == 'GATEWAY'
        provider.@initialized
    }
    
    def "should allow config overrides for retry settings"() {
        when:
        def provider = CosmosDbProvider.fromConfig([
            'database.cosmosdb.endpoint': 'https://test.documents.azure.com:443/',
            'database.cosmosdb.key': 'test-key',
            'database.cosmosdb.database': 'testdb',
            'database.cosmosdb.maxRetryAttempts': 5,
            'database.cosmosdb.requestTimeout': 120
        ])
        
        then:
        provider.maxRetryAttempts == 5
        provider.requestTimeout == 120
        provider.@initialized
    }
    
    def "should handle multiple config overrides"() {
        when:
        def provider = CosmosDbProvider.fromConfig([
            'database.cosmosdb.endpoint': 'https://prod.documents.azure.com:443/',
            'database.cosmosdb.key': 'prod-key',
            'database.cosmosdb.database': 'production',
            'database.cosmosdb.consistencyLevel': 'BOUNDED_STALENESS',
            'database.cosmosdb.connectionMode': 'GATEWAY',
            'database.cosmosdb.maxRetryAttempts': 10
        ])
        
        then:
        provider.endpoint == 'https://prod.documents.azure.com:443/'
        provider.key == 'prod-key'
        provider.database == 'production'
        provider.consistencyLevel == 'BOUNDED_STALENESS'
        provider.connectionMode == 'GATEWAY'
        provider.maxRetryAttempts == 10
        provider.@initialized
    }
    
    def "should work in stub mode after config loading"() {
        given:
        def provider = CosmosDbProvider.fromConfig([
            'database.cosmosdb.endpoint': 'https://test.documents.azure.com:443/',
            'database.cosmosdb.key': 'test-key',
            'database.cosmosdb.database': 'testdb'
        ])
        
        when:
        def result = provider.find("test-collection", [:], null, null)
        
        then:
        result != null
        result.isEmpty()
    }
    
    def "should handle different consistency levels via config"() {
        when:
        def strong = CosmosDbProvider.fromConfig([
            'database.cosmosdb.endpoint': 'https://test.documents.azure.com:443/',
            'database.cosmosdb.key': 'test-key',
            'database.cosmosdb.database': 'testdb',
            'database.cosmosdb.consistencyLevel': 'STRONG'
        ])
        
        def eventual = CosmosDbProvider.fromConfig([
            'database.cosmosdb.endpoint': 'https://test.documents.azure.com:443/',
            'database.cosmosdb.key': 'test-key',
            'database.cosmosdb.database': 'testdb',
            'database.cosmosdb.consistencyLevel': 'EVENTUAL'
        ])
        
        then:
        strong.consistencyLevel == 'STRONG'
        eventual.consistencyLevel == 'EVENTUAL'
    }
}
