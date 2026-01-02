package org.softwood.dag.task.nosql

import spock.lang.Specification

/**
 * Integration tests demonstrating real-world usage scenarios
 * of the NoSqlTask with DocumentCriteriaBuilder.
 */
class NoSqlTaskIntegrationTest extends Specification {
    
    def "should build user search query with criteria"() {
        given: "a criteria builder for user search"
        def builder = new DocumentCriteriaBuilder()
        
        when: "building a query to find active users"
        builder.with {
            from "users"
            select "name", "email", "createdAt"
            where {
                eq "status", "active"
                gt "age", 18
                contains "email", "@company.com"
                exists "verifiedAt"
            }
            orderByDesc "createdAt"
            limit 50
        }
        
        def result = builder.build()
        
        then: "the query should be properly structured"
        result.collection == "users"
        result.projection == [name: 1, email: 1, createdAt: 1]
        result.filter.status == "active"
        result.filter.age == ['$gt': 18]
        result.filter.email['$regex'] != null
        result.filter.verifiedAt == ['$exists': true]
        result.options.sort == [createdAt: -1]
        result.options.limit == 50
    }
    
    def "should build product search with multiple filters"() {
        given: "a criteria builder for products"
        def builder = new DocumentCriteriaBuilder()
        
        when: "searching for products in price range"
        builder.with {
            from "products"
            where {
                inList "category", ["Electronics", "Computers", "Phones"]
                gte "price", 100.0
                lte "price", 1000.0
                gt "stock", 0
                or(
                    { contains "name", "laptop" },
                    { contains "name", "phone" }
                )
            }
            orderBy price: "ASC", rating: "DESC"
            limit 20
        }
        
        def result = builder.build()
        
        then: "the query should handle complex filters"
        result.filter.category == ['$in': ["Electronics", "Computers", "Phones"]]
        result.filter.price != null
        result.filter.stock == ['$gt': 0]
        result.filter['$or'] != null
        result.options.sort == [price: 1, rating: -1]
    }
    
    def "should build order aggregation for customer analytics"() {
        given: "a criteria builder for order analytics"
        def builder = new DocumentCriteriaBuilder()
        
        when: "aggregating orders by customer"
        builder.with {
            from "orders"
            aggregate {
                match {
                    gte "orderDate", "2024-01-01"
                    eq "status", "completed"
                }
                group([
                    _id: '$customerId',
                    totalSpent: ['$sum': '$total'],
                    orderCount: ['$sum': 1],
                    avgOrder: ['$avg': '$total'],
                    firstOrder: ['$min': '$orderDate'],
                    lastOrder: ['$max': '$orderDate']
                ])
                sort totalSpent: -1
                limit 100
            }
        }
        
        def result = builder.build()
        
        then: "the aggregation should be correctly structured"
        result.isAggregation
        result.pipeline.size() == 4
        result.pipeline[0]['$match'] != null
        result.pipeline[1]['$group'] != null
        result.pipeline[1]['$group']._id == '$customerId'
        result.pipeline[2]['$sort'] == [totalSpent: -1]
        result.pipeline[3]['$limit'] == 100
    }
    
    def "should build product sales aggregation with unwind"() {
        given: "a criteria builder for product sales"
        def builder = new DocumentCriteriaBuilder()
        
        when: "analyzing sales by product"
        builder.with {
            from "orders"
            aggregate {
                match { eq "status", "completed" }
                unwind "items"
                group([
                    _id: '$items.productId',
                    totalQuantity: ['$sum': '$items.quantity'],
                    totalRevenue: ['$sum': ['$multiply': ['$items.quantity', '$items.price']]],
                    avgPrice: ['$avg': '$items.price'],
                    orderCount: ['$sum': 1]
                ])
                sort totalRevenue: -1
                limit 20
            }
        }
        
        def result = builder.build()
        
        then: "the pipeline should include unwind and calculations"
        result.pipeline.any { it.containsKey('$unwind') }
        result.pipeline.any { it.containsKey('$group') }
        def groupStage = result.pipeline.find { it.containsKey('$group') }
        groupStage['$group'].totalRevenue != null
    }
    
    def "should build user activity report with lookup"() {
        given: "a criteria builder for user activity"
        def builder = new DocumentCriteriaBuilder()
        
        when: "joining users with their orders"
        builder.with {
            from "users"
            aggregate {
                match { eq "active", true }
                lookup("orders", "_id", "userId", "userOrders")
                project([
                    name: 1,
                    email: 1,
                    orderCount: ['$size': '$userOrders'],
                    totalSpent: ['$sum': '$userOrders.total']
                ])
                sort orderCount: -1
                limit 50
            }
        }
        
        def result = builder.build()
        
        then: "the pipeline should include lookup and calculated fields"
        def lookupStage = result.pipeline.find { it.containsKey('$lookup') }
        lookupStage != null
        lookupStage['$lookup'].from == "orders"
        lookupStage['$lookup'].as == "userOrders"
        
        def projectStage = result.pipeline.find { it.containsKey('$project') }
        projectStage != null
        projectStage['$project'].orderCount != null
    }
    
    def "should build inventory low stock alert query"() {
        given: "a criteria builder for inventory"
        def builder = new DocumentCriteriaBuilder()
        
        when: "finding low stock items"
        builder.with {
            from "inventory"
            where {
                lt "quantity", 10
                ne "status", "discontinued"
                exists "reorderLevel"
                or(
                    { eq "category", "Critical" },
                    { gt "demandRate", 5.0 }
                )
            }
            orderByAsc "quantity"
            limit 100
        }
        
        def result = builder.build()
        
        then: "the query should identify critical stock levels"
        result.filter.quantity == ['$lt': 10]
        result.filter.status == ['$ne': "discontinued"]
        result.filter.reorderLevel == ['$exists': true]
        result.filter['$or'] != null
        result.options.sort == [quantity: 1]
    }
    
