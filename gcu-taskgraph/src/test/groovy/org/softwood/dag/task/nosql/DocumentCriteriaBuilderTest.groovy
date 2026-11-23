package org.softwood.dag.task.nosql

import spock.lang.Specification

/**
 * Tests for DocumentCriteriaBuilder - NoSQL query DSL.
 */
class DocumentCriteriaBuilderTest extends Specification {
    
    // =========================================================================
    // Basic Query Tests
    // =========================================================================
    
    def "should build simple find query"() {
        when:
        def builder = new DocumentCriteriaBuilder()
        builder.from("users")
        
        def result = builder.build()
        
        then:
        result.collection == "users"
        !result.isAggregation
        result.filter == [:]
    }
    
    def "should build query with select fields"() {
        when:
        def builder = new DocumentCriteriaBuilder()
        builder.from("users")
        builder.select("name", "email")
        
        def result = builder.build()
        
        then:
        result.projection == [name: 1, email: 1]
    }
    
    def "should build query with exclude fields"() {
        when:
        def builder = new DocumentCriteriaBuilder()
        builder.from("users")
        builder.exclude("password", "internalId")
        
        def result = builder.build()
        
        then:
        result.projection == [password: 0, internalId: 0]
    }
    
    // =========================================================================
    // Filter Tests
    // =========================================================================
    
    def "should build query with equals filter"() {
        when:
        def builder = new DocumentCriteriaBuilder()
        builder.from("users")
        builder.where {
            eq "status", "active"
        }
        
        def result = builder.build()
        
        then:
        result.filter == [status: "active"]
    }
    
    def "should build query with comparison filters"() {
        when:
        def builder = new DocumentCriteriaBuilder()
        builder.from("users")
        builder.where {
            gt "age", 18
            lte "score", 100
        }
        
        def result = builder.build()
        
        then:
        result.filter.age == ['$gt': 18]
        result.filter.score == ['$lte': 100]
    }
    
    def "should build query with IN filter"() {
        when:
        def builder = new DocumentCriteriaBuilder()
        builder.from("products")
        builder.where {
            inList "category", ["Electronics", "Books", "Toys"]
        }
        
        def result = builder.build()
        
        then:
        result.filter == [category: ['$in': ["Electronics", "Books", "Toys"]]]
    }
    
    def "should build query with NOT IN filter"() {
        when:
        def builder = new DocumentCriteriaBuilder()
        builder.from("users")
        builder.where {
            notIn "role", ["banned", "suspended"]
        }
        
        def result = builder.build()
        
        then:
        result.filter == [role: ['$nin': ["banned", "suspended"]]]
    }
    
    def "should build query with exists filter"() {
        when:
        def builder = new DocumentCriteriaBuilder()
        builder.from("users")
        builder.where {
            exists "email"
        }
        
        def result = builder.build()
        
        then:
        result.filter == [email: ['$exists': true]]
    }
    
    def "should build query with null filters"() {
        when:
        def builder = new DocumentCriteriaBuilder()
        builder.from("users")
        builder.where {
            isNotNull "email"
            isNull "deletedAt"
        }
        
        def result = builder.build()
        
        then:
        result.filter.email == ['$ne': null]
        result.filter.deletedAt == null
    }
    
    // =========================================================================
    // Text Search Tests
    // =========================================================================
    
    def "should build query with regex filter"() {
        when:
        def builder = new DocumentCriteriaBuilder()
        builder.from("users")
        builder.where {
            regex "email", ".*@gmail\\.com"
        }
        
        def result = builder.build()
        
        then:
        result.filter == [email: ['$regex': ".*@gmail\\.com"]]
    }
    
    def "should build query with contains filter"() {
        when:
        def builder = new DocumentCriteriaBuilder()
        builder.from("users")
        builder.where {
            contains "name", "John"
        }
        
        def result = builder.build()
        
        then:
        result.filter.name['$regex'] == ".*John.*"
        result.filter.name['$options'] == 'i'
    }
    
