package org.softwood.dag.task.nosql

import groovy.transform.CompileStatic
import groovy.transform.TypeCheckingMode
import groovy.util.logging.Slf4j

/**
 * MongoDB implementation of NoSqlProvider.
 * 
 * <p><strong>Dependencies:</strong> Requires org.mongodb:mongodb-driver-sync:4.11+
 * to be available at runtime. The provider will work in "stub mode" for testing
 * if MongoDB driver is not available.</p>
 * 
 * <h3>Configuration Example:</h3>
 * <pre>
 * def provider = new MongoProvider(
 *     connectionString: "mongodb://localhost:27017",
 *     databaseName: "mydb"
 * )
 * provider.initialize()
 * </pre>
 * 
 * @since 2.2.0
 */
@Slf4j
@CompileStatic
class MongoProvider implements NoSqlProvider {
    
    // =========================================================================
    // Configuration
    // =========================================================================
    
    /** MongoDB connection string (e.g., "mongodb://localhost:27017") */
    String connectionString
    
    /** Database name to use */
    String databaseName
    
    // =========================================================================
    // Runtime - Using Object types to avoid compile-time dependency on MongoDB
    // =========================================================================
    
    private Object client          // MongoClient
    private Object database        // MongoDatabase
    private boolean initialized = false
    private boolean mongoAvailable = false
    
    // =========================================================================
    // Initialization
    // =========================================================================
    
    @Override
    void initialize() {
        if (initialized) {
            log.warn("MongoProvider already initialized")
            return
        }
        
        if (!connectionString) {
            throw new IllegalStateException("MongoDB connectionString not configured")
        }
        if (!databaseName) {
            throw new IllegalStateException("MongoDB databaseName not configured")
        }
        
        try {
            // Check if MongoDB driver is available
            Class.forName("com.mongodb.client.MongoClients")
            mongoAvailable = true
            
            // Use reflection to avoid compile-time dependency
            def mongoClientsClass = Class.forName("com.mongodb.client.MongoClients")
            def createMethod = mongoClientsClass.getMethod("create", String)
            client = createMethod.invoke(null, connectionString)
            
            def getDatabaseMethod = client.getClass().getMethod("getDatabase", String)
            database = getDatabaseMethod.invoke(client, databaseName)
            
            initialized = true
            log.info("MongoProvider initialized: {}/{}", connectionString, databaseName)
            
        } catch (ClassNotFoundException e) {
            log.warn("MongoDB driver not found. Provider will run in stub mode. " +
                    "Add org.mongodb:mongodb-driver-sync:4.11+ to classpath for full functionality.")
            mongoAvailable = false
            initialized = true
        }
    }
    
    @Override
    void close() {
        if (client && mongoAvailable) {
            try {
                def closeMethod = client.getClass().getMethod("close")
                closeMethod.invoke(client)
                log.info("MongoProvider connection closed")
            } catch (Exception e) {
                log.error("Error closing MongoDB connection", e)
            }
        }
        initialized = false
    }
    
    // =========================================================================
    // Query Operations
    // =========================================================================
    
    @Override
    List<Map<String, Object>> find(
        String collection, 
        Map<String, Object> filter,
        Map<String, Object> projection,
        QueryOptions options
    ) {
        ensureInitialized()
        
        if (!mongoAvailable) {
            return stubFind(collection, filter, projection, options)
        }
        
        try {
            def coll = getCollection(collection)
            def documentClass = Class.forName("org.bson.Document")
            
            // Create filter document
            def filterDoc = documentClass.getDeclaredConstructor(Map).newInstance(filter ?: [:])
            
            // Execute find
            def findMethod = coll.getClass().getMethod("find", documentClass)
            def query = findMethod.invoke(coll, filterDoc)
            
            // Apply projection
            if (projection) {
                def projectionDoc = documentClass.getDeclaredConstructor(Map).newInstance(projection)
                def projectionMethod = query.getClass().getMethod("projection", documentClass)
                query = projectionMethod.invoke(query, projectionDoc)
            }
            
            // Apply options
            if (options) {
                if (options.sort) {
                    def sortDoc = documentClass.getDeclaredConstructor(Map).newInstance(options.sort)
                    def sortMethod = query.getClass().getMethod("sort", documentClass)
                    query = sortMethod.invoke(query, sortDoc)
                }
                if (options.limit != null) {
                    def limitMethod = query.getClass().getMethod("limit", int)
                    query = limitMethod.invoke(query, options.limit)
                }
                if (options.skip != null) {
                    def skipMethod = query.getClass().getMethod("skip", int)
                    query = skipMethod.invoke(query, options.skip)
                }
            }
            
            // Get results
            def intoMethod = query.getClass().getMethod("into", Collection)
            def results = intoMethod.invoke(query, []) as List
            
            return results.collect { doc -> doc as Map<String, Object> }
            
        } catch (Exception e) {
            log.error("Error executing MongoDB find", e)
            throw new RuntimeException("MongoDB find failed: ${e.message}", e)
        }
    }
    
