package examples

import org.softwood.dag.TaskGraph
import org.softwood.dag.task.sql.JdbcSqlProvider

/**
 * Examples demonstrating SQL metadata operations.
 * 
 * Run this to see database introspection in action!
 */
class MetadataExamples {
    
    static void main(String[] args) {
        // Setup H2 in-memory database with sample schema
        def provider = new JdbcSqlProvider(
            url: "jdbc:h2:mem:demo;DB_CLOSE_DELAY=-1",
            username: "sa",
            password: ""
        )
        provider.initialize()
        
        // Create sample schema
        createSampleSchema(provider)
        
        // Example 1: List all tables
        println "\n=== Example 1: List All Tables ==="
        def graph1 = TaskGraph.build {
            sqlTask("list-tables") {
                provider provider
                metadata {
                    tables()
                }
            }
        }
        def tables = graph1.run().get()
        tables.each { table ->
            println "  - ${table.tableName} (${table.type})"
        }
        
        // Example 2: Describe a table
        println "\n=== Example 2: Describe USERS Table ==="
        def graph2 = TaskGraph.build {
            sqlTask("describe-users") {
                provider provider
                metadata {
                    columns "USERS"
                }
            }
        }
        def columns = graph2.run().get()
        columns.each { col ->
            def nullable = col.nullable ? "NULL" : "NOT NULL"
            println "  - ${col.columnName}: ${col.dataType} ${nullable}"
        }
        
        // Example 3: Get primary keys
        println "\n=== Example 3: Primary Keys ==="
        def graph3 = TaskGraph.build {
            sqlTask("get-pk") {
                provider provider
                metadata {
                    primaryKeys "USERS"
                }
            }
        }
        def pk = graph3.run().get()
        println "  Primary key: ${pk.columns.join(', ')}"
        
        // Example 4: Get foreign keys
        println "\n=== Example 4: Foreign Keys ==="
        def graph4 = TaskGraph.build {
            sqlTask("get-fk") {
                provider provider
                metadata {
                    foreignKeys "ORDERS"
                }
            }
        }
        def fks = graph4.run().get()
        fks.each { fk ->
            println "  ${fk.tableName}.${fk.columns[0].fkColumn} -> ${fk.pkTableName}.${fk.columns[0].pkColumn}"
            println "    ON DELETE ${fk.deleteRule}"
        }
        
        // Example 5: Get indexes
        println "\n=== Example 5: Indexes on ORDERS ==="
        def graph5 = TaskGraph.build {
            sqlTask("get-indexes") {
                provider provider
                metadata {
                    indexes "ORDERS"
                }
            }
        }
        def indexes = graph5.run().get()
        indexes.each { idx ->
            def unique = idx.unique ? "UNIQUE" : ""
            println "  ${unique} ${idx.indexName}: ${idx.columns.join(', ')}"
        }
        
        // Example 6: Database info
        println "\n=== Example 6: Database Info ==="
        def graph6 = TaskGraph.build {
            sqlTask("db-info") {
                provider provider
                metadata {
                    databaseInfo()
                }
            }
        }
        def dbInfo = graph6.run().get()
        println "  Product: ${dbInfo.productName} ${dbInfo.productVersion}"
        println "  Driver: ${dbInfo.driverName} ${dbInfo.driverVersion}"
        println "  Supports Transactions: ${dbInfo.supportsTransactions}"
        println "  Supports Batch Updates: ${dbInfo.supportsBatchUpdates}"
        
        // Example 7: Filter tables by type
        println "\n=== Example 7: List Views Only ==="
        def graph7 = TaskGraph.build {
            sqlTask("list-views") {
                provider provider
                metadata {
                    tables type: ["VIEW"]
                }
            }
        }
        def views = graph7.run().get()
        views.each { view ->
            println "  - ${view.tableName}"
        }
        
        // Example 8: Schema discovery workflow
        println "\n=== Example 8: Complete Schema Discovery ==="
        discoverSchema(provider)
        
        provider.close()
    }
    
    static void createSampleSchema(provider) {
        provider.executeUpdate("""
            CREATE TABLE users (
                id INT PRIMARY KEY,
                username VARCHAR(50) NOT NULL UNIQUE,
                email VARCHAR(100),
                age INT,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """, [])
        
        provider.executeUpdate("""
            CREATE TABLE orders (
                id INT PRIMARY KEY,
                user_id INT NOT NULL,
                product VARCHAR(100),
                total DECIMAL(10,2),
                status VARCHAR(20),
                FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
            )
        """, [])
        
        provider.executeUpdate("""
            CREATE INDEX idx_orders_status ON orders(status)
        """, [])
        
        provider.executeUpdate("""
            CREATE INDEX idx_orders_user ON orders(user_id)
        """, [])
        
        provider.executeUpdate("""
            CREATE VIEW active_orders AS 
            SELECT o.*, u.username 
            FROM orders o 
            JOIN users u ON o.user_id = u.id 
            WHERE o.status = 'active'
        """, [])
    }
    
    static void discoverSchema(provider) {
        def graph = TaskGraph.build {
            // Get all tables
            def tablesTask = sqlTask("get-tables") {
                provider provider
                metadata {
                    tables type: ["TABLE"]
                }
            }
            
            // Get database info
            sqlTask("get-db-info") {
                provider provider
                metadata {
                    databaseInfo()
                }
            }
        }
        
        def result = graph.run()
        def tables = result.getTaskResult("get-tables")
        def dbInfo = result.getTaskResult("get-db-info")
        
        println "Database: ${dbInfo.productName}"
        println "\nTables discovered:"
        tables.each { table ->
            println "\n  Table: ${table.tableName}"
            
            // Get columns for this table
            def colGraph = TaskGraph.build {
                sqlTask("cols") {
                    provider provider
                    metadata {
                        columns table.tableName
                    }
                }
            }
            def cols = colGraph.run().get()
            println "    Columns:"
            cols.each { col ->
                def nullable = col.nullable ? "NULL" : "NOT NULL"
                println "      - ${col.columnName}: ${col.dataType} ${nullable}"
            }
            
            // Get indexes
            def idxGraph = TaskGraph.build {
                sqlTask("idx") {
                    provider provider
                    metadata {
                        indexes table.tableName
                    }
                }
            }
            def indexes = idxGraph.run().get()
            if (indexes) {
                println "    Indexes:"
                indexes.each { idx ->
                    println "      - ${idx.indexName}: ${idx.columns.join(', ')}"
                }
            }
        }
    }
}
