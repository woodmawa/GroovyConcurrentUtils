package org.softwood.dag.task

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.softwood.dag.task.nosql.NoSqlProvider
import org.softwood.dag.task.nosql.MongoProvider
import org.softwood.dag.task.nosql.DocumentCriteriaBuilder
import org.softwood.dag.task.nosql.QueryOptions
import org.softwood.dag.task.nosql.UpdateOptions
import org.softwood.promise.Promise

/**
 * NoSQL database task for document stores and key-value databases.
 * 
 * <p>Supports MongoDB, Couchbase, DynamoDB, and other NoSQL databases
 * through pluggable providers. Mirrors SqlTask's architecture but optimized
 * for document-oriented operations.</p>
 * 
 * <h3>Find Mode (Query):</h3>
 * <pre>
 * noSqlTask("fetch-users") {
 *     provider new MongoProvider(
 *         connectionString: "mongodb://localhost:27017",
 *         databaseName: "mydb"
 *     )
 *     
 *     find "users"
 *     filter age: [$gt: 18], status: "active"
 *     select "name", "email", "age"
 *     orderBy age: "DESC"
 *     limit 10
 * }
 * </pre>
 * 
 * <h3>Criteria DSL:</h3>
 * <pre>
 * noSqlTask("find-active-users") {
 *     provider myProvider
 *     
 *     criteria {
 *         from "users"
 *         select "name", "email"
 *         where {
 *             gt "age", 18
 *             eq "status", "active"
 *             contains "email", "@gmail.com"
 *         }
 *         orderByDesc "createdAt"
 *         limit 100
 *     }
 * }
 * </pre>
 * 
 * <h3>Aggregation Pipeline:</h3>
 * <pre>
 * noSqlTask("sales-by-region") {
 *     provider myProvider
 *     
 *     criteria {
 *         from "sales"
 *         aggregate {
 *             match {
 *                 gte "saleDate", "2024-01-01"
 *                 eq "status", "completed"
 *             }
 *             group([
 *                 _id: '$region',
 *                 totalRevenue: [$sum: '$amount'],
 *                 avgOrder: [$avg: '$amount'],
 *                 count: [$sum: 1]
 *             ])
 *             sort totalRevenue: -1
 *             limit 10
 *         }
 *     }
 * }
 * </pre>
 * 
 * @since 2.2.0
 */
@Slf4j
@CompileStatic
class NoSqlTask extends TaskBase<Object> {
    
    // =========================================================================
    // Configuration
    // =========================================================================
    
    private NoSqlProvider provider
    private NoSqlMode mode = NoSqlMode.FIND
    
    // Collection name
    private String collectionName
    
    // Find mode
    private Map<String, Object> filterMap = [:]
    private Map<String, Object> projectionMap
    private QueryOptions queryOptions = new QueryOptions()
    
    // Insert mode
    private Map<String, Object> documentToInsert
    private List<Map<String, Object>> documentsToInsert
    
    // Update mode
    private Map<String, Object> updateFilter
    private Map<String, Object> updateSpec
    private UpdateOptions updateOptions = new UpdateOptions()
    
    // Delete mode
    private Map<String, Object> deleteFilter
    
    // Aggregation mode
    private List<Map<String, Object>> aggregatePipeline
    
    // Criteria mode
    private DocumentCriteriaBuilder criteriaBuilder
    
    // Execute mode
    private Closure executeClosure
    
    // Result mapper
    private Closure resultMapper
    
    // Transaction support
    private boolean useTransaction = false
    
    // =========================================================================
    // Constructor
    // =========================================================================
    
    NoSqlTask(String id, String name, ctx) {
        super(id, name, ctx)
    }
    
    // =========================================================================
    // Configuration Methods
    // =========================================================================
    
    /**
     * Set the NoSQL provider.
     */
    void provider(NoSqlProvider provider) {
        this.provider = provider
    }
    
    /**
     * Configure MongoDB provider with connection details.
     * Creates a MongoProvider automatically.
     */
    void dataSource(@DelegatesTo(MongoProvider) Closure config) {
        def mongoProvider = new MongoProvider()
        config.delegate = mongoProvider
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.call()
        mongoProvider.initialize()
        this.provider = mongoProvider
    }
    
    // =========================================================================
    // Find Operations
    // =========================================================================
    
    /**
     * Execute a find query on the specified collection.
     */
    void find(String collection) {
        this.collectionName = collection
        this.mode = NoSqlMode.FIND
    }
    
    /**
     * Set filter criteria (MongoDB-style query).
     */
    void filter(Map<String, Object> filter) {
        this.filterMap = filter
    }
    
    /**
     * Select specific fields to return.
     */
    void select(String... fields) {
        this.projectionMap = fields.collectEntries { [(it): 1] }
    }
    
    /**
     * Exclude specific fields from results.
     */
    void exclude(String... fields) {
        if (!projectionMap) {
            projectionMap = [:]
        }
        fields.each { field ->
            projectionMap[field] = 0
        }
    }
    