    @Override
    List<Map<String, Object>> aggregate(
        String collection,
        List<Map<String, Object>> pipeline
    ) {
        ensureInitialized()
        
        if (!mongoAvailable) {
            return stubAggregate(collection, pipeline)
        }
        
        try {
            def coll = getCollection(collection)
            def documentClass = Class.forName("org.bson.Document")
            
            // Convert pipeline to Document objects
            def docPipeline = pipeline.collect { stage ->
                documentClass.getDeclaredConstructor(Map).newInstance(stage)
            }
            
            // Execute aggregation
            def aggregateMethod = coll.getClass().getMethod("aggregate", List)
            def aggregateResult = aggregateMethod.invoke(coll, docPipeline)
            
            // Get results
            def intoMethod = aggregateResult.getClass().getMethod("into", Collection)
            def results = intoMethod.invoke(aggregateResult, []) as List
            
            return results.collect { doc -> doc as Map<String, Object> }
            
        } catch (Exception e) {
            log.error("Error executing MongoDB aggregation", e)
            throw new RuntimeException("MongoDB aggregation failed: ${e.message}", e)
        }
    }
    
    // =========================================================================
    // Insert Operations
    // =========================================================================
    
    @Override
    Object insertOne(String collection, Map<String, Object> document) {
        ensureInitialized()
        
        if (!mongoAvailable) {
            return stubInsertOne(collection, document)
        }
        
        try {
            def coll = getCollection(collection)
            def documentClass = Class.forName("org.bson.Document")
            def doc = documentClass.getDeclaredConstructor(Map).newInstance(document)
            
            def insertOneMethod = coll.getClass().getMethod("insertOne", documentClass)
            insertOneMethod.invoke(coll, doc)
            
            def getMethod = doc.getClass().getMethod("get", Object)
            return getMethod.invoke(doc, "_id")
            
        } catch (Exception e) {
            log.error("Error executing MongoDB insertOne", e)
            throw new RuntimeException("MongoDB insertOne failed: ${e.message}", e)
        }
    }
    
    @Override
    List<Object> insertMany(String collection, List<Map<String, Object>> documents) {
        ensureInitialized()
        
        if (!mongoAvailable) {
            return stubInsertMany(collection, documents)
        }
        
        try {
            def coll = getCollection(collection)
            def documentClass = Class.forName("org.bson.Document")
            
            def docs = documents.collect { docMap ->
                documentClass.getDeclaredConstructor(Map).newInstance(docMap)
            }
            
            def insertManyMethod = coll.getClass().getMethod("insertMany", List)
            insertManyMethod.invoke(coll, docs)
            
            return docs.collect { doc ->
                def getMethod = doc.getClass().getMethod("get", Object)
                getMethod.invoke(doc, "_id")
            }
            
        } catch (Exception e) {
            log.error("Error executing MongoDB insertMany", e)
            throw new RuntimeException("MongoDB insertMany failed: ${e.message}", e)
        }
    }
    
    // =========================================================================
    // Update Operations
    // =========================================================================
    
