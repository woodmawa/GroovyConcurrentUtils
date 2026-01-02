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
