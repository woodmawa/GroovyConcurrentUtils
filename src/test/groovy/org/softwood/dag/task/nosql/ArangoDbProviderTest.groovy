package org.softwood.dag.task.nosql

import org.softwood.dag.TaskGraph
import spock.lang.Specification
import spock.lang.Ignore
import spock.lang.Shared

/**
 * Tests for ArangoDB provider.
 * 
 * <p><strong>Note:</strong> These tests run in stub mode by default (no ArangoDB required).</p>
 */
class ArangoDbProviderTest extends Specification {
    
    // Use @Shared to create provider once for all tests instead of per-test
    @Shared
    ArangoDbProvider provider
    
    def setupSpec() {
        provider = new ArangoDbProvider(
            host: "localhost",
            port: 8529,
            database: "testdb",
            username: "root",
            password: "testpass"
        )
        provider.initialize()
    }
    
    def cleanupSpec() {
        provider?.close()
    }
    
    def "should initialize in stub mode without ArangoDB driver"() {
        expect:
        provider != null
    }
    
    def "should return empty results for find in stub mode"() {
        when:
        def result = provider.find(
            "users",
            [age: ['$gt': 18]],
            [name: 1, email: 1],
            new QueryOptions(limit: 10)
        )
        
        then:
        result != null
        result instanceof List
        result.isEmpty()
    }
    
    def "should return empty results for aggregate in stub mode"() {
        when:
        def pipeline = [
            ['$match': [status: 'active']],
            ['$group': [_id: '$city', count: ['$sum': 1]]]
        ]
        def result = provider.aggregate("users", pipeline)
        
        then:
        result != null
        result instanceof List
        result.isEmpty()
    }
    
    def "should return null for insertOne in stub mode"() {
        when:
        def id = provider.insertOne("users", [name: "Alice", age: 30])
        
        then:
        id == null
    }
    
    def "should return empty list for insertMany in stub mode"() {
        when:
        def docs = [
            [name: "Alice", age: 30],
            [name: "Bob", age: 25]
        ]
        def ids = provider.insertMany("users", docs)
        
        then:
        ids != null
        ids.isEmpty()
    }
    
    def "should return 0 for update in stub mode"() {
        when:
        def count = provider.update(
            "users",
            [age: ['$lt': 18]],
            [status: "minor"],
            new UpdateOptions(multi: true)
        )
        
        then:
        count == 0
    }
    
    def "should return 0 for delete in stub mode"() {
        when:
        def count = provider.delete("users", [status: "inactive"])
        
        then:
        count == 0
    }
    
    def "should return empty list for listCollections in stub mode"() {
        when:
        def collections = provider.listCollections()
        
        then:
        collections != null
        collections.isEmpty()
    }
    
    def "should return null for getCollectionStats in stub mode"() {
        when:
        def stats = provider.getCollectionStats("users")
        
        then:
        stats == null
    }
    
    def "should return empty list for listIndexes in stub mode"() {
        when:
        def indexes = provider.listIndexes("users")
        
        then:
        indexes != null
        indexes.isEmpty()
    }
    
    def "should return null for getDatabaseStats in stub mode"() {
        when:
        def stats = provider.getDatabaseStats()
        
        then:
        stats == null
    }
    
    def "should return null for getServerInfo in stub mode"() {
        when:
        def info = provider.getServerInfo()
        
        then:
        info == null
    }
    
    def "should work with NoSqlTask find in stub mode"() {
        when:
        def graph = TaskGraph.build {
            noSqlTask("find-users") {
                provider this.provider
                find "users"  // Collection name passed directly to find
                filter age: ['$gt': 18]
                limit 10
            }
        }
        def result = graph.run().get()
        
        then:
        result != null
        result instanceof List
        // In stub mode, returns empty list
    }
    
    def "should work with NoSqlTask aggregate in stub mode"() {
        when:
        def graph = TaskGraph.build {
            noSqlTask("aggregate-users") {
                provider this.provider
                aggregate "users", [
                    ['$match': [status: 'active']],
                    ['$group': [_id: '$city', count: ['$sum': 1]]]
                ]
            }
        }
        def result = graph.run().get()
        
        then:
        result != null
        result instanceof List
        // In stub mode, returns empty list
    }
    
    def "should work with NoSqlTask metadata in stub mode"() {
        when:
        def graph = TaskGraph.build {
            noSqlTask("list-collections") {
                provider this.provider
                metadata {
                    collections()
                }
            }
        }
        def result = graph.run().get()
        
        then:
        result != null
        result instanceof List
        // In stub mode, returns empty list
    }
    
    def "should work with NoSqlTask criteria DSL in stub mode"() {
        when:
        def graph = TaskGraph.build {
            noSqlTask("criteria-query") {
                provider this.provider
                criteria {
                    from "users"
                    select "name", "email"
                    where {
                        gt "age", 18
                        eq "status", "active"
                    }
                    orderByDesc "createdAt"
                    limit 100
                }
            }
        }
        def result = graph.run().get()
        
        then:
        result != null
        result instanceof List
        // In stub mode, returns empty list
    }
}

