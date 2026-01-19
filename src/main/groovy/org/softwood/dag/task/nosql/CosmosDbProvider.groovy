package org.softwood.dag.task.nosql

import groovy.transform.CompileStatic
import groovy.transform.TypeCheckingMode
import groovy.util.logging.Slf4j
import org.softwood.config.ConfigLoader

/**
 * Azure Cosmos DB provider with ZERO compile-time dependencies.
 *
 * <p><strong>NO DEPENDENCIES:</strong> Uses reflection to avoid requiring
 * Azure Cosmos SDK at compile time. Add the SDK at runtime for full functionality.</p>
 *
 * <h3>Stub Mode (No SDK):</h3>
 * <ul>
 *   <li>Query methods return empty results</li>
 *   <li>Create/Replace methods return null/0</li>
 *   <li>Metadata methods return empty/null</li>
 *   <li>Logs warnings about missing SDK</li>
 * </ul>
 *
 * <h3>Full Mode (SDK Available):</h3>
 * <ul>
 *   <li>All Cosmos DB operations work normally</li>
 *   <li>Supports SQL API (most common)</li>
 *   <li>Global distribution with multi-region writes</li>
 *   <li>Multiple consistency levels</li>
 *   <li>Automatic indexing</li>
 *   <li>Change feed support</li>
 * </ul>
 *
 * <h3>Usage:</h3>
 * <pre>
 * // Basic usage
 * def provider = new CosmosDbProvider(
 *     endpoint: "https://myaccount.documents.azure.com:443/",
 *     key: "...",
 *     database: "mydb"
 * )
 *
 * // From config
 * def provider = CosmosDbProvider.fromConfig()
 * </pre>
 *
 * <h3>Azure Cosmos DB Features:</h3>
 * <ul>
 *   <li><b>Multi-Model:</b> SQL, MongoDB, Cassandra, Gremlin, Table APIs</li>
 *   <li><b>Global Distribution:</b> Turnkey global distribution</li>
 *   <li><b>Consistency Levels:</b> 5 well-defined levels</li>
 *   <li><b>SLA:</b> 99.999% availability SLA</li>
 *   <li><b>Serverless:</b> Consumption-based pricing option</li>
 * </ul>
 *
 * @since 2.3.0
 */
@Slf4j
@CompileStatic
class CosmosDbProvider implements NoSqlProvider {

    // =========================================================================
    // Configuration Properties
    // =========================================================================

    /** Cosmos DB endpoint URL */
    String endpoint

    /** Cosmos DB master key or resource token */
    String key

    /** Database name */
    String database

    /** Consistency level: STRONG, BOUNDED_STALENESS, SESSION, CONSISTENT_PREFIX, EVENTUAL */
    String consistencyLevel = "SESSION"

    /** Connection mode: DIRECT or GATEWAY */
    String connectionMode = "DIRECT"

    /** Maximum retry attempts on throttled requests */
    int maxRetryAttempts = 3

    /** Request timeout in seconds */
    int requestTimeout = 60

    /** Preferred regions for multi-region accounts */
    List<String> preferredRegions = []

    // =========================================================================
    // Static Factory Methods
    // =========================================================================

    /**
     * Create CosmosDbProvider from config file.
     * Reads from database.cosmosdb section.
     *
     * <h3>Config Example (config.yml):</h3>
     * <pre>
     * database:
     *   cosmosdb:
     *     endpoint: https://myaccount.documents.azure.com:443/
     *     key: ${COSMOS_KEY}
     *     database: mydb
     *     consistencyLevel: SESSION
     *     connectionMode: DIRECT
     * </pre>
     */
    static CosmosDbProvider fromConfig(Map configOverrides = [:]) {
        def config = ConfigLoader.loadConfig()

        def provider = new CosmosDbProvider()

        provider.endpoint = getConfigValue(config, configOverrides, 'database.cosmosdb.endpoint', null)
        provider.key = getConfigValue(config, configOverrides, 'database.cosmosdb.key', null)
        provider.database = getConfigValue(config, configOverrides, 'database.cosmosdb.database', null)
        provider.consistencyLevel = getConfigValue(config, configOverrides, 'database.cosmosdb.consistencyLevel', 'SESSION')
        provider.connectionMode = getConfigValue(config, configOverrides, 'database.cosmosdb.connectionMode', 'DIRECT')
        provider.maxRetryAttempts = getConfigValue(config, configOverrides, 'database.cosmosdb.maxRetryAttempts', 3) as int
        provider.requestTimeout = getConfigValue(config, configOverrides, 'database.cosmosdb.requestTimeout', 60) as int

        provider.initialize()
        return provider
    }

