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
 * </ul>
 * 
 * <h3>Usage with Direct Connection:</h3>
 * <pre>
 * def provider = new JdbcSqlProvider(
 *     url: "jdbc:h2:mem:test",
 *     username: "sa",
 *     password: ""
 * )
 * 
 * def users = provider.query("SELECT * FROM users WHERE age > ?", [18])
 * provider.close()
 * </pre>
 * 
 * <h3>Usage with DataSource:</h3>
 * <pre>
 * def provider = new JdbcSqlProvider(dataSource: myDataSource)
 * def count = provider.queryForObject("SELECT COUNT(*) FROM orders", [])
 * </pre>
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
            if (dataSource) closeQuietly(conn)  // Only close if from pool
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
