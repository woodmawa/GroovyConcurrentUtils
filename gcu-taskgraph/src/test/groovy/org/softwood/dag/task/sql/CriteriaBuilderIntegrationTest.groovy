package org.softwood.dag.task.sql

import spock.lang.Specification

/**
 * Integration tests demonstrating real-world usage scenarios
 * of the enhanced CriteriaBuilder features.
 */
class CriteriaBuilderIntegrationTest extends Specification {
    
    def "should build sales analytics query with GROUP BY and HAVING"() {
        given: "a criteria builder for sales analytics"
        def builder = new CriteriaBuilder()
        
        when: "building a query to find top-performing regions"
        builder.with {
            select("region", "product_category")
            sum("revenue", "total_revenue")
            avg("unit_price", "avg_price")
            count("*", "sale_count")
            from("sales")
            where {
                ge "sale_date", "2024-01-01"
                le "sale_date", "2024-12-31"
                eq "status", "completed"
            }
            groupBy("region", "product_category")
            having {
                gt "SUM(revenue)", 100000
                gt "COUNT(*)", 50
            }
            orderBy("total_revenue DESC")
            limit(10)
        }
        
        def result = builder.build()
        
        then: "the query should be properly structured"
        result.sql.contains("SELECT region, product_category, SUM(revenue) AS total_revenue, AVG(unit_price) AS avg_price, COUNT(*) AS sale_count")
        result.sql.contains("FROM sales")
        result.sql.contains("WHERE sale_date >= ? AND sale_date <= ? AND status = ?")
        result.sql.contains("GROUP BY region, product_category")
        result.sql.contains("HAVING SUM(revenue) > ? AND COUNT(*) > ?")
        result.sql.contains("ORDER BY total_revenue DESC")
        result.sql.contains("LIMIT 10")
        result.params == ["2024-01-01", "2024-12-31", "completed", 100000, 50]
    }
    
    def "should build customer order analysis with JOINs and date functions"() {
        given: "a criteria builder for customer analysis"
        def builder = new CriteriaBuilder()
        
        when: "analyzing customer ordering patterns"
        builder.with {
            select("c.customer_id", "c.name", "c.email")
            count("o.id", "order_count")
            sum("o.total", "total_spent")
            max("o.created_at", "last_order_date")
            dateDiff("CURRENT_DATE", "MAX(o.created_at)", "days_since_order")
            from("customers c")
            innerJoin("orders o", "c.id = o.customer_id")
            where {
                eq "c.active", true
                ge "o.created_at", "2024-01-01"
            }
            groupBy("c.customer_id", "c.name", "c.email")
            having {
                ge "COUNT(o.id)", 3
                gt "SUM(o.total)", 500
            }
            orderBy("total_spent DESC", "order_count DESC")
            limit(100)
        }
        
        def result = builder.build()
        
        then: "the query should properly join and aggregate"
        result.sql.contains("SELECT c.customer_id, c.name, c.email")
        result.sql.contains("COUNT(o.id) AS order_count")
        result.sql.contains("SUM(o.total) AS total_spent")
        result.sql.contains("FROM customers c")
        result.sql.contains("INNER JOIN orders o ON c.id = o.customer_id")
        result.sql.contains("WHERE c.active = ? AND o.created_at >= ?")
        result.sql.contains("GROUP BY c.customer_id, c.name, c.email")
        result.sql.contains("HAVING COUNT(o.id) >= ? AND SUM(o.total) > ?")
        result.params == [true, "2024-01-01", 3, 500]
    }
    
    def "should build user engagement report with multiple JOINs"() {
        given: "a criteria builder for engagement analysis"
        def builder = new CriteriaBuilder()
        
        when: "analyzing user engagement across platforms"
        builder.with {
            select("u.username", "u.email")
            count("p.id", "post_count")
            count("c.id", "comment_count")
            count("l.id", "like_count")
            from("users u")
            leftJoin("posts p", "u.id = p.user_id AND p.deleted_at IS NULL")
            leftJoin("comments c", "u.id = c.user_id AND c.deleted_at IS NULL")
            leftJoin("likes l", "u.id = l.user_id")
            where {
                eq "u.active", true
                ge "u.created_at", "2023-01-01"
            }
            groupBy("u.id", "u.username", "u.email")
            having {
                or {
                    ge "COUNT(p.id)", 10
                    ge "COUNT(c.id)", 50
                    ge "COUNT(l.id)", 100
                }
            }
            orderBy("post_count DESC", "comment_count DESC")
        }
        
        def result = builder.build()
        
        then: "the query should handle multiple LEFT JOINs"
        result.sql.contains("FROM users u")
        result.sql.contains("LEFT JOIN posts p ON u.id = p.user_id AND p.deleted_at IS NULL")
        result.sql.contains("LEFT JOIN comments c ON u.id = c.user_id AND c.deleted_at IS NULL")
        result.sql.contains("LEFT JOIN likes l ON u.id = l.user_id")
        result.sql.contains("GROUP BY u.id, u.username, u.email")
        result.sql.contains("HAVING (COUNT(p.id) >= ? OR COUNT(c.id) >= ? OR COUNT(l.id) >= ?)")
    }
    
