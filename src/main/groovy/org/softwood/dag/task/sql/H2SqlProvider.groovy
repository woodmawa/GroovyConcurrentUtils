package org.softwood.dag.task.sql

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

/**
 * H2 in-memory database provider for testing.
 * 
 * <p><strong>OPTIONAL:</strong> This implementation is provided as a framework
 * but is disabled by default. To use it, you must:</p>
 * <ol>
 *   <li>Add H2 dependency to build.gradle</li>
 *   <li>Uncomment the implementation code below</li>
 * </ol>
 * 
 * <h3>Dependencies Required:</h3>
 * <pre>
 * // Add to build.gradle
 * testImplementation 'com.h2database:h2:2.2.224'
 * </pre>
 * 
 * <h3>Usage:</h3>
 * <pre>
 * // Create in-memory database
 * def provider = new H2SqlProvider()  // Uses default "mem:test"
 * 
 * // Or specify database name
 * def provider = new H2SqlProvider(databaseName: "mydb")
 * 
 * // Initialize schema
 * provider.executeUpdate("""
 *     CREATE TABLE users (
 *         id INT PRIMARY KEY,
 *         name VARCHAR(100),
 *         age INT
 *     )
 * """, [])
 * 
 * // Query
 * def users = provider.query("SELECT * FROM users", [])
 * </pre>
 * 
 * @since 2.1.0
 */
@Slf4j
@CompileStatic
class H2SqlProvider extends JdbcSqlProvider {
    
    String databaseName = "test"
    boolean inMemory = true
    
    /**
     * Create H2 provider with defaults (in-memory, database name "test").
     */
    H2SqlProvider() {
        configureH2()
    }
    
    /**
     * Create H2 provider with options.
     * 
     * @param options configuration (databaseName, inMemory)
     */
    H2SqlProvider(Map<String, Object> options) {
        if (options.databaseName) {
            this.databaseName = options.databaseName as String
        }
        if (options.inMemory != null) {
            this.inMemory = options.inMemory as boolean
        }
        configureH2()
    }
    
    private void configureH2() {
        this.driverClassName = "org.h2.Driver"
        this.url = inMemory 
            ? "jdbc:h2:mem:${databaseName};DB_CLOSE_DELAY=-1"
            : "jdbc:h2:file:./data/${databaseName}"
        this.username = "sa"
        this.password = ""
        
        log.debug("H2SqlProvider: configured with database '{}' (inMemory={})", databaseName, inMemory)
    }
    
    @Override
    String getProviderType() {
        return "H2"
    }
    
    /**
     * Helper: Create a table with simple schema.
     * 
     * @param tableName table name
     * @param columns map of column_name -> sql_type
     */
    void createTable(String tableName, Map<String, String> columns) {
        def columnDefs = columns.collect { name, type -> "${name} ${type}" }.join(", ")
        def sql = "CREATE TABLE IF NOT EXISTS ${tableName} (${columnDefs})"
        executeUpdate(sql, [])
        log.debug("H2SqlProvider: created table '{}'", tableName)
    }
    
    /**
     * Helper: Drop a table.
     * 
     * @param tableName table name
     */
    void dropTable(String tableName) {
        executeUpdate("DROP TABLE IF EXISTS ${tableName}", [])
        log.debug("H2SqlProvider: dropped table '{}'", tableName)
    }
    
    /**
     * Helper: Clear all data from a table.
     * 
     * @param tableName table name
     */
    void truncateTable(String tableName) {
        executeUpdate("TRUNCATE TABLE ${tableName}", [])
        log.debug("H2SqlProvider: truncated table '{}'", tableName)
    }
}
