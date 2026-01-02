package org.softwood.dag.task.sql

import groovy.transform.CompileStatic

/**
 * Criteria-based query builder - Hibernate-style type-safe queries.
 * 
 * <p>Build SQL queries using a fluent DSL instead of raw SQL strings.
 * Prevents SQL injection and provides compile-time safety.</p>
 * 
 * <h3>Usage:</h3>
 * <pre>
 * sqlTask("find-users") {
 *     provider myProvider
 *     
 *     criteria {
 *         from "users"
 *         where {
 *             gt "age", 18
 *             eq "status", "active"
 *             like "email", "%@gmail.com"
 *         }
 *         orderBy "name", "age DESC"
 *         limit 10
 *     }
 * }
 * </pre>
 * 
 * @since 2.1.0
 */
@CompileStatic
class CriteriaBuilder {
    
    private String tableName
    private List<String> selectColumns = []
    private List<Condition> conditions = []
    private List<String> orderByColumns = []
    private Integer limitValue
    private Integer offsetValue
    private List<String> groupByColumns = []
    private List<Condition> havingConditions = []
    private List<Join> joins = []
    
    /**
     * Specify the table to query from.
     */
    void from(String table) {
        this.tableName = table
    }
    
    /**
     * Specify columns to select (defaults to *).
     */
    void select(String... columns) {
        this.selectColumns = columns as List
    }
    
    /**
     * Add a SQL function to the SELECT clause.
     * Examples: count("*"), max("age"), sum("amount")
     */
    void function(String functionCall, String alias = null) {
        if (alias) {
            this.selectColumns << "${functionCall} AS ${alias}".toString()
        } else {
            this.selectColumns << functionCall
        }
    }
    
    /**
     * Convenience method for COUNT(*).
     */
    void count(String column = "*", String alias = "count") {
        function("COUNT(${column})".toString(), alias)
    }
    
    /**
     * Convenience method for MAX(column).
     */
    void max(String column, String alias = "max") {
        function("MAX(${column})".toString(), alias)
    }
    
    /**
     * Convenience method for MIN(column).
     */
    void min(String column, String alias = "min") {
        function("MIN(${column})".toString(), alias)
    }
    
    /**
     * Convenience method for AVG(column).
     */
    void avg(String column, String alias = "avg") {
        function("AVG(${column})".toString(), alias)
    }
    
    /**
     * Convenience method for SUM(column).
     */
    void sum(String column, String alias = "sum") {
        function("SUM(${column})".toString(), alias)
    }
    
    /**
     * UPPER(column) - Convert to uppercase.
     */
    void upper(String column, String alias = null) {
        function("UPPER(${column})".toString(), alias)
    }
    
    /**
     * LOWER(column) - Convert to lowercase.
     */
    void lower(String column, String alias = null) {
        function("LOWER(${column})".toString(), alias)
    }
    
    /**
     * LENGTH(column) - String length.
     */
    void length(String column, String alias = "length") {
        function("LENGTH(${column})".toString(), alias)
    }
    
    /**
     * SUBSTRING(column, start, length) - Extract substring.
     */
    void substring(String column, int start, int len, String alias = null) {
        function("SUBSTRING(${column}, ${start}, ${len})".toString(), alias)
    }
    
    /**
     * TRIM(column) - Remove leading/trailing spaces.
     */
    void trim(String column, String alias = null) {
        function("TRIM(${column})".toString(), alias)
    }
    
    /**
     * CONCAT(col1, col2, ...) - Concatenate strings.
     */
    void concat(String alias = null, String... columns) {
        def cols = columns.join(", ")
        function("CONCAT(${cols})".toString(), alias)
    }
    
    /**
     * COALESCE(col1, col2, ...) - Return first non-null value.
     * Pass string literals with quotes already included, e.g., "'Guest'"
     */
    void coalesce(String alias = null, Object... values) {
        def vals = values.join(", ")
        function("COALESCE(${vals})".toString(), alias)
    }
    
    /**
     * CURRENT_DATE - Current date.
     */
    void currentDate(String alias = "current_date") {
        function("CURRENT_DATE", alias)
    }
    
    /**
     * CURRENT_TIME - Current time.
     */
    void currentTime(String alias = "current_time") {
        function("CURRENT_TIME", alias)
    }
    
    /**
     * CURRENT_TIMESTAMP - Current date and time.
     */
    void currentTimestamp(String alias = "current_timestamp") {
        function("CURRENT_TIMESTAMP", alias)
    }
    