    @Override
    long update(
        String collection,
        Map<String, Object> filter,
        Map<String, Object> update,
        UpdateOptions options
    ) {
        ensureInitialized()
        
        if (!mongoAvailable) {
            return stubUpdate(collection, filter, update, options)
        }
        
        try {
            def coll = getCollection(collection)
            def documentClass = Class.forName("org.bson.Document")
            
            def filterDoc = documentClass.getDeclaredConstructor(Map).newInstance(filter)
            def updateDoc = documentClass.getDeclaredConstructor(Map).newInstance(update)
            
            def updateOptionsClass = Class.forName("com.mongodb.client.model.UpdateOptions")
            def updateOpts = updateOptionsClass.getDeclaredConstructor().newInstance()
            
            if (options?.upsert) {
                def upsertMethod = updateOpts.getClass().getMethod("upsert", boolean)
                upsertMethod.invoke(updateOpts, true)
            }
            
            def result
            if (options?.multi) {
                def updateManyMethod = coll.getClass().getMethod("updateMany", 
                    documentClass, documentClass, updateOptionsClass)
                result = updateManyMethod.invoke(coll, filterDoc, updateDoc, updateOpts)
            } else {
                def updateOneMethod = coll.getClass().getMethod("updateOne", 
                    documentClass, documentClass, updateOptionsClass)
                result = updateOneMethod.invoke(coll, filterDoc, updateDoc, updateOpts)
            }
            
            def getModifiedCountMethod = result.getClass().getMethod("getModifiedCount")
            return getModifiedCountMethod.invoke(result) as long
            
        } catch (Exception e) {
            log.error("Error executing MongoDB update", e)
            throw new RuntimeException("MongoDB update failed: ${e.message}", e)
        }
    }
    
    // =========================================================================
    // Delete Operations
    // =========================================================================
    
    @Override
    long delete(String collection, Map<String, Object> filter) {
        ensureInitialized()
        
        if (!mongoAvailable) {
            return stubDelete(collection, filter)
        }
        
        try {
            def coll = getCollection(collection)
            def documentClass = Class.forName("org.bson.Document")
            def filterDoc = documentClass.getDeclaredConstructor(Map).newInstance(filter)
            
            def deleteManyMethod = coll.getClass().getMethod("deleteMany", documentClass)
            def result = deleteManyMethod.invoke(coll, filterDoc)
            
            def getDeletedCountMethod = result.getClass().getMethod("getDeletedCount")
            return getDeletedCountMethod.invoke(result) as long
            
        } catch (Exception e) {
            log.error("Error executing MongoDB delete", e)
            throw new RuntimeException("MongoDB delete failed: ${e.message}", e)
        }
    }
    
    // =========================================================================
    // Custom Execution
    // =========================================================================
    
    @Override
    Object execute(Closure closure) {
        ensureInitialized()
        
        if (!mongoAvailable) {
            throw new UnsupportedOperationException(
                "Custom execution requires MongoDB driver. Add org.mongodb:mongodb-driver-sync to classpath."
            )
        }
        
        return closure.call(client)
    }
    
    @Override
    Object withTransaction(Closure closure) {
        ensureInitialized()
        
        if (!mongoAvailable) {
            throw new UnsupportedOperationException(
                "Transactions require MongoDB driver. Add org.mongodb:mongodb-driver-sync to classpath."
            )
        }
        
        try {
            def startSessionMethod = client.getClass().getMethod("startSession")
            def session = startSessionMethod.invoke(client)
            
            try {
                def startTransactionMethod = session.getClass().getMethod("startTransaction")
                startTransactionMethod.invoke(session)
                
                def result = closure.call(session)
                
                def commitTransactionMethod = session.getClass().getMethod("commitTransaction")
                commitTransactionMethod.invoke(session)
                
                return result
            } catch (Exception e) {
                def abortTransactionMethod = session.getClass().getMethod("abortTransaction")
                abortTransactionMethod.invoke(session)
                throw e
            } finally {
                def closeMethod = session.getClass().getMethod("close")
                closeMethod.invoke(session)
            }
        } catch (Exception e) {
            log.error("Error executing MongoDB transaction", e)
            throw new RuntimeException("MongoDB transaction failed: ${e.message}", e)
        }
    }
    
    // =========================================================================
    // Metadata Operations
    // =========================================================================
    
