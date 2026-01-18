package org.softwood.dag.task

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.softwood.dag.task.sql.JdbcSqlProvider
import org.softwood.dag.task.sql.SqlProvider
import org.softwood.dag.task.sql.CriteriaBuilder
import org.softwood.dag.task.sql.SqlMetadataDsl
import org.softwood.promise.Promise

/**
 * SQL database task for executing queries and updates.
 * 
 * <p><strong>ZERO DEPENDENCIES BY DEFAULT:</strong> Uses JdbcSqlProvider
 * which requires only JDBC (built into JDK). Add groovy-sql, H2, or HikariCP
 * for enhanced features.</p>
 * 
 * <h3>Query Mode (SELECT):</h3>
 * <pre>
 * sqlTask("fetch-users") {
 *     provider new JdbcSqlProvider(
 *         url: "jdbc:h2:mem:test",
 *         username: "sa",
 *         password: ""
 *     )
 *     
 *     query "SELECT * FROM users WHERE age > ?"
 *     params 18
 *     
 *     resultMapper { rows ->
 *         rows.collect { [id: it.id, name: it.name] }
 *     }
 * }
 * </pre>
 * 
 * <h3>Update Mode (INSERT/UPDATE/DELETE):</h3>
 * <pre>
 * sqlTask("create-user") {
 *     provider myProvider
 *     
 *     update "INSERT INTO users (name, age) VALUES (?, ?)"
 *     params { prev -> [prev.name, prev.age] }
 * }
 * </pre>
 * 
 * <h3>Transaction Mode (Multi-Operation):</h3>
 * <pre>
 * sqlTask("create-order") {
 *     provider myProvider
 *     
 *     withTransaction { conn ->
 *         // Step 1: Insert order
 *         def stmt1 = conn.prepareStatement("INSERT INTO orders (user_id, total) VALUES (?, ?)")
 *         stmt1.setInt(1, userId)
 *         stmt1.setBigDecimal(2, total)
 *         stmt1.executeUpdate()
 *         
 *         // Step 2: Update inventory
 *         def stmt2 = conn.prepareStatement("UPDATE inventory SET qty = qty - ? WHERE product_id = ?")
 *         stmt2.setInt(1, quantity)
 *         stmt2.setInt(2, productId)
 *         stmt2.executeUpdate()
 *         
 *         return "Order created"
 *     }
 * }
 * </pre>
 * 
 * <h3>Execute Mode (Custom SQL):</h3>
 * <pre>
 * sqlTask("complex-query") {
 *     provider myProvider
 *     
 *     execute { conn ->
 *         // Direct JDBC access
 *         def stmt = conn.createStatement()
 *         def rs = stmt.executeQuery("SELECT COUNT(*) FROM orders")
 *         rs.next()
 *         return rs.getInt(1)
 *     }
 * }
 * </pre>
 * 
 * @since 2.1.0
 */
@Slf4j
@CompileStatic
class SqlTask extends TaskBase<Object> {
    
    // =========================================================================
    // Configuration
    // =========================================================================
    
    /** SQL provider (default: JdbcSqlProvider) */
    private SqlProvider provider
    
    /** Deferred datasource configuration (with resolver) */
    private Closure deferredDataSourceConfig
    
    /** SQL query/statement */
    private String sql
    
    /** Parameters for query/statement */
    private List<Object> sqlParams = []
    
    /** Parameter builder closure (receives prev result) */
    private Closure paramsBuilder
    
    /** Result mapper closure */
    private Closure resultMapper
    
    /** Execute closure (for custom SQL) */
    private Closure executeClosure
    
    /** Update mode (INSERT/UPDATE/DELETE) vs query mode (SELECT) */
    private SqlMode mode = SqlMode.QUERY
    
    /** Criteria builder (for type-safe queries) */
    private CriteriaBuilder criteriaBuilder
    
    /** Metadata DSL (for metadata queries) */
    private SqlMetadataDsl metadataDsl
    
