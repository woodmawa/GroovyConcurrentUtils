package org.softwood.dag.task.nosql

import groovy.transform.CompileStatic

/**
 * Provider interface for NoSQL database operations.
 * Similar to SqlProvider but for document/key-value stores.
 * 
 * <p>Implementations provide database-specific operations while maintaining
 * a consistent interface for NoSqlTask.</p>
 * 
 * @since 2.2.0
 */
@CompileStatic
interface NoSqlProvider {
    
    /**
     * Initialize the provider (establish connection pool, etc.)
     */
    void initialize()
    
    /**
     * Clean up resources
     */
    void close()
    
    /**
     * Execute a find/query operation.
     * 
     * @param collection Collection/table name
     * @param filter Filter criteria (database-specific format)
     * @param projection Fields to return (null = all)
     * @param options Query options (sort, limit, skip, etc.)
     * @return List of documents as Maps
     */
    List<Map<String, Object>> find(
        String collection, 
        Map<String, Object> filter,
        Map<String, Object> projection,
        QueryOptions options
    )
    
    /**
     * Execute an aggregation pipeline.
     * 
     * @param collection Collection/table name
     * @param pipeline Aggregation pipeline stages
     * @return List of aggregated results
     */
    List<Map<String, Object>> aggregate(
        String collection,
        List<Map<String, Object>> pipeline
    )
    
    /**
     * Insert a document.
     * 
     * @param collection Collection name
     * @param document Document to insert
     * @return Inserted document ID
     */
    Object insertOne(
        String collection,
        Map<String, Object> document
    )
    
    /**
     * Insert multiple documents.
     * 
     * @param collection Collection name
     * @param documents Documents to insert
     * @return List of inserted document IDs
     */
    List<Object> insertMany(
        String collection,
        List<Map<String, Object>> documents
    )
    
    /**
     * Update documents matching filter.
     * 
     * @param collection Collection name
     * @param filter Filter criteria
     * @param update Update operations
     * @param options Update options (upsert, multi, etc.)
     * @return Number of documents modified
     */
    long update(
        String collection,
        Map<String, Object> filter,
        Map<String, Object> update,
        UpdateOptions options
    )
    
    /**
     * Delete documents matching filter.
     * 
     * @param collection Collection name
     * @param filter Filter criteria
     * @return Number of documents deleted
     */
    long delete(
        String collection,
        Map<String, Object> filter
    )
    
    /**
     * Execute custom code with native client.
     * 
     * @param closure Closure receiving native client
     * @return Result from closure
     */
    Object execute(Closure closure)
    
    /**
     * Execute within a transaction (if supported).
     * 
     * @param closure Closure to execute in transaction
     * @return Result from closure
     */
    Object withTransaction(Closure closure)
}
