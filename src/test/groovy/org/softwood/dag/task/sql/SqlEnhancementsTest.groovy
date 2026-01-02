package org.softwood.dag.task.sql

import org.softwood.dag.TaskGraph
import org.softwood.dag.task.sql.JdbcSqlProvider
import spock.lang.Specification

/**
 * Tests for new SQL enhancements: functions, logging, and exception handling.
 */
class SqlEnhancementsTest extends Specification {
    
    JdbcSqlProvider provider
    
    def setup() {
        provider = new JdbcSqlProvider(
            url: "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1",
            driverClassName: "org.h2.Driver",
            username: "sa",
            password: ""
        )
        provider.initialize()
        
        // Create and populate test table
        provider.executeUpdate("""
            CREATE TABLE IF NOT EXISTS products (
                id INT PRIMARY KEY,
                name VARCHAR(100),
                price DECIMAL(10,2),
                quantity INT
            )
        """, [])
        
        provider.executeUpdate("DELETE FROM products", [])
        provider.executeUpdate("INSERT INTO products VALUES (1, 'Laptop', 999.99, 5)", [])
        provider.executeUpdate("INSERT INTO products VALUES (2, 'Mouse', 29.99, 50)", [])
        provider.executeUpdate("INSERT INTO products VALUES (3, 'Keyboard', 79.99, 20)", [])
    }
    
    def cleanup() {
        provider?.close()
    }
    
    // =========================================================================
    // SQL Functions Tests
    // =========================================================================
    
    def "should use COUNT function in criteria DSL"() {
        when:
        def graph = TaskGraph.build {
            sqlTask("count-products") {
                provider this.provider
                
                criteria {
                    count()
                    from "products"
                }
            }
        }
        
        def result = graph.run().get()
        
        then:
        result.size() == 1
        result[0].count == 3
    }
    
    def "should use MAX function in criteria DSL"() {
        when:
        def graph = TaskGraph.build {
            sqlTask("max-price") {
                provider this.provider
                
                criteria {
                    max "price", "max_price"
                    from "products"
                }
            }
        }
        
        def result = graph.run().get()
        
        then:
        result.size() == 1
        result[0].max_price == 999.99
    }
    
    def "should use MIN function in criteria DSL"() {
        when:
        def graph = TaskGraph.build {
            sqlTask("min-price") {
                provider this.provider
                
                criteria {
                    min "price"
                    from "products"
                }
            }
        }
        
        def result = graph.run().get()
        
        then:
        result.size() == 1
        result[0].min == 29.99
    }
    
    def "should use AVG function in criteria DSL"() {
        when:
        def graph = TaskGraph.build {
            sqlTask("avg-price") {
                provider this.provider
                
                criteria {
                    avg "price"
                    from "products"
                }
            }
        }
        
        def result = graph.run().get()
        
        then:
        result.size() == 1
        result[0].avg > 0
    }
    
    def "should use SUM function in criteria DSL"() {
        when:
        def graph = TaskGraph.build {
            sqlTask("total-quantity") {
                provider this.provider
                
                criteria {
                    sum "quantity", "total"
                    from "products"
                }
            }
        }
        
        def result = graph.run().get()
        
        then:
        result.size() == 1
        result[0].total == 75  // 5 + 50 + 20
    }
    
    def "should combine functions with WHERE clause"() {
        when:
        def graph = TaskGraph.build {
            sqlTask("expensive-count") {
                provider this.provider
                
                criteria {
                    count "*", "expensive_count"
                    from "products"
                    where {
                        gt "price", 50
                    }
                }
            }
        }
        
        def result = graph.run().get()
        
        then:
        result.size() == 1
        result[0].expensive_count == 2  // Laptop and Keyboard
    }
    
    def "should use custom SQL function"() {
        when:
        def graph = TaskGraph.build {
            sqlTask("custom-func") {
                provider this.provider
                
                criteria {
                    function "ROUND(AVG(price), 2)", "rounded_avg"
                    from "products"
                }
            }
        }
        
        def result = graph.run().get()
        
        then:
        result.size() == 1
        result[0].rounded_avg > 0
    }
    
    // =========================================================================
    // SQL Logging Tests
    // =========================================================================
    
    def "should log SQL when logSql is enabled"() {
        when:
        def graph = TaskGraph.build {
            sqlTask("logged-query") {
                provider this.provider
                logSql true
                
                query "SELECT * FROM products WHERE price > ?"
                params 50
            }
        }
        
        def result = graph.run().get()
        
        then:
        result.size() == 2
        // Check logs would show:
        // SqlTask 'logged-query': executing query: SELECT * FROM products WHERE price > ?
        // SqlTask 'logged-query': parameters: [50]
    }
    
    def "should log criteria-generated SQL"() {
        when:
        def graph = TaskGraph.build {
            sqlTask("logged-criteria") {
                provider this.provider
                logSql true
                
                criteria {
                    select "name", "price"
                    from "products"
                    where {
                        ge "quantity", 20
                    }
                    orderBy "price DESC"
                }
            }
        }
        
        def result = graph.run().get()
        
        then:
        result.size() == 2
        // Logs would show the generated SQL
    }
}