    /**
     * Set sort order.
     */
    void orderBy(Map<String, String> sortSpec) {
        queryOptions.sort = sortSpec.collectEntries { field, dir ->
            [(field): dir.toUpperCase() == 'DESC' ? -1 : 1]
        }
    }
    
    /**
     * Set result limit.
     */
    void limit(int limit) {
        queryOptions.limit = limit
    }
    
    /**
     * Set result skip (offset).
     */
    void skip(int skip) {
        queryOptions.skip = skip
    }
    
    // =========================================================================
    // Insert Operations
    // =========================================================================
    
    /**
     * Insert a single document.
     */
    void insertOne(String collection, Map<String, Object> document) {
        this.collectionName = collection
        this.documentToInsert = document
        this.mode = NoSqlMode.INSERT_ONE
    }
    
    /**
     * Insert multiple documents.
     */
    void insertMany(String collection, List<Map<String, Object>> documents) {
        this.collectionName = collection
        this.documentsToInsert = documents
        this.mode = NoSqlMode.INSERT_MANY
    }
    
    // =========================================================================
    // Update Operations
    // =========================================================================
    
    /**
     * Update documents matching filter.
     */
    void update(String collection, Map<String, Object> filter, Map<String, Object> update) {
        this.collectionName = collection
        this.updateFilter = filter
        this.updateSpec = update
        this.mode = NoSqlMode.UPDATE
    }
    
    /**
     * Enable upsert (insert if not exists).
     */
    void upsert(boolean enabled = true) {
        updateOptions.upsert = enabled
    }
    
    /**
     * Update multiple documents (default: update only first match).
     */
    void updateMany(boolean enabled = true) {
        updateOptions.multi = enabled
    }
    
    // =========================================================================
    // Delete Operations
    // =========================================================================
    
    /**
     * Delete documents matching filter.
     */
    void delete(String collection, Map<String, Object> filter) {
        this.collectionName = collection
        this.deleteFilter = filter
        this.mode = NoSqlMode.DELETE
    }
    
    // =========================================================================
    // Aggregation
    // =========================================================================
    
    /**
     * Execute aggregation pipeline.
     */
    void aggregate(String collection, List<Map<String, Object>> pipeline) {
        this.collectionName = collection
        this.aggregatePipeline = pipeline
        this.mode = NoSqlMode.AGGREGATE
    }
    
    // =========================================================================
    // Criteria DSL
    // =========================================================================
    
    /**
     * Build query using criteria DSL.
     * Type-safe alternative to raw filter maps.
     */
    void criteria(@DelegatesTo(DocumentCriteriaBuilder) Closure config) {
        this.criteriaBuilder = new DocumentCriteriaBuilder()
        config.delegate = criteriaBuilder
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.call()
        this.mode = NoSqlMode.CRITERIA
    }
    
    // =========================================================================
    // Custom Execution
    // =========================================================================
    
    /**
     * Execute custom code with native client.
     */
    void execute(Closure closure) {
        this.executeClosure = closure
        this.mode = NoSqlMode.EXECUTE
    }
    
    /**
     * Enable transaction for execute mode.
     */
    void transaction(boolean enabled = true) {
        this.useTransaction = enabled
    }
    
    /**
     * Set result mapper (transforms query results).
     */
    void resultMapper(Closure mapper) {
        this.resultMapper = mapper
    }
    
    // =========================================================================
    // Task Execution
    // =========================================================================
    
    @Override
    protected Promise<Object> runTask(TaskContext ctx, Object prevValue) {
        return ctx.promiseFactory.executeAsync {
            if (!provider) {
                throw new IllegalStateException("NoSqlTask: provider not configured")
            }
            
            switch (mode) {
                case NoSqlMode.FIND:
                    return executeFind()
                case NoSqlMode.INSERT_ONE:
                    return executeInsertOne()
                case NoSqlMode.INSERT_MANY:
                    return executeInsertMany()
                case NoSqlMode.UPDATE:
                    return executeUpdate()
                case NoSqlMode.DELETE:
                    return executeDelete()
                case NoSqlMode.AGGREGATE:
                    return executeAggregate()
                case NoSqlMode.CRITERIA:
                    return executeCriteria()
                case NoSqlMode.EXECUTE:
                    return executeCustom()
                default:
                    throw new IllegalStateException("NoSqlTask: unknown mode ${mode}")
            }
        } as Promise<Object>
    }
    
    private Object executeFind() {
        if (!collectionName) {
            throw new IllegalStateException("NoSqlTask: collection not specified")
        }
        
        log.info("NoSqlTask '{}': executing find on collection '{}'", id, collectionName)
        
        def results = provider.find(collectionName, filterMap, projectionMap, queryOptions)
        
        log.info("NoSqlTask '{}': find returned {} documents", id, results.size())
        
        return applyMapper(results)
    }
    