    /**
     * YEAR(date_column) - Extract year from date.
     */
    void year(String column, String alias = "year") {
        function("YEAR(${column})".toString(), alias)
    }
    
    /**
     * MONTH(date_column) - Extract month from date.
     */
    void month(String column, String alias = "month") {
        function("MONTH(${column})".toString(), alias)
    }
    
    /**
     * DAY(date_column) - Extract day from date.
     */
    void day(String column, String alias = "day") {
        function("DAY(${column})".toString(), alias)
    }
    
    /**
     * DATE_ADD(date_column, INTERVAL value unit) - Add to date.
     * Example: dateAdd("created_at", 1, "DAY")
     */
    void dateAdd(String column, int value, String unit, String alias = null) {
        function("DATE_ADD(${column}, INTERVAL ${value} ${unit})".toString(), alias)
    }
    
    /**
     * DATE_SUB(date_column, INTERVAL value unit) - Subtract from date.
     */
    void dateSub(String column, int value, String unit, String alias = null) {
        function("DATE_SUB(${column}, INTERVAL ${value} ${unit})".toString(), alias)
    }
    
    /**
     * DATEDIFF(date1, date2) - Difference between dates in days.
     */
    void dateDiff(String date1, String date2, String alias = "date_diff") {
        function("DATEDIFF(${date1}, ${date2})".toString(), alias)
    }
    
    /**
     * Add WHERE conditions.
     */
    void where(@DelegatesTo(WhereBuilder) Closure closure) {
        def builder = new WhereBuilder()
        closure.delegate = builder
        closure.resolveStrategy = Closure.DELEGATE_FIRST
        closure.call()
        this.conditions.addAll(builder.conditions)
    }
    
    /**
     * Add ORDER BY columns.
     */
    void orderBy(String... columns) {
        this.orderByColumns = columns as List
    }
    
    /**
     * Set LIMIT.
     */
    void limit(int limit) {
        this.limitValue = limit
    }
    
    /**
     * Set OFFSET.
     */
    void offset(int offset) {
        this.offsetValue = offset
    }
    
    /**
     * Add GROUP BY columns.
     */
    void groupBy(String... columns) {
        this.groupByColumns = columns as List
    }
    
    /**
     * Add HAVING conditions (used with GROUP BY).
     */
    void having(@DelegatesTo(WhereBuilder) Closure closure) {
        def builder = new WhereBuilder()
        closure.delegate = builder
        closure.resolveStrategy = Closure.DELEGATE_FIRST
        closure.call()
        this.havingConditions.addAll(builder.conditions)
    }
    
    /**
     * Add an INNER JOIN.
     */
    void innerJoin(String table, String onCondition) {
        this.joins << new Join(JoinType.INNER, table, onCondition)
    }
    
    /**
     * Add a LEFT JOIN.
     */
    void leftJoin(String table, String onCondition) {
        this.joins << new Join(JoinType.LEFT, table, onCondition)
    }
    
    /**
     * Add a RIGHT JOIN.
     */
    void rightJoin(String table, String onCondition) {
        this.joins << new Join(JoinType.RIGHT, table, onCondition)
    }
    
    /**
     * Add a FULL OUTER JOIN.
     */
    void fullJoin(String table, String onCondition) {
        this.joins << new Join(JoinType.FULL, table, onCondition)
    }
    
    /**
     * Build the SQL query and parameters.
     * 
     * @return map with 'sql' and 'params' keys
     */
    Map<String, Object> build() {
        if (!tableName) {
            throw new IllegalStateException("Table name not specified")
        }
        
        def sql = new StringBuilder()
        def params = []
        
        // SELECT
        sql.append("SELECT ")
        if (selectColumns) {
            sql.append(selectColumns.join(", "))
        } else {
            sql.append("*")
        }
        
        // FROM
        sql.append(" FROM ").append(tableName)
        
        // JOINS
        if (joins) {
            joins.each { join ->
                sql.append(" ").append(join.toSql())
            }
        }
        
        // WHERE
        if (conditions) {
            sql.append(" WHERE ")
            def whereClauses = []
            conditions.each { condition ->
                def clause = condition.toSql()
                whereClauses << clause.sql
                if (clause.params) {
                    // Cast to List to properly flatten params
                    params.addAll(clause.params as List)
                }
            }
            sql.append(whereClauses.join(" AND "))
        }
        
        // GROUP BY
        if (groupByColumns) {
            sql.append(" GROUP BY ").append(groupByColumns.join(", "))
        }
        
        // HAVING
        if (havingConditions) {
            sql.append(" HAVING ")
            def havingClauses = []
            havingConditions.each { condition ->
                def clause = condition.toSql()
                havingClauses << clause.sql
                if (clause.params) {
                    params.addAll(clause.params as List)
                }
            }
            sql.append(havingClauses.join(" AND "))
        }
        
        // ORDER BY
        if (orderByColumns) {
            sql.append(" ORDER BY ").append(orderByColumns.join(", "))
        }
        
        // LIMIT
        if (limitValue != null) {
            sql.append(" LIMIT ").append(limitValue)
        }
        
        // OFFSET
        if (offsetValue != null) {
            sql.append(" OFFSET ").append(offsetValue)
        }
        
        return [
            sql: sql.toString(),
            params: params
        ]
    }
    
