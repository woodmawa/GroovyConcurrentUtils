package org.softwood.dag.task.nosql

import groovy.transform.CompileStatic

/**
 * Query options for find operations.
 * 
 * @since 2.2.0
 */
@CompileStatic
class QueryOptions {
    /** Sort specification: field name -> 1 (ascending) or -1 (descending) */
    Map<String, Integer> sort
    
    /** Maximum number of documents to return */
    Integer limit
    
    /** Number of documents to skip */
    Integer skip
    
    /** Index hint for query optimization */
    Map<String, Object> hint
    
    /** Query timeout in milliseconds */
    Integer maxTimeMS
}
