package org.softwood.dag.task.sql

import spock.lang.Specification

/**
 * Tests for enhanced Criteria DSL features.
 * 
 * Tests GROUP BY, HAVING, JOIN, SQL functions, and subquery support.
 */
class CriteriaBuilderEnhancedTest extends Specification {
    
    // =========================================================================
    // GROUP BY Tests
    // =========================================================================
    
    def "should build query with GROUP BY"() {
        when:
        def builder = new CriteriaBuilder()
        builder.select("country", "COUNT(*) AS user_count")
        builder.from("users")
        builder.groupBy("country")
        
        def result = builder.build()
        
        then:
        result.sql == "SELECT country, COUNT(*) AS user_count FROM users GROUP BY country"
        result.params == []
    }
    
    def "should build query with GROUP BY multiple columns"() {
        when:
        def builder = new CriteriaBuilder()
        builder.select("country", "city", "COUNT(*) AS count")
        builder.from("users")
        builder.groupBy("country", "city")
        
        def result = builder.build()
        
        then:
        result.sql == "SELECT country, city, COUNT(*) AS count FROM users GROUP BY country, city"
        result.params == []
    }
    
    // =========================================================================
    // HAVING Tests
    // =========================================================================
    
    def "should build query with HAVING clause"() {
        when:
        def builder = new CriteriaBuilder()
        builder.select("country", "COUNT(*) AS count")
        builder.from("users")
        builder.groupBy("country")
        builder.having {
            gt "COUNT(*)", 100
        }
        
        def result = builder.build()
        
        then:
        result.sql == "SELECT country, COUNT(*) AS count FROM users GROUP BY country HAVING COUNT(*) > ?"
        result.params == [100]
    }
    
    def "should build query with multiple HAVING conditions"() {
        when:
        def builder = new CriteriaBuilder()
        builder.select("country", "AVG(age) AS avg_age", "COUNT(*) AS count")
        builder.from("users")
        builder.groupBy("country")
        builder.having {
            gt "COUNT(*)", 50
            le "AVG(age)", 40
        }
        
        def result = builder.build()
        
        then:
        result.sql == "SELECT country, AVG(age) AS avg_age, COUNT(*) AS count FROM users GROUP BY country HAVING COUNT(*) > ? AND AVG(age) <= ?"
        result.params == [50, 40]
    }
    
    def "should build query with WHERE, GROUP BY, HAVING, and ORDER BY"() {
        when:
        def builder = new CriteriaBuilder()
        builder.select("department", "COUNT(*) AS emp_count", "AVG(salary) AS avg_salary")
        builder.from("employees")
        builder.where {
            eq "active", true
        }
        builder.groupBy("department")
        builder.having {
            gt "COUNT(*)", 10
            gt "AVG(salary)", 50000
        }
        builder.orderBy("emp_count DESC")
        
        def result = builder.build()
        
        then:
        result.sql.contains("WHERE active = ?")
        result.sql.contains("GROUP BY department")
        result.sql.contains("HAVING COUNT(*) > ? AND AVG(salary) > ?")
        result.sql.contains("ORDER BY emp_count DESC")
        result.params == [true, 10, 50000]
    }
    
    // =========================================================================
    // String Function Tests
    // =========================================================================
    
    def "should build query with UPPER function"() {
        when:
        def builder = new CriteriaBuilder()
        builder.upper("name", "upper_name")
        builder.from("users")
        
        def result = builder.build()
        
        then:
        result.sql == "SELECT UPPER(name) AS upper_name FROM users"
    }
    
    def "should build query with LOWER function"() {
        when:
        def builder = new CriteriaBuilder()
        builder.select("id")
        builder.lower("email")
        builder.from("users")
        
        def result = builder.build()
        
        then:
        result.sql == "SELECT id, LOWER(email) FROM users"
    }
    
    def "should build query with LENGTH function"() {
        when:
        def builder = new CriteriaBuilder()
        builder.select("name")
        builder.length("name", "name_length")
        builder.from("users")
        
        def result = builder.build()
        
        then:
        result.sql == "SELECT name, LENGTH(name) AS name_length FROM users"
    }
    
    def "should build query with SUBSTRING function"() {
        when:
        def builder = new CriteriaBuilder()
        builder.substring("email", 1, 5, "prefix")
        builder.from("users")
        
        def result = builder.build()
        
        then:
        result.sql == "SELECT SUBSTRING(email, 1, 5) AS prefix FROM users"
    }
    
