package org.softwood.dag.task.nosql

import groovy.transform.CompileStatic
import groovy.transform.TypeCheckingMode
import groovy.util.logging.Slf4j
import org.softwood.config.ConfigLoader

/**
 * Google Cloud Firestore provider with ZERO compile-time dependencies.
 *
 * <p><strong>NO DEPENDENCIES:</strong> Uses reflection to avoid requiring
 * Google Cloud client libraries at compile time. Add the SDK at runtime for full functionality.</p>
 *
 * <h3>Stub Mode (No SDK):</h3>
 * <ul>
 *   <li>Query methods return empty results</li>
 *   <li>Write methods return synthetic IDs</li>
 *   <li>Metadata methods return empty/null</li>
 *   <li>Logs warnings about missing SDK</li>
 * </ul>
 *
 * <h3>Full Mode (SDK Available):</h3>
 * <ul>
 *   <li>All Firestore operations work normally</li>
 *   <li>Document-based NoSQL storage</li>
 *   <li>Real-time synchronization support</li>
 *   <li>Compound queries</li>
 *   <li>Transactions and batched writes</li>
 *   <li>Automatic indexing</li>
 * </ul>
 *
 * <h3>Usage:</h3>
 * <pre>
 * // Basic usage
 * def provider = new FirestoreProvider(
 *     projectId: "my-gcp-project",
 *     databaseId: "(default)"
 * )
 *
 * // With service account credentials
 * def provider = new FirestoreProvider(
 *     projectId: "my-gcp-project",
 *     credentialsPath: "/path/to/service-account.json"
 * )
 *
 * // From config
 * def provider = FirestoreProvider.fromConfig()
 * </pre>
 *
 * <h3>Google Cloud Firestore Features:</h3>
 * <ul>
 *   <li><b>Serverless:</b> Fully managed, scales automatically</li>
 *   <li><b>Real-time:</b> Live synchronization of data changes</li>
 *   <li><b>Offline support:</b> Client SDKs work offline</li>
 *   <li><b>Strong consistency:</b> Within a region</li>
 *   <li><b>ACID transactions:</b> Multi-document transactions</li>
 *   <li><b>Hierarchical data:</b> Collections and subcollections</li>
 * </ul>
 *
 * @since 2.3.0
 */
@Slf4j
@CompileStatic
class FirestoreProvider implements NoSqlProvider {

    // =========================================================================
    // Configuration Properties
    // =========================================================================

    /** GCP Project ID */
    String projectId

    /** Firestore Database ID (default is "(default)") */
    String databaseId = "(default)"

    /** Path to service account JSON credentials file */
    String credentialsPath

    /** Emulator host (for local testing) */
    String emulatorHost

    /** Connection timeout in milliseconds */
    int connectionTimeout = 30000

    /** Request timeout in milliseconds */
    int requestTimeout = 60000

    // =========================================================================
    // Static Factory Methods
    // =========================================================================

    /**
     * Create FirestoreProvider from config file.
     * Reads from database.firestore section.
     *
     * <h3>Config Example (config.yml):</h3>
     * <pre>
     * database:
     *   firestore:
     *     projectId: my-gcp-project
     *     databaseId: (default)
     *     credentialsPath: /path/to/service-account.json
     *     emulatorHost: localhost:8080  # For local testing
     * </pre>
     */
    static FirestoreProvider fromConfig(Map configOverrides = [:]) {
        def config = ConfigLoader.loadConfig()

        def provider = new FirestoreProvider()

        provider.projectId = getConfigValue(config, configOverrides, 'database.firestore.projectId', null)
        provider.databaseId = getConfigValue(config, configOverrides, 'database.firestore.databaseId', '(default)')
        provider.credentialsPath = getConfigValue(config, configOverrides, 'database.firestore.credentialsPath', null)
        provider.emulatorHost = getConfigValue(config, configOverrides, 'database.firestore.emulatorHost', null)
        provider.connectionTimeout = getConfigValue(config, configOverrides, 'database.firestore.connectionTimeout', 30000) as int
        provider.requestTimeout = getConfigValue(config, configOverrides, 'database.firestore.requestTimeout', 60000) as int

        provider.initialize()
        return provider
    }

