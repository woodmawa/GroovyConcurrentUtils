package org.softwood.dag.task

import org.softwood.dag.TaskGraph
import org.softwood.dag.task.sql.PostgreSqlProvider
import org.softwood.dag.task.nosql.MongoProvider
import org.softwood.dag.task.nosql.ArangoDbProvider
import spock.lang.Specification

/**
 * Test that DSL supports static factory methods for database providers.
 */
class DatabaseProviderDslTest extends Specification {
    
    def "SqlTask DSL should support PostgreSqlProvider.fromConfig()"() {
        when:
        def graph = TaskGraph.build {
            sqlTask("test-postgres") {
                // Use static factory method - config loaded automatically
                provider PostgreSqlProvider.fromConfig()
                query "SELECT * FROM users"
            }
        }
        def result = graph.run().get()
        
        then:
        result != null
        result instanceof List
        // In stub mode (no driver), returns empty list
    }
    
    def "SqlTask DSL should support PostgreSqlProvider.fromConfig() with overrides"() {
        when:
        def graph = TaskGraph.build {
            sqlTask("test-postgres-custom") {
                // Override specific config values
                provider PostgreSqlProvider.fromConfig([
                    'database.postgres.database': 'customdb',
                    'database.postgres.schema': 'myschema'
                ])
                query "SELECT * FROM users"
            }
        }
        
        then:
        notThrown(Exception)
    }
    
    def "NoSqlTask DSL should support MongoProvider.fromConfig()"() {
        when:
        def graph = TaskGraph.build {
            noSqlTask("test-mongo") {
                // Use static factory method
                provider MongoProvider.fromConfig()
                find "users"
                filter status: "active"
            }
        }
        def result = graph.run().get()
        
        then:
        result != null
        result instanceof List
        // In stub mode (no driver), returns empty list
    }
    
    def "NoSqlTask DSL should support ArangoDbProvider.fromConfig()"() {
        when:
        def graph = TaskGraph.build {
            noSqlTask("test-arango") {
                // Use static factory method
                provider ArangoDbProvider.fromConfig()
                find "users"
                filter age: ['$gt': 18]
            }
        }
        def result = graph.run().get()
        
        then:
        result != null
        result instanceof List
        // In stub mode (no driver), returns empty list
    }
    
    def "DSL should support mix of config-loaded and direct providers"() {
        when:
        def graph = TaskGraph.build {
            // Task 1: Load from config
            sqlTask("from-config") {
                provider PostgreSqlProvider.fromConfig()
                query "SELECT * FROM users"
            }
            
            // Task 2: Direct construction (old way still works)
            sqlTask("direct") {
                provider new PostgreSqlProvider(
                    url: "jdbc:postgresql://localhost:5432/mydb",
                    username: "user",
                    password: "pass"
                )
                query "SELECT * FROM orders"
            }
            
            // Task 3: Config with overrides
            noSqlTask("config-override") {
                provider MongoProvider.fromConfig([
                    'database.mongodb.database': 'production'
                ])
                find "products"
            }
        }
        
        then:
        notThrown(Exception)
    }
}
