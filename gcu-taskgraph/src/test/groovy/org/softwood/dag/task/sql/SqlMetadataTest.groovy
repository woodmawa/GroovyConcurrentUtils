package org.softwood.dag.task.sql

import org.softwood.dag.TaskGraph
import org.softwood.dag.task.sql.JdbcSqlProvider
import spock.lang.Specification

/**
 * Tests for SQL metadata operations.
 * 
 * <p><strong>Note:</strong> These tests use H2 database which has some quirks:</p>
 * <ul>
 *   <li>H2 reports table type as "BASE TABLE" instead of "TABLE"</li>
 *   <li>H2 has system tables in INFORMATION_SCHEMA that may conflict with test tables</li>
 *   <li>Tests specify schema: "PUBLIC" to avoid system table conflicts</li>
 *   <li>H2 reports "VIEW" for views, which is standard SQL</li>
 * </ul>
 */
class SqlMetadataTest extends Specification {
    
    JdbcSqlProvider provider
    
    def setup() {
        // Create H2 in-memory database with test schema
        provider = new JdbcSqlProvider(
            url: "jdbc:h2:mem:metadatatest_${System.currentTimeMillis()};DB_CLOSE_DELAY=-1",
            username: "sa",
            password: ""
        )
        provider.initialize()
        
        // Create test schema with various table types
        provider.executeUpdate("""
            CREATE TABLE users (
                id INT PRIMARY KEY,
                username VARCHAR(50) NOT NULL UNIQUE,
                email VARCHAR(100),
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """, [])
        
        provider.executeUpdate("""
            CREATE TABLE orders (
                id INT PRIMARY KEY,
                user_id INT NOT NULL,
                total DECIMAL(10,2),
                status VARCHAR(20),
                FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
            )
        """, [])
        
        provider.executeUpdate("""
            CREATE INDEX idx_orders_status ON orders(status)
        """, [])
        
        provider.executeUpdate("""
            CREATE VIEW active_users AS 
            SELECT id, username FROM users WHERE id > 0
        """, [])
    }
    
    def cleanup() {
        provider?.close()
    }
    
    def "should list all tables"() {
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
        result instanceof List
        result.size() >= 2
        result.any { it.tableName == "USERS" }
        result.any { it.tableName == "ORDERS" }
    }
    
    def "should list only tables (not views)"() {
        when:
        def graph = TaskGraph.build {
            sqlTask("list-tables-only") {
                provider this.provider
                metadata {
                    tables([schema: "PUBLIC", type: ["BASE TABLE"]])  // H2 uses "BASE TABLE" not "TABLE"
                }
            }
        }
        def result = graph.run().get()
        
        then:
        result.every { it.type == "BASE TABLE" }
        !result.any { it.tableName == "ACTIVE_USERS" }
    }
    
    def "should get columns for a table"() {
        when:
        def graph = TaskGraph.build {
            sqlTask("describe-users") {
                provider this.provider
                metadata {
                    columns("USERS", [schema: "PUBLIC"])  // Fix: Use proper method call syntax
                }
            }
        }
        def result = graph.run().get()
        
        then:
        result instanceof List
        result.size() == 4
        result.any { it.columnName == "ID" }
        result.any { it.columnName == "USERNAME" }
        result.any { it.columnName == "EMAIL" }
        result.any { it.columnName == "CREATED_AT" }
        
        and: "ID column should be NOT NULL"
        def idColumn = result.find { it.columnName == "ID" }
        !idColumn.nullable
        
        and: "EMAIL column should be nullable"
        def emailColumn = result.find { it.columnName == "EMAIL" }
        emailColumn.nullable
    }
    
    def "should get indexes for a table"() {
        when:
        def graph = TaskGraph.build {
            sqlTask("list-indexes") {
                provider this.provider
                metadata {
                    indexes("ORDERS", [schema: "PUBLIC"])
                }
            }
        }
        def result = graph.run().get()
        
        then:
        result instanceof List
        result.size() >= 1
        result.any { it.indexName.contains("STATUS") }
    }
    
    def "should get primary keys for a table"() {
        when:
        def graph = TaskGraph.build {
            sqlTask("get-pk") {
                provider this.provider
                metadata {
                    primaryKeys("USERS", [schema: "PUBLIC"])
                }
            }
        }
        def result = graph.run().get()
        
        then:
        result != null
        result.tableName == "USERS"
        result.columns.size() == 1
        result.columns[0] == "ID"
    }
    
    def "should get foreign keys for a table"() {
        when:
        def graph = TaskGraph.build {
            sqlTask("get-fk") {
                provider this.provider
                metadata {
                    foreignKeys("ORDERS", [schema: "PUBLIC"])
                }
            }
        }
        def result = graph.run().get()
        
        then:
        result instanceof List
        result.size() >= 1
        result[0].pkTableName == "USERS"
        result[0].columns.size() == 1
        result[0].columns[0].fkColumn == "USER_ID"
        result[0].columns[0].pkColumn == "ID"
        result[0].deleteRule == "CASCADE"
    }
    
    def "should get database info"() {
        when:
        def graph = TaskGraph.build {
            sqlTask("db-info") {
                provider this.provider
                metadata {
                    databaseInfo()
                }
            }
        }
        def result = graph.run().get()
        
        then:
        result != null
        result.productName.contains("H2")
        result.supportsTransactions
        result.supportsBatchUpdates
    }
    
    def "should get schemas"() {
        when:
        def graph = TaskGraph.build {
            sqlTask("list-schemas") {
                provider this.provider
                metadata {
                    schemas()
                }
            }
        }
        def result = graph.run().get()
        
        then:
        result instanceof List
        result.size() >= 1
    }
    
    def "should filter tables by pattern"() {
        when:
        def graph = TaskGraph.build {
            sqlTask("filter-tables") {
                provider this.provider
                metadata {
                    tables([schema: "PUBLIC", pattern: "USER%"])
                }
            }
        }
        def result = graph.run().get()
        
        then:
        result instanceof List
        result.every { it.tableName.startsWith("USER") }
        result.every { it.schema == "PUBLIC" }  // Ensure only PUBLIC schema
    }
    
    def "should get unique indexes only"() {
        when:
        def graph = TaskGraph.build {
            sqlTask("unique-indexes") {
                provider this.provider
                metadata {
                    indexes("USERS", [schema: "PUBLIC", unique: true])
                }
            }
        }
        def result = graph.run().get()
        
        then:
        result instanceof List
        result.every { it.unique }
    }
    
    def "should handle table with no foreign keys"() {
        when:
        def graph = TaskGraph.build {
            sqlTask("get-fk-users") {
                provider this.provider
                metadata {
                    foreignKeys("USERS", [schema: "PUBLIC"])
                }
            }
        }
        def result = graph.run().get()
        
        then:
        result instanceof List
        result.isEmpty()
    }
}