    private static Object getConfigValue(Map config, Map overrides, String key, Object defaultValue) {
        if (overrides.containsKey(key)) return overrides[key]

        // Navigate nested map structure (e.g., "database.firestore.projectId")
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

    private Object firestore  // Firestore instance
    private boolean sdkAvailable = false
    private boolean initialized = false

    // =========================================================================
    // Initialization
    // =========================================================================

    @Override
    void initialize() {
        if (initialized) {
            log.warn("FirestoreProvider already initialized")
            return
        }

        sdkAvailable = checkSdkAvailable()

        if (!sdkAvailable) {
            log.warn("Google Cloud Firestore SDK not found. Provider running in STUB MODE.")
            log.warn("Add dependency: com.google.cloud:google-cloud-firestore:3.15+ for full functionality")
            initialized = true
            return
        }

        if (!projectId) {
            throw new IllegalStateException("Firestore projectId not configured")
        }

        initializeClient()
        initialized = true
        log.info("FirestoreProvider initialized (project: {}, database: {})", projectId, databaseId)
    }

    private boolean checkSdkAvailable() {
        try {
            Class.forName("com.google.cloud.firestore.Firestore")
            return true
        } catch (ClassNotFoundException e) {
            return false
        }
    }

    @CompileStatic(TypeCheckingMode.SKIP)
    private void initializeClient() {
        try {
            // Set emulator host if provided
            if (emulatorHost) {
                System.setProperty("FIRESTORE_EMULATOR_HOST", emulatorHost)
                log.info("Using Firestore emulator at: {}", emulatorHost)
            }

            // Build Firestore options
            def optionsClass = Class.forName("com.google.cloud.firestore.FirestoreOptions")
            def builder = optionsClass.newBuilder()

            // Set project ID
            builder.setProjectId(projectId)

            // Set database ID (if not default)
            if (databaseId && databaseId != "(default)") {
                builder.setDatabaseId(databaseId)
            }

            // Set credentials if provided
            if (credentialsPath && !emulatorHost) {
                def credentialsClass = Class.forName("com.google.auth.oauth2.ServiceAccountCredentials")
                def fileInputStream = new FileInputStream(credentialsPath)
                def credentials = credentialsClass.fromStream(fileInputStream)
                fileInputStream.close()

                def credentialsProviderClass = Class.forName("com.google.api.gax.core.FixedCredentialsProvider")
                def credentialsProvider = credentialsProviderClass.create(credentials)
                builder.setCredentialsProvider(credentialsProvider)
            }

            def options = builder.build()
            firestore = options.getService()

            log.info("Firestore client created successfully")

        } catch (Exception e) {
            log.error("Failed to initialize Firestore client", e)
            throw new RuntimeException("Firestore initialization failed: ${e.message}", e)
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
            log.warn("Firestore SDK not available - returning empty result")
            return []
        }

        try {
            // Get collection reference
            def collectionRef = firestore.collection(collection)
            def query = collectionRef

            // Apply filters
            if (filter) {
                filter.each { key, value ->
                    if (key.contains('.')) {
                        // Nested field query
                        query = query.whereEqualTo(key, value)
                    } else {
                        query = query.whereEqualTo(key, value)
                    }
                }
            }

            // Apply sorting
            if (options?.sort) {
                options.sort.each { key, value ->
                    def direction = value == 1 || value == "asc" ?
                            firestore.getClass().getDeclaredField("ASCENDING").get(null) :
                            firestore.getClass().getDeclaredField("DESCENDING").get(null)
                    query = query.orderBy(key as String, direction)
                }
            }

            // Apply limit
            if (options?.limit) {
                query = query.limit(options.limit)
            }

            // Apply skip (offset)
            if (options?.skip) {
                query = query.offset(options.skip)
            }

            // Execute query
            def querySnapshot = query.get().get()

            // Convert documents to Maps
            def results = []
            querySnapshot.getDocuments().each { doc ->
                def data = doc.getData()
                data['_id'] = doc.getId()

                // Apply projection if provided
                if (projection) {
                    data = data.subMap(projection.keySet())
                }

                results << data
            }

            return results

        } catch (Exception e) {
            log.error("Firestore find failed", e)
            throw new RuntimeException("Find operation failed: ${e.message}", e)
        }
    }

    @Override
    List<Map<String, Object>> aggregate(String collection, List<Map<String, Object>> pipeline) {
        // Firestore doesn't support MongoDB-style aggregation pipelines
        // Use regular queries with client-side aggregation
        log.warn("Firestore does not support aggregation pipelines - use find() with client-side processing")
        return []
    }

    // =========================================================================
    // Modification Operations
    // =========================================================================

    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    Object insertOne(String collection, Map<String, Object> document) {
        if (!sdkAvailable) {
            log.warn("Firestore SDK not available - returning synthetic ID")
            return UUID.randomUUID().toString()
        }

        try {
            def collectionRef = firestore.collection(collection)

            // Generate ID if not provided
            def docId = document._id ?: document.id

            if (docId) {
                // Use specified ID
                def docRef = collectionRef.document(docId as String)
                docRef.set(document).get()
                return docId
            } else {
                // Auto-generate ID
                def docRef = collectionRef.document()
                docRef.set(document).get()
                return docRef.getId()
            }

        } catch (Exception e) {
            log.error("Firestore insertOne failed", e)
            throw new RuntimeException("Insert failed: ${e.message}", e)
        }
    }

    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    List<Object> insertMany(String collection, List<Map<String, Object>> documents) {
        if (!sdkAvailable) {
            log.warn("Firestore SDK not available - returning synthetic IDs")
            return documents.collect { UUID.randomUUID().toString() }
        }

        try {
            // Use batch write for efficiency
            def batch = firestore.batch()
            def collectionRef = firestore.collection(collection)
            def ids = []

            documents.each { doc ->
                def docId = doc._id ?: doc.id

                if (docId) {
                    def docRef = collectionRef.document(docId as String)
                    batch.set(docRef, doc)
                    ids << docId
                } else {
                    def docRef = collectionRef.document()
                    batch.set(docRef, doc)
                    ids << docRef.getId()
                }
            }

            batch.commit().get()
            return ids

        } catch (Exception e) {
            log.error("Firestore insertMany failed", e)
            throw new RuntimeException("Batch insert failed: ${e.message}", e)
        }
    }

    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    long update(String collection, Map<String, Object> filter,
                Map<String, Object> update, UpdateOptions options) {
        if (!sdkAvailable) {
            log.warn("Firestore SDK not available - returning 0")
            return 0
        }

        try {
            // Find documents matching filter
            def docs = find(collection, filter, null, new QueryOptions())

            if (docs.isEmpty()) {
                return 0
            }

            // Update documents
            def collectionRef = firestore.collection(collection)
            def batch = firestore.batch()

            docs.each { doc ->
                def docRef = collectionRef.document(doc._id as String)

                // Apply updates
                if (update.containsKey('$set')) {
                    // MongoDB-style $set operator
                    batch.update(docRef, update['$set'] as Map)
                } else {
                    // Direct field updates
                    batch.update(docRef, update)
                }
            }

            batch.commit().get()

            return docs.size() as long

        } catch (Exception e) {
            log.error("Firestore update failed", e)
            throw new RuntimeException("Update failed: ${e.message}", e)
        }
    }

    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    long delete(String collection, Map<String, Object> filter) {
        if (!sdkAvailable) {
            log.warn("Firestore SDK not available - returning 0")
            return 0
        }

        try {
            // Find documents matching filter
            def docs = find(collection, filter, null, new QueryOptions())

            if (docs.isEmpty()) {
                return 0
            }

            // Delete documents
            def collectionRef = firestore.collection(collection)
            def batch = firestore.batch()

            docs.each { doc ->
                def docRef = collectionRef.document(doc._id as String)
                batch.delete(docRef)
            }

            batch.commit().get()

            return docs.size() as long

        } catch (Exception e) {
            log.error("Firestore delete failed", e)
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
                    "Custom execution requires Firestore SDK. Add com.google.cloud:google-cloud-firestore to classpath."
            )
        }
        return closure.call(firestore)
    }

    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    Object withTransaction(Closure closure) {
        if (!sdkAvailable) {
            throw new UnsupportedOperationException(
                    "Transactions require Firestore SDK. Add com.google.cloud:google-cloud-firestore to classpath."
            )
        }

        try {
            def result = null

            firestore.runTransaction { transaction ->
                result = closure.call(transaction)
                return result
            }.get()

            return result

        } catch (Exception e) {
            log.error("Firestore transaction failed", e)
            throw new RuntimeException("Transaction failed: ${e.message}", e)
        }
    }

