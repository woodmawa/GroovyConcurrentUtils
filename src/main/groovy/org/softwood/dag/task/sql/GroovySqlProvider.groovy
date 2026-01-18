package org.softwood.dag.task.sql

import groovy.sql.Sql
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import javax.sql.DataSource

/**
 * GroovySQL-based provider for idiomatic Groovy database access.
 * 
 * <p><strong>REQUIRES:</strong> groovy-sql dependency (now included!)</p>
 * 
 * <h3>Features:</h3>
 * <ul>
 *   <li>Groovy-idiomatic API (eachRow, rows, etc.)</li>
 *   <li>Automatic resource management</li>
 *   <li>Named parameters support</li>
 *   <li>Batch operations</li>
 *   <li>Transaction support</li>
 * </ul>
 * 
 * <h3>Usage:</h3>
 * <pre>
 * def provider = new GroovySqlProvider(dataSource: myDataSource)
 * 
 * // Idiomatic Groovy queries
 * def users = provider.query("SELECT * FROM users WHERE age > ?", [18])
 * 
 * // Or use execute mode for full GroovySQL power
 * sqlTask("complex-query") {
 *     provider new GroovySqlProvider(dataSource: ds)
 *     
 *     execute { sql ->
 *         def result = []
 *         sql.eachRow("SELECT * FROM users") { row ->
 *             result << [name: row.name, age: row.age]
 *         }
 *         return result
 *     }
 * }
 * </pre>
 * 
 * @since 2.1.0
 */
@Slf4j
@CompileStatic
class GroovySqlProvider implements SqlProvider {
    
    DataSource dataSource
    String url
    String username
    String password
    String driverClassName
    
    private Sql sql
    private volatile boolean connected = false
    
    /**
     * Initialize the provider.
     * Creates Groovy Sql instance.
     */
    void initialize() {
        try {
            if (dataSource) {
                sql = new Sql(dataSource)
                log.debug("GroovySqlProvider: initialized with DataSource")
            } else if (url) {
                if (driverClassName) {
                    sql = Sql.newInstance(url, username, password, driverClassName)
                } else {
                    sql = Sql.newInstance(url, username, password)
                }
                log.debug("GroovySqlProvider: connected to {}", sanitizeUrl(url))
            } else {
                throw new IllegalStateException("Either dataSource or url must be configured")
            }
            connected = true
        } catch (Exception e) {
            log.error("GroovySqlProvider: initialization failed", e)
            connected = false
            throw e
        }
    }
    
    @Override
    List<Map<String, Object>> query(String sqlQuery, List<Object> params) {
        ensureConnected()
        
        try {
            def results = [] as List<Map<String, Object>>
            if (params) {
                sql.eachRow(sqlQuery, params as List) { row ->
                    results << rowToMap(row)
                }
            } else {
                sql.eachRow(sqlQuery) { row ->
                    results << rowToMap(row)
                }
            }
            
            log.debug("GroovySqlProvider: query returned {} rows", results.size())
            return results
        } catch (Exception e) {
            log.error("GroovySqlProvider: query failed: {}", sqlQuery, e)
            throw new RuntimeException("Query failed: ${extractRootCause(e)}", e)
        }
    }
    
    @Override
    List<Map<String, Object>> query(String sqlQuery) {
        return query(sqlQuery, [])
    }
    
    @Override
    Map<String, Object> queryForMap(String sqlQuery, List<Object> params) {
        def results = query(sqlQuery, params)
        return results.isEmpty() ? null : results[0]
    }
    
    @Override
    Map<String, Object> queryForMap(String sqlQuery) {
        return queryForMap(sqlQuery, [])
    }
    
    @Override
    Object queryForObject(String sqlQuery, List<Object> params) {
        ensureConnected()
        
        try {
            def result = params ? sql.firstRow(sqlQuery, params as List) : sql.firstRow(sqlQuery)
            if (!result) return null
            
            // Return first column value
            return result.values().iterator().next()
        } catch (Exception e) {
            log.error("GroovySqlProvider: queryForObject failed: {}", sqlQuery, e)
            throw new RuntimeException("QueryForObject failed: ${extractRootCause(e)}", e)
        }
    }
    
    @Override
    Object queryForObject(String sqlQuery) {
        return queryForObject(sqlQuery, [])
    }
    