    private static Object getConfigValue(Map config, Map overrides, String key, Object defaultValue) {
        if (overrides.containsKey(key)) return overrides[key]
        if (config.containsKey(key)) return config[key]
        return defaultValue
    }

    // =========================================================================
    // Internal State
    // =========================================================================

    private Object cosmosClient      // CosmosClient instance
    private Object cosmosDatabase    // CosmosDatabase instance
    private boolean sdkAvailable = false
    private boolean initialized = false

    // =========================================================================
    // Initialization
    // =========================================================================

    @Override
    void initialize() {
        if (initialized) {
            log.warn("CosmosDbProvider already initialized")
            return
        }

        sdkAvailable = checkSdkAvailable()

        if (!sdkAvailable) {
            log.warn("Azure Cosmos DB SDK not found. Provider running in STUB MODE.")
            log.warn("Add dependency: com.azure:azure-cosmos:4.50+ for full functionality")
            initialized = true
            return
        }

        initializeClient()
        initialized = true
        log.info("CosmosDbProvider initialized (database: {})", database)
    }

    private boolean checkSdkAvailable() {
        try {
            Class.forName("com.azure.cosmos.CosmosClient")
            return true
        } catch (ClassNotFoundException e) {
            return false
        }
    }

    @CompileStatic(TypeCheckingMode.SKIP)
    private void initializeClient() {
        try {
            if (!endpoint || !key) {
                throw new IllegalStateException("Cosmos DB endpoint and key are required")
            }

            def builderClass = Class.forName("com.azure.cosmos.CosmosClientBuilder")
            def builder = builderClass.getDeclaredConstructor().newInstance()

            // Set endpoint and key
            builder.endpoint(endpoint)
            builder.key(key)

            // Set consistency level
            if (consistencyLevel) {
                def consistencyClass = Class.forName("com.azure.cosmos.ConsistencyLevel")
                def level = consistencyClass.valueOf(consistencyLevel.toUpperCase())
                builder.consistencyLevel(level)
            }

            // Set connection mode
            if (connectionMode) {
                def connectionPolicyClass = Class.forName("com.azure.cosmos.DirectConnectionConfig")
                def gatewayConfigClass = Class.forName("com.azure.cosmos.GatewayConnectionConfig")

                if (connectionMode.toUpperCase() == "DIRECT") {
                    def directConfig = connectionPolicyClass.getDeclaredConstructor().newInstance()
                    builder.directMode(directConfig)
                } else {
                    def gatewayConfig = gatewayConfigClass.getDeclaredConstructor().newInstance()
                    builder.gatewayMode(gatewayConfig)
                }
            }

            // Set preferred regions if specified
            if (preferredRegions && !preferredRegions.isEmpty()) {
                builder.preferredRegions(preferredRegions)
            }

            cosmosClient = builder.buildClient()

            // Get database reference
            if (database) {
                cosmosDatabase = cosmosClient.getDatabase(database)
            }

            log.info("Cosmos DB client created successfully")

        } catch (Exception e) {
            log.error("Failed to initialize Cosmos DB client", e)
            throw new RuntimeException("Cosmos DB initialization failed: ${e.message}", e)
        }
    }

    // =========================================================================
    // Query Operations
    // =========================================================================

    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    List<Map<String, Object>> find(String collection, Map<String, Object> filter,
                                   Map<String, Object> projection, QueryOptions options) {
        if (!sdkAvailable) {
            log.warn("Cosmos DB SDK not available - returning empty result")
            return []
        }

        try {
            def container = cosmosDatabase.getContainer(collection)

            // Build SQL query from filter
            def query = buildSqlQuery(filter, projection, options)

            // Execute query
            def querySpec = Class.forName("com.azure.cosmos.models.SqlQuerySpec")
                    .getDeclaredConstructor(String).newInstance(query)

            def queryOptions = Class.forName("com.azure.cosmos.models.CosmosQueryRequestOptions")
                    .getDeclaredConstructor().newInstance()

            def pagedIterable = container.queryItems(querySpec, queryOptions, Map)

            def results = []
            pagedIterable.iterableByPage().each { page ->
                page.getResults().each { item ->
                    results << (item as Map<String, Object>)
                }
            }

            return results

        } catch (Exception e) {
            log.error("Cosmos DB find failed", e)
            throw new RuntimeException("Find operation failed: ${e.message}", e)
        }
    }

