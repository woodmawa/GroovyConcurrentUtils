package org.softwood.dag.task.sql

import groovy.transform.CompileStatic
import groovy.transform.TypeCheckingMode
import groovy.util.logging.Slf4j
import org.softwood.config.ConfigLoader

/**
 * PostgreSQL database provider with ZERO compile-time dependencies.
 * 
 * <p><strong>NO DEPENDENCIES:</strong> Uses reflection to avoid requiring
 * org.postgresql:postgresql at compile time. Add the driver at runtime for full functionality.</p>
 * 
 * <h3>Stub Mode (No Driver):</h3>
 * <ul>
 *   <li>Query methods return empty results</li>
 *   <li>Update methods return 0 rows affected</li>
 *   <li>Metadata methods return empty/null</li>
 *   <li>Logs warnings about missing driver</li>
 * </ul>
 * 
 * <h3>Full Mode (Driver Available):</h3>
 * <ul>
 *   <li>All SQL operations work normally</li>
 *   <li>PostgreSQL-specific features available (JSONB, arrays, COPY, etc.)</li>
 *   <li>Full connection pooling support</li>
 * </ul>
 * 
 * <h3>Usage:</h3>
 * <pre>
 * // Basic usage (no pooling)
 * def provider = new PostgreSqlProvider(
 *     url: "jdbc:postgresql://localhost:5432/mydb",
 *     username: "user",
 *     password: "pass"
 * )
 * 
 * // With connection pooling
 * def provider = new PostgreSqlProvider(
 *     url: "jdbc:postgresql://localhost:5432/mydb",
 *     username: "user",
 *     password: "pass",
 *     poolSize: 10,
 *     maxPoolSize: 20
 * )
 * 
 * // With PostgreSQL-specific options
 * def provider = new PostgreSqlProvider(
 *     url: "jdbc:postgresql://localhost:5432/mydb",
 *     username: "user",
 *     password: "pass",
 *     schema: "public",
 *     sslMode: "require",
 *     applicationName: "MyApp"
 * )
 * </pre>
 * 
 * <h3>PostgreSQL-Specific Features:</h3>
 * <ul>
 *   <li><b>JSONB Support:</b> Store and query JSON data efficiently</li>
 *   <li><b>Array Support:</b> Native PostgreSQL array types</li>
 *   <li><b>COPY Command:</b> Bulk data import/export</li>
 *   <li><b>LISTEN/NOTIFY:</b> Async notifications</li>
 *   <li><b>Full Text Search:</b> Built-in text search</li>
 * </ul>
 * 
 * @since 2.3.0
 */
@Slf4j
@CompileStatic
class PostgreSqlProvider implements SqlProvider {
    
    // =========================================================================
    // Configuration Properties
    // =========================================================================
    
    /** JDBC URL (e.g., jdbc:postgresql://localhost:5432/mydb) */
    String url
    
    /** PostgreSQL host (default: localhost) - used if url not provided */
    String host = "localhost"
    
    /** PostgreSQL port (default: 5432) - used if url not provided */
    int port = 5432
    
    /** Database name - used if url not provided */
    String database
    
    /** Database username */
    String username
    
    /** Database password */
    String password
    
    /** Schema to use (default: public) */
    String schema = "public"
    
    /** SSL mode: disable, allow, prefer, require, verify-ca, verify-full */
    String sslMode
    
    /** Application name (shows in pg_stat_activity) */
    String applicationName
    
    /** Connection pool size (default: 1 = no pooling) */
    int poolSize = 1
    
    /** Maximum pool size (default: 10) */
    int maxPoolSize = 10
    
    /** Connection timeout in seconds (default: 30) */
    int connectionTimeout = 30
    
    /** Idle timeout in seconds (default: 600 = 10 minutes) */
    int idleTimeout = 600
    
    /** Enable query logging (default: false) */
    boolean logQueries = false
    
    // =========================================================================
    // Static Factory Methods
    // =========================================================================
    