    @Override
    int executeUpdate(String sqlStatement, List<Object> params) {
        ensureConnected()
        
        try {
            int rowsAffected = params 
                ? sql.executeUpdate(sqlStatement, params as List)
                : sql.executeUpdate(sqlStatement)
                
            log.debug("GroovySqlProvider: update affected {} rows", rowsAffected)
            return rowsAffected
        } catch (Exception e) {
            log.error("GroovySqlProvider: update failed: {}", sqlStatement, e)
            throw new RuntimeException("Update failed: ${extractRootCause(e)}", e)
        }
    }
    
    @Override
    int executeUpdate(String sqlStatement) {
        return executeUpdate(sqlStatement, [])
    }
    
    @Override
    Object execute(Closure work) {
        ensureConnected()
        
        try {
            return work.call(sql)
        } catch (Exception e) {
            log.error("GroovySqlProvider: execute failed", e)
            throw new RuntimeException("Execute failed: ${extractRootCause(e)}", e)
        }
    }
    
    @Override
    Object withTransaction(Closure work) {
        ensureConnected()
        
        try {
            return sql.withTransaction { 
                work.call(sql)
            }
        } catch (Exception e) {
            log.error("GroovySqlProvider: transaction failed", e)
            throw new RuntimeException("Transaction failed: ${extractRootCause(e)}", e)
        }
    }
    
    @Override
    void close() {
        if (sql) {
            try {
                sql.close()
            } catch (Exception e) {
                log.warn("GroovySqlProvider: error closing Sql instance", e)
            }
            sql = null
        }
        connected = false
        log.debug("GroovySqlProvider: closed")
    }
    
    @Override
    String getProviderType() {
        return "GroovySQL"
    }
    
    @Override
    boolean isConnected() {
        return connected && sql != null
    }
    
    // =========================================================================
    // GroovySQL-specific Methods
    // =========================================================================
    
    /**
     * Execute a batch of updates.
     * 
     * @param sqlStatement SQL with parameters
     * @param batchParams list of parameter lists
     * @return array of update counts
     */
    int[] executeBatch(String sqlStatement, List<List<Object>> batchParams) {
        ensureConnected()
        
        try {
            return sql.withBatch(sqlStatement) { stmt ->
                batchParams.each { params ->
                    stmt.addBatch(params as List)
                }
            }
        } catch (Exception e) {
            log.error("GroovySqlProvider: batch execution failed", e)
            throw new RuntimeException("Batch execution failed: ${extractRootCause(e)}", e)
        }
    }
    
    /**
     * Get the underlying Groovy Sql instance for advanced usage.
     * 
     * @return Sql instance
     */
    Sql getSql() {
        ensureConnected()
        return sql
    }
    
    // =========================================================================
    // Helper Methods
    // =========================================================================
    
    private void ensureConnected() {
        if (!connected || !sql) {
            initialize()
        }
    }
    
    // =========================================================================
    // Metadata Operations - Delegate to JDBC
    // =========================================================================
    
    @Override
    List<DatabaseMetadata.TableInfo> getTables(String catalog, String schema, String tableNamePattern, List<String> types) {
        ensureConnected()
        
        def conn = sql.connection
        def meta = conn.getMetaData()
        def typeArray = types ? types as String[] : null
        def rs = meta.getTables(catalog, schema, tableNamePattern, typeArray)
        
        List<DatabaseMetadata.TableInfo> tables = new ArrayList<>()
        try {
            while (rs.next()) {
                tables << new DatabaseMetadata.TableInfo(
                    catalog: rs.getString("TABLE_CAT"),
                    schema: rs.getString("TABLE_SCHEM"),
                    tableName: rs.getString("TABLE_NAME"),
                    type: rs.getString("TABLE_TYPE"),
                    remarks: rs.getString("REMARKS")
                )
            }
        } finally {
            rs.close()
        }
        return tables
    }
    
