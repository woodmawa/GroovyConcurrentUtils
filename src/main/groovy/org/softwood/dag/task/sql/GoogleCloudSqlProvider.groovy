package org.softwood.dag.task.sql

import groovy.transform.CompileStatic
import groovy.transform.TypeCheckingMode
import groovy.util.logging.Slf4j
import org.softwood.config.ConfigLoader

/**
 * Google Cloud SQL database provider with ZERO compile-time dependencies.
 *
 * <p><strong>NO DEPENDENCIES:</strong> Uses reflection to avoid requiring
 * JDBC drivers at compile time. Add the appropriate driver at runtime for full functionality.</p>
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
 *   <li>Supports PostgreSQL, MySQL, and SQL Server on Cloud SQL</li>
 *   <li>Connection pooling support via HikariCP</li>
 *   <li>Cloud SQL Socket Factory support for secure connections</li>
 * </ul>
 *
 * <h3>Usage:</h3>
 * <pre>
 * // PostgreSQL on Cloud SQL (recommended - use Unix socket)
 * def provider = new GoogleCloudSqlProvider(
 *     instanceConnectionName: "project:region:instance",
 *     databaseType: "postgresql",
 *     database: "mydb",
 *     username: "user",
 *     password: "pass"
 * )
 *
 * // MySQL on Cloud SQL
 * def provider = new GoogleCloudSqlProvider(
 *     instanceConnectionName: "project:region:instance",
 *     databaseType: "mysql",
 *     database: "mydb",
 *     username: "root",
 *     password: "pass"
 * )
 *
 * // SQL Server on Cloud SQL
 * def provider = new GoogleCloudSqlProvider(
 *     instanceConnectionName: "project:region:instance",
 *     databaseType: "sqlserver",
 *     database: "mydb",
 *     username: "sqlserver",
 *     password: "pass"
 * )
 *
 * // With connection pooling
 * def provider = new GoogleCloudSqlProvider(
 *     instanceConnectionName: "project:region:instance",
 *     databaseType: "postgresql",
 *     database: "mydb",
 *     username: "user",
 *     password: "pass",
 *     poolSize: 10,
 *     maxPoolSize: 20
 * )
 *
 * // Direct TCP/IP connection (not recommended for production)
 * def provider = new GoogleCloudSqlProvider(
 *     host: "1.2.3.4",
 *     port: 5432,
 *     databaseType: "postgresql",
 *     database: "mydb",
 *     username: "user",
 *     password: "pass"
 * )
 * </pre>
 *
 * <h3>Google Cloud SQL Features:</h3>
 * <ul>
 *   <li><b>Fully Managed:</b> Automated backups, replication, and patches</li>
 *   <li><b>High Availability:</b> 99.95% SLA with automatic failover</li>
 *   <li><b>Secure by Default:</b> Encrypted connections via Cloud SQL Proxy</li>
 *   <li><b>Multiple Engines:</b> PostgreSQL, MySQL, SQL Server</li>
 *   <li><b>Auto-scaling:</b> Storage and compute auto-scaling</li>
 *   <li><b>Private IP:</b> VPC peering for private connectivity</li>
 * </ul>
 *
 * <h3>Required Dependencies (choose based on database type):</h3>
 * <ul>
 *   <li><b>PostgreSQL:</b> org.postgresql:postgresql:42.7+</li>
 *   <li><b>MySQL:</b> com.mysql:mysql-connector-j:8.3+</li>
 *   <li><b>SQL Server:</b> com.microsoft.sqlserver:mssql-jdbc:12.6+</li>
 *   <li><b>Cloud SQL Socket (recommended):</b> com.google.cloud.sql:postgres-socket-factory:1.15+ (or mysql-socket-factory, sqlserver-socket-factory)</li>
 *   <li><b>Connection Pooling (optional):</b> com.zaxxer:HikariCP:5.1+</li>
 * </ul>
 *
 * @since 2.3.0
 */
@Slf4j
@CompileStatic
class GoogleCloudSqlProvider implements SqlProvider {

