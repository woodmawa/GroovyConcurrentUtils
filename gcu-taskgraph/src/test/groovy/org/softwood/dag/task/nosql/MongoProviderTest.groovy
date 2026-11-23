package org.softwood.dag.task.nosql

import spock.lang.Specification

/**
 * Tests for MongoProvider in stub mode (no MongoDB driver required).
 * These tests verify the provider works without the MongoDB driver dependency.
 */
class MongoProviderTest extends Specification {
    
    def "should initialize in stub mode without MongoDB driver"() {
        when:
        def provider = new MongoProvider(
            connectionString: "mongodb://localhost:27017",
            database: "testdb"
        )
        provider.initialize()
        
        then:
        provider != null
        !provider.@mongoAvailable  // Driver not available
        provider.@initialized       // But still initialized
    }
    
    def "should return empty results for find in stub mode"() {
        given:
        def provider = new MongoProvider(
            connectionString: "mongodb://localhost:27017",
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
        def provider = new MongoProvider(
            connectionString: "mongodb://localhost:27017",
            database: "testdb"
        )
        provider.initialize()
        
        when:
        def result = provider.find(
            "users",
            [status: "active"],
            null,
            null
        )
        
        then:
        result != null
        result.isEmpty()
    }
    
    def "should return empty results for find with projection in stub mode"() {
        given:
        def provider = new MongoProvider(
            connectionString: "mongodb://localhost:27017",
            database: "testdb"
        )
        provider.initialize()
        
        when:
        def result = provider.find(
            "users",
            [:],
            [name: 1, email: 1],
            null
        )
        
        then:
        result != null
        result.isEmpty()
    }
    
    def "should return empty results for find with options in stub mode"() {
        given:
        def provider = new MongoProvider(
            connectionString: "mongodb://localhost:27017",
            database: "testdb"
        )
        provider.initialize()
        
        when:
        def result = provider.find(
            "products",
            [category: "electronics"],
            null,
            new QueryOptions(limit: 10, skip: 5)
        )
        
        then:
        result != null
        result.isEmpty()
    }
    
    def "should return null for insertOne in stub mode"() {
        given:
        def provider = new MongoProvider(
            connectionString: "mongodb://localhost:27017",
            database: "testdb"
        )
        provider.initialize()
        
        when:
        def result = provider.insertOne("test-collection", [name: "Test", value: 123])
        
        then:
        result == null
    }
    
    def "should return empty list for insertMany in stub mode"() {
        given:
        def provider = new MongoProvider(
            connectionString: "mongodb://localhost:27017",
            database: "testdb"
        )
        provider.initialize()
        
        when:
        def result = provider.insertMany("test-collection", [
            [name: "Item 1", value: 100],
            [name: "Item 2", value: 200]
        ])
        
        then:
        result != null
        result.isEmpty()
    }
    
    def "should return 0 for update in stub mode"() {
        given:
        def provider = new MongoProvider(
            connectionString: "mongodb://localhost:27017",
            database: "testdb"
        )
        provider.initialize()
        
        when:
        def count = provider.update(
            "test-collection",
            [_id: "123"],
            ['$set': [name: "Updated"]],
            null
        )
        
        then:
        count == 0
    }
    
    def "should return 0 for delete in stub mode"() {
        given:
        def provider = new MongoProvider(
            connectionString: "mongodb://localhost:27017",
            database: "testdb"
        )
        provider.initialize()
        
        when:
        def count = provider.delete("test-collection", [_id: "123"])
        
        then:
        count == 0
    }
    
    def "should return empty list for aggregate in stub mode"() {
        given:
        def provider = new MongoProvider(
            connectionString: "mongodb://localhost:27017",
            database: "testdb"
        )
        provider.initialize()
        
        when:
        def result = provider.aggregate("orders", [
            ['$match': [status: 'completed']],
            ['$group': [_id: '$customerId', total: ['$sum': '$amount']]]
        ])
        
        then:
        result != null
        result.isEmpty()
    }
    
    def "should throw exception for execute in stub mode"() {
        given:
        def provider = new MongoProvider(
            connectionString: "mongodb://localhost:27017",
            database: "testdb"
        )
        provider.initialize()
        
        when:
        provider.execute { db -> db.listCollectionNames() }
        
        then:
        thrown(UnsupportedOperationException)
    }
    
    def "should throw exception for withTransaction in stub mode"() {
        given:
        def provider = new MongoProvider(
            connectionString: "mongodb://localhost:27017",
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
        def provider = new MongoProvider(
            connectionString: "mongodb://localhost:27017",
            database: "testdb"
        )
        provider.initialize()
        
        when:
        def collections = provider.listCollections()
        
        then:
        collections != null
        collections.isEmpty()
    }
    
    def "should return null for getCollectionStats in stub mode"() {
        given:
        def provider = new MongoProvider(
            connectionString: "mongodb://localhost:27017",
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
        def provider = new MongoProvider(
            connectionString: "mongodb://localhost:27017",
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
        def provider = new MongoProvider(
            connectionString: "mongodb://localhost:27017",
            database: "testdb"
        )
        provider.initialize()
        
        when:
        def stats = provider.getDatabaseStats()
        
        then:
        stats == null
    }
    
    def "should return null for getServerInfo in stub mode"() {
        given:
        def provider = new MongoProvider(
            connectionString: "mongodb://localhost:27017",
            database: "testdb"
        )
        provider.initialize()
        
        when:
        def info = provider.getServerInfo()
        
        then:
        info == null
    }
    
    def "should use default connection string"() {
        when:
        def provider = new MongoProvider(database: "testdb")
        
        then:
        provider.connectionString == "mongodb://localhost:27017"
    }
    
    def "should use custom connection string"() {
        when:
        def provider = new MongoProvider(
            connectionString: "mongodb://myhost:27018",
            database: "mydb"
        )
        
        then:
        provider.connectionString == "mongodb://myhost:27018"
        provider.database == "mydb"
    }
    
    def "should handle multiple initializations gracefully"() {
        given:
        def provider = new MongoProvider(
            connectionString: "mongodb://localhost:27017",
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
        def provider = new MongoProvider(
            connectionString: "mongodb://localhost:27017",
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
        def provider = new MongoProvider(
            connectionString: "mongodb://localhost:27017",
            database: "testdb"
        )
        
        when:
        provider.close()
        
        then:
        noExceptionThrown()
    }
    
    def "should support MongoDB query operators in filter"() {
        given:
        def provider = new MongoProvider(
            connectionString: "mongodb://localhost:27017",
            database: "testdb"
        )
        provider.initialize()
        
        when:
        def result = provider.find(
            "products",
            [
                price: ['$gte': 100, '$lte': 500],
                category: ['$in': ['electronics', 'computers']]
            ],
            null,
            null
        )
        
        then:
        result != null
        result.isEmpty()
    }
    
    def "should support sort and limit in query options"() {
        given:
        def provider = new MongoProvider(
            connectionString: "mongodb://localhost:27017",
            database: "testdb"
        )
        provider.initialize()
        
        when:
        def result = provider.find(
            "users",
            [status: "active"],
            null,
            new QueryOptions(
                sort: [createdAt: -1],
                limit: 20
            )
        )
        
        then:
        result != null
        result.isEmpty()
    }
    
    def "should support update operators"() {
        given:
        def provider = new MongoProvider(
            connectionString: "mongodb://localhost:27017",
            database: "testdb"
        )
        provider.initialize()
        
        when:
        def count = provider.update(
            "users",
            [_id: "123"],
            [
                '$set': [lastLogin: new Date()],
                '$inc': [loginCount: 1]
            ],
            null
        )
        
        then:
        count == 0
    }
}
