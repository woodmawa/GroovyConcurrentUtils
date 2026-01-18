package org.softwood.dag.task.nosql

import org.softwood.dag.TaskGraph
import spock.lang.Specification
import spock.lang.Ignore

/**
 * Tests for NoSQL metadata operations.
 * 
 * <p><strong>Note:</strong> These tests run in stub mode by default (no MongoDB required).
 * To test with real MongoDB, add the MongoDB driver dependency and use @Testcontainers.</p>
 * 
 * <p><strong>Stub Mode:</strong> MongoProvider returns empty results when MongoDB driver
 * is not available. Tests verify the DSL and method signatures work correctly.</p>
 */
class NoSqlMetadataTest extends Specification {
    
    MongoProvider provider
    
    def setup() {
        provider = new MongoProvider(
            connectionString: "mongodb://localhost:27017",
            databaseName: "testdb"
        )
        provider.initialize()
    }
    
    def cleanup() {
        provider?.close()
    }
    
    def "should list collections in stub mode"() {
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
    
    def "should get collection stats in stub mode"() {
        when:
        def graph = TaskGraph.build {
            noSqlTask("collection-stats") {
                provider this.provider
                metadata {
                    collectionStats("users")
                }
            }
        }
        def result = graph.run().get()
        
        then:
        // In stub mode, returns null or empty list
        result == null || result == [] || result instanceof NoSqlMetadata.CollectionStats
    }
    
    def "should list indexes in stub mode"() {
        when:
        def graph = TaskGraph.build {
            noSqlTask("list-indexes") {
                provider this.provider
                metadata {
                    indexes("users")
                }
            }
        }
        def result = graph.run().get()
        
        then:
        result != null
        result instanceof List
        // In stub mode, returns empty list
    }
    
    def "should get database stats in stub mode"() {
        when:
        def graph = TaskGraph.build {
            noSqlTask("db-stats") {
                provider this.provider
                metadata {
                    databaseStats()
                }
            }
        }
        def result = graph.run().get()
        
        then:
        // In stub mode, returns null or empty list
        result == null || result == [] || result instanceof NoSqlMetadata.DatabaseStats
    }
    
    def "should get server info in stub mode"() {
        when:
        def graph = TaskGraph.build {
            noSqlTask("server-info") {
                provider this.provider
                metadata {
                    serverInfo()
                }
            }
        }
        def result = graph.run().get()
        
        then:
        // In stub mode, returns null or empty list  
        result == null || result == [] || result instanceof NoSqlMetadata.ServerInfo
    }
    
    def "should handle metadata DSL with multiple operations"() {
        when:
        def collectionsGraph = TaskGraph.build {
            noSqlTask("collections") {
                provider this.provider
                metadata { collections() }
            }
        }
        
        def statsGraph = TaskGraph.build {
            noSqlTask("stats") {
                provider this.provider
                metadata { databaseStats() }
            }
        }
        
        def collectionsResult = collectionsGraph.run().get()
        def statsResult = statsGraph.run().get()
        
        then:
        collectionsResult instanceof List
        // statsResult may be null in stub mode
        noExceptionThrown()
    }
    
    def "should throw exception if operation not configured"() {
        when:
        def graph = TaskGraph.build {
            noSqlTask("empty-metadata") {
                provider this.provider
                metadata {
                    // No operation configured
                }
            }
        }
        graph.run().get()
        
        then:
        thrown(IllegalStateException)
    }
}

/**
 * Integration tests with real MongoDB.
 * 
 * <p>These tests are ignored by default. To run them:</p>
 * <ol>
 *   <li>Add MongoDB driver: org.mongodb:mongodb-driver-sync:4.11+</li>
 *   <li>Add Testcontainers: org.testcontainers:mongodb:1.19+</li>
 *   <li>Remove @Ignore annotations</li>
 * </ol>
 */
@Ignore("Requires MongoDB driver and Testcontainers")
class NoSqlMetadataIntegrationTest extends Specification {
    
    // NOTE: This requires Testcontainers dependency
    // @Container
    // static MongoDBContainer mongoContainer = new MongoDBContainer("mongo:7.0")
    
    MongoProvider provider
    
    def setup() {
        // Initialize with real MongoDB from testcontainer
        // provider = new MongoProvider(
        //     connectionString: mongoContainer.getReplicaSetUrl(),
        //     databaseName: "testdb"
        // )
        // provider.initialize()
        
        // Create test data
        // provider.insertOne("users", [name: "Alice", age: 30])
        // provider.insertOne("users", [name: "Bob", age: 25])
    }
    
    def cleanup() {
        provider?.close()
    }
    
    def "should list collections with real MongoDB"() {
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
        result.size() > 0
        result.any { it.name == "users" }
    }
    
    def "should get collection stats with real MongoDB"() {
        when:
        def graph = TaskGraph.build {
            noSqlTask("collection-stats") {
                provider this.provider
                metadata {
                    collectionStats("users")
                }
            }
        }
        def result = graph.run().get()
        
        then:
        result != null
        result.name == "users"
        result.count == 2
        result.size > 0
    }
    
    def "should list indexes with real MongoDB"() {
        when:
        def graph = TaskGraph.build {
            noSqlTask("list-indexes") {
                provider this.provider
                metadata {
                    indexes("users")
                }
            }
        }
        def result = graph.run().get()
        
        then:
        result != null
        result instanceof List
        result.size() > 0
        result.any { it.name == "_id_" }  // Default _id index
    }
    
    def "should get database stats with real MongoDB"() {
        when:
        def graph = TaskGraph.build {
            noSqlTask("db-stats") {
                provider this.provider
                metadata {
                    databaseStats()
                }
            }
        }
        def result = graph.run().get()
        
        then:
        result != null
        result.db == "testdb"
        result.collections > 0
        result.objects > 0
    }
    
    def "should get server info with real MongoDB"() {
        when:
        def graph = TaskGraph.build {
            noSqlTask("server-info") {
                provider this.provider
                metadata {
                    serverInfo()
                }
            }
        }
        def result = graph.run().get()
        
        then:
        result != null
        result.version != null
        result.version.startsWith("7.")
        result.maxBsonObjectSize == 16777216
    }
}
