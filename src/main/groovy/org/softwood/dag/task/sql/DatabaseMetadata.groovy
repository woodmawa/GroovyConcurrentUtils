package org.softwood.dag.task.sql

import groovy.transform.Canonical
import groovy.transform.ToString

/**
 * Database metadata information classes.
 * 
 * <p>Provides structured access to database schema information including
 * tables, columns, indexes, keys, schemas, and database properties.</p>
 * 
 * @since 2.3.0
 */
class DatabaseMetadata {
    
    /**
     * Information about a database table or view.
     */
    @Canonical
    @ToString(includeNames = true)
    static class TableInfo {
        String catalog
        String schema
        String tableName
        String type           // TABLE, VIEW, SYSTEM TABLE, etc.
        String remarks
    }
    
    /**
     * Information about a table column.
     */
    @Canonical
    @ToString(includeNames = true)
    static class ColumnInfo {
        String tableName
        String columnName
        String dataType       // Type name (VARCHAR, INTEGER, etc.)
        int sqlType          // java.sql.Types value
        int columnSize
        int decimalDigits
        boolean nullable
        String defaultValue
        String remarks
        int ordinalPosition
    }
    
    /**
     * Information about a table index.
     */
    @Canonical
    @ToString(includeNames = true)
    static class IndexInfo {
        String tableName
        String indexName
        boolean unique
        String type          // clustered, hashed, other
        List<String> columns = []
    }
    
    /**
     * Information about a table's primary key.
     */
    @Canonical
    @ToString(includeNames = true)
    static class PrimaryKeyInfo {
        String tableName
        String pkName
        List<String> columns = []
    }
    
    /**
     * Information about a foreign key relationship.
     */
    @Canonical
    @ToString(includeNames = true)
    static class ForeignKeyInfo {
        String fkName
        String tableName        // Foreign key table
        String pkTableName      // Referenced table
        String updateRule       // CASCADE, SET NULL, etc.
        String deleteRule       // CASCADE, SET NULL, etc.
        List<ColumnMapping> columns = []
        
        /**
         * Maps a foreign key column to its referenced primary key column.
         */
        @Canonical
        @ToString(includeNames = true)
        static class ColumnMapping {
            String fkColumn     // Foreign key column name
            String pkColumn     // Referenced primary key column name
        }
    }
    
    /**
     * Information about a database schema.
     */
    @Canonical
    @ToString(includeNames = true)
    static class SchemaInfo {
        String catalog
        String schemaName
    }
    
    /**
     * Information about a database catalog.
     */
    @Canonical
    @ToString(includeNames = true)
    static class CatalogInfo {
        String catalog
    }
    
    /**
     * Database product information and capabilities.
     */
    @Canonical
    @ToString(includeNames = true)
    static class DatabaseInfo {
        String productName
        String productVersion
        String driverName
        String driverVersion
        String url
        String userName
        boolean readOnly
        int maxConnections
        boolean supportsBatchUpdates
        boolean supportsTransactions
        boolean supportsStoredProcedures
        int defaultTransactionIsolation
    }
}