    // =========================================================================
    // Join Support
    // =========================================================================
    
    static enum JoinType {
        INNER, LEFT, RIGHT, FULL
    }
    
    static class Join {
        JoinType type
        String table
        String onCondition
        
        Join(JoinType type, String table, String onCondition) {
            this.type = type
            this.table = table
            this.onCondition = onCondition
        }
        
        String toSql() {
            def joinType = type == JoinType.FULL ? "FULL OUTER JOIN" : "${type} JOIN"
            return "${joinType} ${table} ON ${onCondition}".toString()
        }
    }
    
    // =========================================================================
    // WHERE Builder
    // =========================================================================
    
    static class WhereBuilder {
        List<Condition> conditions = []
        
        /** column = value */
        void eq(String column, Object value) {
            conditions << new EqualsCondition(column, value)
        }
        
        /** column != value */
        void ne(String column, Object value) {
            conditions << new NotEqualsCondition(column, value)
        }
        
        /** column > value */
        void gt(String column, Object value) {
            conditions << new GreaterThanCondition(column, value)
        }
        
        /** column >= value */
        void ge(String column, Object value) {
            conditions << new GreaterOrEqualCondition(column, value)
        }
        
        /** column < value */
        void lt(String column, Object value) {
            conditions << new LessThanCondition(column, value)
        }
        
        /** column <= value */
        void le(String column, Object value) {
            conditions << new LessOrEqualCondition(column, value)
        }
        
        /** column LIKE pattern */
        void like(String column, String pattern) {
            conditions << new LikeCondition(column, pattern)
        }
        
        /** column IN (values...) */
        void inList(String column, List values) {
            conditions << new InCondition(column, values)
        }
        
        /** column BETWEEN min AND max */
        void between(String column, Object min, Object max) {
            conditions << new BetweenCondition(column, min, max)
        }
        
        /** column IS NULL */
        void isNull(String column) {
            conditions << new IsNullCondition(column)
        }
        
        /** column IS NOT NULL */
        void isNotNull(String column) {
            conditions << new IsNotNullCondition(column)
        }
        
        /** OR group of conditions */
        void or(@DelegatesTo(WhereBuilder) Closure closure) {
            def builder = new WhereBuilder()
            closure.delegate = builder
            closure.resolveStrategy = Closure.DELEGATE_FIRST
            closure.call()
            conditions << new OrCondition(builder.conditions)
        }
        
        /** EXISTS (subquery) */
        void exists(String subquery) {
            conditions << new ExistsCondition(subquery)
        }
        
        /** NOT EXISTS (subquery) */
        void notExists(String subquery) {
            conditions << new NotExistsCondition(subquery)
        }
        
        /** column IN (subquery) */
        void inSubquery(String column, String subquery) {
            conditions << new InSubqueryCondition(column, subquery)
        }
        
        /** column operator (subquery) - for custom subquery comparisons */
        void subquery(String column, String operator, String subquery) {
            conditions << new SubqueryCondition(column, operator, subquery)
        }
    }
    
    // =========================================================================
    // Condition Interfaces and Implementations
    // =========================================================================
    
    static interface Condition {
        Map<String, Object> toSql()
    }
    
    static class EqualsCondition implements Condition {
        String column
        Object value
        
        EqualsCondition(String column, Object value) {
            this.column = column
            this.value = value
        }
        
        Map<String, Object> toSql() {
            return [sql: "${column} = ?".toString(), params: [value]]
        }
    }
    
    static class NotEqualsCondition implements Condition {
        String column
        Object value
        
        NotEqualsCondition(String column, Object value) {
            this.column = column
            this.value = value
        }
        
        Map<String, Object> toSql() {
            return [sql: "${column} != ?".toString(), params: [value]]
        }
    }
    