    /**
     * Create PostgreSqlProvider from config file.
     * Reads from database.postgres section.
     * 
     * <h3>Config Example (config.yml):</h3>
     * <pre>
     * database:
     *   postgres:
     *     host: localhost
     *     port: 5432
     *     database: mydb
     *     username: postgres
     *     password: secret
     *     schema: public
     *     poolSize: 5
     *     maxPoolSize: 20
     * </pre>
     * 
     * <h3>Usage:</h3>
     * <pre>
     * def provider = PostgreSqlProvider.fromConfig()
     * provider.initialize()
     * </pre>
     */
    static PostgreSqlProvider fromConfig(Map configOverrides = [:]) {
        def config = ConfigLoader.loadConfig()
        
        def provider = new PostgreSqlProvider()
        
        // Apply config values (config.yml or config.groovy)
        provider.host = getConfigValue(config, configOverrides, 'database.postgres.host', 'localhost')
        provider.port = getConfigValue(config, configOverrides, 'database.postgres.port', 5432) as int
        provider.database = getConfigValue(config, configOverrides, 'database.postgres.database', 'postgres')
        provider.username = getConfigValue(config, configOverrides, 'database.postgres.username', 'postgres')
        provider.password = getConfigValue(config, configOverrides, 'database.postgres.password', '')
        provider.schema = getConfigValue(config, configOverrides, 'database.postgres.schema', 'public')
        provider.poolSize = getConfigValue(config, configOverrides, 'database.postgres.poolSize', 1) as int
        provider.maxPoolSize = getConfigValue(config, configOverrides, 'database.postgres.maxPoolSize', 10) as int
        provider.connectionTimeout = getConfigValue(config, configOverrides, 'database.postgres.connectionTimeout', 30) as int
        provider.idleTimeout = getConfigValue(config, configOverrides, 'database.postgres.idleTimeout', 600) as int
        provider.logQueries = getConfigValue(config, configOverrides, 'database.postgres.logQueries', false) as boolean
        
        // Build URL from host/port/database
        if (!provider.url) {
            provider.url = "jdbc:postgresql://${provider.host}:${provider.port}/${provider.database}"
        }
        
        // Auto-initialize for convenience
        provider.initialize()
        
        return provider
    }
    
    private static Object getConfigValue(Map config, Map overrides, String key, Object defaultValue) {
        // Check overrides first
        if (overrides.containsKey(key)) {
            return overrides[key]
        }
        // Then config
        if (config.containsKey(key)) {
            return config[key]
        }
        // Finally default
        return defaultValue
    }
    
    // =========================================================================
    // Internal State
    // =========================================================================
    
    private Object dataSource  // HikariDataSource or single Connection
    private boolean driverAvailable = false
    private boolean poolingEnabled = false
    private boolean initialized = false
    
    // =========================================================================
    // Initialization
    // =========================================================================
    
    /**
     * Initialize the provider - checks for PostgreSQL driver and sets up connection.
     */
    void initialize() {
        if (initialized) {
            log.warn("PostgreSqlProvider already initialized")
            return
        }
        
        // Check if PostgreSQL driver is available
        driverAvailable = checkDriverAvailable()
        
        if (!driverAvailable) {
            log.warn("PostgreSQL JDBC driver not found. Provider running in STUB MODE.")
            log.warn("Add dependency: org.postgresql:postgresql:42.7+ for full functionality")
            initialized = true
            return
        }
        
        // Initialize connection or connection pool
        if (poolSize > 1) {
            initializeConnectionPool()
        } else {
            initializeSingleConnection()
        }
        
        initialized = true
        log.info("PostgreSqlProvider initialized successfully (pooling: {})", poolingEnabled)
    }
    
    /**
     * Check if PostgreSQL JDBC driver is available at runtime.
     */
    private boolean checkDriverAvailable() {
        try {
            Class.forName("org.postgresql.Driver")
            return true
        } catch (ClassNotFoundException e) {
            return false
        }
    }
    