    @CompileStatic(TypeCheckingMode.SKIP)
    private String buildSqlQuery(Map<String, Object> filter, Map<String, Object> projection, QueryOptions options) {
        def query = new StringBuilder("SELECT ")

        // Projection
        if (projection && !projection.isEmpty()) {
            query.append(projection.keySet().collect { "c.$it" }.join(", "))
        } else {
            query.append("*")
        }

        query.append(" FROM c")

        // Filter (WHERE clause)
        if (filter && !filter.isEmpty()) {
            query.append(" WHERE ")
            def conditions = filter.collect { key, value ->
                if (value instanceof Map && value.containsKey('$eq')) {
                    return "c.$key = '${value['$eq']}'"
                } else if (value instanceof Map && value.containsKey('$gt')) {
                    return "c.$key > ${value['$gt']}"
                } else if (value instanceof Map && value.containsKey('$gte')) {
                    return "c.$key >= ${value['$gte']}"
                } else if (value instanceof Map && value.containsKey('$lt')) {
                    return "c.$key < ${value['$lt']}"
                } else if (value instanceof Map && value.containsKey('$lte')) {
                    return "c.$key <= ${value['$lte']}"
                } else {
                    return "c.$key = '${value}'"
                }
            }
            query.append(conditions.join(" AND "))
        }

        // Sort (ORDER BY)
        if (options?.sort) {
            query.append(" ORDER BY ")
            query.append(options.sort.collect { key, direction ->
                def dir = direction == 1 ? "ASC" : "DESC"
                "c.$key $dir"
            }.join(", "))
        }

        // Limit (OFFSET/LIMIT not fully supported in Cosmos SQL API, using TOP)
        if (options?.limit) {
            // Insert TOP after SELECT
            def selectPos = query.indexOf("SELECT") + 6
            query.insert(selectPos, " TOP ${options.limit}")
        }

        return query.toString()
    }

    @Override
    List<Map<String, Object>> aggregate(String collection, List<Map<String, Object>> pipeline) {
        // Cosmos DB SQL API doesn't support MongoDB-style aggregation pipelines
        log.warn("Cosmos DB SQL API does not support aggregation pipelines directly")
        return []
    }

    // =========================================================================
    // Modification Operations
    // =========================================================================

    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    Object insertOne(String collection, Map<String, Object> document) {
        if (!sdkAvailable) {
            log.warn("Cosmos DB SDK not available - returning null")
            return null
        }

        try {
            def container = cosmosDatabase.getContainer(collection)

            // Ensure document has an id
            if (!document.id && !document._id) {
                document.id = UUID.randomUUID().toString()
            } else if (document._id && !document.id) {
                document.id = document._id.toString()
            }

            def response = container.createItem(document)

            return document.id

        } catch (Exception e) {
            log.error("Cosmos DB insertOne failed", e)
            throw new RuntimeException("Insert failed: ${e.message}", e)
        }
    }

    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    List<Object> insertMany(String collection, List<Map<String, Object>> documents) {
        if (!sdkAvailable) {
            log.warn("Cosmos DB SDK not available - returning empty list")
            return []
        }

        try {
            def container = cosmosDatabase.getContainer(collection)
            def ids = []

            // Cosmos DB doesn't have bulk insert in Java SDK v4
            // Use individual inserts
            documents.each { doc ->
                if (!doc.id && !doc._id) {
                    doc.id = UUID.randomUUID().toString()
                } else if (doc._id && !doc.id) {
                    doc.id = doc._id.toString()
                }

                container.createItem(doc)
                ids << doc.id
            }

            return ids

        } catch (Exception e) {
            log.error("Cosmos DB insertMany failed", e)
            throw new RuntimeException("Batch insert failed: ${e.message}", e)
        }
    }

    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    long update(String collection, Map<String, Object> filter,
                Map<String, Object> update, UpdateOptions options) {
        if (!sdkAvailable) {
            log.warn("Cosmos DB SDK not available - returning 0")
            return 0
        }

        try {
            def container = cosmosDatabase.getContainer(collection)

            // Cosmos DB requires id and partition key for update
            // First, find the documents to update
            def documents = find(collection, filter, null, null)

            def updateCount = 0
            documents.each { doc ->
                // Apply updates
                update.each { key, value ->
                    if (value instanceof Map && value.containsKey('$set')) {
                        doc[key] = value['$set']
                    } else {
                        doc[key] = value
                    }
                }

                // Replace item
                container.replaceItem(doc, doc.id as String, null, null)
                updateCount++
            }

            return updateCount

        } catch (Exception e) {
            log.error("Cosmos DB update failed", e)
            throw new RuntimeException("Update failed: ${e.message}", e)
        }
    }

    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    long delete(String collection, Map<String, Object> filter) {
        if (!sdkAvailable) {
            log.warn("Cosmos DB SDK not available - returning 0")
            return 0
        }

        try {
            def container = cosmosDatabase.getContainer(collection)

            // Find documents to delete
            def documents = find(collection, filter, null, null)

            def deleteCount = 0
            documents.each { doc ->
                container.deleteItem(doc.id as String, null, null)
                deleteCount++
            }

            return deleteCount

        } catch (Exception e) {
            log.error("Cosmos DB delete failed", e)
            throw new RuntimeException("Delete failed: ${e.message}", e)
        }
    }

