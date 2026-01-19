package org.softwood.dag.task.nosql

import spock.lang.Specification

/**
 * Tests for CosmosDbProvider in stub mode (no Azure SDK required).
 * These tests verify the provider works without the Azure Cosmos SDK dependency.
 */
class CosmosDbProviderTest extends Specification {
    
    def "should initialize in stub mode without Azure SDK"() {
        when:
        def provider = new CosmosDbProvider(
            endpoint: "https://test.documents.azure.com:443/",
            key: "test-key",
            database: "testdb"
        )
        provider.initialize()
        
        then:
        provider != null
        !provider.@sdkAvailable  // SDK not available
        provider.@initialized    // But still initialized
    }
    
    def "should return empty results for find in stub mode"() {
        given:
        def provider = new CosmosDbProvider(
            endpoint: "https://test.documents.azure.com:443/",
            key: "test-key",
            database: "testdb"
        )
        provider.initialize()
        
        when:
        def result = provider.find("test-collection", [:], null, null)
        
        then:
        result != null
        result.isEmpty()
    }
    
    def "should return empty results for find with filter in stub mode"() {
        given:
        def provider = new CosmosDbProvider(
            endpoint: "https://test.documents.azure.com:443/",
            key: "test-key",
            database: "testdb"
        )
        provider.initialize()
        
        when:
        def result = provider.find(
            "products",
            [category: ['$eq': 'electronics']],
            null,
            null
        )
        
        then:
        result != null
        result.isEmpty()
    }
    
    def "should return null for insertOne in stub mode"() {
        given:
        def provider = new CosmosDbProvider(
            endpoint: "https://test.documents.azure.com:443/",
            key: "test-key",
            database: "testdb"
        )
        provider.initialize()
        
        when:
        def result = provider.insertOne("test-collection", [id: "123", name: "Test"])
        
        then:
        result == null
    }
    
    def "should return empty list for insertMany in stub mode"() {
        given:
        def provider = new CosmosDbProvider(
            endpoint: "https://test.documents.azure.com:443/",
            key: "test-key",
            database: "testdb"
        )
        provider.initialize()
        
        when:
        def result = provider.insertMany("test-collection", [
            [id: "1", name: "Item 1"],
            [id: "2", name: "Item 2"]
        ])
        
        then:
        result != null
        result.isEmpty()
    }
    
    def "should return 0 for update in stub mode"() {
        given:
        def provider = new CosmosDbProvider(
            endpoint: "https://test.documents.azure.com:443/",
            key: "test-key",
            database: "testdb"
        )
        provider.initialize()
        
        when:
        def count = provider.update(
            "test-collection",
            [id: "123"],
            [name: "Updated"],
            null
        )
        
        then:
        count == 0
    }
    
    def "should return 0 for delete in stub mode"() {
        given:
        def provider = new CosmosDbProvider(
            endpoint: "https://test.documents.azure.com:443/",
            key: "test-key",
            database: "testdb"
        )
        provider.initialize()
        
        when:
        def count = provider.delete("test-collection", [id: "123"])
        
        then:
        count == 0
    }
    
    def "should return empty list for aggregate in stub mode"() {
        given:
        def provider = new CosmosDbProvider(
            endpoint: "https://test.documents.azure.com:443/",
            key: "test-key",
            database: "testdb"
        )
        provider.initialize()
        
        when:
        def result = provider.aggregate("test-collection", [])
        
        then:
        result != null
        result.isEmpty()
    }
    
    def "should throw exception for execute in stub mode"() {
        given:
        def provider = new CosmosDbProvider(
            endpoint: "https://test.documents.azure.com:443/",
            key: "test-key",
            database: "testdb"
        )
        provider.initialize()
        
        when:
        provider.execute { client -> client.getDatabase("test") }
        
        then:
        thrown(UnsupportedOperationException)
    }
    
    def "should throw exception for withTransaction in stub mode"() {
        given:
        def provider = new CosmosDbProvider(
            endpoint: "https://test.documents.azure.com:443/",
            key: "test-key",
            database: "testdb"
        )
        provider.initialize()
        
        when:
        provider.withTransaction { }
        
        then:
        thrown(UnsupportedOperationException)
    }
    
    def "should return empty list for listCollections in stub mode"() {
        given:
        def provider = new CosmosDbProvider(
            endpoint: "https://test.documents.azure.com:443/",
            key: "test-key",
            database: "testdb"
        )
        provider.initialize()
        
        when:
        def containers = provider.listCollections()
        
        then:
        containers != null
        containers.isEmpty()
    }
    
    def "should return null for getCollectionStats in stub mode"() {
        given:
        def provider = new CosmosDbProvider(
            endpoint: "https://test.documents.azure.com:443/",
            key: "test-key",
            database: "testdb"
        )
        provider.initialize()
        
        when:
        def stats = provider.getCollectionStats("test-collection")
        
        then:
        stats == null
    }
    
    def "should return empty list for listIndexes in stub mode"() {
        given:
        def provider = new CosmosDbProvider(
            endpoint: "https://test.documents.azure.com:443/",
            key: "test-key",
            database: "testdb"
        )
        provider.initialize()
        
        when:
        def indexes = provider.listIndexes("test-collection")
        
        then:
        indexes != null
        indexes.isEmpty()
    }
    
    def "should return null for getDatabaseStats in stub mode"() {
        given:
        def provider = new CosmosDbProvider(
            endpoint: "https://test.documents.azure.com:443/",
            key: "test-key",
            database: "testdb"
        )
        provider.initialize()
        
        when:
        def stats = provider.getDatabaseStats()
        
        then:
        stats == null
    }
    
    def "should return server info in stub mode"() {
        given:
        def provider = new CosmosDbProvider(
            endpoint: "https://test.documents.azure.com:443/",
            key: "test-key",
            database: "testdb"
        )
        provider.initialize()
        
        when:
        def info = provider.getServerInfo()
        
        then:
        info != null
        info.version == "Azure Cosmos DB"
        info.gitVersion == "N/A"
    }
    
    def "should use default consistency level"() {
        when:
        def provider = new CosmosDbProvider(
            endpoint: "https://test.documents.azure.com:443/",
            key: "test-key",
            database: "testdb"
        )
        
        then:
        provider.consistencyLevel == "SESSION"
    }
    
    def "should use default connection mode"() {
        when:
        def provider = new CosmosDbProvider(
            endpoint: "https://test.documents.azure.com:443/",
            key: "test-key",
            database: "testdb"
        )
        
        then:
        provider.connectionMode == "DIRECT"
    }
    
    def "should handle multiple initializations gracefully"() {
        given:
        def provider = new CosmosDbProvider(
            endpoint: "https://test.documents.azure.com:443/",
            key: "test-key",
            database: "testdb"
        )
        
        when:
        provider.initialize()
        provider.initialize()  // Second call
        provider.initialize()  // Third call
        
        then:
        noExceptionThrown()
        provider.@initialized
    }
    
    def "should close without errors in stub mode"() {
        given:
        def provider = new CosmosDbProvider(
            endpoint: "https://test.documents.azure.com:443/",
            key: "test-key",
            database: "testdb"
        )
        provider.initialize()
        
        when:
        provider.close()
        
        then:
        noExceptionThrown()
        !provider.@initialized
    }
    
    def "should handle close before initialization"() {
        given:
        def provider = new CosmosDbProvider(
            endpoint: "https://test.documents.azure.com:443/",
            key: "test-key",
            database: "testdb"
        )
        
        when:
        provider.close()
        
        then:
        noExceptionThrown()
    }
}