    /**
     * Initialize a single connection (no pooling).
     */
    @CompileStatic(TypeCheckingMode.SKIP)
    private void initializeSingleConnection() {
        try {
            def driverClass = Class.forName("org.postgresql.Driver")
            def driver = driverClass.getDeclaredConstructor().newInstance()
            
            // Build connection URL with optional parameters
            def connectionUrl = buildConnectionUrl()
            
            def properties = new Properties()
            properties.setProperty("user", username)
            properties.setProperty("password", password)
            if (applicationName) {
                properties.setProperty("ApplicationName", applicationName)
            }
            
            dataSource = driver.connect(connectionUrl, properties)
            log.info("PostgreSQL connection established: {}", url)
            
        } catch (Exception e) {
            log.error("Failed to establish PostgreSQL connection", e)
            throw new RuntimeException("PostgreSQL connection failed: ${e.message}", e)
        }
    }
    
    /**
     * Initialize HikariCP connection pool (if available).
     */
    @CompileStatic(TypeCheckingMode.SKIP)
    private void initializeConnectionPool() {
        try {
            // Try to use HikariCP if available
            def hikariClass = Class.forName("com.zaxxer.hikari.HikariConfig")
            def hikariDataSourceClass = Class.forName("com.zaxxer.hikari.HikariDataSource")
            
            def config = hikariClass.getDeclaredConstructor().newInstance()
            config.setJdbcUrl(buildConnectionUrl())
            config.setUsername(username)
            config.setPassword(password)
            config.setMinimumIdle(poolSize)
            config.setMaximumPoolSize(maxPoolSize)
            config.setConnectionTimeout(connectionTimeout * 1000L)
            config.setIdleTimeout(idleTimeout * 1000L)
            
            if (applicationName) {
                config.addDataSourceProperty("ApplicationName", applicationName)
            }
            if (schema) {
                config.setSchema(schema)
            }
            
            dataSource = hikariDataSourceClass.getDeclaredConstructor(hikariClass).newInstance(config)
            poolingEnabled = true
            
            log.info("PostgreSQL connection pool initialized (size: {}-{}))", poolSize, maxPoolSize)
            
        } catch (ClassNotFoundException e) {
            log.warn("HikariCP not found, falling back to single connection")
            log.warn("Add dependency: com.zaxxer:HikariCP:5.1+ for connection pooling")
            initializeSingleConnection()
        } catch (Exception e) {
            log.error("Failed to initialize connection pool", e)
            throw new RuntimeException("Connection pool initialization failed: ${e.message}", e)
        }
    }
    
    /**
     * Build PostgreSQL connection URL with optional parameters.
     */
    private String buildConnectionUrl() {
        def urlBuilder = new StringBuilder(url)
        
        // Add query parameters if not already in URL
        def hasParams = url.contains("?")
        
        if (sslMode) {
            urlBuilder.append(hasParams ? "&" : "?")
            urlBuilder.append("sslmode=").append(sslMode)
            hasParams = true
        }
        
        if (applicationName && !url.contains("ApplicationName")) {
            urlBuilder.append(hasParams ? "&" : "?")
            urlBuilder.append("ApplicationName=").append(URLEncoder.encode(applicationName, "UTF-8"))
            hasParams = true
        }
        
        return urlBuilder.toString()
    }
    