    private Object executeInsertOne() {
        if (!collectionName) {
            throw new IllegalStateException("NoSqlTask: collection not specified")
        }
        if (!documentToInsert) {
            throw new IllegalStateException("NoSqlTask: document not specified")
        }
        
        log.info("NoSqlTask '{}': inserting one document into '{}'", id, collectionName)
        
        def insertedId = provider.insertOne(collectionName, documentToInsert)
        
        log.info("NoSqlTask '{}': inserted document with id {}", id, insertedId)
        
        return [
            insertedId: insertedId,
            success: true
        ]
    }
    
    private Object executeInsertMany() {
        if (!collectionName) {
            throw new IllegalStateException("NoSqlTask: collection not specified")
        }
        if (!documentsToInsert) {
            throw new IllegalStateException("NoSqlTask: documents not specified")
        }
        
        log.info("NoSqlTask '{}': inserting {} documents into '{}'", 
            id, documentsToInsert.size(), collectionName)
        
        def insertedIds = provider.insertMany(collectionName, documentsToInsert)
        
        log.info("NoSqlTask '{}': inserted {} documents", id, insertedIds.size())
        
        return [
            insertedIds: insertedIds,
            count: insertedIds.size(),
            success: true
        ]
    }
    
    private Object executeUpdate() {
        if (!collectionName) {
            throw new IllegalStateException("NoSqlTask: collection not specified")
        }
        if (!updateFilter) {
            throw new IllegalStateException("NoSqlTask: update filter not specified")
        }
        if (!updateSpec) {
            throw new IllegalStateException("NoSqlTask: update specification not specified")
        }
        
        log.info("NoSqlTask '{}': updating documents in '{}'", id, collectionName)
        
        def modifiedCount = provider.update(collectionName, updateFilter, updateSpec, updateOptions)
        
        log.info("NoSqlTask '{}': updated {} documents", id, modifiedCount)
        
        return [
            modifiedCount: modifiedCount,
            success: true
        ]
    }
    
    private Object executeDelete() {
        if (!collectionName) {
            throw new IllegalStateException("NoSqlTask: collection not specified")
        }
        if (!deleteFilter) {
            throw new IllegalStateException("NoSqlTask: delete filter not specified")
        }
        
        log.info("NoSqlTask '{}': deleting documents from '{}'", id, collectionName)
        
        def deletedCount = provider.delete(collectionName, deleteFilter)
        
        log.info("NoSqlTask '{}': deleted {} documents", id, deletedCount)
        
        return [
            deletedCount: deletedCount,
            success: true
        ]
    }
    
    private Object executeAggregate() {
        if (!collectionName) {
            throw new IllegalStateException("NoSqlTask: collection not specified")
        }
        if (!aggregatePipeline) {
            throw new IllegalStateException("NoSqlTask: aggregation pipeline not specified")
        }
        
        log.info("NoSqlTask '{}': executing aggregation on '{}'", id, collectionName)
        
        def results = provider.aggregate(collectionName, aggregatePipeline)
        
        log.info("NoSqlTask '{}': aggregation returned {} results", id, results.size())
        
        return applyMapper(results)
    }
    
    private Object executeCriteria() {
        if (!criteriaBuilder) {
            throw new IllegalStateException("NoSqlTask: criteria not configured")
        }
        
        def built = criteriaBuilder.build()
        
        log.info("NoSqlTask '{}': executing criteria query on '{}'", id, built.collection)
        
        if (built.isAggregation) {
            def results = provider.aggregate(
                built.collection as String,
                built.pipeline as List<Map<String, Object>>
            )
            log.info("NoSqlTask '{}': criteria aggregation returned {} results", id, results.size())
            return applyMapper(results)
        } else {
            def results = provider.find(
                built.collection as String,
                built.filter as Map<String, Object>,
                built.projection as Map<String, Object>,
                built.options as QueryOptions
            )
            log.info("NoSqlTask '{}': criteria query returned {} documents", id, results.size())
            return applyMapper(results)
        }
    }
    
    private Object executeCustom() {
        if (!executeClosure) {
            throw new IllegalStateException("NoSqlTask: execute closure not configured")
        }
        
        log.info("NoSqlTask '{}': executing custom code", id)
        
        if (useTransaction) {
            return provider.withTransaction(executeClosure)
        } else {
            return provider.execute(executeClosure)
        }
    }
    
    private Object applyMapper(Object results) {
        if (resultMapper) {
            return resultMapper.call(results)
        }
        return results
    }
    
    // =========================================================================
    // Enums
    // =========================================================================
    
    static enum NoSqlMode {
        FIND,           // Query documents
        INSERT_ONE,     // Insert single document
        INSERT_MANY,    // Insert multiple documents
        UPDATE,         // Update documents
        DELETE,         // Delete documents
        AGGREGATE,      // Aggregation pipeline
        CRITERIA,       // Criteria DSL
        EXECUTE         // Custom code
    }
}
