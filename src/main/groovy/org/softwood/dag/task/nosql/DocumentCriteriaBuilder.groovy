package org.softwood.dag.task.nosql

import groovy.transform.CompileStatic

/**
 * Criteria builder for NoSQL document queries.
 * Generates database-agnostic filter/query structures.
 * 
 * <p>Similar to SqlTask's CriteriaBuilder but for documents.
 * Supports MongoDB-style query syntax that can be adapted
 * to other NoSQL databases.</p>
 * 
 * <h3>Basic Query Example:</h3>
 * <pre>
 * criteria {
 *     from "users"
 *     select "name", "email"
 *     where {
 *         gt "age", 18
 *         eq "status", "active"
 *         contains "email", "@gmail.com"
 *     }
 *     orderByDesc "createdAt"
 *     limit 100
 * }
 * </pre>
 * 
 * <h3>Aggregation Example:</h3>
 * <pre>
 * criteria {
 *     from "orders"
 *     aggregate {
 *         match {
 *             gte "orderDate", "2024-01-01"
 *         }
 *         group([
 *             _id: '$customerId',
 *             total: [$sum: '$amount']
 *         ])
 *         sort total: -1
 *     }
 * }
 * </pre>
 * 
 * @since 2.2.0
 */
@CompileStatic
class DocumentCriteriaBuilder {
    
    private String collectionName
    private Map<String, Object> filter = [:]
    private Map<String, Object> projection = [:]
    private Map<String, Integer> sort = [:]
    private Integer limitValue
    private Integer skipValue
    private List<Map<String, Object>> aggregationPipeline = []
    private boolean isAggregation = false
    
    /**
     * Specify the collection to query.
     */
    void from(String collection) {
        this.collectionName = collection
    }
    
    /**
     * Specify fields to return (projection).
     * By default, all fields are returned.
     */
    void select(String... fields) {
        fields.each { field ->
            projection[field] = 1
        }
    }
    
    /**
     * Exclude specific fields from results.
     */
    void exclude(String... fields) {
        fields.each { field ->
            projection[field] = 0
        }
    }
    
    /**
     * Add filter conditions (WHERE equivalent).
     */
    void where(@DelegatesTo(FilterBuilder) Closure closure) {
        def builder = new FilterBuilder()
        closure.delegate = builder
        closure.resolveStrategy = Closure.DELEGATE_FIRST
        closure.call()
        this.filter.putAll(builder.build())
    }
    
    /**
     * Add sort order with field-direction pairs.
     * 
     * @param sortSpec Map of field -> direction (ASC or DESC)
     */
    void orderBy(Map<String, String> sortSpec) {
        sortSpec.each { field, direction ->
            sort[field] = direction.toUpperCase() == 'DESC' ? -1 : 1
        }
    }
    
    /**
     * Convenience method for ascending sort.
     */
    void orderByAsc(String... fields) {
        fields.each { field -> sort[field] = 1 }
    }
    
    /**
     * Convenience method for descending sort.
     */
    void orderByDesc(String... fields) {
        fields.each { field -> sort[field] = -1 }
    }
    
    /**
     * Set result limit.
     */
    void limit(int limit) {
        this.limitValue = limit
    }
    
    /**
     * Set result offset (skip).
     */
    void skip(int skip) {
        this.skipValue = skip
    }
    
    /**
     * Build aggregation pipeline.
     */
    void aggregate(@DelegatesTo(AggregationBuilder) Closure closure) {
        this.isAggregation = true
        def builder = new AggregationBuilder()
        closure.delegate = builder
        closure.resolveStrategy = Closure.DELEGATE_FIRST
        closure.call()
        this.aggregationPipeline = builder.build()
    }
    
