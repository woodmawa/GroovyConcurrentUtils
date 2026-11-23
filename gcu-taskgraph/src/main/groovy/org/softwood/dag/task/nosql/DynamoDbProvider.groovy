package org.softwood.dag.task.nosql

import groovy.transform.CompileStatic
import groovy.transform.TypeCheckingMode
import groovy.util.logging.Slf4j
import org.softwood.config.ConfigLoader

/**
 * AWS DynamoDB provider with ZERO compile-time dependencies.
 * 
 * <p><strong>NO DEPENDENCIES:</strong> Uses reflection to avoid requiring
 * AWS SDK at compile time. Add the SDK at runtime for full functionality.</p>
 * 
 * <h3>Stub Mode (No SDK):</h3>
 * <ul>
 *   <li>Query methods return empty results</li>
 *   <li>Put/Update methods return null/0</li>
 *   <li>Metadata methods return empty/null</li>
 *   <li>Logs warnings about missing SDK</li>
 * </ul>
 * 
 * <h3>Full Mode (SDK Available):</h3>
 * <ul>
 *   <li>All DynamoDB operations work normally</li>
 *   <li>Supports key-value and document operations</li>
 *   <li>Batch operations supported</li>
 *   <li>Secondary indexes supported</li>
 *   <li>Conditional writes</li>
 * </ul>
 * 
 * <h3>Usage:</h3>
 * <pre>
 * // Basic usage
 * def provider = new DynamoDbProvider(
 *     region: "us-east-1",
 *     accessKeyId: "...",
 *     secretAccessKey: "..."
 * )
 * 
 * // From config
 * def provider = DynamoDbProvider.fromConfig()
 * </pre>
 * 
 * <h3>AWS DynamoDB Features:</h3>
 * <ul>
 *   <li><b>Serverless:</b> Fully managed, no servers to manage</li>
 *   <li><b>Single-digit millisecond latency:</b> Fast key-value access</li>
 *   <li><b>Global tables:</b> Multi-region replication</li>
 *   <li><b>Streams:</b> Change data capture</li>
 *   <li><b>DynamoDB Accelerator (DAX):</b> In-memory caching</li>
 * </ul>
 * 
 * @since 2.3.0
 */
@Slf4j
@CompileStatic
class DynamoDbProvider implements NoSqlProvider {
    
    // =========================================================================
    // Configuration Properties
    // =========================================================================
    
    /** AWS Region (e.g., us-east-1) */
    String region = "us-east-1"
    
    /** AWS Access Key ID (optional if using IAM roles) */
    String accessKeyId
    
    /** AWS Secret Access Key (optional if using IAM roles) */
    String secretAccessKey
    
    /** DynamoDB endpoint URL (for local testing with DynamoDB Local) */
    String endpointUrl
    
    /** Read capacity units for on-demand (reserved for future use) */
    @SuppressWarnings("unused")
    Integer readCapacityUnits
    
    /** Write capacity units for on-demand (reserved for future use) */
    @SuppressWarnings("unused")
    Integer writeCapacityUnits
    
    /** Connection timeout in milliseconds */
    int connectionTimeout = 10000
    
    /** Request timeout in milliseconds */
    int requestTimeout = 30000
    
    // =========================================================================
    // Static Factory Methods
    // =========================================================================
    