    def "should build query with TRIM function"() {
        when:
        def builder = new CriteriaBuilder()
        builder.trim("name", "trimmed_name")
        builder.from("users")
        
        def result = builder.build()
        
        then:
        result.sql == "SELECT TRIM(name) AS trimmed_name FROM users"
    }
    
    def "should build query with CONCAT function"() {
        when:
        def builder = new CriteriaBuilder()
        builder.concat("full_name", "first_name", "' '", "last_name")
        builder.from("users")
        
        def result = builder.build()
        
        then:
        result.sql == "SELECT CONCAT(first_name, ' ', last_name) AS full_name FROM users"
    }
    
    def "should build query with COALESCE function"() {
        when:
        def builder = new CriteriaBuilder()
        builder.coalesce("display_name", "nickname", "first_name", "'Guest'")
        builder.from("users")
        
        def result = builder.build()
        
        then:
        result.sql == "SELECT COALESCE(nickname, first_name, 'Guest') AS display_name FROM users"
    }
    
    // =========================================================================
    // Date Function Tests
    // =========================================================================
    
    def "should build query with CURRENT_DATE function"() {
        when:
        def builder = new CriteriaBuilder()
        builder.select("*")
        builder.currentDate("today")
        builder.from("users")
        
        def result = builder.build()
        
        then:
        result.sql == "SELECT *, CURRENT_DATE AS today FROM users"
    }
    
    def "should build query with CURRENT_TIMESTAMP function"() {
        when:
        def builder = new CriteriaBuilder()
        builder.currentTimestamp("now")
        builder.from("users")
        
        def result = builder.build()
        
        then:
        result.sql == "SELECT CURRENT_TIMESTAMP AS now FROM users"
    }
    
    def "should build query with YEAR function"() {
        when:
        def builder = new CriteriaBuilder()
        builder.year("created_at", "year_created")
        builder.from("users")
        
        def result = builder.build()
        
        then:
        result.sql == "SELECT YEAR(created_at) AS year_created FROM users"
    }
    
    def "should build query with MONTH and DAY functions"() {
        when:
        def builder = new CriteriaBuilder()
        builder.month("created_at", "month")
        builder.day("created_at", "day")
        builder.from("users")
        
        def result = builder.build()
        
        then:
        result.sql == "SELECT MONTH(created_at) AS month, DAY(created_at) AS day FROM users"
    }
    
    def "should build query with DATE_ADD function"() {
        when:
        def builder = new CriteriaBuilder()
        builder.dateAdd("created_at", 30, "DAY", "expire_date")
        builder.from("users")
        
        def result = builder.build()
        
        then:
        result.sql == "SELECT DATE_ADD(created_at, INTERVAL 30 DAY) AS expire_date FROM users"
    }
    
    def "should build query with DATE_SUB function"() {
        when:
        def builder = new CriteriaBuilder()
        builder.dateSub("created_at", 1, "YEAR", "last_year")
        builder.from("users")
        
        def result = builder.build()
        
        then:
        result.sql == "SELECT DATE_SUB(created_at, INTERVAL 1 YEAR) AS last_year FROM users"
    }
    
    def "should build query with DATEDIFF function"() {
        when:
        def builder = new CriteriaBuilder()
        builder.dateDiff("end_date", "start_date", "duration")
        builder.from("projects")
        
        def result = builder.build()
        
        then:
        result.sql == "SELECT DATEDIFF(end_date, start_date) AS duration FROM projects"
    }
    
    // =========================================================================
    // JOIN Tests
    // =========================================================================
    
    def "should build query with INNER JOIN"() {
        when:
        def builder = new CriteriaBuilder()
        builder.select("users.name", "orders.total")
        builder.from("users")
        builder.innerJoin("orders", "users.id = orders.user_id")
        
        def result = builder.build()
        
        then:
        result.sql == "SELECT users.name, orders.total FROM users INNER JOIN orders ON users.id = orders.user_id"
        result.params == []
    }
    
    def "should build query with LEFT JOIN"() {
        when:
        def builder = new CriteriaBuilder()
        builder.select("users.name", "orders.total")
        builder.from("users")
        builder.leftJoin("orders", "users.id = orders.user_id")
        
        def result = builder.build()
        
        then:
        result.sql == "SELECT users.name, orders.total FROM users LEFT JOIN orders ON users.id = orders.user_id"
    }
    