    // =========================================================================
    // Configuration Properties
    // =========================================================================

    /** Google Cloud SQL instance connection name (project:region:instance) */
    String instanceConnectionName

    /** Database type: postgresql, mysql, sqlserver */
    String databaseType = "postgresql"

    /** JDBC URL (auto-generated if not provided) */
    String url

    /** Database host (for direct TCP/IP - not recommended) */
    String host

    /** Database port (for direct TCP/IP - not recommended) */
    Integer port

    /** Database name */
    String database

    /** Database username */
    String username

    /** Database password */
    String password

    /** Enable IAM authentication (Cloud SQL) */
    boolean enableIamAuth = false

    /** Service account credentials path (for IAM auth) */
    String credentialsPath

    /** Schema to use (PostgreSQL/SQL Server only) */
    String schema

    /** SSL mode for PostgreSQL: disable, allow, prefer, require, verify-ca, verify-full */
    String sslMode

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
     * Create GoogleCloudSqlProvider from config file.
     * Reads from database.googlecloudsql section.
     *
     * <h3>Config Example (config.yml):</h3>
     * <pre>
     * database:
     *   googlecloudsql:
     *     instanceConnectionName: "my-project:us-central1:my-instance"
     *     databaseType: postgresql
     *     database: mydb
     *     username: postgres
     *     password: secret
     *     schema: public
     *     poolSize: 5
     *     maxPoolSize: 20
     *     enableIamAuth: false
     * </pre>
     */
    static GoogleCloudSqlProvider fromConfig(Map configOverrides = [:]) {
        def config = ConfigLoader.loadConfig()

        def provider = new GoogleCloudSqlProvider()

        provider.instanceConnectionName = getConfigValue(config, configOverrides, 'database.googlecloudsql.instanceConnectionName', null)
        provider.databaseType = getConfigValue(config, configOverrides, 'database.googlecloudsql.databaseType', 'postgresql')
        provider.database = getConfigValue(config, configOverrides, 'database.googlecloudsql.database', 'postgres')
        provider.username = getConfigValue(config, configOverrides, 'database.googlecloudsql.username', 'postgres')
        provider.password = getConfigValue(config, configOverrides, 'database.googlecloudsql.password', '')
        provider.schema = getConfigValue(config, configOverrides, 'database.googlecloudsql.schema', null)
        provider.enableIamAuth = getConfigValue(config, configOverrides, 'database.googlecloudsql.enableIamAuth', false) as boolean
        provider.credentialsPath = getConfigValue(config, configOverrides, 'database.googlecloudsql.credentialsPath', null)
        provider.poolSize = getConfigValue(config, configOverrides, 'database.googlecloudsql.poolSize', 1) as int
        provider.maxPoolSize = getConfigValue(config, configOverrides, 'database.googlecloudsql.maxPoolSize', 10) as int
        provider.connectionTimeout = getConfigValue(config, configOverrides, 'database.googlecloudsql.connectionTimeout', 30) as int
        provider.idleTimeout = getConfigValue(config, configOverrides, 'database.googlecloudsql.idleTimeout', 600) as int
        provider.logQueries = getConfigValue(config, configOverrides, 'database.googlecloudsql.logQueries', false) as boolean

        // Host/port for direct TCP (fallback)
        provider.host = getConfigValue(config, configOverrides, 'database.googlecloudsql.host', null)
        provider.port = getConfigValue(config, configOverrides, 'database.googlecloudsql.port', null) as Integer

        provider.initialize()
        return provider
    }