    /**
     * Create DynamoDbProvider from config file.
     * Reads from database.dynamodb section.
     * 
     * <h3>Config Example (config.yml):</h3>
     * <pre>
     * database:
     *   dynamodb:
     *     region: us-east-1
     *     accessKeyId: ${AWS_ACCESS_KEY_ID}
     *     secretAccessKey: ${AWS_SECRET_ACCESS_KEY}
     *     endpointUrl: http://localhost:8000  # For local testing
     * </pre>
     */
    static DynamoDbProvider fromConfig(Map configOverrides = [:]) {
        def config = ConfigLoader.loadConfig()
        
        def provider = new DynamoDbProvider()
        
        provider.region = getConfigValue(config, configOverrides, 'database.dynamodb.region', 'us-east-1')
        provider.accessKeyId = getConfigValue(config, configOverrides, 'database.dynamodb.accessKeyId', null)
        provider.secretAccessKey = getConfigValue(config, configOverrides, 'database.dynamodb.secretAccessKey', null)
        provider.endpointUrl = getConfigValue(config, configOverrides, 'database.dynamodb.endpointUrl', null)
        provider.connectionTimeout = getConfigValue(config, configOverrides, 'database.dynamodb.connectionTimeout', 10000) as int
        provider.requestTimeout = getConfigValue(config, configOverrides, 'database.dynamodb.requestTimeout', 30000) as int
        
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
    
    private Object dynamoDbClient  // DynamoDbClient instance
    private boolean sdkAvailable = false
    private boolean initialized = false
    
    // =========================================================================
    // Initialization
    // =========================================================================
    
    @Override
    void initialize() {
        if (initialized) {
            log.warn("DynamoDbProvider already initialized")
            return
        }
        
        sdkAvailable = checkSdkAvailable()
        
        if (!sdkAvailable) {
            log.warn("AWS SDK for DynamoDB not found. Provider running in STUB MODE.")
            log.warn("Add dependency: software.amazon.awssdk:dynamodb:2.20+ for full functionality")
            initialized = true
            return
        }
        
        initializeClient()
        initialized = true
        log.info("DynamoDbProvider initialized (region: {})", region)
    }
    
    private boolean checkSdkAvailable() {
        try {
            Class.forName("software.amazon.awssdk.services.dynamodb.DynamoDbClient")
            return true
        } catch (ClassNotFoundException e) {
            return false
        }
    }
    
    @CompileStatic(TypeCheckingMode.SKIP)
    private void initializeClient() {
        try {
            def builderClass = Class.forName("software.amazon.awssdk.services.dynamodb.DynamoDbClient")
            def builder = builderClass.builder()
            
            // Set region
            def regionClass = Class.forName("software.amazon.awssdk.regions.Region")
            def regionInstance = regionClass.of(region)
            builder.region(regionInstance)
            
            // Set credentials if provided
            if (accessKeyId && secretAccessKey) {
                def credsProviderClass = Class.forName("software.amazon.awssdk.auth.credentials.AwsBasicCredentials")
                def creds = credsProviderClass.create(accessKeyId, secretAccessKey)
                
                def staticProviderClass = Class.forName("software.amazon.awssdk.auth.credentials.StaticCredentialsProvider")
                def credsProvider = staticProviderClass.create(creds)
                builder.credentialsProvider(credsProvider)
            }
            
            // Set endpoint URL if provided (for local testing)
            if (endpointUrl) {
                def uriClass = Class.forName("java.net.URI")
                def uri = uriClass.create(endpointUrl)
                builder.endpointOverride(uri)
            }
            
            dynamoDbClient = builder.build()
            log.info("DynamoDB client created successfully")
            
        } catch (Exception e) {
            log.error("Failed to initialize DynamoDB client", e)
            throw new RuntimeException("DynamoDB initialization failed: ${e.message}", e)
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
            log.warn("DynamoDB SDK not available - returning empty result")
            return []
        }
        
        try {
            // DynamoDB uses Scan or Query depending on whether we have a key condition
            def hasKeyCondition = filter.containsKey('Key') || filter.containsKey('KeyConditionExpression')
            
            if (hasKeyCondition) {
                return executeQuery(collection, filter, projection, options)
            } else {
                return executeScan(collection, filter, projection, options)
            }
            
        } catch (Exception e) {
            log.error("DynamoDB find failed", e)
            throw new RuntimeException("Find operation failed: ${e.message}", e)
        }
    }
    
    @CompileStatic(TypeCheckingMode.SKIP)
    private List<Map<String, Object>> executeQuery(String tableName, Map<String, Object> filter,
                                                     Map<String, Object> projection, QueryOptions options) {
        // Build QueryRequest
        def requestClass = Class.forName("software.amazon.awssdk.services.dynamodb.model.QueryRequest")
        def builder = requestClass.builder()
        
        builder.tableName(tableName)
        
        if (filter.KeyConditionExpression) {
            builder.keyConditionExpression(filter.KeyConditionExpression)
        }
        
        if (filter.ExpressionAttributeValues) {
            builder.expressionAttributeValues(filter.ExpressionAttributeValues)
        }
        
        if (projection) {
            builder.projectionExpression(projection.keySet().join(', '))
        }
        
        if (options?.limit) {
            builder.limit(options.limit)
        }
        
        def request = builder.build()
        def response = dynamoDbClient.query(request)
        
        return response.items().collect { item -> convertDynamoItem(item) }
    }
    
    @CompileStatic(TypeCheckingMode.SKIP)
    private List<Map<String, Object>> executeScan(String tableName, Map<String, Object> filter,
                                                    Map<String, Object> projection, QueryOptions options) {
        // Build ScanRequest
        def requestClass = Class.forName("software.amazon.awssdk.services.dynamodb.model.ScanRequest")
        def builder = requestClass.builder()
        
        builder.tableName(tableName)
        
        if (filter && !filter.isEmpty()) {
            // Build filter expression
            def filterExpr = filter.collect { k, v -> "#${k} = :${k}" }.join(' AND ')
            builder.filterExpression(filterExpr)
            
            // Expression attribute names
            def attrNames = filter.collectEntries { k, v -> ["#${k}", k] }
            builder.expressionAttributeNames(attrNames)
            
            // Expression attribute values
            def attrValues = filter.collectEntries { k, v ->
                def attrValue = createAttributeValue(v)
                [":${k}", attrValue]
            }
            builder.expressionAttributeValues(attrValues)
        }
        
        if (projection) {
            builder.projectionExpression(projection.keySet().join(', '))
        }
        
        if (options?.limit) {
            builder.limit(options.limit)
        }
        
        def request = builder.build()
        def response = dynamoDbClient.scan(request)
        
        return response.items().collect { item -> convertDynamoItem(item) }
    }
    
    @Override
    List<Map<String, Object>> aggregate(String collection, List<Map<String, Object>> pipeline) {
        // DynamoDB doesn't support aggregation pipelines natively
        log.warn("DynamoDB does not support aggregation pipelines")
        return []
    }
    
    // =========================================================================
    // Modification Operations
    // =========================================================================
    
    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    Object insertOne(String collection, Map<String, Object> document) {
        if (!sdkAvailable) {
            log.warn("DynamoDB SDK not available - returning null")
            return null
        }
        
        try {
            def requestClass = Class.forName("software.amazon.awssdk.services.dynamodb.model.PutItemRequest")
            def builder = requestClass.builder()
            
            builder.tableName(collection)
            
            // Convert document to DynamoDB item
            def item = document.collectEntries { k, v ->
                [k, createAttributeValue(v)]
            }
            builder.item(item)
            
            def request = builder.build()
            dynamoDbClient.putItem(request)
            
            return document.id ?: document._id ?: UUID.randomUUID().toString()
            
        } catch (Exception e) {
            log.error("DynamoDB insertOne failed", e)
            throw new RuntimeException("Insert failed: ${e.message}", e)
        }
    }
    
    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    List<Object> insertMany(String collection, List<Map<String, Object>> documents) {
        if (!sdkAvailable) {
            log.warn("DynamoDB SDK not available - returning empty list")
            return []
        }
        
        try {
            // DynamoDB batch write
            def requestClass = Class.forName("software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest")
            def writeRequestClass = Class.forName("software.amazon.awssdk.services.dynamodb.model.WriteRequest")
            def putRequestClass = Class.forName("software.amazon.awssdk.services.dynamodb.model.PutRequest")
            
            def writeRequests = documents.collect { doc ->
                def item = doc.collectEntries { k, v -> [k, createAttributeValue(v)] }
                def putRequest = putRequestClass.builder().item(item).build()
                writeRequestClass.builder().putRequest(putRequest).build()
            }
            
            def builder = requestClass.builder()
            builder.requestItems([(collection): writeRequests])
            
            def request = builder.build()
            dynamoDbClient.batchWriteItem(request)
            
            return documents.collect { it.id ?: it._id ?: UUID.randomUUID().toString() }
            
        } catch (Exception e) {
            log.error("DynamoDB insertMany failed", e)
            throw new RuntimeException("Batch insert failed: ${e.message}", e)
        }
    }
    
    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    long update(String collection, Map<String, Object> filter,
                Map<String, Object> update, UpdateOptions options) {
        if (!sdkAvailable) {
            log.warn("DynamoDB SDK not available - returning 0")
            return 0
        }
        
        try {
            // DynamoDB UpdateItem
            def requestClass = Class.forName("software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest")
            def builder = requestClass.builder()
            
            builder.tableName(collection)
            
            // Key must be provided in filter
            if (filter.Key) {
                builder.key(filter.Key)
            }
            
            // Build update expression
            def updateExpr = update.collect { k, v -> "SET #${k} = :${k}" }.join(', ')
            builder.updateExpression(updateExpr)
            
            // Expression attribute names
            def attrNames = update.collectEntries { k, v -> ["#${k}", k] }
            builder.expressionAttributeNames(attrNames)
            
            // Expression attribute values
            def attrValues = update.collectEntries { k, v ->
                [":${k}", createAttributeValue(v)]
            }
            builder.expressionAttributeValues(attrValues)
            
            def request = builder.build()
            dynamoDbClient.updateItem(request)
            
            return 1  // DynamoDB updates single item
            
        } catch (Exception e) {
            log.error("DynamoDB update failed", e)
            throw new RuntimeException("Update failed: ${e.message}", e)
        }
    }
    
    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    long delete(String collection, Map<String, Object> filter) {
        if (!sdkAvailable) {
            log.warn("DynamoDB SDK not available - returning 0")
            return 0
        }
        
        try {
            def requestClass = Class.forName("software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest")
            def builder = requestClass.builder()
            
            builder.tableName(collection)
            
            // Key must be provided in filter
            if (filter.Key) {
                builder.key(filter.Key)
            }
            
            def request = builder.build()
            dynamoDbClient.deleteItem(request)
            
            return 1  // DynamoDB deletes single item
            
        } catch (Exception e) {
            log.error("DynamoDB delete failed", e)
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
                "Custom execution requires AWS SDK. Add software.amazon.awssdk:dynamodb to classpath."
            )
        }
        return closure.call(dynamoDbClient)
    }
    
    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    Object withTransaction(Closure closure) {
        if (!sdkAvailable) {
            throw new UnsupportedOperationException(
                "Transactions require AWS SDK. Add software.amazon.awssdk:dynamodb to classpath."
            )
        }
        
        // DynamoDB transactions use TransactWriteItems
        log.warn("DynamoDB transactions require TransactWriteItems API")
        return execute(closure)
    }
    
