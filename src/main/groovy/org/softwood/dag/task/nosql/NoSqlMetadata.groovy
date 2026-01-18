package org.softwood.dag.task.nosql

import groovy.transform.Canonical
import groovy.transform.ToString

/**
 * NoSQL database metadata information classes.
 * 
 * <p>Provides structured access to NoSQL database metadata including
 * collections, indexes, statistics, and server information.</p>
 * 
 * <p>Primarily designed for MongoDB but can be adapted for other
 * NoSQL databases (DynamoDB, Couchbase, Redis, etc.).</p>
 * 
 * @since 2.3.0
 */
class NoSqlMetadata {
    
    /**
     * Information about a database collection.
     */
    @Canonical
    @ToString(includeNames = true)
    static class CollectionInfo {
        String name
        String type           // collection, view, timeseries
        Map<String, Object> options
    }
    
    /**
     * Statistics for a collection.
     */
    @Canonical
    @ToString(includeNames = true)
    static class CollectionStats {
        String name
        long count            // Document count
        long size             // Collection size in bytes
        long storageSize      // Storage size in bytes
        int nindexes          // Number of indexes
        long totalIndexSize   // Total size of indexes
        double avgObjSize     // Average document size
        Map<String, Object> wiredTiger  // Storage engine stats (if applicable)
    }
    
    /**
     * Information about an index.
     */
    @Canonical
    @ToString(includeNames = true)
    static class IndexInfo {
        String name
        Map<String, Object> key      // Index key specification
        boolean unique
        boolean sparse
        String background
        Map<String, Object> options
    }
    
    /**
     * Database statistics.
     */
    @Canonical
    @ToString(includeNames = true)
    static class DatabaseStats {
        String db
        long collections
        long views
        long objects          // Total documents
        double avgObjSize
        long dataSize
        long storageSize
        long indexes
        long indexSize
        double fsUsedSize     // File system used size
        double fsTotalSize    // File system total size
    }
    
    /**
     * Server/database information.
     */
    @Canonical
    @ToString(includeNames = true)
    static class ServerInfo {
        String version
        String gitVersion
        Map<String, Object> sysInfo
        long maxBsonObjectSize
        Map<String, Object> storageEngines
        List<String> modules
        boolean ok
    }
}
