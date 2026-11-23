package org.softwood.dag.task.sql

import org.softwood.dag.TaskGraph
import spock.lang.Specification
import spock.lang.Ignore
import spock.lang.Shared

/**
 * Tests for PostgreSQL provider.
 * 
 * <p><strong>Note:</strong> These tests run in stub mode by default (no PostgreSQL required).</p>
 */
class PostgreSqlProviderTest extends Specification {
    
    // Use @Shared to create provider once for all tests instead of per-test
    @Shared
    PostgreSqlProvider provider
    
    def setupSpec() {
        provider = new PostgreSqlProvider(
            url: "jdbc:postgresql://localhost:5432/testdb",
            username: "testuser",
            password: "testpass"
        )
        provider.initialize()
    }
    
    def cleanupSpec() {
        provider?.close()
    }
    
    def "should initialize in stub mode without PostgreSQL driver"() {
        expect:
        provider != null
        provider.getProviderType() == "PostgreSQL (JDBC)"
    }
    
    def "should return empty results for queries in stub mode"() {
        when:
        def result = provider.query("SELECT * FROM users WHERE age > ?", [18])
        
        then:
        result != null
        result instanceof List
        result.isEmpty()
    }
    
    def "should return 0 for updates in stub mode"() {
        when:
        def rowsAffected = provider.executeUpdate("INSERT INTO users (name, age) VALUES (?, ?)", ["Alice", 30])
        
        then:
        rowsAffected == 0
    }
    
    def "should return null for queryForMap in stub mode"() {
        when:
        def result = provider.queryForMap("SELECT * FROM users WHERE id = ?", [1])
        
        then:
        result == null
    }
    
    def "should return null for queryForObject in stub mode"() {
        when:
        def result = provider.queryForObject("SELECT COUNT(*) FROM users")
        
        then:
        result == null
    }
    
    def "should return empty list for getTables in stub mode"() {
        when:
        def tables = provider.getTables(null, "public", null, ["TABLE"])
        
        then:
        tables != null
        tables.isEmpty()
    }
    
    def "should return empty list for getColumns in stub mode"() {
        when:
        def columns = provider.getColumns(null, "public", "users", null)
        
        then:
        columns != null
        columns.isEmpty()
    }
    
    def "should work with SqlTask in stub mode"() {
        when:
        def graph = TaskGraph.build {
            sqlTask("fetch-users") {
                provider this.provider
                query "SELECT * FROM users WHERE age > ?"
                params 18
            }
        }
        def result = graph.run().get()
        
        then:
        result != null
        result instanceof List
        // In stub mode, returns empty list
    }
    
    def "should handle metadata queries in stub mode"() {
        when:
        def graph = TaskGraph.build {
            sqlTask("list-tables") {
                provider this.provider
                metadata {
                    tables()
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