    // =========================================================================
    // Query Operations
    // =========================================================================
    
    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    List<Map<String, Object>> query(String sql, List<Object> params) {
        if (!driverAvailable) {
            log.warn("PostgreSQL driver not available - returning empty result")
            return []
        }
        
        if (logQueries) {
            log.info("Executing query: {} with params: {}", sql, params)
        }
        
        def connection = getConnection()
        try {
            def stmt = connection.prepareStatement(sql)
            setParameters(stmt, params)
            
            def rs = stmt.executeQuery()
            return resultSetToMaps(rs)
            
        } finally {
            releaseConnection(connection)
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
    @CompileStatic(TypeCheckingMode.SKIP)
    Object queryForObject(String sql, List<Object> params) {
        def result = queryForMap(sql, params)
        if (!result) return null
        
        // Return first column value
        return result.values().iterator().next()
    }
    
    @Override
    Object queryForObject(String sql) {
        return queryForObject(sql, [])
    }
    
    // =========================================================================
    // Update Operations
    // =========================================================================
    
    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    int executeUpdate(String sql, List<Object> params) {
        if (!driverAvailable) {
            log.warn("PostgreSQL driver not available - returning 0 rows affected")
            return 0
        }
        
        if (logQueries) {
            log.info("Executing update: {} with params: {}", sql, params)
        }
        
        def connection = getConnection()
        try {
            def stmt = connection.prepareStatement(sql)
            setParameters(stmt, params)
            return stmt.executeUpdate()
            
        } finally {
            releaseConnection(connection)
        }
    }
    
    @Override
    int executeUpdate(String sql) {
        return executeUpdate(sql, [])
    }
    
    // =========================================================================
    // Custom Execution
    // =========================================================================
    
    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    Object execute(Closure work) {
        if (!driverAvailable) {
            throw new UnsupportedOperationException(
                "Custom execution requires PostgreSQL driver. Add org.postgresql:postgresql to classpath."
            )
        }
        
        def connection = getConnection()
        try {
            return work.call(connection)
        } finally {
            releaseConnection(connection)
        }
    }
    
    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    Object withTransaction(Closure work) {
        if (!driverAvailable) {
            throw new UnsupportedOperationException(
                "Transactions require PostgreSQL driver. Add org.postgresql:postgresql to classpath."
            )
        }
        
        def connection = getConnection()
        def autoCommit = connection.getAutoCommit()
        
        try {
            connection.setAutoCommit(false)
            def result = work.call(connection)
            connection.commit()
            return result
            
        } catch (Exception e) {
            connection.rollback()
            throw e
            
        } finally {
            connection.setAutoCommit(autoCommit)
            releaseConnection(connection)
        }
    }
    
    // =========================================================================
    // Metadata Operations
    // =========================================================================
    
    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    List<DatabaseMetadata.TableInfo> getTables(String catalog, String schema, String tableNamePattern, List<String> types) {
        if (!driverAvailable) {
            log.warn("PostgreSQL driver not available - returning empty table list")
            return []
        }
        
        def connection = getConnection()
        try {
            def metadata = connection.getMetaData()
            def rs = metadata.getTables(catalog, schema ?: this.schema, tableNamePattern, types?.toArray(new String[0]))
            
            def tables = []
            while (rs.next()) {
                tables << new DatabaseMetadata.TableInfo(
                    catalog: rs.getString("TABLE_CAT"),
                    schema: rs.getString("TABLE_SCHEM"),
                    name: rs.getString("TABLE_NAME"),
                    type: rs.getString("TABLE_TYPE"),
                    remarks: rs.getString("REMARKS")
                )
            }
            return tables
            
        } finally {
            releaseConnection(connection)
        }
    }
    
    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    List<DatabaseMetadata.ColumnInfo> getColumns(String catalog, String schema, String tableName, String columnNamePattern) {
        if (!driverAvailable) {
            log.warn("PostgreSQL driver not available - returning empty column list")
            return []
        }
        
        def connection = getConnection()
        try {
            def metadata = connection.getMetaData()
            def rs = metadata.getColumns(catalog, schema ?: this.schema, tableName, columnNamePattern)
            
            def columns = []
            while (rs.next()) {
                columns << new DatabaseMetadata.ColumnInfo(
                    catalog: rs.getString("TABLE_CAT"),
                    schema: rs.getString("TABLE_SCHEM"),
                    tableName: rs.getString("TABLE_NAME"),
                    name: rs.getString("COLUMN_NAME"),
                    dataType: rs.getInt("DATA_TYPE"),
                    typeName: rs.getString("TYPE_NAME"),
                    columnSize: rs.getInt("COLUMN_SIZE"),
                    nullable: rs.getInt("NULLABLE") == 1,
                    remarks: rs.getString("REMARKS"),
                    defaultValue: rs.getString("COLUMN_DEF")
                )
            }
            return columns
            
        } finally {
            releaseConnection(connection)
        }
    }
    
    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    List<DatabaseMetadata.IndexInfo> getIndexes(String catalog, String schema, String tableName, boolean unique) {
        if (!driverAvailable) {
            log.warn("PostgreSQL driver not available - returning empty index list")
            return []
        }
        
        def connection = getConnection()
        try {
            def metadata = connection.getMetaData()
            def rs = metadata.getIndexInfo(catalog, schema ?: this.schema, tableName, unique, false)
            
            def indexes = []
            while (rs.next()) {
                if (rs.getString("INDEX_NAME")) {  // Skip null index names
                    indexes << new DatabaseMetadata.IndexInfo(
                        catalog: rs.getString("TABLE_CAT"),
                        schema: rs.getString("TABLE_SCHEM"),
                        tableName: rs.getString("TABLE_NAME"),
                        name: rs.getString("INDEX_NAME"),
                        unique: !rs.getBoolean("NON_UNIQUE"),
                        columnName: rs.getString("COLUMN_NAME"),
                        ordinalPosition: rs.getInt("ORDINAL_POSITION"),
                        type: rs.getInt("TYPE")
                    )
                }
            }
            return indexes
            
        } finally {
            releaseConnection(connection)
        }
    }
    
    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    DatabaseMetadata.PrimaryKeyInfo getPrimaryKeys(String catalog, String schema, String tableName) {
        if (!driverAvailable) {
            log.warn("PostgreSQL driver not available - returning null")
            return null
        }
        
        def connection = getConnection()
        try {
            def metadata = connection.getMetaData()
            def rs = metadata.getPrimaryKeys(catalog, schema ?: this.schema, tableName)
            
            def columns = []
            String pkName = null
            
            while (rs.next()) {
                pkName = rs.getString("PK_NAME")
                columns << rs.getString("COLUMN_NAME")
            }
            
            return pkName ? new DatabaseMetadata.PrimaryKeyInfo(
                catalog: catalog,
                schema: schema ?: this.schema,
                tableName: tableName,
                name: pkName,
                columns: columns
            ) : null
            
        } finally {
            releaseConnection(connection)
        }
    }
    
    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    List<DatabaseMetadata.ForeignKeyInfo> getForeignKeys(String catalog, String schema, String tableName) {
        if (!driverAvailable) {
            log.warn("PostgreSQL driver not available - returning empty foreign key list")
            return []
        }
        
        def connection = getConnection()
        try {
            def metadata = connection.getMetaData()
            def rs = metadata.getImportedKeys(catalog, schema ?: this.schema, tableName)
            
            def foreignKeys = []
            while (rs.next()) {
                foreignKeys << new DatabaseMetadata.ForeignKeyInfo(
                    catalog: rs.getString("FKTABLE_CAT"),
                    schema: rs.getString("FKTABLE_SCHEM"),
                    tableName: rs.getString("FKTABLE_NAME"),
                    name: rs.getString("FK_NAME"),
                    columnName: rs.getString("FKCOLUMN_NAME"),
                    referencedTableCatalog: rs.getString("PKTABLE_CAT"),
                    referencedTableSchema: rs.getString("PKTABLE_SCHEM"),
                    referencedTableName: rs.getString("PKTABLE_NAME"),
                    referencedColumnName: rs.getString("PKCOLUMN_NAME"),
                    updateRule: rs.getInt("UPDATE_RULE"),
                    deleteRule: rs.getInt("DELETE_RULE")
                )
            }
            return foreignKeys
            
        } finally {
            releaseConnection(connection)
        }
    }
    
    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    List<DatabaseMetadata.SchemaInfo> getSchemas() {
        if (!driverAvailable) {
            log.warn("PostgreSQL driver not available - returning empty schema list")
            return []
        }
        
        def connection = getConnection()
        try {
            def metadata = connection.getMetaData()
            def rs = metadata.getSchemas()
            
            def schemas = []
            while (rs.next()) {
                schemas << new DatabaseMetadata.SchemaInfo(
                    name: rs.getString("TABLE_SCHEM"),
                    catalog: rs.getString("TABLE_CATALOG")
                )
            }
            return schemas
            
        } finally {
            releaseConnection(connection)
        }
    }
    
    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    List<DatabaseMetadata.CatalogInfo> getCatalogs() {
        if (!driverAvailable) {
            log.warn("PostgreSQL driver not available - returning empty catalog list")
            return []
        }
        
        def connection = getConnection()
        try {
            def metadata = connection.getMetaData()
            def rs = metadata.getCatalogs()
            
            def catalogs = []
            while (rs.next()) {
                catalogs << new DatabaseMetadata.CatalogInfo(
                    name: rs.getString("TABLE_CAT")
                )
            }
            return catalogs
            
        } finally {
            releaseConnection(connection)
        }
    }
    
    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    DatabaseMetadata.DatabaseInfo getDatabaseInfo() {
        if (!driverAvailable) {
            log.warn("PostgreSQL driver not available - returning null")
            return null
        }
        
        def connection = getConnection()
        try {
            def metadata = connection.getMetaData()
            
            return new DatabaseMetadata.DatabaseInfo(
                productName: metadata.getDatabaseProductName(),
                productVersion: metadata.getDatabaseProductVersion(),
                driverName: metadata.getDriverName(),
                driverVersion: metadata.getDriverVersion(),
                url: metadata.getURL(),
                username: metadata.getUserName(),
                supportsTransactions: metadata.supportsTransactions(),
                defaultTransactionIsolation: metadata.getDefaultTransactionIsolation()
            )
            
        } finally {
            releaseConnection(connection)
        }
    }
    
    // =========================================================================
    // Connection Management
    // =========================================================================
    
    @CompileStatic(TypeCheckingMode.SKIP)
    private Object getConnection() {
        if (poolingEnabled) {
            return dataSource.getConnection()
        } else {
            return dataSource
        }
    }
    
    @CompileStatic(TypeCheckingMode.SKIP)
    private void releaseConnection(Object connection) {
        if (poolingEnabled) {
            connection.close()
        }
        // For single connection, don't close it
    }
    
    // =========================================================================
    // Helper Methods
    // =========================================================================
    
    @CompileStatic(TypeCheckingMode.SKIP)
    private void setParameters(Object stmt, List<Object> params) {
        params.eachWithIndex { param, index ->
            stmt.setObject(index + 1, param)
        }
    }
    
    @CompileStatic(TypeCheckingMode.SKIP)
    private List<Map<String, Object>> resultSetToMaps(Object rs) {
        def results = []
        def metadata = rs.getMetaData()
        def columnCount = metadata.getColumnCount()
        
        while (rs.next()) {
            def row = [:]
            for (int i = 1; i <= columnCount; i++) {
                def columnName = metadata.getColumnName(i)
                row[columnName] = rs.getObject(i)
            }
            results << row
        }
        
        return results
    }
    
    // =========================================================================
    // Provider Interface
    // =========================================================================
    
    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    void close() {
        if (!initialized) return
        
        if (driverAvailable && dataSource) {
            try {
                if (poolingEnabled) {
                    dataSource.close()
                } else {
                    dataSource.close()
                }
                log.info("PostgreSQL provider closed")
            } catch (Exception e) {
                log.error("Error closing PostgreSQL provider", e)
            }
        }
        
        initialized = false
    }
    
    @Override
    String getProviderType() {
        return poolingEnabled ? "PostgreSQL (HikariCP)" : "PostgreSQL (JDBC)"
    }
    
    @Override
    boolean isConnected() {
        return initialized && driverAvailable
    }
}