    def "should build inventory analysis with string functions"() {
        given: "a criteria builder for inventory"
        def builder = new CriteriaBuilder()
        
        when: "analyzing product inventory with normalized names"
        builder.with {
            upper("category", "category_upper")
            trim("product_name", "clean_name")
            count("*", "product_count")
            sum("quantity", "total_quantity")
            avg("unit_price", "avg_price")
            from("inventory")
            where {
                gt "quantity", 0
                isNotNull("category")
            }
            groupBy("UPPER(category)")
            having {
                ge "COUNT(*)", 5
            }
            orderBy("total_quantity DESC")
        }
        
        def result = builder.build()
        
        then: "the query should use string functions"
        result.sql.contains("UPPER(category) AS category_upper")
        result.sql.contains("TRIM(product_name) AS clean_name")
        result.sql.contains("COUNT(*) AS product_count")
        result.sql.contains("GROUP BY UPPER(category)")
    }
    
    def "should build time series analysis with date functions"() {
        given: "a criteria builder for time series"
        def builder = new CriteriaBuilder()
        
        when: "analyzing monthly trends"
        builder.with {
            year("created_at", "year")
            month("created_at", "month")
            count("*", "event_count")
            sum("amount", "total_amount")
            avg("amount", "avg_amount")
            from("transactions")
            where {
                ge "created_at", "2024-01-01"
                eq "status", "completed"
            }
            groupBy("YEAR(created_at)", "MONTH(created_at)")
            orderBy("year DESC", "month DESC")
        }
        
        def result = builder.build()
        
        then: "the query should extract date components"
        result.sql.contains("YEAR(created_at) AS year")
        result.sql.contains("MONTH(created_at) AS month")
        result.sql.contains("GROUP BY YEAR(created_at), MONTH(created_at)")
        result.sql.contains("ORDER BY year DESC, month DESC")
    }
    
    def "should build subquery for finding high-value customers"() {
        given: "a criteria builder with subqueries"
        def builder = new CriteriaBuilder()
        
        when: "finding customers who spent more than average"
        builder.with {
            select("customer_id", "name", "email")
            sum("order_total", "total_spent")
            from("customers c")
            innerJoin("orders o", "c.id = o.customer_id")
            where {
                exists("SELECT 1 FROM payments WHERE payments.order_id = o.id AND payments.status = 'completed'")
                inSubquery("c.country_id", "SELECT id FROM countries WHERE gdp_per_capita > 30000")
            }
            groupBy("c.customer_id", "c.name", "c.email")
            having {
                subquery("SUM(o.total)", ">", "SELECT AVG(total) * 2 FROM orders")
            }
            orderBy("total_spent DESC")
            limit(50)
        }
        
        def result = builder.build()
        
        then: "the query should include subqueries"
        result.sql.contains("EXISTS (SELECT 1 FROM payments")
        result.sql.contains("c.country_id IN (SELECT id FROM countries WHERE gdp_per_capita > 30000)")
        result.sql.contains("HAVING SUM(o.total) > (SELECT AVG(total) * 2 FROM orders)")
    }
    
    def "should build product recommendation query with complex JOINs"() {
        given: "a criteria builder for recommendations"
        def builder = new CriteriaBuilder()
        
        when: "finding frequently bought together products"
        builder.with {
            select("p1.product_id AS product_a", "p2.product_id AS product_b")
            count("*", "times_bought_together")
            from("order_items p1")
            innerJoin("order_items p2", "p1.order_id = p2.order_id AND p1.product_id < p2.product_id")
            innerJoin("orders o", "p1.order_id = o.id")
            where {
                eq "o.status", "completed"
                ge "o.created_at", "2024-01-01"
            }
            groupBy("p1.product_id", "p2.product_id")
            having {
                ge "COUNT(*)", 10
            }
            orderBy("times_bought_together DESC")
            limit(100)
        }
        
        def result = builder.build()
        
        then: "the query should find product pairs"
        result.sql.contains("INNER JOIN order_items p2 ON p1.order_id = p2.order_id AND p1.product_id < p2.product_id")
        result.sql.contains("GROUP BY p1.product_id, p2.product_id")
        result.sql.contains("HAVING COUNT(*) >= ?")
    }
    