    @CompileStatic(TypeCheckingMode.SKIP)
    @Override
    List<NoSqlMetadata.CollectionInfo> listCollections() {
        ensureInitialized()
        
        if (!mongoAvailable) {
            log.warn("MongoProvider running in stub mode: listCollections()")
            return []
        }
        
        try {
            def listCollectionsMethod = database.getClass().getMethod("listCollections")
            def collections = listCollectionsMethod.invoke(database)
            
            List<NoSqlMetadata.CollectionInfo> result = new ArrayList<>()
            def iteratorMethod = collections.getClass().getMethod("iterator")
            def iterator = iteratorMethod.invoke(collections)
            
            while (iterator.hasNext()) {
                def doc = iterator.next()
                result << new NoSqlMetadata.CollectionInfo(
                    name: doc.getString("name"),
                    type: doc.getString("type") ?: "collection",
                    options: doc.get("options") as Map<String, Object> ?: [:]
                )
            }
            
            return result
        } catch (Exception e) {
            log.error("Error listing collections", e)
            throw new RuntimeException("Failed to list collections: ${e.message}", e)
        }
    }
    
    @CompileStatic(TypeCheckingMode.SKIP)
    @Override
    NoSqlMetadata.CollectionStats getCollectionStats(String collection) {
        ensureInitialized()
        
        if (!mongoAvailable) {
            log.warn("MongoProvider running in stub mode: collectionStats({})", collection)
            return null
        }
        
        try {
            def documentClass = Class.forName("org.bson.Document")
            def command = documentClass.getDeclaredConstructor(Map).newInstance([collStats: collection])
            
            def runCommandMethod = database.getClass().getMethod("runCommand", documentClass)
            def stats = runCommandMethod.invoke(database, command)
            
            return new NoSqlMetadata.CollectionStats(
                name: collection,
                count: stats.getLong("count") ?: 0L,
                size: stats.getLong("size") ?: 0L,
                storageSize: stats.getLong("storageSize") ?: 0L,
                nindexes: stats.getInteger("nindexes") ?: 0,
                totalIndexSize: stats.getLong("totalIndexSize") ?: 0L,
                avgObjSize: stats.getDouble("avgObjSize") ?: 0.0,
                wiredTiger: stats.get("wiredTiger") as Map<String, Object> ?: [:]
            )
        } catch (Exception e) {
            log.error("Error getting collection stats for {}", collection, e)
            throw new RuntimeException("Failed to get collection stats: ${e.message}", e)
        }
    }
    
    @CompileStatic(TypeCheckingMode.SKIP)
    @Override
    List<NoSqlMetadata.IndexInfo> listIndexes(String collection) {
        ensureInitialized()
        
        if (!mongoAvailable) {
            log.warn("MongoProvider running in stub mode: listIndexes({})", collection)
            return []
        }
        
        try {
            def coll = getCollection(collection)
            def listIndexesMethod = coll.getClass().getMethod("listIndexes")
            def indexes = listIndexesMethod.invoke(coll)
            
            List<NoSqlMetadata.IndexInfo> result = new ArrayList<>()
            def iteratorMethod = indexes.getClass().getMethod("iterator")
            def iterator = iteratorMethod.invoke(indexes)
            
            while (iterator.hasNext()) {
                def doc = iterator.next()
                result << new NoSqlMetadata.IndexInfo(
                    name: doc.getString("name"),
                    key: doc.get("key") as Map<String, Object>,
                    unique: doc.getBoolean("unique") ?: false,
                    sparse: doc.getBoolean("sparse") ?: false,
                    background: doc.get("background")?.toString(),
                    options: [:]
                )
            }
            
            return result
        } catch (Exception e) {
            log.error("Error listing indexes for {}", collection, e)
            throw new RuntimeException("Failed to list indexes: ${e.message}", e)
        }
    }
    
    @CompileStatic(TypeCheckingMode.SKIP)
    @Override
    NoSqlMetadata.DatabaseStats getDatabaseStats() {
        ensureInitialized()
        
        if (!mongoAvailable) {
            log.warn("MongoProvider running in stub mode: databaseStats()")
            return null
        }
        
        try {
            def documentClass = Class.forName("org.bson.Document")
            def command = documentClass.getDeclaredConstructor(Map).newInstance([dbStats: 1])
            
            def runCommandMethod = database.getClass().getMethod("runCommand", documentClass)
            def stats = runCommandMethod.invoke(database, command)
            
            return new NoSqlMetadata.DatabaseStats(
                db: stats.getString("db"),
                collections: stats.getLong("collections") ?: 0L,
                views: stats.getLong("views") ?: 0L,
                objects: stats.getLong("objects") ?: 0L,
                avgObjSize: stats.getDouble("avgObjSize") ?: 0.0,
                dataSize: stats.getLong("dataSize") ?: 0L,
                storageSize: stats.getLong("storageSize") ?: 0L,
                indexes: stats.getLong("indexes") ?: 0L,
                indexSize: stats.getLong("indexSize") ?: 0L,
                fsUsedSize: stats.getDouble("fsUsedSize") ?: 0.0,
                fsTotalSize: stats.getDouble("fsTotalSize") ?: 0.0
            )
        } catch (Exception e) {
            log.error("Error getting database stats", e)
            throw new RuntimeException("Failed to get database stats: ${e.message}", e)
        }
    }
    