    def "should build query with startsWith filter"() {
        when:
        def builder = new DocumentCriteriaBuilder()
        builder.from("users")
        builder.where {
            startsWith "email", "admin"
        }
        
        def result = builder.build()
        
        then:
        result.filter.email['$regex'] == "^admin"
        result.filter.email['$options'] == 'i'
    }
    
    def "should build query with endsWith filter"() {
        when:
        def builder = new DocumentCriteriaBuilder()
        builder.from("users")
        builder.where {
            endsWith "email", ".com"
        }
        
        def result = builder.build()
        
        then:
        result.filter.email['$regex'] == ".com\$"
        result.filter.email['$options'] == 'i'
    }
    
    // =========================================================================
    // Logical Operators
    // =========================================================================
    
    def "should build query with OR conditions"() {
        when:
        def builder = new DocumentCriteriaBuilder()
        builder.from("users")
        builder.where {
            or(
                { eq "status", "active" },
                { eq "status", "pending" }
            )
        }
        
        def result = builder.build()
        
        then:
        result.filter['$or'] != null
        result.filter['$or'].size() == 2
    }
    
    def "should build query with AND conditions"() {
        when:
        def builder = new DocumentCriteriaBuilder()
        builder.from("users")
        builder.where {
            and(
                { gt "age", 18 },
                { lt "age", 65 }
            )
        }
        
        def result = builder.build()
        
        then:
        result.filter['$and'] != null
        result.filter['$and'].size() == 2
    }
    
    // =========================================================================
    // Array Operators
    // =========================================================================
    
    def "should build query with all operator"() {
        when:
        def builder = new DocumentCriteriaBuilder()
        builder.from("users")
        builder.where {
            all "tags", ["premium", "verified"]
        }
        
        def result = builder.build()
        
        then:
        result.filter == [tags: ['$all': ["premium", "verified"]]]
    }
    
    def "should build query with size operator"() {
        when:
        def builder = new DocumentCriteriaBuilder()
        builder.from("users")
        builder.where {
            size "tags", 3
        }
        
        def result = builder.build()
        
        then:
        result.filter == [tags: ['$size': 3]]
    }
    
    def "should build query with elemMatch"() {
        when:
        def builder = new DocumentCriteriaBuilder()
        builder.from("users")
        builder.where {
            elemMatch "orders", {
                gt "total", 100
                eq "status", "completed"
            }
        }
        
        def result = builder.build()
        
        then:
        result.filter.orders['$elemMatch'] != null
    }
    
    // =========================================================================
    // Sort and Pagination
    // =========================================================================
    
    def "should build query with sort"() {
        when:
        def builder = new DocumentCriteriaBuilder()
        builder.from("users")
        builder.orderBy(createdAt: "DESC", name: "ASC")
        
        def result = builder.build()
        
        then:
        result.options.sort == [createdAt: -1, name: 1]
    }
    
    def "should build query with orderByAsc"() {
        when:
        def builder = new DocumentCriteriaBuilder()
        builder.from("users")
        builder.orderByAsc("name", "email")
        
        def result = builder.build()
        
        then:
        result.options.sort == [name: 1, email: 1]
    }
    
    def "should build query with orderByDesc"() {
        when:
        def builder = new DocumentCriteriaBuilder()
        builder.from("users")
        builder.orderByDesc("createdAt")
        
        def result = builder.build()
        
        then:
        result.options.sort == [createdAt: -1]
    }
    
    def "should build query with limit and skip"() {
        when:
        def builder = new DocumentCriteriaBuilder()
        builder.from("users")
        builder.limit(10)
        builder.skip(20)
        
        def result = builder.build()
        
        then:
        result.options.limit == 10
        result.options.skip == 20
    }
    
    // =========================================================================
    // Complex Queries
    // =========================================================================
    
    def "should build complex query with all features"() {
        when:
        def builder = new DocumentCriteriaBuilder()
        builder.from("users")
        builder.select("name", "email", "age")
        builder.where {
            gt "age", 18
            lt "age", 65
            inList "country", ["US", "UK", "CA"]
            contains "email", "@company.com"
            exists "verifiedAt"
        }
        builder.orderByDesc("createdAt")
        builder.limit(100)
        builder.skip(50)
        
        def result = builder.build()
        
        then:
        result.collection == "users"
        result.projection == [name: 1, email: 1, age: 1]
        result.filter.age != null
        result.filter.country != null
        result.filter.email != null
        result.filter.verifiedAt != null
        result.options.sort == [createdAt: -1]
        result.options.limit == 100
        result.options.skip == 50
    }
    