    def "should build employee salary analysis with COALESCE"() {
        given: "a criteria builder for salary analysis"
        def builder = new CriteriaBuilder()
        
        when: "analyzing salaries with default values"
        builder.with {
            select("department")
            coalesce("avg_salary", "AVG(salary)", "'50000'")
            count("*", "employee_count")
            min("salary", "min_salary")
            max("salary", "max_salary")
            from("employees")
            where {
                eq "active", true
            }
            groupBy("department")
            having {
                ge "COUNT(*)", 3
            }
            orderBy("avg_salary DESC")
        }
        
        def result = builder.build()
        
        then: "the query should use COALESCE"
        result.sql.contains("COALESCE(AVG(salary), '50000') AS avg_salary")
        result.sql.contains("MIN(salary) AS min_salary")
        result.sql.contains("MAX(salary) AS max_salary")
    }
    
    def "should build subscription churn analysis"() {
        given: "a criteria builder for churn analysis"
        def builder = new CriteriaBuilder()
        
        when: "analyzing subscription patterns"
        builder.with {
            select("s.plan_type")
            count("s.id", "total_subscriptions")
            count("c.id", "churned")
            from("subscriptions s")
            leftJoin("cancellations c", "s.id = c.subscription_id")
            where {
                ge "s.created_at", "2024-01-01"
                notExists("SELECT 1 FROM renewals WHERE renewals.subscription_id = s.id")
            }
            groupBy("s.plan_type")
            having {
                gt "COUNT(s.id)", 100
            }
            orderBy("churned DESC")
        }
        
        def result = builder.build()
        
        then: "the query should analyze churn"
        result.sql.contains("LEFT JOIN cancellations c ON s.id = c.subscription_id")
        result.sql.contains("NOT EXISTS (SELECT 1 FROM renewals WHERE renewals.subscription_id = s.id)")
        result.sql.contains("GROUP BY s.plan_type")
    }
    
    def "should build complex report combining all features"() {
        given: "a criteria builder using all features"
        def builder = new CriteriaBuilder()
        
        when: "building a comprehensive business intelligence query"
        builder.with {
            select("r.region_name")
            upper("c.country", "country_upper")
            year("o.order_date", "year")
            month("o.order_date", "month")
            count("DISTINCT c.id", "customer_count")
            count("o.id", "order_count")
            sum("o.total", "revenue")
            avg("o.total", "avg_order_value")
            max("o.total", "largest_order")
            from("regions r")
            innerJoin("countries c", "r.id = c.region_id")
            innerJoin("customers cust", "c.id = cust.country_id")
            innerJoin("orders o", "cust.id = o.customer_id")
            leftJoin("refunds ref", "o.id = ref.order_id")
            where {
                ge "o.order_date", "2024-01-01"
                eq "o.status", "completed"
                isNull("ref.id")
                exists("SELECT 1 FROM payments p WHERE p.order_id = o.id AND p.status = 'confirmed'")
                inSubquery("r.id", "SELECT region_id FROM region_targets WHERE year = 2024")
            }
            groupBy("r.region_name", "UPPER(c.country)", "YEAR(o.order_date)", "MONTH(o.order_date)")
            having {
                gt "COUNT(o.id)", 100
                gt "SUM(o.total)", 50000
                gt "AVG(o.total)", 200
            }
            orderBy("revenue DESC", "year DESC", "month DESC")
            limit(50)
        }
        
        def result = builder.build()
        
        then: "the query should be fully formed"
        result.sql.contains("SELECT r.region_name, UPPER(c.country) AS country_upper")
        result.sql.contains("YEAR(o.order_date) AS year, MONTH(o.order_date) AS month")
        result.sql.contains("COUNT(DISTINCT c.id) AS customer_count")
        result.sql.contains("FROM regions r")
        result.sql.contains("INNER JOIN countries c ON r.id = c.region_id")
        result.sql.contains("LEFT JOIN refunds ref ON o.id = ref.order_id")
        result.sql.contains("WHERE o.order_date >= ?")
        result.sql.contains("ref.id IS NULL")
        result.sql.contains("EXISTS (SELECT 1 FROM payments")
        result.sql.contains("r.id IN (SELECT region_id FROM region_targets WHERE year = 2024)")
        result.sql.contains("GROUP BY r.region_name, UPPER(c.country), YEAR(o.order_date), MONTH(o.order_date)")
        result.sql.contains("HAVING COUNT(o.id) > ? AND SUM(o.total) > ? AND AVG(o.total) > ?")
        result.sql.contains("ORDER BY revenue DESC, year DESC, month DESC")
        result.sql.contains("LIMIT 50")
        result.params.contains("2024-01-01")
        result.params.contains("completed")
    }
}