    @Override
    List<DatabaseMetadata.ColumnInfo> getColumns(String catalog, String schema, String tableName, String columnNamePattern) {
        ensureConnected()
        
        def conn = sql.connection
        def meta = conn.getMetaData()
        def rs = meta.getColumns(catalog, schema, tableName, columnNamePattern)
        
        List<DatabaseMetadata.ColumnInfo> columns = new ArrayList<>()
        try {
            while (rs.next()) {
                columns << new DatabaseMetadata.ColumnInfo(
                    tableName: rs.getString("TABLE_NAME"),
                    columnName: rs.getString("COLUMN_NAME"),
                    dataType: rs.getString("TYPE_NAME"),
                    sqlType: rs.getInt("DATA_TYPE"),
                    columnSize: rs.getInt("COLUMN_SIZE"),
                    decimalDigits: rs.getInt("DECIMAL_DIGITS"),
                    nullable: rs.getInt("NULLABLE") == java.sql.DatabaseMetaData.columnNullable,
                    defaultValue: rs.getString("COLUMN_DEF"),
                    remarks: rs.getString("REMARKS"),
                    ordinalPosition: rs.getInt("ORDINAL_POSITION")
                )
            }
        } finally {
            rs.close()
        }
        return columns
    }
    
    @Override
    List<DatabaseMetadata.IndexInfo> getIndexes(String catalog, String schema, String tableName, boolean unique) {
        ensureConnected()
        
        def conn = sql.connection
        def meta = conn.getMetaData()
        def rs = meta.getIndexInfo(catalog, schema, tableName, unique, false)
        
        Map<String, DatabaseMetadata.IndexInfo> indexMap = new HashMap<>()
        try {
            while (rs.next()) {
                def indexName = rs.getString("INDEX_NAME")
                if (!indexName) continue
                
                if (!indexMap.containsKey(indexName)) {
                    indexMap[indexName] = new DatabaseMetadata.IndexInfo(
                        tableName: rs.getString("TABLE_NAME"),
                        indexName: indexName,
                        unique: !rs.getBoolean("NON_UNIQUE"),
                        type: getIndexType(rs.getShort("TYPE")),
                        columns: []
                    )
                }
                
                def columnName = rs.getString("COLUMN_NAME")
                if (columnName) {
                    indexMap[indexName].columns << columnName
                }
            }
        } finally {
            rs.close()
        }
        return new ArrayList<DatabaseMetadata.IndexInfo>(indexMap.values())
    }
    
    @Override
    DatabaseMetadata.PrimaryKeyInfo getPrimaryKeys(String catalog, String schema, String tableName) {
        ensureConnected()
        
        def conn = sql.connection
        def meta = conn.getMetaData()
        def rs = meta.getPrimaryKeys(catalog, schema, tableName)
        
        DatabaseMetadata.PrimaryKeyInfo pkInfo = null
        try {
            while (rs.next()) {
                if (!pkInfo) {
                    pkInfo = new DatabaseMetadata.PrimaryKeyInfo(
                        tableName: rs.getString("TABLE_NAME"),
                        pkName: rs.getString("PK_NAME"),
                        columns: []
                    )
                }
                pkInfo.columns << rs.getString("COLUMN_NAME")
            }
        } finally {
            rs.close()
        }
        return pkInfo
    }
    
    @Override
    List<DatabaseMetadata.ForeignKeyInfo> getForeignKeys(String catalog, String schema, String tableName) {
        ensureConnected()
        
        def conn = sql.connection
        def meta = conn.getMetaData()
        def rs = meta.getImportedKeys(catalog, schema, tableName)
        
        Map<String, DatabaseMetadata.ForeignKeyInfo> fkMap = new HashMap<>()
        try {
            while (rs.next()) {
                def fkName = rs.getString("FK_NAME")
                
                if (!fkMap.containsKey(fkName)) {
                    fkMap[fkName] = new DatabaseMetadata.ForeignKeyInfo(
                        fkName: fkName,
                        tableName: rs.getString("FKTABLE_NAME"),
                        pkTableName: rs.getString("PKTABLE_NAME"),
                        updateRule: getForeignKeyRule(rs.getShort("UPDATE_RULE")),
                        deleteRule: getForeignKeyRule(rs.getShort("DELETE_RULE")),
                        columns: []
                    )
                }
                
                fkMap[fkName].columns << new DatabaseMetadata.ForeignKeyInfo.ColumnMapping(
                    fkColumn: rs.getString("FKCOLUMN_NAME"),
                    pkColumn: rs.getString("PKCOLUMN_NAME")
                )
            }
        } finally {
            rs.close()
        }
        return new ArrayList<DatabaseMetadata.ForeignKeyInfo>(fkMap.values())
    }
    