    // =========================================================================
    // Metadata Operations
    // =========================================================================
    
    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    List<NoSqlMetadata.CollectionInfo> listCollections() {
        if (!sdkAvailable) {
            log.warn("DynamoDB SDK not available - returning empty list")
            return []
        }
        
        try {
            def requestClass = Class.forName("software.amazon.awssdk.services.dynamodb.model.ListTablesRequest")
            def request = requestClass.builder().build()
            
            def response = dynamoDbClient.listTables(request)
            
            return response.tableNames().collect { tableName ->
                new NoSqlMetadata.CollectionInfo(
                    name: tableName,
                    type: "table"
                )
            }
            
        } catch (Exception e) {
            log.error("Failed to list DynamoDB tables", e)
            return []
        }
    }
    
    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    NoSqlMetadata.CollectionStats getCollectionStats(String collection) {
        if (!sdkAvailable) {
            log.warn("DynamoDB SDK not available - returning null")
            return null
        }
        
        try {
            def requestClass = Class.forName("software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest")
            def request = requestClass.builder().tableName(collection).build()
            
            def response = dynamoDbClient.describeTable(request)
            def table = response.table()
            
            return new NoSqlMetadata.CollectionStats(
                name: collection,
                count: (table.itemCount() ?: 0L) as Long,
                size: (table.tableSizeBytes() ?: 0L) as Long
            )
            
        } catch (Exception e) {
            log.error("Failed to get DynamoDB table stats", e)
            return null
        }
    }
    
    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    List<NoSqlMetadata.IndexInfo> listIndexes(String collection) {
        if (!sdkAvailable) {
            log.warn("DynamoDB SDK not available - returning empty list")
            return []
        }
        
        try {
            def requestClass = Class.forName("software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest")
            def request = requestClass.builder().tableName(collection).build()
            
            def response = dynamoDbClient.describeTable(request)
            def table = response.table()
            
            def indexes = []
            
            // Global Secondary Indexes
            table.globalSecondaryIndexes()?.each { gsi ->
                indexes << new NoSqlMetadata.IndexInfo(
                    name: gsi.indexName(),
                    keys: gsi.keySchema().collect { it.attributeName() }
                )
            }
            
            // Local Secondary Indexes
            table.localSecondaryIndexes()?.each { lsi ->
                indexes << new NoSqlMetadata.IndexInfo(
                    name: lsi.indexName(),
                    keys: lsi.keySchema().collect { it.attributeName() }
                )
            }
            
            return indexes
            
        } catch (Exception e) {
            log.error("Failed to list DynamoDB indexes", e)
            return []
        }
    }
    