    /**
     * Build the query.
     * 
     * @return Map with query details
     */
    Map<String, Object> build() {
        if (!collectionName) {
            throw new IllegalStateException("Collection name not specified")
        }
        
        Map<String, Object> result = new LinkedHashMap<>()
        result.put("collection", collectionName)
        result.put("isAggregation", isAggregation)
        
        if (isAggregation) {
            result.put("pipeline", aggregationPipeline)
        } else {
            result.put("filter", filter)
            if (projection) {
                result.put("projection", projection)
            }
            
            def options = new QueryOptions()
            if (sort) {
                options.sort = sort
            }
            if (limitValue != null) {
                options.limit = limitValue
            }
            if (skipValue != null) {
                options.skip = skipValue
            }
            result.put("options", options)
        }
        
        return result
    }
    
    // =========================================================================
    // Filter Builder (WHERE equivalent)
    // =========================================================================
    
    static class FilterBuilder {
        private Map<String, Object> filter = [:]
        
        /** field == value */
        void eq(String field, Object value) {
            filter[field] = value
        }
        
        /** field != value */
        void ne(String field, Object value) {
            filter[field] = ['$ne': value]
        }
        
        /** field > value */
        void gt(String field, Object value) {
            addComparison(field, '$gt', value)
        }
        
        /** field >= value */
        void gte(String field, Object value) {
            addComparison(field, '$gte', value)
        }
        
        /** field < value */
        void lt(String field, Object value) {
            addComparison(field, '$lt', value)
        }
        
        /** field <= value */
        void lte(String field, Object value) {
            addComparison(field, '$lte', value)
        }
        
        /** field IN [values] */
        void inList(String field, List values) {
            filter[field] = ['$in': values]
        }
        
        /** field NOT IN [values] */
        void notIn(String field, List values) {
            filter[field] = ['$nin': values]
        }
        
        /** field exists (or doesn't exist if exists=false) */
        void exists(String field, boolean exists = true) {
            filter[field] = ['$exists': exists]
        }
        
        /** field is null */
        void isNull(String field) {
            filter[field] = null
        }
        
        /** field is not null */
        void isNotNull(String field) {
            filter[field] = ['$ne': null]
        }
        
        /** field matches regex pattern */
        void regex(String field, String pattern, String options = null) {
            def regexFilter = ['$regex': pattern]
            if (options) {
                regexFilter['$options'] = options
            }
            filter[field] = regexFilter
        }
        
        /** field contains text (case-insensitive substring match) */
        void contains(String field, String text) {
            regex(field, ".*${text}.*", 'i')
        }
        
        /** field starts with text (case-insensitive) */
        void startsWith(String field, String text) {
            regex(field, "^${text}", 'i')
        }
        
        /** field ends with text (case-insensitive) */
        void endsWith(String field, String text) {
            regex(field, "${text}\$", 'i')
        }
        
        /** AND conditions */
        void and(@DelegatesTo(FilterBuilder) Closure... closures) {
            def conditions = closures.collect { closure ->
                def builder = new FilterBuilder()
                closure.delegate = builder
                closure.resolveStrategy = Closure.DELEGATE_FIRST
                closure.call()
                builder.build()
            }
            filter['$and'] = conditions
        }
        
        /** OR conditions */
        void or(@DelegatesTo(FilterBuilder) Closure... closures) {
            def conditions = closures.collect { closure ->
                def builder = new FilterBuilder()
                closure.delegate = builder
                closure.resolveStrategy = Closure.DELEGATE_FIRST
                closure.call()
                builder.build()
            }
            filter['$or'] = conditions
        }
        
        /** NOT condition */
        void not(String field, @DelegatesTo(FilterBuilder) Closure closure) {
            def builder = new FilterBuilder()
            closure.delegate = builder
            closure.resolveStrategy = Closure.DELEGATE_FIRST
            closure.call()
            def innerFilter = builder.build()
            filter[field] = ['$not': innerFilter.get(field)]
        }
        
        /** All elements in array match condition */
        void all(String field, List values) {
            filter[field] = ['$all': values]
        }
        
        /** Array field has specified size */
        void size(String field, int size) {
            filter[field] = ['$size': size]
        }
        