    static class GreaterThanCondition implements Condition {
        String column
        Object value
        
        GreaterThanCondition(String column, Object value) {
            this.column = column
            this.value = value
        }
        
        Map<String, Object> toSql() {
            return [sql: "${column} > ?".toString(), params: [value]]
        }
    }
    
    static class GreaterOrEqualCondition implements Condition {
        String column
        Object value
        
        GreaterOrEqualCondition(String column, Object value) {
            this.column = column
            this.value = value
        }
        
        Map<String, Object> toSql() {
            return [sql: "${column} >= ?".toString(), params: [value]]
        }
    }
    
    static class LessThanCondition implements Condition {
        String column
        Object value
        
        LessThanCondition(String column, Object value) {
            this.column = column
            this.value = value
        }
        
        Map<String, Object> toSql() {
            return [sql: "${column} < ?".toString(), params: [value]]
        }
    }
    
    static class LessOrEqualCondition implements Condition {
        String column
        Object value
        
        LessOrEqualCondition(String column, Object value) {
            this.column = column
            this.value = value
        }
        
        Map<String, Object> toSql() {
            return [sql: "${column} <= ?".toString(), params: [value]]
        }
    }
    
    static class LikeCondition implements Condition {
        String column
        String pattern
        
        LikeCondition(String column, String pattern) {
            this.column = column
            this.pattern = pattern
        }
        
        Map<String, Object> toSql() {
            return [sql: "${column} LIKE ?".toString(), params: [pattern]]
        }
    }
    
    static class InCondition implements Condition {
        String column
        List values
        
        InCondition(String column, List values) {
            this.column = column
            this.values = values
        }
        
        Map<String, Object> toSql() {
            def placeholders = (1..values.size()).collect { "?" }.join(", ")
            return [sql: "${column} IN (${placeholders})".toString(), params: values]
        }
    }
    
    static class BetweenCondition implements Condition {
        String column
        Object min
        Object max
        
        BetweenCondition(String column, Object min, Object max) {
            this.column = column
            this.min = min
            this.max = max
        }
        
        Map<String, Object> toSql() {
            return [sql: "${column} BETWEEN ? AND ?".toString(), params: [min, max]]
        }
    }
    
    static class IsNullCondition implements Condition {
        String column
        
        IsNullCondition(String column) {
            this.column = column
        }
        
        Map<String, Object> toSql() {
            return [sql: "${column} IS NULL".toString(), params: []]
        }
    }
    
    static class IsNotNullCondition implements Condition {
        String column
        
        IsNotNullCondition(String column) {
            this.column = column
        }
        
        Map<String, Object> toSql() {
            return [sql: "${column} IS NOT NULL".toString(), params: []]
        }
    }
    
    static class OrCondition implements Condition {
        List<Condition> conditions
        
        OrCondition(List<Condition> conditions) {
            this.conditions = conditions
        }
        
        Map<String, Object> toSql() {
            def clauses = []
            def params = []
            
            conditions.each { condition ->
                def clause = condition.toSql()
                clauses << clause.sql
                if (clause.params) {
                    // Cast to List to properly flatten params
                    params.addAll(clause.params as List)
                }
            }
            
            return [sql: "(${clauses.join(' OR ')})".toString(), params: params]
        }
    }
    
    static class ExistsCondition implements Condition {
        String subquery
        
        ExistsCondition(String subquery) {
            this.subquery = subquery
        }
        
        Map<String, Object> toSql() {
            return [sql: "EXISTS (${subquery})".toString(), params: []]
        }
    }
    
    static class NotExistsCondition implements Condition {
        String subquery
        
        NotExistsCondition(String subquery) {
            this.subquery = subquery
        }
        
        Map<String, Object> toSql() {
            return [sql: "NOT EXISTS (${subquery})".toString(), params: []]
        }
    }
    
    static class InSubqueryCondition implements Condition {
        String column
        String subquery
        
        InSubqueryCondition(String column, String subquery) {
            this.column = column
            this.subquery = subquery
        }
        
        Map<String, Object> toSql() {
            return [sql: "${column} IN (${subquery})".toString(), params: []]
        }
    }
    
    static class SubqueryCondition implements Condition {
        String column
        String operator
        String subquery
        
        SubqueryCondition(String column, String operator, String subquery) {
            this.column = column
            this.operator = operator
            this.subquery = subquery
        }
        
        Map<String, Object> toSql() {
            return [sql: "${column} ${operator} (${subquery})".toString(), params: []]
        }
    }
}