    def "should build query with RIGHT JOIN"() {
        when:
        def builder = new CriteriaBuilder()
        builder.from("users")
        builder.rightJoin("orders", "users.id = orders.user_id")
        
        def result = builder.build()
        
        then:
        result.sql == "SELECT * FROM users RIGHT JOIN orders ON users.id = orders.user_id"
    }
    
    def "should build query with FULL OUTER JOIN"() {
        when:
        def builder = new CriteriaBuilder()
        builder.from("users")
        builder.fullJoin("orders", "users.id = orders.user_id")
        
        def result = builder.build()
        
        then:
        result.sql == "SELECT * FROM users FULL OUTER JOIN orders ON users.id = orders.user_id"
    }
    
    def "should build query with multiple JOINs"() {
        when:
        def builder = new CriteriaBuilder()
        builder.select("u.name", "o.total", "p.name AS product")
        builder.from("users u")
        builder.innerJoin("orders o", "u.id = o.user_id")
        builder.innerJoin("order_items oi", "o.id = oi.order_id")
        builder.leftJoin("products p", "oi.product_id = p.id")
        
        def result = builder.build()
        
        then:
        result.sql.contains("FROM users u")
        result.sql.contains("INNER JOIN orders o ON u.id = o.user_id")
        result.sql.contains("INNER JOIN order_items oi ON o.id = oi.order_id")
        result.sql.contains("LEFT JOIN products p ON oi.product_id = p.id")
    }
    
    def "should build query with JOIN and WHERE"() {
        when:
        def builder = new CriteriaBuilder()
        builder.from("users")
        builder.innerJoin("orders", "users.id = orders.user_id")
        builder.where {
            gt "orders.total", 100
            eq "users.country", "US"
        }
        
        def result = builder.build()
        
        then:
        result.sql.contains("INNER JOIN orders ON users.id = orders.user_id")
        result.sql.contains("WHERE orders.total > ? AND users.country = ?")
        result.params == [100, "US"]
    }
    
    // =========================================================================
    // Subquery Tests
    // =========================================================================
    
    def "should build query with EXISTS subquery"() {
        when:
        def builder = new CriteriaBuilder()
        builder.from("users")
        builder.where {
            exists("SELECT 1 FROM orders WHERE orders.user_id = users.id")
        }
        
        def result = builder.build()
        
        then:
        result.sql == "SELECT * FROM users WHERE EXISTS (SELECT 1 FROM orders WHERE orders.user_id = users.id)"
        result.params == []
    }
    
    def "should build query with NOT EXISTS subquery"() {
        when:
        def builder = new CriteriaBuilder()
        builder.from("users")
        builder.where {
            notExists("SELECT 1 FROM orders WHERE orders.user_id = users.id")
        }
        
        def result = builder.build()
        
        then:
        result.sql == "SELECT * FROM users WHERE NOT EXISTS (SELECT 1 FROM orders WHERE orders.user_id = users.id)"
    }
    
    def "should build query with IN subquery"() {
        when:
        def builder = new CriteriaBuilder()
        builder.from("users")
        builder.where {
            inSubquery("id", "SELECT user_id FROM orders WHERE total > 1000")
        }
        
        def result = builder.build()
        
        then:
        result.sql == "SELECT * FROM users WHERE id IN (SELECT user_id FROM orders WHERE total > 1000)"
    }
    
    def "should build query with custom subquery comparison"() {
        when:
        def builder = new CriteriaBuilder()
        builder.from("employees")
        builder.where {
            subquery("salary", ">", "SELECT AVG(salary) FROM employees")
        }
        
        def result = builder.build()
        
        then:
        result.sql == "SELECT * FROM employees WHERE salary > (SELECT AVG(salary) FROM employees)"
    }
    
    def "should build query with multiple subquery conditions"() {
        when:
        def builder = new CriteriaBuilder()
        builder.from("users")
        builder.where {
            exists("SELECT 1 FROM orders WHERE orders.user_id = users.id")
            inSubquery("country_id", "SELECT id FROM countries WHERE region = 'EU'")
        }
        
        def result = builder.build()
        
        then:
        result.sql.contains("EXISTS (SELECT 1 FROM orders WHERE orders.user_id = users.id)")
        result.sql.contains("country_id IN (SELECT id FROM countries WHERE region = 'EU')")
    }
    
