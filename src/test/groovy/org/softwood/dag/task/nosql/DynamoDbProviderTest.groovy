package org.softwood.dag.task.nosql

import spock.lang.Specification

/**
 * Tests for DynamoDbProvider in stub mode (no AWS SDK required).
 * These tests verify the provider works without the AWS SDK dependency.
 */
class DynamoDbProviderTest extends Specification {
    
    def "should initialize in stub mode without AWS SDK"() {
        when:
        def provider = new DynamoDbProvider(
            region: "us-east-1"
        )
        provider.initialize()
        
        then:
        provider != null
        !provider.@sdkAvailable  // SDK not available
        provider.@initialized    // But still initialized
    }
    
    def "should return empty results for find in stub mode"() {
        given:
        def provider = new DynamoDbProvider(region: "us-east-1")
        provider.initialize()
        
        when:
        def result = provider.find("test-table", [:], null, null)
        
        then:
        result != null
        result.isEmpty()
    }
    
    def "should return empty results for find with key condition in stub mode"() {
        given:
        def provider = new DynamoDbProvider(region: "us-east-1")
        provider.initialize()
        
        when:
        def result = provider.find(
            "orders",
            [KeyConditionExpression: "userId = :uid"],
            null,
            null
        )
        
        then:
        result != null
        result.isEmpty()
    }
    
    def "should return null for insertOne in stub mode"() {
        given:
        def provider = new DynamoDbProvider(region: "us-east-1")
        provider.initialize()
        
        when:
        def result = provider.insertOne("test-table", [id: "123", name: "Test"])
        
        then:
        result == null
    }
    
    def "should return empty list for insertMany in stub mode"() {
        given:
        def provider = new DynamoDbProvider(region: "us-east-1")
        provider.initialize()
        
        when:
        def result = provider.insertMany("test-table", [
            [id: "1", name: "Item 1"],
            [id: "2", name: "Item 2"]
        ])
        
        then:
        result != null
        result.isEmpty()
    }
    
    def "should return 0 for update in stub mode"() {
        given:
        def provider = new DynamoDbProvider(region: "us-east-1")
        provider.initialize()
        
        when:
        def count = provider.update(
            "test-table",
            [Key: [id: "123"]],
            [name: "Updated"],
            null
        )
        
        then:
        count == 0
    }
    
    def "should return 0 for delete in stub mode"() {
        given:
        def provider = new DynamoDbProvider(region: "us-east-1")
        provider.initialize()
        
        when:
        def count = provider.delete("test-table", [Key: [id: "123"]])
        
        then:
        count == 0
    }
    
    def "should return empty list for aggregate in stub mode"() {
        given:
        def provider = new DynamoDbProvider(region: "us-east-1")
        provider.initialize()
        
        when:
        def result = provider.aggregate("test-table", [])
        
        then:
        result != null
        result.isEmpty()
    }
    
    def "should throw exception for execute in stub mode"() {
        given:
        def provider = new DynamoDbProvider(region: "us-east-1")
        provider.initialize()
        
        when:
        provider.execute { client -> client.listTables() }
        
        then:
        thrown(UnsupportedOperationException)
    }
    
    def "should throw exception for withTransaction in stub mode"() {
        given:
        def provider = new DynamoDbProvider(region: "us-east-1")
        provider.initialize()
        
        when:
        provider.withTransaction { }
        
        then:
        thrown(UnsupportedOperationException)
    }
    
    def "should return empty list for listCollections in stub mode"() {
        given:
        def provider = new DynamoDbProvider(region: "us-east-1")
        provider.initialize()
        
        when:
        def tables = provider.listCollections()
        
        then:
        tables != null
        tables.isEmpty()
    }
    
    def "should return null for getCollectionStats in stub mode"() {
        given:
        def provider = new DynamoDbProvider(region: "us-east-1")
        provider.initialize()
        
        when:
        def stats = provider.getCollectionStats("test-table")
        
        then:
        stats == null
    }
    
    def "should return empty list for listIndexes in stub mode"() {
        given:
        def provider = new DynamoDbProvider(region: "us-east-1")
        provider.initialize()
        
        when:
        def indexes = provider.listIndexes("test-table")
        
        then:
        indexes != null
        indexes.isEmpty()
    }
    
    def "should return null for getDatabaseStats in stub mode"() {
        given:
        def provider = new DynamoDbProvider(region: "us-east-1")
        provider.initialize()
        
        when:
        def stats = provider.getDatabaseStats()
        
        then:
        stats == null
    }
    
    def "should return server info in stub mode"() {
        given:
        def provider = new DynamoDbProvider(region: "us-east-1")
        provider.initialize()
        
        when:
        def info = provider.getServerInfo()
        
        then:
        info != null
        info.version == "AWS DynamoDB"
        info.gitVersion == "N/A"
    }
    
    def "should handle multiple initializations gracefully"() {
        given:
        def provider = new DynamoDbProvider(region: "us-east-1")
        
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
        def provider = new DynamoDbProvider(region: "us-east-1")
        provider.initialize()
        
        when:
        provider.close()
        
        then:
        noExceptionThrown()
        !provider.@initialized
    }
    
    def "should handle close before initialization"() {
        given:
        def provider = new DynamoDbProvider(region: "us-east-1")
        
        when:
        provider.close()
        
        then:
        noExceptionThrown()
    }
}