    private static Object getConfigValue(Map config, Map overrides, String key, Object defaultValue) {
        if (overrides.containsKey(key)) return overrides[key]

        // Navigate nested map structure
        def keys = key.split('\\.')
        def value = config
        for (String k : keys) {
            if (value instanceof Map && value.containsKey(k)) {
                value = value[k]
            } else {
                return defaultValue
            }
        }
        return value
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

    void initialize() {
        if (initialized) {
            log.warn("GoogleCloudSqlProvider already initialized")
            return
        }

        driverAvailable = checkDriverAvailable()

        if (!driverAvailable) {
            log.warn("Google Cloud SQL JDBC driver not found. Provider running in STUB MODE.")
            log.warn("Add dependency based on database type:")
            log.warn("  PostgreSQL: org.postgresql:postgresql:42.7+")
            log.warn("  MySQL: com.mysql:mysql-connector-j:8.3+")
            log.warn("  SQL Server: com.microsoft.sqlserver:mssql-jdbc:12.6+")
            initialized = true
            return
        }

        if (poolSize > 1) {
            initializeConnectionPool()
        } else {
            initializeSingleConnection()
        }

        initialized = true
        log.info("GoogleCloudSqlProvider initialized successfully (type: {}, pooling: {})", databaseType, poolingEnabled)
    }

    private boolean checkDriverAvailable() {
        def driverClass = getDriverClassName()
        try {
            Class.forName(driverClass)
            return true
        } catch (ClassNotFoundException e) {
            return false
        }
    }

    private String getDriverClassName() {
        switch (databaseType.toLowerCase()) {
            case 'postgresql':
            case 'postgres':
                return 'org.postgresql.Driver'
            case 'mysql':
                return 'com.mysql.cj.jdbc.Driver'
            case 'sqlserver':
            case 'mssql':
                return 'com.microsoft.sqlserver.jdbc.SQLServerDriver'
            default:
                throw new IllegalArgumentException("Unsupported database type: ${databaseType}. Use: postgresql, mysql, or sqlserver")
        }
    }

    @CompileStatic(TypeCheckingMode.SKIP)
    private void initializeSingleConnection() {
        try {
            def driverClass = Class.forName(getDriverClassName())
            def driver = driverClass.getDeclaredConstructor().newInstance()

            def connectionUrl = buildConnectionUrl()
            def properties = buildConnectionProperties()

            dataSource = driver.connect(connectionUrl, properties)
            log.info("Google Cloud SQL connection established: {}", connectionUrl)

        } catch (Exception e) {
            log.error("Failed to establish Google Cloud SQL connection", e)
            throw new RuntimeException("Cloud SQL connection failed: ${e.message}", e)
        }
    }

    @CompileStatic(TypeCheckingMode.SKIP)
    private void initializeConnectionPool() {
        try {
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

            // Add Cloud SQL specific properties
            def props = buildConnectionProperties()
            props.each { key, value ->
                if (key != 'user' && key != 'password') {
                    config.addDataSourceProperty(key as String, value)
                }
            }

            if (schema) {
                config.setSchema(schema)
            }

            dataSource = hikariDataSourceClass.getDeclaredConstructor(hikariClass).newInstance(config)
            poolingEnabled = true

            log.info("Google Cloud SQL connection pool initialized (size: {}-{})", poolSize, maxPoolSize)

        } catch (ClassNotFoundException e) {
            log.warn("HikariCP not found, falling back to single connection")
            log.warn("Add dependency: com.zaxxer:HikariCP:5.1+ for connection pooling")
            initializeSingleConnection()
        } catch (Exception e) {
            log.error("Failed to initialize connection pool", e)
            throw new RuntimeException("Connection pool initialization failed: ${e.message}", e)
        }
    }

    private String buildConnectionUrl() {
        if (url) {
            return url
        }

        def urlBuilder = new StringBuilder()

        switch (databaseType.toLowerCase()) {
            case 'postgresql':
            case 'postgres':
                if (instanceConnectionName) {
                    // Use Cloud SQL Socket Factory
                    urlBuilder.append("jdbc:postgresql:///")
                    urlBuilder.append(database)
                    urlBuilder.append("?cloudSqlInstance=")
                    urlBuilder.append(instanceConnectionName)
                    urlBuilder.append("&socketFactory=com.google.cloud.sql.postgres.SocketFactory")
                    if (enableIamAuth) {
                        urlBuilder.append("&enableIamAuth=true")
                    }
                } else if (host) {
                    // Direct TCP/IP
                    urlBuilder.append("jdbc:postgresql://")
                    urlBuilder.append(host)
                    urlBuilder.append(":")
                    urlBuilder.append(port ?: 5432)
                    urlBuilder.append("/")
                    urlBuilder.append(database)
                }
                break

            case 'mysql':
                if (instanceConnectionName) {
                    urlBuilder.append("jdbc:mysql:///")
                    urlBuilder.append(database)
                    urlBuilder.append("?cloudSqlInstance=")
                    urlBuilder.append(instanceConnectionName)
                    urlBuilder.append("&socketFactory=com.google.cloud.sql.mysql.SocketFactory")
                    if (enableIamAuth) {
                        urlBuilder.append("&enableIamAuth=true")
                    }
                } else if (host) {
                    urlBuilder.append("jdbc:mysql://")
                    urlBuilder.append(host)
                    urlBuilder.append(":")
                    urlBuilder.append(port ?: 3306)
                    urlBuilder.append("/")
                    urlBuilder.append(database)
                }
                break

            case 'sqlserver':
            case 'mssql':
                if (instanceConnectionName) {
                    urlBuilder.append("jdbc:sqlserver://;databaseName=")
                    urlBuilder.append(database)
                    urlBuilder.append(";cloudSqlInstance=")
                    urlBuilder.append(instanceConnectionName)
                    urlBuilder.append(";socketFactory=com.google.cloud.sql.sqlserver.SocketFactory")
                } else if (host) {
                    urlBuilder.append("jdbc:sqlserver://")
                    urlBuilder.append(host)
                    urlBuilder.append(":")
                    urlBuilder.append(port ?: 1433)
                    urlBuilder.append(";databaseName=")
                    urlBuilder.append(database)
                }
                break
        }

        return urlBuilder.toString()
    }

    private Properties buildConnectionProperties() {
        def props = new Properties()
        props.setProperty("user", username)
        props.setProperty("password", password)

        if (credentialsPath) {
            System.setProperty("GOOGLE_APPLICATION_CREDENTIALS", credentialsPath)
        }

        return props
    }

    // =========================================================================
    // Query Operations
    // =========================================================================

    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    List<Map<String, Object>> query(String sql, List<Object> params) {
        if (!driverAvailable) {
            log.warn("Google Cloud SQL driver not available - returning empty result")
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
            log.warn("Google Cloud SQL driver not available - returning 0 rows affected")
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
                    "Custom execution requires JDBC driver. Add appropriate driver to classpath."
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
                    "Transactions require JDBC driver. Add appropriate driver to classpath."
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
    // Metadata Operations (delegating to JDBC metadata)
    // =========================================================================

    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    List<DatabaseMetadata.TableInfo> getTables(String catalog, String schema, String tableNamePattern, List<String> types) {
        if (!driverAvailable) {
            log.warn("Google Cloud SQL driver not available - returning empty table list")
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
            log.warn("Google Cloud SQL driver not available - returning empty column list")
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
        if (!driverAvailable) return []

        def connection = getConnection()
        try {
            def metadata = connection.getMetaData()
            def rs = metadata.getIndexInfo(catalog, schema ?: this.schema, tableName, unique, false)

            def indexes = []
            while (rs.next()) {
                if (rs.getString("INDEX_NAME")) {
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
        if (!driverAvailable) return null

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
        if (!driverAvailable) return []

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
        if (!driverAvailable) return []

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
        if (!driverAvailable) return []

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
        if (!driverAvailable) return null

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
                log.info("Google Cloud SQL provider closed")
            } catch (Exception e) {
                log.error("Error closing Google Cloud SQL provider", e)
            }
        }

        initialized = false
    }

    @Override
    String getProviderType() {
        def type = "Google Cloud SQL (${databaseType})"
        return poolingEnabled ? "${type} - HikariCP" : "${type} - JDBC"
    }

    @Override
    boolean isConnected() {
        return initialized && driverAvailable
    }
}