        /** Element match for arrays */
        void elemMatch(String field, @DelegatesTo(FilterBuilder) Closure closure) {
            def builder = new FilterBuilder()
            closure.delegate = builder
            closure.resolveStrategy = Closure.DELEGATE_FIRST
            closure.call()
            filter[field] = ['$elemMatch': builder.build()]
        }
        
        private void addComparison(String field, String operator, Object value) {
            if (filter[field] instanceof Map) {
                (filter[field] as Map)[operator] = value
            } else {
                filter[field] = [(operator): value]
            }
        }
        
        Map<String, Object> build() {
            return filter
        }
    }
    
    // =========================================================================
    // Aggregation Builder
    // =========================================================================
    
    static class AggregationBuilder {
        private List<Map<String, Object>> pipeline = []
        
        /** $match stage (filter documents) */
        void match(@DelegatesTo(FilterBuilder) Closure closure) {
            def builder = new FilterBuilder()
            closure.delegate = builder
            closure.resolveStrategy = Closure.DELEGATE_FIRST
            closure.call()
            Map<String, Object> stage = ['$match': builder.build()]
            pipeline.add(stage)
        }
        
        /** $group stage (aggregate documents) */
        void group(Map<String, Object> groupSpec) {
            Map<String, Object> stage = ['$group': groupSpec]
            pipeline.add(stage)
        }
        
        /** $project stage (select/compute fields) */
        void project(Map<String, Object> projectSpec) {
            Map<String, Object> stage = ['$project': projectSpec]
            pipeline.add(stage)
        }
        
        /** $sort stage */
        void sort(Map<String, Integer> sortSpec) {
            Map<String, Object> stage = ['$sort': sortSpec]
            pipeline.add(stage)
        }
        
        /** $limit stage */
        void limit(int limit) {
            Map<String, Object> stage = ['$limit': limit]
            pipeline.add(stage)
        }
        
        /** $skip stage */
        void skip(int skip) {
            Map<String, Object> stage = ['$skip': skip]
            pipeline.add(stage)
        }
        
        /** $unwind stage (flatten array field) */
        void unwind(String field) {
            def unwindField = field.startsWith('$') ? field : "\$${field}"
            Map<String, Object> stage = ['$unwind': unwindField]
            pipeline.add(stage)
        }
        
        /** $lookup stage (join with another collection) */
        void lookup(String from, String localField, String foreignField, String as) {
            Map<String, Object> lookupSpec = [
                from: from,
                localField: localField,
                foreignField: foreignField,
                as: as
            ]
            Map<String, Object> stage = ['$lookup': lookupSpec]
            pipeline.add(stage)
        }
        
        /** $addFields stage (add computed fields) */
        void addFields(Map<String, Object> fields) {
            Map<String, Object> stage = ['$addFields': fields]
            pipeline.add(stage)
        }
        
        /** $replaceRoot stage (promote embedded document) */
        void replaceRoot(String newRoot) {
            Map<String, Object> stage = ['$replaceRoot': [newRoot: newRoot]]
            pipeline.add(stage)
        }
        
        /** $count stage (count documents) */
        void count(String fieldName) {
            Map<String, Object> stage = ['$count': fieldName]
            pipeline.add(stage)
        }
        
        /** $facet stage (multiple pipelines) */
        void facet(Map<String, List<Map<String, Object>>> facets) {
            Map<String, Object> stage = ['$facet': facets]
            pipeline.add(stage)
        }
        
        /** $bucket stage (group by ranges) */
        void bucket(Map<String, Object> bucketSpec) {
            Map<String, Object> stage = ['$bucket': bucketSpec]
            pipeline.add(stage)
        }
        
        /** Custom aggregation stage */
        void stage(Map<String, Object> stageSpec) {
            pipeline.add(stageSpec)
        }
        
        List<Map<String, Object>> build() {
            return pipeline
        }
    }
}
