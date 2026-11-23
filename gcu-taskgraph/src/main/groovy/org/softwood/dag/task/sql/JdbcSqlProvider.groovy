package org.softwood.dag.task.sql

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData

/**
 * JDBC-based SQL provider using only JDK classes.
 * 
 * <p><strong>ZERO DEPENDENCIES:</strong> Uses only java.sql.* from JDK.
 * Perfect for production use without additional libraries.</p>
 * 
 * <h3>Features:</h3>
 * <ul>
 *   <li>No external dependencies (uses JDBC from JDK)</li>
 *   <li>Connection pooling via external DataSource (optional)</li>
 *   <li>Transaction support</li>
 *   <li>Automatic resource cleanup</li>
 *   <li>Database metadata introspection</li>
 * </ul>
 * 
 * @since 2.1.0
 */
@Slf4j
@CompileStatic
class JdbcSqlProvider implements SqlProvider {
    
    // Configuration
    String url
    String username
    String password
    String driverClassName
    javax.sql.DataSource dataSource
    
    // State
    private volatile boolean connected = false
    private Connection sharedConnection  // Only used if no DataSource
    
    /**
     * Initialize the provider.
     * Call this after setting configuration properties.
     */
    void initialize() {
        if (dataSource) {
            // Using DataSource - test connection
            try {
                dataSource.getConnection().close()
                connected = true
                log.debug("JdbcSqlProvider: initialized with DataSource")
            } catch (Exception e) {
                log.error("JdbcSqlProvider: failed to connect to DataSource", e)
                connected = false
            }
        } else if (url) {
            // Using direct JDBC connection
            try {
                if (driverClassName) {
                    Class.forName(driverClassName)
                }
                sharedConnection = DriverManager.getConnection(url, username, password)
                connected = true
                log.debug("JdbcSqlProvider: connected to {}", sanitizeUrl(url))
            } catch (Exception e) {
                log.error("JdbcSqlProvider: failed to connect to {}", sanitizeUrl(url), e)
                connected = false
            }
        } else {
            log.warn("JdbcSqlProvider: no DataSource or URL configured")
            connected = false
        }
    }
    
    // =========================================================================
    // Query Operations
    // =========================================================================
    
    @Override
    List<Map<String, Object>> query(String sql, List<Object> params) {
        if (!connected) {
            initialize()
        }
        
        if (!sql) {
            throw new IllegalArgumentException("SQL query cannot be null")
        }
        
        Connection conn = null
        PreparedStatement stmt = null
        ResultSet rs = null
        
        try {
            conn = getConnection()
            stmt = conn.prepareStatement(sql)
            setParameters(stmt, params)
            
            rs = stmt.executeQuery()
            def results = convertResultSetToList(rs)
            
            log.debug("JdbcSqlProvider: query returned {} rows", results.size())
            return results
            
        } catch (Exception e) {
            log.error("JdbcSqlProvider: query failed: {}", sql, e)
            throw new RuntimeException("Query failed: ${extractRootCause(e)}", e)
        } finally {
            closeQuietly(rs)
            closeQuietly(stmt)
            if (dataSource) closeQuietly(conn)
        }
    }
    
    @Override
    List<Map<String, Object>> query(String sql) {
        return query(sql, [])
    }
    
    @Override
    Map<String, Object> queryForMap(String sql, List<Object> params) {
        def results = query(sql, params)
        return results.isEmpty() ? null : results[0]
    }
    
    @Override
    Map<String, Object> queryForMap(String sql) {
        return queryForMap(sql, [])
    }
    
    @Override
    Object queryForObject(String sql, List<Object> params) {
        def row = queryForMap(sql, params)
        if (!row) return null
        
        // Return first column value
        return row.values().iterator().next()
    }
    
    @Override
    Object queryForObject(String sql) {
        return queryForObject(sql, [])
    }
    
    // =========================================================================
    // Update Operations
    // =========================================================================
    
    @Override
    int executeUpdate(String sql, List<Object> params) {
        if (!connected) {
            initialize()
        }
        
        if (!sql) {
            throw new IllegalArgumentException("SQL statement cannot be null")
        }
        
        Connection conn = null
        PreparedStatement stmt = null
        
        try {
            conn = getConnection()
            stmt = conn.prepareStatement(sql)
            setParameters(stmt, params)
            
            int rowsAffected = stmt.executeUpdate()
            log.debug("JdbcSqlProvider: update affected {} rows", rowsAffected)
            return rowsAffected
            
        } catch (Exception e) {
            log.error("JdbcSqlProvider: update failed: {}", sql, e)
            throw new RuntimeException("Update failed: ${extractRootCause(e)}", e)
        } finally {
            closeQuietly(stmt)
            if (dataSource) closeQuietly(conn)
        }
    }
    
    @Override
    int executeUpdate(String sql) {
        return executeUpdate(sql, [])
    }
    
    // =========================================================================
    // Execute and Transaction Operations
    // =========================================================================
    