    @Override
    List<DatabaseMetadata.SchemaInfo> getSchemas() {
        ensureConnected()
        
        def conn = sql.connection
        def meta = conn.getMetaData()
        def rs = meta.getSchemas()
        
        List<DatabaseMetadata.SchemaInfo> schemas = new ArrayList<>()
        try {
            while (rs.next()) {
                schemas << new DatabaseMetadata.SchemaInfo(
                    catalog: rs.getString("TABLE_CATALOG"),
                    schemaName: rs.getString("TABLE_SCHEM")
                )
            }
        } finally {
            rs.close()
        }
        return schemas
    }
    
    @Override
    List<DatabaseMetadata.CatalogInfo> getCatalogs() {
        ensureConnected()
        
        def conn = sql.connection
        def meta = conn.getMetaData()
        def rs = meta.getCatalogs()
        
        List<DatabaseMetadata.CatalogInfo> catalogs = new ArrayList<>()
        try {
            while (rs.next()) {
                catalogs << new DatabaseMetadata.CatalogInfo(
                    catalog: rs.getString("TABLE_CAT")
                )
            }
        } finally {
            rs.close()
        }
        return catalogs
    }
    
    @Override
    DatabaseMetadata.DatabaseInfo getDatabaseInfo() {
        ensureConnected()
        
        def conn = sql.connection
        def meta = conn.getMetaData()
        
        return new DatabaseMetadata.DatabaseInfo(
            productName: meta.getDatabaseProductName(),
            productVersion: meta.getDatabaseProductVersion(),
            driverName: meta.getDriverName(),
            driverVersion: meta.getDriverVersion(),
            url: meta.getURL(),
            userName: meta.getUserName(),
            readOnly: meta.isReadOnly(),
            maxConnections: meta.getMaxConnections(),
            supportsBatchUpdates: meta.supportsBatchUpdates(),
            supportsTransactions: meta.supportsTransactions(),
            supportsStoredProcedures: meta.supportsStoredProcedures(),
            defaultTransactionIsolation: meta.getDefaultTransactionIsolation()
        )
    }
    
    private String getIndexType(short type) {
        switch (type) {
            case java.sql.DatabaseMetaData.tableIndexStatistic:
                return "statistic"
            case java.sql.DatabaseMetaData.tableIndexClustered:
                return "clustered"
            case java.sql.DatabaseMetaData.tableIndexHashed:
                return "hashed"
            case java.sql.DatabaseMetaData.tableIndexOther:
                return "other"
            default:
                return "unknown"
        }
    }
    
    private String getForeignKeyRule(short rule) {
        switch (rule) {
            case java.sql.DatabaseMetaData.importedKeyCascade:
                return "CASCADE"
            case java.sql.DatabaseMetaData.importedKeySetNull:
                return "SET NULL"
            case java.sql.DatabaseMetaData.importedKeySetDefault:
                return "SET DEFAULT"
            case java.sql.DatabaseMetaData.importedKeyRestrict:
                return "RESTRICT"
            case java.sql.DatabaseMetaData.importedKeyNoAction:
                return "NO ACTION"
            default:
                return "UNKNOWN"
        }
    }
    
    private Map<String, Object> rowToMap(Object row) {
        def map = [:] as Map<String, Object>
        if (row instanceof groovy.sql.GroovyRowResult) {
            row.each { key, value ->
                map[key.toString().toLowerCase()] = value
            }
        }
        return map
    }
    
    private static String sanitizeUrl(String url) {
        if (!url) return "<empty>"
        return url.replaceAll(/password=[^&;]*/, 'password=***')
    }
    
    /**
     * Extract the most informative error message from nested SQL exceptions.
     * SQL exceptions are often nested, and the root cause has the actual DB error.
     */
    private static String extractRootCause(Throwable throwable) {
        if (!throwable) return "Unknown error"
        
        // Walk down the exception chain to find the most specific message
        Throwable current = throwable
        Throwable root = throwable
        String bestMessage = throwable.message ?: "Unknown error"
        
        while (current != null) {
            root = current
            
            // Prefer SQLException messages as they're usually most specific
            if (current.class.name.contains("SQLException") && current.message) {
                bestMessage = current.message
            }
            
            current = current.cause
        }
        
        // If we found a better message in the chain, use it
        if (root != throwable && root.message) {
            return root.message
        }
        
        return bestMessage
    }
}