    // =========================================================================
    // Custom Execution & Transactions
    // =========================================================================

    @Override
    Object execute(Closure closure) {
        if (!sdkAvailable) {
            throw new UnsupportedOperationException(
                    "Custom execution requires Azure Cosmos SDK. Add com.azure:azure-cosmos to classpath."
            )
        }
        return closure.call(cosmosClient)
    }

    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    Object withTransaction(Closure closure) {
        if (!sdkAvailable) {
            throw new UnsupportedOperationException(
                    "Transactions require Azure Cosmos SDK. Add com.azure:azure-cosmos to classpath."
            )
        }

        // Cosmos DB supports transactions via stored procedures or batch operations
        log.warn("Cosmos DB transactions require stored procedures or batch API")
        return execute(closure)
    }

    // =========================================================================
    // Metadata Operations
    // =========================================================================

    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    List<NoSqlMetadata.CollectionInfo> listCollections() {
        if (!sdkAvailable) {
            log.warn("Cosmos DB SDK not available - returning empty list")
            return []
        }

        try {
            def containers = cosmosDatabase.readAllContainers()

            def result = []
            containers.each { container ->
                result << new NoSqlMetadata.CollectionInfo(
                        name: container.getId(),
                        type: "container"
                )
            }

            return result

        } catch (Exception e) {
            log.error("Failed to list Cosmos DB containers", e)
            return []
        }
    }

    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    NoSqlMetadata.CollectionStats getCollectionStats(String collection) {
        if (!sdkAvailable) {
            log.warn("Cosmos DB SDK not available - returning null")
            return null
        }

        try {
            def container = cosmosDatabase.getContainer(collection)

            // Cosmos DB doesn't provide direct item count
            // Would need to execute COUNT query
            def query = "SELECT VALUE COUNT(1) FROM c"
            def querySpec = Class.forName("com.azure.cosmos.models.SqlQuerySpec")
                    .getDeclaredConstructor(String).newInstance(query)

            def results = container.queryItems(querySpec, null, Long)
            def count = results.iterator().hasNext() ? (results.iterator().next() as Long) : 0L

            return new NoSqlMetadata.CollectionStats(
                    name: collection,
                    count: count as Long
            )

        } catch (Exception e) {
            log.error("Failed to get Cosmos DB container stats", e)
            return null
        }
    }

    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    List<NoSqlMetadata.IndexInfo> listIndexes(String collection) {
        if (!sdkAvailable) {
            log.warn("Cosmos DB SDK not available - returning empty list")
            return []
        }

        try {
            def container = cosmosDatabase.getContainer(collection)
            def containerProps = container.read().getProperties()

            // Cosmos DB has automatic indexing
            def indexingPolicy = containerProps.getIndexingPolicy()

            def result = []
            // Cosmos DB indexes are defined in indexing policy
            result << new NoSqlMetadata.IndexInfo(
                    name: "automatic-index",
                    type: "automatic",
                    keys: ["all-paths"]  // Cosmos DB indexes all paths by default
            )

            return result

        } catch (Exception e) {
            log.error("Failed to list Cosmos DB indexes", e)
            return []
        }
    }

    @Override
    NoSqlMetadata.DatabaseStats getDatabaseStats() {
        if (!sdkAvailable) {
            log.warn("Cosmos DB SDK not available - returning null")
            return null
        }

        // Cosmos DB doesn't provide database-level stats via API
        return new NoSqlMetadata.DatabaseStats(
                db: database
        )
    }

    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    NoSqlMetadata.ServerInfo getServerInfo() {
        return new NoSqlMetadata.ServerInfo(
            version: "Azure Cosmos DB",
            gitVersion: "N/A",
            sysInfo: [:] as Map<String, Object>,
            maxBsonObjectSize: 2097152L,  // 2MB default
            storageEngines: [:] as Map<String, Object>,
            modules: [] as List<String>,
            ok: true
        )
    }

    // =========================================================================
    // Provider Interface
    // =========================================================================

    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    void close() {
        if (!initialized) return
        
        if (sdkAvailable && cosmosClient) {
            try {
                cosmosClient.close()
                log.info("Cosmos DB provider closed")
            } catch (Exception e) {
                log.error("Error closing Cosmos DB provider", e)
            }
        }
        
        initialized = false
    }
}