    @Override
    Object execute(Closure work) {
        if (!connected) {
            initialize()
        }
        
        Connection conn = null
        try {
            conn = getConnection()
            return work.call(conn)
        } catch (Exception e) {
            log.error("JdbcSqlProvider: execute failed", e)
            throw new RuntimeException("Execute failed: ${extractRootCause(e)}", e)
        } finally {
            if (dataSource) closeQuietly(conn)
        }
    }
    
    @Override
    Object withTransaction(Closure work) {
        if (!connected) {
            initialize()
        }
        
        Connection conn = null
        boolean originalAutoCommit = true
        
        try {
            conn = getConnection()
            originalAutoCommit = conn.getAutoCommit()
            conn.setAutoCommit(false)
            
            def result = work.call(conn)
            
            conn.commit()
            log.debug("JdbcSqlProvider: transaction committed")
            return result
            
        } catch (Exception e) {
            if (conn) {
                try {
                    conn.rollback()
                    log.warn("JdbcSqlProvider: transaction rolled back due to error", e)
                } catch (Exception rollbackEx) {
                    log.error("JdbcSqlProvider: rollback failed", rollbackEx)
                }
            }
            throw new RuntimeException("Transaction failed: ${extractRootCause(e)}", e)
        } finally {
            if (conn) {
                try {
                    conn.setAutoCommit(originalAutoCommit)
                } catch (Exception e) {
                    log.warn("JdbcSqlProvider: failed to restore autoCommit", e)
                }
                if (dataSource) closeQuietly(conn)
            }
        }
    }
    
    // =========================================================================
    // Provider Management
    // =========================================================================
    
    @Override
    void close() {
        if (sharedConnection) {
            closeQuietly(sharedConnection)
            sharedConnection = null
        }
        connected = false
        log.debug("JdbcSqlProvider: closed")
    }
    
    @Override
    String getProviderType() {
        return "JDBC"
    }
    
    @Override
    boolean isConnected() {
        return connected
    }
    
    // =========================================================================
    // Metadata Operations
    // =========================================================================
    
    @Override
    List<DatabaseMetadata.TableInfo> getTables(String catalog, String schema, String tableNamePattern, List<String> types) {
        Connection conn = null
        ResultSet rs = null
        
        try {
            conn = getConnection()
            def meta = conn.getMetaData()
            def typeArray = types ? types as String[] : null
            
            rs = meta.getTables(catalog, schema, tableNamePattern, typeArray)
            
            List<DatabaseMetadata.TableInfo> tables = new ArrayList<>()
            while (rs.next()) {
                def table = new DatabaseMetadata.TableInfo(
                    catalog: rs.getString("TABLE_CAT"),
                    schema: rs.getString("TABLE_SCHEM"),
                    tableName: rs.getString("TABLE_NAME"),
                    type: rs.getString("TABLE_TYPE"),
                    remarks: rs.getString("REMARKS")
                )
                tables << table
            }
            
            return tables
            
        } finally {
            closeQuietly(rs)
            if (!sharedConnection && dataSource) {
                closeQuietly(conn)
            }
        }
    }
    
    @Override
    List<DatabaseMetadata.ColumnInfo> getColumns(String catalog, String schema, String tableName, String columnNamePattern) {
        Connection conn = null
        ResultSet rs = null
        
        try {
            conn = getConnection()
            def meta = conn.getMetaData()
            
            rs = meta.getColumns(catalog, schema, tableName, columnNamePattern)
            
            List<DatabaseMetadata.ColumnInfo> columns = new ArrayList<>()
            while (rs.next()) {
                def column = new DatabaseMetadata.ColumnInfo(
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
                columns << column
            }
            
            return columns
            
        } finally {
            closeQuietly(rs)
            if (!sharedConnection && dataSource) {
                closeQuietly(conn)
            }
        }
    }
    
    @Override
    List<DatabaseMetadata.IndexInfo> getIndexes(String catalog, String schema, String tableName, boolean unique) {
        Connection conn = null
        ResultSet rs = null
        
        try {
            conn = getConnection()
            def meta = conn.getMetaData()
            
            rs = meta.getIndexInfo(catalog, schema, tableName, unique, false)
            
            // Group columns by index name
            Map<String, DatabaseMetadata.IndexInfo> indexMap = new HashMap<>()
            while (rs.next()) {
                def indexName = rs.getString("INDEX_NAME")
                if (!indexName) continue  // Skip statistics
                
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
                    DatabaseMetadata.IndexInfo indexInfo = indexMap.get(indexName)
                    indexInfo.columns.add(columnName)
                }
            }
            
            return new ArrayList<DatabaseMetadata.IndexInfo>(indexMap.values())
            
        } finally {
            closeQuietly(rs)
            if (!sharedConnection && dataSource) {
                closeQuietly(conn)
            }
        }
    }
    
    @Override
    DatabaseMetadata.PrimaryKeyInfo getPrimaryKeys(String catalog, String schema, String tableName) {
        Connection conn = null
        ResultSet rs = null
        
        try {
            conn = getConnection()
            def meta = conn.getMetaData()
            
            rs = meta.getPrimaryKeys(catalog, schema, tableName)
            
            DatabaseMetadata.PrimaryKeyInfo pkInfo = null
            
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
            
            return pkInfo
            
        } finally {
            closeQuietly(rs)
            if (!sharedConnection && dataSource) {
                closeQuietly(conn)
            }
        }
    }
    