    /** Use transaction for execute mode */
    private boolean useTransaction = false
    
    /** Log SQL statements and parameters */
    private boolean logSql = false
    
    // =========================================================================
    // Constructor
    // =========================================================================
    
    SqlTask(String id, String name, ctx) {
        super(id, name, ctx)
    }
    
    // =========================================================================
    // DSL Configuration Methods
    // =========================================================================
    
    /**
     * Set the SQL provider.
     */
    void provider(SqlProvider provider) {
        this.provider = provider
    }
    
    /**
     * Configure provider with JDBC connection details.
     * Creates a JdbcSqlProvider automatically.
     * 
     * <h3>Static Configuration:</h3>
     * <pre>
     * dataSource {
     *     url = "jdbc:h2:mem:test"
     *     username = "sa"
     *     password = ""
     * }
     * </pre>
     * 
     * <h3>Dynamic Configuration (with resolver):</h3>
     * <pre>
     * dataSource { r ->
     *     url = r.global('db.url')
     *     username = r.credential('db.username')
     *     password = r.credential('db.password')
     * }
     * </pre>
     */
    void dataSource(@DelegatesTo(JdbcSqlProvider) Closure config) {
        def jdbcProvider = new JdbcSqlProvider()
        
        // configureDsl returns false if needs to be deferred
        if (!configureDsl(jdbcProvider, config, null, null)) {
            // Defer until runTask when resolver is available
            this.deferredDataSourceConfig = config
        } else {
            // Executed immediately - initialize provider
            jdbcProvider.initialize()
            this.provider = jdbcProvider
        }
    }
    
    /**
     * Set SQL query (SELECT).
     */
    void query(String sql) {
        this.sql = sql
        this.mode = SqlMode.QUERY
    }
    
    /**
     * Set SQL update statement (INSERT/UPDATE/DELETE).
     */
    void update(String sql) {
        this.sql = sql
        this.mode = SqlMode.UPDATE
    }
    
    /**
     * Set SQL statement.
     * Mode is auto-detected from SQL (SELECT vs INSERT/UPDATE/DELETE).
     */
    void sql(String sql) {
        this.sql = sql
        // Auto-detect mode
        def trimmed = sql.trim().toUpperCase()
        if (trimmed.startsWith("SELECT")) {
            this.mode = SqlMode.QUERY
        } else {
            this.mode = SqlMode.UPDATE
        }
    }
    
    /**
     * Set static parameters.
     */
    void params(Object... params) {
        this.sqlParams = params as List
    }
    
    /**
     * Set parameter builder.
     * 
     * <h3>Legacy (receives prev result directly):</h3>
     * <pre>
     * params { prev -> [prev.name, prev.age] }
     * </pre>
     * 
     * <h3>With Resolver:</h3>
     * <pre>
     * params { r -> [r.prev.name, r.prev.age] }
     * </pre>
     */
    void params(Closure builder) {
        this.paramsBuilder = builder
    }
    
    /**
     * Set result mapper (transforms query results).
     * 
     * <h3>Example:</h3>
     * <pre>
     * resultMapper { rows ->
     *     rows.collect { [id: it.id, name: it.name] }
     * }
     * </pre>
     * 
     * <h3>With Resolver:</h3>
     * <pre>
     * resultMapper { r, rows ->
     *     def format = r.global('date.format', 'yyyy-MM-dd')
     *     rows.collect { [id: it.id, date: formatDate(it.date, format)] }
     * }
     * </pre>
     */
    void resultMapper(Closure mapper) {
        this.resultMapper = mapper
    }
    
    /**
     * Set custom execute closure (receives Connection).
     */
    void execute(Closure closure) {
        this.executeClosure = closure
        this.mode = SqlMode.EXECUTE
    }
    