    @Override
    NoSqlMetadata.DatabaseStats getDatabaseStats() {
        // DynamoDB is serverless - no database-level stats
        return null
    }
    
    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    NoSqlMetadata.ServerInfo getServerInfo() {
        // DynamoDB is serverless - no server info
        return new NoSqlMetadata.ServerInfo(
            version: "AWS DynamoDB",
            gitVersion: "N/A",
            sysInfo: [:] as Map<String, Object>,
            maxBsonObjectSize: 400000L,  // 400KB item size limit
            storageEngines: [:] as Map<String, Object>,
            modules: [] as List<String>,
            ok: true
        )
    }
    
    // =========================================================================
    // Helper Methods
    // =========================================================================
    
    @CompileStatic(TypeCheckingMode.SKIP)
    private Object createAttributeValue(Object value) {
        def attrValueClass = Class.forName("software.amazon.awssdk.services.dynamodb.model.AttributeValue")
        def builder = attrValueClass.builder()
        
        if (value == null) {
            return builder.nul(true).build()
        } else if (value instanceof String) {
            return builder.s(value as String).build()
        } else if (value instanceof Number) {
            return builder.n(value.toString()).build()
        } else if (value instanceof Boolean) {
            return builder.bool(value as Boolean).build()
        } else if (value instanceof List) {
            def listValues = (value as List).collect { createAttributeValue(it) }
            return builder.l(listValues).build()
        } else if (value instanceof Map) {
            def mapValues = (value as Map).collectEntries { k, v ->
                [k as String, createAttributeValue(v)]
            }
            return builder.m(mapValues).build()
        } else {
            return builder.s(value.toString()).build()
        }
    }
    