    // =========================================================================
    // Complex Combined Tests
    // =========================================================================
    
    def "should build complex query with GROUP BY, HAVING, and JOINs"() {
        when:
        def builder = new CriteriaBuilder()
        builder.select("u.country", "COUNT(*) AS order_count", "SUM(o.total) AS revenue")
        builder.from("users u")
        builder.innerJoin("orders o", "u.id = o.user_id")
        builder.where {
            ge "o.created_at", "2024-01-01"
            eq "u.active", true
        }
        builder.groupBy("u.country")
        builder.having {
            gt "COUNT(*)", 100
            gt "SUM(o.total)", 10000
        }
        builder.orderBy("revenue DESC")
        builder.limit(10)
        
        def result = builder.build()
        
        then:
        result.sql.contains("SELECT u.country, COUNT(*) AS order_count, SUM(o.total) AS revenue")
        result.sql.contains("FROM users u")
        result.sql.contains("INNER JOIN orders o ON u.id = o.user_id")
        result.sql.contains("WHERE o.created_at >= ? AND u.active = ?")
        result.sql.contains("GROUP BY u.country")
        result.sql.contains("HAVING COUNT(*) > ? AND SUM(o.total) > ?")
        result.sql.contains("ORDER BY revenue DESC")
        result.sql.contains("LIMIT 10")
        result.params == ["2024-01-01", true, 100, 10000]
    }
    
    def "should build query with string functions and GROUP BY"() {
        when:
        def builder = new CriteriaBuilder()
        builder.upper("country", "country_upper")
        builder.count("*", "count")
        builder.from("users")
        builder.where {
            isNotNull("email")
        }
        builder.groupBy("UPPER(country)")
        builder.having {
            gt "COUNT(*)", 5
        }
        builder.orderBy("count DESC")
        
        def result = builder.build()
        
        then:
        result.sql.contains("SELECT UPPER(country) AS country_upper, COUNT(*) AS count")
        result.sql.contains("WHERE email IS NOT NULL")
        result.sql.contains("GROUP BY UPPER(country)")
        result.sql.contains("HAVING COUNT(*) > ?")
        result.params == [5]
    }
    
    def "should build query with date functions and aggregation"() {
        when:
        def builder = new CriteriaBuilder()
        builder.year("created_at", "year")
        builder.month("created_at", "month")
        builder.count("*", "user_count")
        builder.from("users")
        builder.groupBy("YEAR(created_at)", "MONTH(created_at)")
        builder.orderBy("year DESC", "month DESC")
        
        def result = builder.build()
        
        then:
        result.sql.contains("SELECT YEAR(created_at) AS year, MONTH(created_at) AS month, COUNT(*) AS user_count")
        result.sql.contains("GROUP BY YEAR(created_at), MONTH(created_at)")
        result.sql.contains("ORDER BY year DESC, month DESC")
    }
    
    def "should build query with JOINs, subqueries, and functions"() {
        when:
        def builder = new CriteriaBuilder()
        builder.select("u.name", "o.total", "c.name AS country")
        builder.avg("o.total", "avg_order")
        builder.from("users u")
        builder.innerJoin("orders o", "u.id = o.user_id")
        builder.leftJoin("countries c", "u.country_id = c.id")
        builder.where {
            exists("SELECT 1 FROM payments WHERE payments.order_id = o.id")
            inSubquery("c.region_id", "SELECT id FROM regions WHERE active = 1")
        }
        builder.groupBy("u.name", "o.total", "c.name")
        builder.having {
            gt "AVG(o.total)", 500
        }
        builder.orderBy("avg_order DESC")
        builder.limit(20)
        
        def result = builder.build()
        
        then:
        result.sql.contains("SELECT u.name, o.total, c.name AS country, AVG(o.total) AS avg_order")
        result.sql.contains("FROM users u")
        result.sql.contains("INNER JOIN orders o ON u.id = o.user_id")
        result.sql.contains("LEFT JOIN countries c ON u.country_id = c.id")
        result.sql.contains("WHERE EXISTS")
        result.sql.contains("IN (SELECT id FROM regions WHERE active = 1)")
        result.sql.contains("GROUP BY u.name, o.total, c.name")
        result.sql.contains("HAVING AVG(o.total) > ?")
        result.sql.contains("ORDER BY avg_order DESC")
        result.sql.contains("LIMIT 20")
        result.params == [500]
    }
}