    // =========================================================================
    // Aggregation Tests
    // =========================================================================
    
    def "should build aggregation with match stage"() {
        when:
        def builder = new DocumentCriteriaBuilder()
        builder.from("orders")
        builder.aggregate {
            match {
                eq "status", "completed"
                gte "total", 100
            }
        }
        
        def result = builder.build()
        
        then:
        result.isAggregation
        result.pipeline.size() == 1
        result.pipeline[0]['$match'] != null
    }
    
    def "should build aggregation with group stage"() {
        when:
        def builder = new DocumentCriteriaBuilder()
        builder.from("orders")
        builder.aggregate {
            group([
                _id: '$customerId',
                total: ['$sum': '$amount'],
                count: ['$sum': 1]
            ])
        }
        
        def result = builder.build()
        
        then:
        result.pipeline.size() == 1
        result.pipeline[0]['$group'] != null
        result.pipeline[0]['$group']._id == '$customerId'
    }
    
    def "should build aggregation with project stage"() {
        when:
        def builder = new DocumentCriteriaBuilder()
        builder.from("users")
        builder.aggregate {
            project([
                name: 1,
                email: 1,
                fullName: ['$concat': ['$firstName', ' ', '$lastName']]
            ])
        }
        
        def result = builder.build()
        
        then:
        result.pipeline[0]['$project'] != null
    }
    
    def "should build aggregation with sort and limit"() {
        when:
        def builder = new DocumentCriteriaBuilder()
        builder.from("orders")
        builder.aggregate {
            match { eq "status", "completed" }
            group([_id: '$customerId', total: ['$sum': '$amount']])
            sort total: -1
            limit 10
        }
        
        def result = builder.build()
        
        then:
        result.pipeline.size() == 4
        result.pipeline[0]['$match'] != null
        result.pipeline[1]['$group'] != null
        result.pipeline[2]['$sort'] == [total: -1]
        result.pipeline[3]['$limit'] == 10
    }
    
    def "should build aggregation with unwind"() {
        when:
        def builder = new DocumentCriteriaBuilder()
        builder.from("orders")
        builder.aggregate {
            unwind "items"
        }
        
        def result = builder.build()
        
        then:
        result.pipeline[0]['$unwind'] == '$items'
    }
    
    def "should build aggregation with lookup (join)"() {
        when:
        def builder = new DocumentCriteriaBuilder()
        builder.from("orders")
        builder.aggregate {
            lookup("customers", "customerId", "_id", "customer")
        }
        
        def result = builder.build()
        
        then:
        result.pipeline[0]['$lookup'] != null
        result.pipeline[0]['$lookup'].from == "customers"
        result.pipeline[0]['$lookup'].localField == "customerId"
        result.pipeline[0]['$lookup'].foreignField == "_id"
        result.pipeline[0]['$lookup'].as == "customer"
    }
    
    def "should build complex aggregation pipeline"() {
        when:
        def builder = new DocumentCriteriaBuilder()
        builder.from("orders")
        builder.aggregate {
            match {
                gte "orderDate", "2024-01-01"
                eq "status", "completed"
            }
            unwind "items"
            group([
                _id: '$items.productId',
                totalQuantity: ['$sum': '$items.quantity'],
                totalRevenue: ['$sum': ['$multiply': ['$items.quantity', '$items.price']]],
                orderCount: ['$sum': 1]
            ])
            sort totalRevenue: -1
            limit 10
        }
        
        def result = builder.build()
        
        then:
        result.isAggregation
        result.pipeline.size() == 5
    }
    
    // =========================================================================
    // Error Cases
    // =========================================================================
    
    def "should throw exception if collection not specified"() {
        when:
        def builder = new DocumentCriteriaBuilder()
        builder.where { eq "status", "active" }
        builder.build()
        
        then:
        thrown(IllegalStateException)
    }
}