    /**
     * Execute custom SQL within a transaction.
     * 
     * This is a clearer alternative to execute { } + transaction(true).
     * The closure receives a JDBC Connection.
     * 
     * <h3>Example:</h3>
     * <pre>
     * sqlTask("create-order") {
     *     provider myProvider
     *     
     *     withTransaction { conn ->
     *         // Step 1: Insert order
     *         def stmt1 = conn.prepareStatement("INSERT INTO orders (user_id, total) VALUES (?, ?)")
     *         stmt1.setInt(1, userId)
     *         stmt1.setBigDecimal(2, total)
     *         stmt1.executeUpdate()
     *         
     *         // Step 2: Update inventory
     *         def stmt2 = conn.prepareStatement("UPDATE inventory SET qty = qty - ? WHERE product_id = ?")
     *         stmt2.setInt(1, quantity)
     *         stmt2.setInt(2, productId)
     *         stmt2.executeUpdate()
     *         
     *         // Step 3: Insert audit log
     *         def stmt3 = conn.prepareStatement("INSERT INTO audit_log (action) VALUES (?)")
     *         stmt3.setString(1, "order_created")
     *         stmt3.executeUpdate()
     *         
     *         return "Order created successfully"
     *     }
     * }
     * </pre>
     * 
     * @param closure closure receiving JDBC Connection
     */
    void withTransaction(Closure closure) {
        this.executeClosure = closure
        this.mode = SqlMode.EXECUTE
        this.useTransaction = true
    }
    
    /**
     * Enable transaction for execute mode.
     */
    void transaction(boolean enabled = true) {
        this.useTransaction = enabled
    }
    
    /**
     * Enable SQL logging (logs SQL and parameters at INFO level).
     */
    void logSql(boolean enabled = true) {
        this.logSql = enabled
    }
    
    /**
     * Build query using Hibernate-style criteria.
     * Type-safe alternative to raw SQL.
     */
    void criteria(@DelegatesTo(CriteriaBuilder) Closure config) {
        this.criteriaBuilder = new CriteriaBuilder()
        config.delegate = criteriaBuilder
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.call()
        this.mode = SqlMode.CRITERIA
    }
    
    /**
     * Query database metadata.
     * 
     * <h3>Examples:</h3>
     * <pre>
     * // List all tables
     * sqlTask("list-tables") {
     *     provider myProvider
     *     metadata {
     *         tables()
     *     }
     * }
     * 
     * // Get columns for table
     * sqlTask("describe-users") {
     *     provider myProvider
     *     metadata {
     *         columns("users")
     *     }
     * }
     * 
     * // Get database info
     * sqlTask("db-info") {
     *     provider myProvider
     *     metadata {
     *         databaseInfo()
     *     }
     * }
     * </pre>
     */
    void metadata(@DelegatesTo(SqlMetadataDsl) Closure config) {
        this.metadataDsl = new SqlMetadataDsl(provider)
        config.delegate = metadataDsl
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.call()
        
        this.mode = SqlMode.METADATA
    }
    
    // =========================================================================
    // Task Execution
    // =========================================================================
    
    @Override
    protected Promise<Object> runTask(TaskContext ctx, Object prevValue) {
        return ctx.promiseFactory.executeAsync {
            // Configure deferred datasource if needed
            if (deferredDataSourceConfig && !provider) {
                def jdbcProvider = new JdbcSqlProvider()
                executeDeferredConfig(deferredDataSourceConfig, jdbcProvider, ctx, prevValue)
                jdbcProvider.initialize()
                this.provider = jdbcProvider
            }
            
            if (!provider) {
                throw new IllegalStateException("SqlTask '${id}': provider not configured")
            }
            
            switch (mode) {
                case SqlMode.QUERY:
                    return executeQuery(ctx, prevValue)
                case SqlMode.UPDATE:
                    return executeUpdate(ctx, prevValue)
                case SqlMode.CRITERIA:
                    return executeCriteria(ctx, prevValue)
                case SqlMode.METADATA:
                    return executeMetadata(ctx, prevValue)
                case SqlMode.EXECUTE:
                    return executeCustom(ctx, prevValue)
                default:
                    throw new IllegalStateException("SqlTask: unknown mode ${mode}")
            }
        } as Promise<Object>
    }
    