/**
 * Integration tests with real ArangoDB.
 * 
 * <p>These tests are ignored by default. To run them:</p>
 * <ol>
 *   <li>Add ArangoDB driver: com.arangodb:arangodb-java-driver:7.2+</li>
 *   <li>Add Testcontainers: org.testcontainers:testcontainers:1.19+</li>
 *   <li>Remove @Ignore annotations</li>
 * </ol>
 */
@Ignore("Requires ArangoDB driver and Testcontainers")
class ArangoDbProviderIntegrationTest extends Specification {
    
    // NOTE: This requires Testcontainers dependency
    // @Container
    // static GenericContainer arangoContainer = new GenericContainer("arangodb:3.11")
    //     .withExposedPorts(8529)
    //     .withEnv("ARANGO_ROOT_PASSWORD", "testpass")
    
    ArangoDbProvider provider
    
    def setup() {
        // Initialize with real ArangoDB from testcontainer
        // provider = new ArangoDbProvider(
        //     host: arangoContainer.getHost(),
        //     port: arangoContainer.getMappedPort(8529),
        //     database: "_system",
        //     username: "root",
        //     password: "testpass"
        // )
        // provider.initialize()
        
        // Create test collection
        // provider.execute { db ->
        //     if (!db.collection("users").exists()) {
        //         db.createCollection("users")
        //     }
        // }
        
        // Insert test data
        // provider.insertOne("users", [name: "Alice", age: 30, status: "active", city: "NYC"])
        // provider.insertOne("users", [name: "Bob", age: 25, status: "active", city: "LA"])
        // provider.insertOne("users", [name: "Charlie", age: 17, status: "inactive", city: "NYC"])
    }
    
    def cleanup() {
        provider?.close()
    }
    
    def "should connect to real ArangoDB"() {
        expect:
        provider != null
    }
    
    def "should execute find with real ArangoDB"() {
        when:
        def users = provider.find(
            "users",
            [age: ['$gt': 20]],
            null,
            new QueryOptions(limit: 10)
        )
        
        then:
        users != null
        users.size() == 2
        users.every { it.age > 20 }
    }
    
    def "should execute aggregate with real ArangoDB"() {
        when:
        def pipeline = [
            ['$match': [status: 'active']],
            ['$group': [_id: '$city', count: ['$sum': 1]]]
        ]
        def result = provider.aggregate("users", pipeline)
        
        then:
        result != null
        result.size() == 2  // NYC and LA
    }
    
    def "should insert documents with real ArangoDB"() {
        when:
        def id = provider.insertOne("users", [name: "David", age: 35, status: "active"])
        
        then:
        id != null
        
        when:
        def user = provider.find("users", [name: "David"], null, null)
        
        then:
        user.size() == 1
        user[0].age == 35
    }
    
    def "should update documents with real ArangoDB"() {
        when:
        def count = provider.update(
            "users",
            [age: ['$lt': 18]],
            [status: "minor"],
            new UpdateOptions(multi: true)
        )
        
        then:
        count == 1  // Charlie
        
        when:
        def charlie = provider.find("users", [name: "Charlie"], null, null)
        
        then:
        charlie[0].status == "minor"
    }
    
    def "should delete documents with real ArangoDB"() {
        when:
        def count = provider.delete("users", [status: "inactive"])
        
        then:
        count == 1
        
        when:
        def remaining = provider.find("users", [:], null, null)
        
        then:
        remaining.size() == 2  // Only active users remain
    }
    
    def "should retrieve collections metadata with real ArangoDB"() {
        when:
        def collections = provider.listCollections()
        
        then:
        collections != null
        collections.any { it.name == "users" }
    }
    
    def "should retrieve collection stats with real ArangoDB"() {
        when:
        def stats = provider.getCollectionStats("users")
        
        then:
        stats != null
        stats.name == "users"
        stats.count == 3
    }
    
    def "should retrieve indexes with real ArangoDB"() {
        when:
        def indexes = provider.listIndexes("users")
        
        then:
        indexes != null
        indexes.size() > 0
        indexes.any { it.name.contains("primary") }
    }
    
    def "should retrieve database stats with real ArangoDB"() {
        when:
        def stats = provider.getDatabaseStats()
        
        then:
        stats != null
        stats.name == "_system"
        stats.collections > 0
    }
    
    def "should retrieve server info with real ArangoDB"() {
        when:
        def info = provider.getServerInfo()
        
        then:
        info != null
        info.version.startsWith("3.")
        info.serverType == "ArangoDB"
    }
    
    def "should work with criteria DSL and real ArangoDB"() {
        when:
        def graph = TaskGraph.build {
            noSqlTask("criteria-query") {
                provider this.provider
                criteria {
                    from "users"
                    select "name", "age"
                    where {
                        gt "age", 18
                        eq "status", "active"
                    }
                    orderByDesc "age"
                    limit 10
                }
            }
        }
        def result = graph.run().get()
        
        then:
        result != null
        result.size() == 2
        result[0].name == "Alice"  // Ordered by age desc
        result[1].name == "Bob"
    }
}
