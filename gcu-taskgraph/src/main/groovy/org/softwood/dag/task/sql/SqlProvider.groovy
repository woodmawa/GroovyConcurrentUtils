package org.softwood.dag.task.sql

/**
 * Interface for SQL database providers - enables pluggable database access.
 * 
 * <p><strong>ZERO DEPENDENCIES:</strong> This interface is always available.
 * Concrete implementations require their respective libraries.</p>
 * 
 * <h3>Available Implementations:</h3>
 * <ul>
 *   <li><b>JdbcSqlProvider</b> - Default, uses JDBC (JDK built-in, zero deps!)</li>
 *   <li><b>GroovySqlProvider</b> - Requires groovy-sql dependency</li>
 *   <li><b>H2SqlProvider</b> - In-memory testing, requires h2 dependency</li>
 *   <li><b>HikariSqlProvider</b> - Connection pooling, requires hikaricp dependency</li>
 * </ul>
 * 
 * <h3>Usage:</h3>
 * <pre>
 * // Default (JDBC - zero deps!)
 * def provider = new JdbcSqlProvider(
 *     url: "jdbc:h2:mem:test",
 *     username: "sa",
 *     password: ""
 * )
 * 
 * // GroovySQL (requires dependency)
 * def provider = new GroovySqlProvider(dataSource)
 * 
 * // HikariCP pooling (requires dependency)
 * def provider = new HikariSqlProvider(
 *     jdbcUrl: "jdbc:postgresql://localhost/mydb",
 *     username: "user",
 *     password: "pass"
 * )
 * </pre>
 * 
 * @since 2.1.0
 */
interface SqlProvider {
    
    /**
     * Execute a SELECT query and return all rows as maps.
     * 
     * @param sql SQL query with ? placeholders
     * @param params query parameters
     * @return list of rows, each row is a map of column->value
     */
    List<Map<String, Object>> query(String sql, List<Object> params)
    
    /**
     * Execute a SELECT query without parameters.
     * 
     * @param sql SQL query
     * @return list of rows
     */
    List<Map<String, Object>> query(String sql)
    
    /**
     * Execute a SELECT query and return a single row as a map.
     * Returns null if no rows found.
     * 
     * @param sql SQL query with ? placeholders
     * @param params query parameters
     * @return single row as map, or null if not found
     */
    Map<String, Object> queryForMap(String sql, List<Object> params)
    
    /**
     * Execute a SELECT query and return a single row without parameters.
     * 
     * @param sql SQL query
     * @return single row as map, or null if not found
     */
    Map<String, Object> queryForMap(String sql)
    
    /**
     * Execute a SELECT query and return a single value.
     * Useful for COUNT, MAX, etc.
     * 
     * @param sql SQL query with ? placeholders
     * @param params query parameters
     * @return single value from first column of first row
     */
    Object queryForObject(String sql, List<Object> params)
    
    /**
     * Execute a SELECT query and return a single value without parameters.
     * 
     * @param sql SQL query
     * @return single value
     */
    Object queryForObject(String sql)
    
    /**
     * Execute an INSERT, UPDATE, or DELETE statement.
     * 
     * @param sql SQL statement with ? placeholders
     * @param params statement parameters
     * @return number of rows affected
     */
    int executeUpdate(String sql, List<Object> params)
    
    /**
     * Execute an INSERT, UPDATE, or DELETE statement without parameters.
     * 
     * @param sql SQL statement
     * @return number of rows affected
     */
    int executeUpdate(String sql)
    
    /**
     * Execute arbitrary SQL with a closure for custom processing.
     * The closure receives a connection or SQL object depending on provider.
     * 
     * @param work closure that receives provider-specific SQL helper
     * @return result from closure
     */
    Object execute(Closure work)
    
    /**
     * Execute work within a transaction.
     * Automatically commits on success, rolls back on exception.
     * 
     * @param work closure that receives provider-specific SQL helper
     * @return result from closure
     */
    Object withTransaction(Closure work)
    
    /**
     * Close the provider and release all resources (connections, pools, etc).
     */
    void close()
    
    /**
     * Get the provider name/type.
     * @return provider identifier (e.g., "JDBC", "GroovySQL", "HikariCP")
     */
    String getProviderType()
    
    /**
     * Check if provider is connected and ready.
     * @return true if ready to execute queries
     */
    boolean isConnected()
    
    // =========================================================================
    // Metadata Operations
    // =========================================================================
    
    /**
     * Get information about all tables in the database.
     * 
     * @param catalog catalog name (null for current)
     * @param schema schema pattern (null for all, % for wildcard)
     * @param tableNamePattern table name pattern (null for all, % for wildcard)
     * @param types table types to include (e.g., ["TABLE", "VIEW"])
     * @return list of table information
     */
    List<DatabaseMetadata.TableInfo> getTables(String catalog, String schema, String tableNamePattern, List<String> types)
    
    /**
     * Get information about columns in a table.
     * 
     * @param catalog catalog name (null for current)
     * @param schema schema name (null for all)
     * @param tableName table name
     * @param columnNamePattern column name pattern (null for all, % for wildcard)
     * @return list of column information
     */
    List<DatabaseMetadata.ColumnInfo> getColumns(String catalog, String schema, String tableName, String columnNamePattern)
    
    /**
     * Get information about indexes on a table.
     * 
     * @param catalog catalog name (null for current)
     * @param schema schema name (null for all)
     * @param tableName table name
     * @param unique if true, return only unique indexes
     * @return list of index information
     */
    List<DatabaseMetadata.IndexInfo> getIndexes(String catalog, String schema, String tableName, boolean unique)
    
    /**
     * Get primary key information for a table.
     * 
     * @param catalog catalog name (null for current)
     * @param schema schema name (null for all)
     * @param tableName table name
     * @return primary key information, or null if none
     */
    DatabaseMetadata.PrimaryKeyInfo getPrimaryKeys(String catalog, String schema, String tableName)
    
    /**
     * Get foreign key information for a table.
     * 
     * @param catalog catalog name (null for current)
     * @param schema schema name (null for all)
     * @param tableName table name
     * @return list of foreign keys
     */
    List<DatabaseMetadata.ForeignKeyInfo> getForeignKeys(String catalog, String schema, String tableName)
    
    /**
     * Get all schemas in the database.
     * 
     * @return list of schemas
     */
    List<DatabaseMetadata.SchemaInfo> getSchemas()
    
    /**
     * Get all catalogs in the database.
     * 
     * @return list of catalogs
     */
    List<DatabaseMetadata.CatalogInfo> getCatalogs()
    
    /**
     * Get database product information.
     * 
     * @return database information
     */
    DatabaseMetadata.DatabaseInfo getDatabaseInfo()
}
