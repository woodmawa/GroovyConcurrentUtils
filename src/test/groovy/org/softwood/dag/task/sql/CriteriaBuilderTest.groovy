package org.softwood.dag.task.sql

import spock.lang.Specification

/**
 * Tests for Criteria DSL query builder.
 */
class CriteriaBuilderTest extends Specification {
    
    def "should build simple SELECT query"() {
        when:
        def builder = new CriteriaBuilder()
        builder.from("users")
        
        def result = builder.build()
        
        then:
        result.sql == "SELECT * FROM users"
        result.params == []
    }
    
    def "should build SELECT with specific columns"() {
        when:
        def builder = new CriteriaBuilder()
        builder.select("name", "email")
        builder.from("users")
        
        def result = builder.build()
        
        then:
        result.sql == "SELECT name, email FROM users"
        result.params == []
    }
    
    def "should build query with WHERE equals condition"() {
        when:
        def builder = new CriteriaBuilder()
        builder.from("users")
        builder.where {
            eq "status", "active"
        }
        
        def result = builder.build()
        
        then:
        result.sql == "SELECT * FROM users WHERE status = ?"
        result.params == ["active"]
    }
    
    def "should build query with multiple AND conditions"() {
        when:
        def builder = new CriteriaBuilder()
        builder.from("users")
        builder.where {
            gt "age", 18
            eq "status", "active"
            like "email", "%@gmail.com"
        }
        
        def result = builder.build()
        
        then:
        result.sql == "SELECT * FROM users WHERE age > ? AND status = ? AND email LIKE ?"
        result.params == [18, "active", "%@gmail.com"]
    }
    
    def "should build query with OR condition"() {
        when:
        def builder = new CriteriaBuilder()
        builder.from("users")
        builder.where {
            or {
                eq "status", "active"
                eq "status", "pending"
            }
        }
        
        def result = builder.build()
        
        then:
        result.sql == "SELECT * FROM users WHERE (status = ? OR status = ?)"
        result.params == ["active", "pending"]
    }
    
    def "should build query with IN condition"() {
        when:
        def builder = new CriteriaBuilder()
        builder.from("users")
        builder.where {
            inList "id", [1, 2, 3, 4, 5]
        }
        
        def result = builder.build()
        
        then:
        result.sql == "SELECT * FROM users WHERE id IN (?, ?, ?, ?, ?)"
        result.params == [1, 2, 3, 4, 5]
    }
    
    def "should build query with BETWEEN condition"() {
        when:
        def builder = new CriteriaBuilder()
        builder.from("users")
        builder.where {
            between "age", 18, 65
        }
        
        def result = builder.build()
        
        then:
        result.sql == "SELECT * FROM users WHERE age BETWEEN ? AND ?"
        result.params == [18, 65]
    }
    
    def "should build query with NULL conditions"() {
        when:
        def builder = new CriteriaBuilder()
        builder.from("users")
        builder.where {
            isNotNull "email"
            isNull "deleted_at"
        }
        
        def result = builder.build()
        
        then:
        result.sql == "SELECT * FROM users WHERE email IS NOT NULL AND deleted_at IS NULL"
        result.params == []
    }
    
    def "should build query with ORDER BY"() {
        when:
        def builder = new CriteriaBuilder()
        builder.from("users")
        builder.where {
            eq "status", "active"
        }
        builder.orderBy("name", "age DESC")
        
        def result = builder.build()
        
        then:
        result.sql == "SELECT * FROM users WHERE status = ? ORDER BY name, age DESC"
        result.params == ["active"]
    }
    
    def "should build query with LIMIT and OFFSET"() {
        when:
        def builder = new CriteriaBuilder()
        builder.from("users")
        builder.where {
            eq "status", "active"
        }
        builder.orderBy("created_at DESC")
        builder.limit(10)
        builder.offset(20)
        
        def result = builder.build()
        
        then:
        result.sql == "SELECT * FROM users WHERE status = ? ORDER BY created_at DESC LIMIT 10 OFFSET 20"
        result.params == ["active"]
    }
    
    def "should build complex query with all features"() {
        when:
        def builder = new CriteriaBuilder()
        builder.select("id", "name", "email", "age")
        builder.from("users")
        builder.where {
            ge "age", 18
            le "age", 65
            or {
                eq "country", "US"
                eq "country", "UK"
            }
            like "email", "%@company.com"
            isNotNull "verified_at"
        }
        builder.orderBy("name", "age DESC")
        builder.limit(50)
        builder.offset(100)
        
        def result = builder.build()
        
        then:
        result.sql.contains("SELECT id, name, email, age FROM users")
        result.sql.contains("WHERE")
        result.sql.contains("age >= ?")
        result.sql.contains("age <= ?")
        result.sql.contains("(country = ? OR country = ?)")
        result.sql.contains("email LIKE ?")
        result.sql.contains("verified_at IS NOT NULL")
        result.sql.contains("ORDER BY name, age DESC")
        result.sql.contains("LIMIT 50")
        result.sql.contains("OFFSET 100")
        result.params == [18, 65, "US", "UK", "%@company.com"]
    }
    
    def "should throw exception if table not specified"() {
        when:
        def builder = new CriteriaBuilder()
        builder.where {
            eq "status", "active"
        }
        builder.build()
        
        then:
        thrown(IllegalStateException)
    }
}