    @Override
    List<DatabaseMetadata.ForeignKeyInfo> getForeignKeys(String catalog, String schema, String tableName) {
        Connection conn = null
        ResultSet rs = null
        
        try {
            conn = getConnection()
            def meta = conn.getMetaData()
            
            rs = meta.getImportedKeys(catalog, schema, tableName)
            
            // Group by foreign key name
            Map<String, DatabaseMetadata.ForeignKeyInfo> fkMap = new HashMap<>()
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
                
                def mapping = new DatabaseMetadata.ForeignKeyInfo.ColumnMapping(
                    fkColumn: rs.getString("FKCOLUMN_NAME"),
                    pkColumn: rs.getString("PKCOLUMN_NAME")
                )
                DatabaseMetadata.ForeignKeyInfo fkInfo = fkMap.get(fkName)
                fkInfo.columns.add(mapping)
            }
            
            return new ArrayList<DatabaseMetadata.ForeignKeyInfo>(fkMap.values())
            
        } finally {
            closeQuietly(rs)
            if (!sharedConnection && dataSource) {
                closeQuietly(conn)
            }
        }
    }
    
    @Override
    List<DatabaseMetadata.SchemaInfo> getSchemas() {
        Connection conn = null
        ResultSet rs = null
        
        try {
            conn = getConnection()
            def meta = conn.getMetaData()
            
            rs = meta.getSchemas()
            
            List<DatabaseMetadata.SchemaInfo> schemas = new ArrayList<>()
            while (rs.next()) {
                def schema = new DatabaseMetadata.SchemaInfo(
                    catalog: rs.getString("TABLE_CATALOG"),
                    schemaName: rs.getString("TABLE_SCHEM")
                )
                schemas << schema
            }
            
            return schemas
            
        } finally {
            closeQuietly(rs)
            if (!sharedConnection && dataSource) {
                closeQuietly(conn)
            }
        }
    }
    
    @Override
    List<DatabaseMetadata.CatalogInfo> getCatalogs() {
        Connection conn = null
        ResultSet rs = null
        
        try {
            conn = getConnection()
            def meta = conn.getMetaData()
            
            rs = meta.getCatalogs()
            
            List<DatabaseMetadata.CatalogInfo> catalogs = new ArrayList<>()
            while (rs.next()) {
                def catalog = new DatabaseMetadata.CatalogInfo(
                    catalog: rs.getString("TABLE_CAT")
                )
                catalogs << catalog
            }
            
            return catalogs
            
        } finally {
            closeQuietly(rs)
            if (!sharedConnection && dataSource) {
                closeQuietly(conn)
            }
        }
    }
    
    @Override
    DatabaseMetadata.DatabaseInfo getDatabaseInfo() {
        Connection conn = null
        
        try {
            conn = getConnection()
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
            
        } finally {
            if (!sharedConnection && dataSource) {
                closeQuietly(conn)
            }
        }
    }
    
    // =========================================================================
    // Helper Methods
    // =========================================================================
    
    private Connection getConnection() {
        if (dataSource) {
            return dataSource.getConnection()
        } else if (sharedConnection) {
            return sharedConnection
        } else {
            throw new IllegalStateException("No connection available")
        }
    }
    
    private void setParameters(PreparedStatement stmt, List<Object> params) {
        if (params) {
            params.eachWithIndex { param, index ->
                stmt.setObject(index + 1, param)
            }
        }
    }
    
    private List<Map<String, Object>> convertResultSetToList(ResultSet rs) {
        def results = [] as List<Map<String, Object>>
        ResultSetMetaData metaData = rs.getMetaData()
        int columnCount = metaData.getColumnCount()
        
        while (rs.next()) {
            def row = [:] as Map<String, Object>
            for (int i = 1; i <= columnCount; i++) {
                String columnName = metaData.getColumnLabel(i).toLowerCase()
                Object value = rs.getObject(i)
                row[columnName] = value
            }
            results << row
        }
        
        return results
    }
    
    private void closeQuietly(AutoCloseable closeable) {
        if (closeable) {
            try {
                closeable.close()
            } catch (Exception e) {
                log.trace("Failed to close resource", e)
            }
        }
    }
    
    private static String sanitizeUrl(String url) {
        if (!url) return "<empty>"
        // Remove password from URL for logging
        return url.replaceAll(/password=[^&;]*/, 'password=***')
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
    
    /**
     * Extract the most informative error message from nested SQL exceptions.
     */
    private static String extractRootCause(Throwable throwable) {
        if (!throwable) return "Unknown error"
        
        Throwable current = throwable
        Throwable root = throwable
        String bestMessage = throwable.message ?: "Unknown error"
        
        while (current != null) {
            root = current
            
            if (current.class.name.contains("SQLException") && current.message) {
                bestMessage = current.message
            }
            
            current = current.cause
        }
        
        if (root != throwable && root.message) {
            return root.message
        }
        
        return bestMessage
    }
}