    def "should build user engagement metrics aggregation"() {
        given: "a criteria builder for engagement metrics"
        def builder = new DocumentCriteriaBuilder()
        
        when: "calculating user engagement"
        builder.with {
            from "events"
            aggregate {
                match {
                    gte "timestamp", "2024-01-01"
                    inList "eventType", ["login", "pageView", "action"]
                }
                group([
                    _id: [
                        userId: '$userId',
                        date: ['$dateToString': [format: '%Y-%m-%d', date: '$timestamp']]
                    ],
                    eventCount: ['$sum': 1],
                    uniqueActions: ['$addToSet': '$eventType'],
                    firstEvent: ['$min': '$timestamp'],
                    lastEvent: ['$max': '$timestamp']
                ])
                project([
                    userId: '$_id.userId',
                    date: '$_id.date',
                    eventCount: 1,
                    actionCount: ['$size': '$uniqueActions'],
                    sessionDuration: ['$subtract': ['$lastEvent', '$firstEvent']]
                ])
                sort eventCount: -1
            }
        }
        
        def result = builder.build()
        
        then: "the aggregation should calculate engagement metrics"
        result.isAggregation
        result.pipeline.any { it.containsKey('$group') }
        result.pipeline.any { it.containsKey('$project') }
    }
    
    def "should build geographic sales analysis"() {
        given: "a criteria builder for geographic analysis"
        def builder = new DocumentCriteriaBuilder()
        
        when: "analyzing sales by region and country"
        builder.with {
            from "orders"
            aggregate {
                match {
                    gte "orderDate", "2024-01-01"
                    eq "status", "completed"
                }
                lookup("customers", "customerId", "_id", "customer")
                unwind "customer"
                group([
                    _id: [
                        country: '$customer.country',
                        region: '$customer.region'
                    ],
                    totalOrders: ['$sum': 1],
                    totalRevenue: ['$sum': '$total'],
                    avgOrderValue: ['$avg': '$total'],
                    uniqueCustomers: ['$addToSet': '$customerId']
                ])
                addFields([
                    customerCount: ['$size': '$uniqueCustomers']
                ])
                sort totalRevenue: -1
                limit 50
            }
        }
        
        def result = builder.build()
        
        then: "the pipeline should join and aggregate by geography"
        result.pipeline.any { it.containsKey('$lookup') }
        result.pipeline.any { it.containsKey('$addFields') }
        def groupStage = result.pipeline.find { it.containsKey('$group') }
        groupStage['$group']._id.country != null
        groupStage['$group']._id.region != null
    }
    
    def "should build time-series data query"() {
        given: "a criteria builder for time-series"
        def builder = new DocumentCriteriaBuilder()
        
        when: "querying sensor data with time windows"
        builder.with {
            from "sensorData"
            where {
                eq "sensorId", "SENSOR_001"
                gte "timestamp", "2024-01-01T00:00:00Z"
                lte "timestamp", "2024-01-31T23:59:59Z"
                exists "value"
            }
            orderByAsc "timestamp"
            limit 10000
        }
        
        def result = builder.build()
        
        then: "the query should handle time-series efficiently"
        result.filter.sensorId == "SENSOR_001"
        result.filter.timestamp != null
        result.filter.value == ['$exists': true]
        result.options.sort == [timestamp: 1]
        result.options.limit == 10000
    }
    
    def "should build recommendation engine query"() {
        given: "a criteria builder for recommendations"
        def builder = new DocumentCriteriaBuilder()
        
        when: "finding similar products based on user behavior"
        builder.with {
            from "productInteractions"
            aggregate {
                match {
                    eq "userId", "USER_123"
                    inList "eventType", ["view", "purchase", "addToCart"]
                }
                group([
                    _id: '$productId',
                    score: ['$sum': [
                        '$cond': [
                            ['$eq': ['$eventType', 'purchase']], 10,
                            ['$cond': [['$eq': ['$eventType', 'addToCart']], 5, 1]]
                        ]
                    ]],
                    lastInteraction: ['$max': '$timestamp']
                ])
                lookup("products", "_id", "_id", "product")
                unwind "product"
                match { eq "product.available", true }
                sort score: -1, lastInteraction: -1
                limit 10
            }
        }
        
        def result = builder.build()
        
        then: "the pipeline should calculate recommendation scores"
        result.isAggregation
        result.pipeline.size() > 5
        def groupStage = result.pipeline.find { it.containsKey('$group') }
        groupStage['$group'].score != null
    }
    
    def "should build complex filtering with nested OR and AND"() {
        given: "a criteria builder with complex logic"
        def builder = new DocumentCriteriaBuilder()
        
        when: "combining multiple logical operators"
        builder.with {
            from "users"
            where {
                or(
                    { eq "role", "admin" },
                    { 
                        and(
                            { eq "role", "user" },
                            { gt "reputation", 1000 },
                            { exists "verifiedEmail" }
                        )
                    }
                )
                ne "status", "banned"
                gte "createdAt", "2023-01-01"
            }
            orderByDesc "reputation"
        }
        
        def result = builder.build()
        
        then: "the query should handle nested logic correctly"
        result.filter['$or'] != null
        result.filter.status == ['$ne': "banned"]
        result.filter.createdAt != null
    }
}
