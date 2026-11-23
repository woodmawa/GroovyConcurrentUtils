package org.softwood.dag.task.nosql

/**
 * DSL for NoSQL database metadata queries.
 * 
 * <p>Provides a fluent interface for querying NoSQL database metadata including
 * collections, indexes, statistics, and server information.</p>
 * 
 * @since 2.3.0
 */
class NoSqlMetadataDsl {
    private final NoSqlProvider provider
    private MetadataOperation operation
    
    NoSqlMetadataDsl(NoSqlProvider provider) {
        this.provider = provider
    }
    
    /**
     * List all collections in the database.
     * 
     * <h3>Example:</h3>
     * <pre>
     * metadata {
     *     collections()
     * }
     * </pre>
     */
    void collections() {
        this.operation = new MetadataOperation(
            type: OperationType.COLLECTIONS
        )
    }
    
    /**
     * Get statistics for a collection.
     * 
     * <h3>Example:</h3>
     * <pre>
     * metadata {
     *     collectionStats("users")
     * }
     * </pre>
     */
    void collectionStats(String collection) {
        this.operation = new MetadataOperation(
            type: OperationType.COLLECTION_STATS,
            collection: collection
        )
    }
    
    /**
     * List indexes on a collection.
     * 
     * <h3>Example:</h3>
     * <pre>
     * metadata {
     *     indexes("users")
     * }
     * </pre>
     */
    void indexes(String collection) {
        this.operation = new MetadataOperation(
            type: OperationType.INDEXES,
            collection: collection
        )
    }
    
    /**
     * Get database statistics.
     * 
     * <h3>Example:</h3>
     * <pre>
     * metadata {
     *     databaseStats()
     * }
     * </pre>
     */
    void databaseStats() {
        this.operation = new MetadataOperation(
            type: OperationType.DATABASE_STATS
        )
    }
    
    /**
     * Get server information.
     * 
     * <h3>Example:</h3>
     * <pre>
     * metadata {
     *     serverInfo()
     * }
     * </pre>
     */
    void serverInfo() {
        this.operation = new MetadataOperation(
            type: OperationType.SERVER_INFO
        )
    }
    
    /**
     * Execute the configured metadata operation.
     */
    Object execute() {
        if (!operation) {
            throw new IllegalStateException("No metadata operation configured")
        }
        
        switch (operation.type) {
            case OperationType.COLLECTIONS:
                return provider.listCollections()
            case OperationType.COLLECTION_STATS:
                return provider.getCollectionStats(operation.collection)
            case OperationType.INDEXES:
                return provider.listIndexes(operation.collection)
            case OperationType.DATABASE_STATS:
                return provider.getDatabaseStats()
            case OperationType.SERVER_INFO:
                return provider.getServerInfo()
            default:
                throw new IllegalStateException("Unknown metadata operation: ${operation.type}")
        }
    }
    
    /**
     * Internal class to hold metadata operation configuration.
     */
    private static class MetadataOperation {
        OperationType type
        String collection
    }
    
    /**
     * Enumeration of metadata operation types.
     */
    private static enum OperationType {
        COLLECTIONS,
        COLLECTION_STATS,
        INDEXES,
        DATABASE_STATS,
        SERVER_INFO
    }
}