    // =========================================================================
    // Metadata Operations
    // =========================================================================

    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    List<NoSqlMetadata.CollectionInfo> listCollections() {
        if (!sdkAvailable) {
            log.warn("Firestore SDK not available - returning empty list")
            return []
        }

        try {
            def collections = firestore.listCollections()
            def results = []

            collections.each { collectionRef ->
                results << new NoSqlMetadata.CollectionInfo(
                        name: collectionRef.getId(),
                        type: "collection"
                )
            }

            return results

        } catch (Exception e) {
            log.error("Failed to list Firestore collections", e)
            return []
        }
    }

    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    NoSqlMetadata.CollectionStats getCollectionStats(String collection) {
        if (!sdkAvailable) {
            log.warn("Firestore SDK not available - returning null")
            return null
        }

        try {
            // Count documents (expensive operation in Firestore)
            def collectionRef = firestore.collection(collection)
            def snapshot = collectionRef.get().get()
            def count = snapshot.size()

            return new NoSqlMetadata.CollectionStats(
                    name: collection,
                    count: count as long,
                    size: 0L  // Firestore doesn't expose storage size
            )

        } catch (Exception e) {
            log.error("Failed to get Firestore collection stats", e)
            return null
        }
    }

    @Override
    List<NoSqlMetadata.IndexInfo> listIndexes(String collection) {
        // Firestore manages indexes automatically
        // Manual index configuration is done through Firebase console or CLI
        log.info("Firestore indexes are managed through GCP console or CLI")
        return []
    }

    @Override
    NoSqlMetadata.DatabaseStats getDatabaseStats() {
        // Firestore is serverless - no database-level stats available via API
        return null
    }

    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    NoSqlMetadata.ServerInfo getServerInfo() {
        // Firestore is serverless - provide basic info
        return new NoSqlMetadata.ServerInfo(
                version: "Google Cloud Firestore",
                gitVersion: "N/A",
                sysInfo: [
                        serverless: true,
                        projectId: projectId,
                        databaseId: databaseId
                ] as Map<String, Object>,
                maxBsonObjectSize: 1048576L,  // 1MB document size limit
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

        if (sdkAvailable && firestore) {
            try {
                firestore.close()
                log.info("Firestore provider closed")
            } catch (Exception e) {
                log.error("Error closing Firestore provider", e)
            }
        }

        initialized = false
    }
}