    @CompileStatic(TypeCheckingMode.SKIP)
    @Override
    NoSqlMetadata.ServerInfo getServerInfo() {
        ensureInitialized()
        
        if (!mongoAvailable) {
            log.warn("MongoProvider running in stub mode: serverInfo()")
            return null
        }
        
        try {
            // Get admin database
            def getDatabaseMethod = client.getClass().getMethod("getDatabase", String)
            def adminDb = getDatabaseMethod.invoke(client, "admin")
            
            def documentClass = Class.forName("org.bson.Document")
            def command = documentClass.getDeclaredConstructor(Map).newInstance([buildInfo: 1])
            
            def runCommandMethod = adminDb.getClass().getMethod("runCommand", documentClass)
            def info = runCommandMethod.invoke(adminDb, command)
            
            return new NoSqlMetadata.ServerInfo(
                version: info.getString("version"),
                gitVersion: info.getString("gitVersion"),
                sysInfo: info.get("sysInfo") as Map<String, Object> ?: [:],
                maxBsonObjectSize: info.getLong("maxBsonObjectSize") ?: 16777216L,
                storageEngines: info.get("storageEngines") as Map<String, Object> ?: [:],
                modules: info.get("modules") as List<String> ?: [],
                ok: info.getDouble("ok") == 1.0
            )
        } catch (Exception e) {
            log.error("Error getting server info", e)
            throw new RuntimeException("Failed to get server info: ${e.message}", e)
        }
    }
    
    // =========================================================================
    // Helper Methods
    // =========================================================================
    
    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("MongoProvider not initialized. Call initialize() first.")
        }
    }
    
    private Object getCollection(String collectionName) {
        try {
            def getCollectionMethod = database.getClass().getMethod("getCollection", String)
            return getCollectionMethod.invoke(database, collectionName)
        } catch (Exception e) {
            throw new RuntimeException("Failed to get collection: ${collectionName}", e)
        }
    }
    
    // =========================================================================
    // Stub Methods (for testing without MongoDB driver)
    // =========================================================================
    
    private List<Map<String, Object>> stubFind(
        String collection, 
        Map<String, Object> filter,
        Map<String, Object> projection,
        QueryOptions options
    ) {
        log.warn("MongoProvider running in stub mode: find({}, filter={}, projection={}, options={})", 
            collection, filter, projection, options)
        return []
    }
    
    private List<Map<String, Object>> stubAggregate(String collection, List<Map<String, Object>> pipeline) {
        log.warn("MongoProvider running in stub mode: aggregate({}, pipeline={})", collection, pipeline)
        return []
    }
    
    private Object stubInsertOne(String collection, Map<String, Object> document) {
        log.warn("MongoProvider running in stub mode: insertOne({}, document={})", collection, document)
        return UUID.randomUUID().toString()
    }
    
    private List<Object> stubInsertMany(String collection, List<Map<String, Object>> documents) {
        log.warn("MongoProvider running in stub mode: insertMany({}, {} documents)", collection, documents.size())
        List<Object> ids = documents.collect { UUID.randomUUID().toString() as Object }
        return ids
    }
    
    private long stubUpdate(
        String collection,
        Map<String, Object> filter,
        Map<String, Object> update,
        UpdateOptions options
    ) {
        log.warn("MongoProvider running in stub mode: update({}, filter={}, update={}, options={})", 
            collection, filter, update, options)
        return 0L
    }
    
    private long stubDelete(String collection, Map<String, Object> filter) {
        log.warn("MongoProvider running in stub mode: delete({}, filter={})", collection, filter)
        return 0L
    }
}
