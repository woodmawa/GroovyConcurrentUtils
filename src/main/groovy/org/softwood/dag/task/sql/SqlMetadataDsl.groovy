package org.softwood.dag.task.sql

/**
 * DSL for database metadata queries.
 * 
 * <p>Provides a fluent interface for querying database metadata including
 * tables, columns, indexes, keys, schemas, and database information.</p>
 * 
 * @since 2.3.0
 */
class SqlMetadataDsl {
    private final SqlProvider provider
    private MetadataOperation operation
    
    SqlMetadataDsl(SqlProvider provider) {
        this.provider = provider
    }
    
    /**
     * Get tables from the database.
     * 
     * <h3>Examples:</h3>
     * <pre>
     * tables()  // All tables
     * tables([schema: "public"])
     * tables([schema: "public", type: ["TABLE", "VIEW"]])
     * tables([pattern: "user%"])
     * </pre>
     */
    void tables(Map options = [:]) {
        this.operation = new MetadataOperation(
            type: OperationType.TABLES,
            catalog: options.catalog as String,
            schema: options.schema as String,
            pattern: options.pattern as String,
            types: (options.type ?: options.types) as List<String>
        )
    }
    
    /**
     * Get columns for a table.
     * 
     * <h3>Examples:</h3>
     * <pre>
     * columns("users")
     * columns("users", [schema: "public"])
     * columns("users", [pattern: "id%"])
     * </pre>
     */
    void columns(String tableName, Map options = [:]) {
        this.operation = new MetadataOperation(
            type: OperationType.COLUMNS,
            catalog: options.catalog as String,
            schema: options.schema as String,
            tableName: tableName,
            pattern: options.pattern as String
        )
    }
    
    /**
     * Get indexes for a table.
     * 
     * <h3>Examples:</h3>
     * <pre>
     * indexes("users")
     * indexes("users", [unique: true])
     * indexes("users", [schema: "public"])
     * </pre>
     */
    void indexes(String tableName, Map options = [:]) {
        this.operation = new MetadataOperation(
            type: OperationType.INDEXES,
            catalog: options.catalog as String,
            schema: options.schema as String,
            tableName: tableName,
            unique: options.unique as Boolean ?: false
        )
    }
    
    /**
     * Get primary keys for a table.
     * 
     * <h3>Example:</h3>
     * <pre>
     * primaryKeys("users")
     * primaryKeys("users", [schema: "public"])
     * </pre>
     */
    void primaryKeys(String tableName, Map options = [:]) {
        this.operation = new MetadataOperation(
            type: OperationType.PRIMARY_KEYS,
            catalog: options.catalog as String,
            schema: options.schema as String,
            tableName: tableName
        )
    }
    
    /**
     * Get foreign keys for a table.
     * 
     * <h3>Example:</h3>
     * <pre>
     * foreignKeys("orders")
     * foreignKeys("orders", [schema: "public"])
     * </pre>
     */
    void foreignKeys(String tableName, Map options = [:]) {
        this.operation = new MetadataOperation(
            type: OperationType.FOREIGN_KEYS,
            catalog: options.catalog as String,
            schema: options.schema as String,
            tableName: tableName
        )
    }
    
    /**
     * Get all schemas in the database.
     */
    void schemas() {
        this.operation = new MetadataOperation(type: OperationType.SCHEMAS)
    }
    
    /**
     * Get all catalogs in the database.
     */
    void catalogs() {
        this.operation = new MetadataOperation(type: OperationType.CATALOGS)
    }
    
    /**
     * Get database product information.
     */
    void databaseInfo() {
        this.operation = new MetadataOperation(type: OperationType.DATABASE_INFO)
    }
    
    /**
     * Execute the configured metadata operation.
     */
    Object execute() {
        if (!operation) {
            throw new IllegalStateException("No metadata operation configured")
        }
        
        switch (operation.type) {
            case OperationType.TABLES:
                return provider.getTables(
                    operation.catalog,
                    operation.schema,
                    operation.pattern,
                    operation.types
                )
            case OperationType.COLUMNS:
                return provider.getColumns(
                    operation.catalog,
                    operation.schema,
                    operation.tableName,
                    operation.pattern
                )
            case OperationType.INDEXES:
                return provider.getIndexes(
                    operation.catalog,
                    operation.schema,
                    operation.tableName,
                    operation.unique
                )
            case OperationType.PRIMARY_KEYS:
                return provider.getPrimaryKeys(
                    operation.catalog,
                    operation.schema,
                    operation.tableName
                )
            case OperationType.FOREIGN_KEYS:
                return provider.getForeignKeys(
                    operation.catalog,
                    operation.schema,
                    operation.tableName
                )
            case OperationType.SCHEMAS:
                return provider.getSchemas()
            case OperationType.CATALOGS:
                return provider.getCatalogs()
            case OperationType.DATABASE_INFO:
                return provider.getDatabaseInfo()
            default:
                throw new IllegalStateException("Unknown metadata operation: ${operation.type}")
        }
    }
    
    /**
     * Internal class to hold metadata operation configuration.
     */
    private static class MetadataOperation {
        OperationType type
        String catalog
        String schema
        String tableName
        String pattern
        List<String> types
        boolean unique
    }
    
    /**
     * Enumeration of metadata operation types.
     */
    private static enum OperationType {
        TABLES,
        COLUMNS,
        INDEXES,
        PRIMARY_KEYS,
        FOREIGN_KEYS,
        SCHEMAS,
        CATALOGS,
        DATABASE_INFO
    }
}