    @CompileStatic(TypeCheckingMode.SKIP)
    private Map<String, Object> convertDynamoItem(Object item) {
        def result = [:]
        
        item.each { key, attrValue ->
            result[key as String] = convertAttributeValue(attrValue)
        }
        
        return result
    }
    
    @CompileStatic(TypeCheckingMode.SKIP)
    private Object convertAttributeValue(Object attrValue) {
        if (attrValue.s() != null) {
            return attrValue.s()
        } else if (attrValue.n() != null) {
            def numStr = attrValue.n()
            return numStr.contains('.') ? Double.parseDouble(numStr) : Long.parseLong(numStr)
        } else if (attrValue.bool() != null) {
            return attrValue.bool()
        } else if (attrValue.nul() != null) {
            return null
        } else if (attrValue.l() != null) {
            return attrValue.l().collect { convertAttributeValue(it) }
        } else if (attrValue.m() != null) {
            return attrValue.m().collectEntries { k, v ->
                [k, convertAttributeValue(v)]
            }
        } else {
            return null
        }
    }
    
    // =========================================================================
    // Provider Interface
    // =========================================================================
    
    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    void close() {
        if (!initialized) return
        
        if (sdkAvailable && dynamoDbClient) {
            try {
                dynamoDbClient.close()
                log.info("DynamoDB provider closed")
            } catch (Exception e) {
                log.error("Error closing DynamoDB provider", e)
            }
        }
        
        initialized = false
    }
}