    private Object executeQuery(TaskContext ctx, Object prevValue) {
        if (!sql) {
            throw new IllegalStateException("SqlTask '${id}': query not configured")
        }
        
        def params = buildParams(ctx, prevValue)
        
        if (logSql) {
            log.info("SqlTask '{}': executing query: {}", id, sql)
            log.info("SqlTask '{}': parameters: {}", id, params)
        }
        
        def results = provider.query(sql, params)
        
        log.info("SqlTask '{}': query returned {} rows", id, results.size())
        
        // Apply result mapper if configured
        if (resultMapper) {
            return applyResultMapper(results, ctx, prevValue)
        }
        
        return results
    }
    
    private Object executeUpdate(TaskContext ctx, Object prevValue) {
        if (!sql) {
            throw new IllegalStateException("SqlTask '${id}': update statement not configured")
        }
        
        def params = buildParams(ctx, prevValue)
        
        if (logSql) {
            log.info("SqlTask '{}': executing update: {}", id, sql)
            log.info("SqlTask '{}': parameters: {}", id, params)
        }
        
        int rowsAffected = provider.executeUpdate(sql, params)
        
        log.info("SqlTask '{}': update affected {} rows", id, rowsAffected)
        
        return [
            rowsAffected: rowsAffected,
            success: true
        ]
    }
    
    private Object executeCriteria(TaskContext ctx, Object prevValue) {
        if (!criteriaBuilder) {
            throw new IllegalStateException("SqlTask '${id}': criteria not configured")
        }
        
        def built = criteriaBuilder.build()
        
        if (logSql) {
            log.info("SqlTask '{}': executing criteria query: {}", id, built.sql)
            log.info("SqlTask '{}': parameters: {}", id, built.params)
        }
        
        def results = provider.query(built.sql as String, built.params as List)
        
        log.info("SqlTask '{}': criteria query returned {} rows", id, results.size())
        
        // Apply result mapper if configured
        if (resultMapper) {
            return applyResultMapper(results, ctx, prevValue)
        }
        
        return results
    }
    
    private Object executeMetadata(TaskContext ctx, Object prevValue) {
        if (!metadataDsl) {
            throw new IllegalStateException("SqlTask '${id}': metadata DSL not configured")
        }
        
        log.info("SqlTask '{}': executing metadata query", id)
        return metadataDsl.execute()
    }
    
    private Object executeCustom(TaskContext ctx, Object prevValue) {
        if (!executeClosure) {
            throw new IllegalStateException("SqlTask '${id}': execute closure not configured")
        }
        
        if (useTransaction) {
            return provider.withTransaction(executeClosure)
        } else {
            return provider.execute(executeClosure)
        }
    }
    
    /**
     * Build parameters using resolver-aware execution.
     */
    private List<Object> buildParams(TaskContext ctx, Object prevValue) {
        if (paramsBuilder) {
            def result = executeWithResolver(paramsBuilder, prevValue, ctx)
            return result instanceof List ? (List<Object>)result : [result]
        }
        return sqlParams
    }
    
    /**
     * Apply result mapper using resolver-aware execution.
     */
    private Object applyResultMapper(Object results, TaskContext ctx, Object prevValue) {
        // Check if mapper expects resolver parameter
        if (resultMapper.maximumNumberOfParameters > 1) {
            // Mapper expects (resolver, results)
            def resolver = createResolver(prevValue, ctx)
            return resultMapper.call(resolver, results)
        } else {
            // Legacy mapper expects just (results)
            return resultMapper.call(results)
        }
    }
    
    // =========================================================================
    // Enums
    // =========================================================================
    
    static enum SqlMode {
        QUERY,      // SELECT
        UPDATE,     // INSERT/UPDATE/DELETE
        CRITERIA,   // Criteria-based query
        METADATA,   // Metadata query
        EXECUTE     // Custom closure
    }
}
