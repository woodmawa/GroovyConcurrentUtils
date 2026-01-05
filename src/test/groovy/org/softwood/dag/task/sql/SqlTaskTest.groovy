package org.softwood.dag.task.sql

import org.softwood.dag.TaskGraph
import org.softwood.dag.task.sql.JdbcSqlProvider
import spock.lang.Specification

/**
 * Comprehensive tests for SqlTask with JDBC provider.
 */
class SqlTaskTest extends Specification {
    
    JdbcSqlProvider provider
    
    def setup() {
        // Create in-memory H2 database
        provider = new JdbcSqlProvider(
            url: "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1",
            driverClassName: "org.h2.Driver",
            username: "sa",
            password: ""
        )
        provider.initialize()
        
        // Create test schema
        provider.executeUpdate("""
            CREATE TABLE IF NOT EXISTS users (
                id INT PRIMARY KEY,
                name VARCHAR(100),
                age INT,
                email VARCHAR(100)
            )
        """, [])
        
        // Insert test data
        provider.executeUpdate("DELETE FROM users", [])
        provider.executeUpdate("INSERT INTO users VALUES (1, 'Alice', 25, 'alice@example.com')", [])
        provider.executeUpdate("INSERT INTO users VALUES (2, 'Bob', 30, 'bob@example.com')", [])
        provider.executeUpdate("INSERT INTO users VALUES (3, 'Charlie', 17, 'charlie@example.com')", [])
    }
    
    def cleanup() {
        if (provider) {
            provider.close()
        }
    }
    
    // =========================================================================
    // Query Tests
    // =========================================================================
    
    def "should execute SELECT query and return results"() {
        when:
        def graph = TaskGraph.build {
            sqlTask("fetch-users") {
                provider this.provider
                query "SELECT * FROM users WHERE age >= ?"
                params 18
            }
        }
        
        def result = graph.run().get()
        
        then:
        result.size() == 2
        result[0].name == "Alice"
        result[1].name == "Bob"
    }
    
    def "should execute query with dynamic parameters"() {
        when:
        def graph = TaskGraph.build {
            serviceTask("set-age") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        [minAge: 25]
                    }
                }
            }
            
            sqlTask("fetch-filtered") {
                provider this.provider
                query "SELECT * FROM users WHERE age > ?"
                params { r -> [r.prev.minAge] }
            }
            
            chainVia("set-age", "fetch-filtered")
        }
        
        def result = graph.run().get()
        
        then:
        result.size() == 1
        result[0].name == "Bob"
        result[0].age == 30
    }
    
    def "should apply result mapper"() {
        when:
        def graph = TaskGraph.build {
            sqlTask("fetch-names") {
                provider this.provider
                query "SELECT * FROM users"
                params()
                
                resultMapper { rows ->
                    rows.collect { it.name.toUpperCase() }
                }
            }
        }
        
        def result = graph.run().get()
        
        then:
        result == ["ALICE", "BOB", "CHARLIE"]
    }
    
    def "should execute queryForObject for single value"() {
        when:
        def count = provider.queryForObject("SELECT COUNT(*) FROM users", [])
        
        then:
        count == 3
    }
    
    def "should execute queryForMap for single row"() {
        when:
        def user = provider.queryForMap("SELECT * FROM users WHERE id = ?", [1])
        
        then:
        user.name == "Alice"
        user.age == 25
    }
    
    // =========================================================================
    // Update Tests
    // =========================================================================
    
    def "should execute INSERT statement"() {
        when:
        def graph = TaskGraph.build {
            sqlTask("create-user") {
                provider this.provider
                update "INSERT INTO users (id, name, age, email) VALUES (?, ?, ?, ?)"
                params 4, "Diana", 28, "diana@example.com"
            }
        }
        
        def result = graph.run().get()
        
        then:
        result.rowsAffected == 1
        result.success
        
        when:
        def users = provider.query("SELECT * FROM users WHERE id = ?", [4])
        
        then:
        users.size() == 1
        users[0].name == "Diana"
    }
    
    def "should execute UPDATE statement"() {
        when:
        def graph = TaskGraph.build {
            sqlTask("update-user") {
                provider this.provider
                update "UPDATE users SET age = ? WHERE name = ?"
                params 26, "Alice"
            }
        }
        
        def result = graph.run().get()
        
        then:
        result.rowsAffected == 1
        
        when:
        def user = provider.queryForMap("SELECT * FROM users WHERE name = ?", ["Alice"])
        
        then:
        user.age == 26
    }
    
    def "should execute DELETE statement"() {
        when:
        def graph = TaskGraph.build {
            sqlTask("delete-user") {
                provider this.provider
                update "DELETE FROM users WHERE age < ?"
                params 18
            }
        }
        
        def result = graph.run().get()
        
        then:
        result.rowsAffected == 1
        
        when:
        def remaining = provider.queryForObject("SELECT COUNT(*) FROM users", [])
        
        then:
        remaining == 2
    }
    
    def "should execute update with dynamic parameters"() {
        when:
        def graph = TaskGraph.build {
            serviceTask("prepare-data") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        [name: "Eve", age: 35, email: "eve@example.com"]
                    }
                }
            }
            
            sqlTask("insert-user") {
                provider this.provider
                update "INSERT INTO users (id, name, age, email) VALUES (?, ?, ?, ?)"
                params { r -> [5, r.prev.name, r.prev.age, r.prev.email] }
            }
            
            chainVia("prepare-data", "insert-user")
        }
        
        def result = graph.run().get()
        
        then:
        result.rowsAffected == 1
        
        when:
        def user = provider.queryForMap("SELECT * FROM users WHERE id = ?", [5])
        
        then:
        user.name == "Eve"
        user.age == 35
    }
    
    // =========================================================================
    // Execute Mode Tests
    // =========================================================================
    
    def "should execute custom closure"() {
        when:
        def graph = TaskGraph.build {
            sqlTask("custom-query") {
                provider this.provider
                
                execute { conn ->
                    def stmt = conn.createStatement()
                    def rs = stmt.executeQuery("SELECT AVG(age) as avg_age FROM users")
                    rs.next()
                    def avgAge = rs.getDouble("avg_age")
                    rs.close()
                    stmt.close()
                    return [averageAge: avgAge]
                }
            }
        }
        
        def result = graph.run().get()
        
        then:
        result.averageAge == 24.0  // (25 + 30 + 17) / 3
    }
    
    def "should execute transaction"() {
        when:
        def graph = TaskGraph.build {
            sqlTask("batch-insert") {
                provider this.provider
                transaction true
                
                execute { conn ->
                    def stmt = conn.prepareStatement("INSERT INTO users (id, name, age, email) VALUES (?, ?, ?, ?)")
                    
                    // Insert multiple users in transaction
                    [[6, "Frank", 40, "frank@example.com"],
                     [7, "Grace", 32, "grace@example.com"]].each { user ->
                        stmt.setInt(1, user[0])
                        stmt.setString(2, user[1])
                        stmt.setInt(3, user[2])
                        stmt.setString(4, user[3])
                        stmt.executeUpdate()
                    }
                    
                    stmt.close()
                    return [inserted: 2]
                }
            }
        }
        
        def result = graph.run().get()
        
        then:
        result.inserted == 2
        
        when:
        def count = provider.queryForObject("SELECT COUNT(*) FROM users", [])
        
        then:
        count == 5  // 3 original + 2 new
    }
    
    // =========================================================================
    // Integration Tests
    // =========================================================================
    
    def "should integrate with task pipeline"() {
        when:
        def graph = TaskGraph.build {
            serviceTask("create-user-data") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        [name: "Henry", age: 45, email: "henry@example.com"]
                    }
                }
            }
            
            sqlTask("insert-user") {
                provider this.provider
                update "INSERT INTO users (id, name, age, email) VALUES (?, ?, ?, ?)"
                params { r -> [8, r.prev.name, r.prev.age, r.prev.email] }
            }
            
            sqlTask("verify-insert") {
                provider this.provider
                query "SELECT * FROM users WHERE name = ?"
                params { r -> ["Henry"] }
            }
            
            chainVia("create-user-data", "insert-user", "verify-insert")
        }
        
        def result = graph.run().get()
        
        then:
        result.size() == 1
        result[0].name == "Henry"
        result[0].age == 45
    }
    
    def "should use dataSource DSL"() {
        when:
        def graph = TaskGraph.build {
            sqlTask("fetch-with-dsl") {
                dataSource {
                    url = "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1"
                    driverClassName = "org.h2.Driver"
                    username = "sa"
                    password = ""
                }
                
                query "SELECT COUNT(*) as total FROM users"
                params()
                
                resultMapper { rows ->
                    rows[0].total
                }
            }
        }
        
        def result = graph.run().get()
        
        then:
        result == 3
    }
    
    // =========================================================================
    // Error Handling Tests
    // =========================================================================
    
    def "should handle SQL syntax errors"() {
        when:
        def graph = TaskGraph.build {
            sqlTask("invalid-sql") {
                provider this.provider
                query "INVALID SQL SYNTAX"
                params()
            }
        }
        
        graph.run().get()
        
        then:
        thrown(Exception)
    }
    
    def "should handle missing parameters"() {
        when:
        def graph = TaskGraph.build {
            sqlTask("missing-params") {
                provider this.provider
                query "SELECT * FROM users WHERE id = ?"
                // No params provided
            }
        }
        
        graph.run().get()
        
        then:
        thrown(Exception)
    }
    
    // =========================================================================
    // Provider Tests
    // =========================================================================
    
    def "should check provider connection status"() {
        expect:
        provider.isConnected()
        provider.getProviderType() == "JDBC"
    }
    
    def "should close provider cleanly"() {
        when:
        def testProvider = new JdbcSqlProvider(
            url: "jdbc:h2:mem:test2",
            driverClassName: "org.h2.Driver",
            username: "sa",
            password: ""
        )
        testProvider.initialize()
        
        then:
        testProvider.isConnected()
        
        when:
        testProvider.close()
        
        then:
        !testProvider.isConnected()
    }
}
