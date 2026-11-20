# NoSqlTask User Guide

Complete guide to using NoSqlTask for document store operations in DAG workflows.

## Table of Contents
- [Overview](#overview)
- [Getting Started](#getting-started)
- [Provider Configuration](#provider-configuration)
- [Query Operations](#query-operations)
  - [Basic Find](#basic-find)
  - [Criteria DSL](#criteria-dsl)
  - [Filter Operations](#filter-operations)
- [Aggregation Pipelines](#aggregation-pipelines)
- [Insert Operations](#insert-operations)
- [Update Operations](#update-operations)
- [Delete Operations](#delete-operations)
- [Advanced Features](#advanced-features)
- [Real-World Examples](#real-world-examples)

---

## Overview

NoSqlTask provides a type-safe DSL for working with NoSQL document stores like MongoDB, Couchbase, and others. It mirrors SqlTask's architecture but is optimized for document-oriented operations.

### Key Features
- ✅ **Pluggable Providers** - MongoDB, Couchbase, DynamoDB support
- ✅ **Type-Safe DSL** - Groovy-based criteria builder
- ✅ **Aggregation Pipelines** - Complex data transformations
- ✅ **Zero Dependencies** - MongoProvider works in stub mode without MongoDB driver
- ✅ **Transaction Support** - For databases that support transactions
- ✅ **Result Mappers** - Transform query results

---

## Getting Started

### Dependencies

**Core (Required):**
```groovy
// GroovyConcurrentUtils already included

No additional dependencies required for basic functionality
```

**MongoDB Support (Optional):**
```groovy
dependencies {
    implementation 'org.mongodb:mongodb-driver-sync:4.11.1'
}
```

### Simple Example

```groovy
import org.softwood.dag.task.NoSqlTask
import org.softwood.dag.task.nosql.MongoProvider

// Create task
def findUsers = new NoSqlTask("find-users", "Find Active Users", context)

findUsers.with {
    provider new MongoProvider(
        connectionString: "mongodb://localhost:27017",
        databaseName: "myapp"
    )
    
    criteria {
        from "users"
        where {
            eq "status", "active"
            gt "age", 18
        }
        limit 10
    }
}

// Execute
def result = findUsers.execute(previousResult).get()
```

---

## Provider Configuration

### MongoDB Provider

#### Connection String Configuration
```groovy
noSqlTask("my-task") {
    provider new MongoProvider(
        connectionString: "mongodb://localhost:27017",
        databaseName: "mydb"
    )
}
```

#### Using DataSource Builder
```groovy
noSqlTask("my-task") {
    dataSource {
        connectionString = "mongodb://localhost:27017"
        databaseName = "mydb"
    }
}
```

#### Connection String Formats
```groovy
// Local MongoDB
"mongodb://localhost:27017"

// With authentication
"mongodb://username:password@localhost:27017"

// Replica set
"mongodb://host1:27017,host2:27017,host3:27017/?replicaSet=myReplicaSet"

// MongoDB Atlas
"mongodb+srv://username:password@cluster.mongodb.net/mydb"
```

---

## Query Operations

### Basic Find

#### Simple Query
```groovy
noSqlTask("find-all-users") {
    provider myProvider
    
    find "users"
    limit 100
}
```

#### With Filter Map
```groovy
noSqlTask("find-active-users") {
    provider myProvider
    
    find "users"
    filter status: "active", age: [$gt: 18]
    select "name", "email"
    orderBy createdAt: "DESC"
    limit 50
}
```

### Criteria DSL

The Criteria DSL provides a type-safe way to build queries:

#### Basic Criteria
```groovy
noSqlTask("user-search") {
    provider myProvider
    
    criteria {
        from "users"
        select "name", "email", "age"
        where {
            eq "status", "active"
            gt "age", 18
        }
        orderByDesc "createdAt"
        limit 100
    }
}
```

### Filter Operations

#### Comparison Operators
```groovy
criteria {
    from "products"
    where {
        eq "status", "active"      // equals
        ne "status", "deleted"     // not equals
        gt "price", 10.0           // greater than
        gte "price", 10.0          // greater than or equal
        lt "price", 1000.0         // less than
        lte "price", 1000.0        // less than or equal
    }
}
```

#### List Operators
```groovy
criteria {
    from "products"
    where {
        inList "category", ["Electronics", "Books", "Toys"]
        notIn "status", ["deleted", "archived"]
    }
}
```

#### Existence Checks
```groovy
criteria {
    from "users"
    where {
        exists "email"              // field exists
        exists "phone", false       // field doesn't exist
        isNotNull "email"           // field is not null
        isNull "deletedAt"          // field is null
    }
}
```

#### Text Search
```groovy
criteria {
    from "products"
    where {
        contains "name", "laptop"      // case-insensitive substring
        startsWith "sku", "PROD"       // starts with
        endsWith "email", ".com"       // ends with
        regex "phone", "^\\+1"         // custom regex
    }
}
```

#### Array Operators
```groovy
criteria {
    from "users"
    where {
        all "tags", ["premium", "verified"]     // has all tags
        size "tags", 3                          // array size is 3
        elemMatch "orders", {                   // array element matches
            gt "total", 100
            eq "status", "completed"
        }
    }
}
```

#### Logical Operators
```groovy
criteria {
    from "users"
    where {
        // OR
        or(
            { eq "role", "admin" },
            { eq "role", "moderator" }
        )
        
        // AND
        and(
            { gt "age", 18 },
            { lt "age", 65 }
        )
        
        // NOT
        not "status", {
            eq "status", "banned"
        }
    }
}
```

### Sort and Pagination

```groovy
criteria {
    from "users"
    
    // Sort by multiple fields
    orderBy createdAt: "DESC", name: "ASC"
    
    // Or use convenience methods
    orderByAsc "name", "email"
    orderByDesc "createdAt"
    
    // Pagination
    limit 50
    skip 100
}
```

### Field Projection

```groovy
criteria {
    from "users"
    
    // Include specific fields
    select "name", "email", "age"
    
    // Exclude specific fields
    exclude "password", "internalId"
}
```

---

## Aggregation Pipelines

Aggregation pipelines transform and analyze data through multiple stages.

### Basic Aggregation

```groovy
noSqlTask("total-sales") {
    provider myProvider
    
    criteria {
        from "orders"
        aggregate {
            match {
                gte "orderDate", "2024-01-01"
                eq "status", "completed"
            }
            group([
                _id: null,
                totalRevenue: ['$sum': '$total'],
                orderCount: ['$sum': 1],
                avgOrder: ['$avg': '$total']
            ])
        }
    }
}
```

### Group By Field

```groovy
criteria {
    from "orders"
    aggregate {
        match { eq "status", "completed" }
        group([
            _id: '$customerId',
            totalSpent: ['$sum': '$total'],
            orderCount: ['$sum': 1]
        ])
        sort totalSpent: -1
        limit 10
    }
}
```

### Unwind Arrays

```groovy
criteria {
    from "orders"
    aggregate {
        unwind "items"
        group([
            _id: '$items.productId',
            quantitySold: ['$sum': '$items.quantity']
        ])
    }
}
```

### Lookup (Join)

```groovy
criteria {
    from "orders"
    aggregate {
        lookup("customers", "customerId", "_id", "customer")
        unwind "customer"
        project([
            orderNumber: 1,
            total: 1,
            customerName: '$customer.name'
        ])
    }
}
```

### Complex Pipeline

```groovy
criteria {
    from "orders"
    aggregate {
        // Filter
        match {
            gte "orderDate", "2024-01-01"
            eq "status", "completed"
        }
        
        // Unwind line items
        unwind "items"
        
        // Group by product
        group([
            _id: '$items.productId',
            totalQuantity: ['$sum': '$items.quantity'],
            totalRevenue: ['$sum': ['$multiply': ['$items.quantity', '$items.price']]],
            orderCount: ['$sum': 1]
        ])
        
        // Join with products
        lookup("products", "_id", "_id", "product")
        unwind "product"
        
        // Calculate metrics
        project([
            productId: '$_id',
            productName: '$product.name',
            totalQuantity: 1,
            totalRevenue: 1,
            orderCount: 1,
            avgPrice: ['$divide': ['$totalRevenue', '$totalQuantity']]
        ])
        
        // Sort and limit
        sort totalRevenue: -1
        limit 20
    }
}
```

---

## Insert Operations

### Insert Single Document

```groovy
noSqlTask("create-user") {
    provider myProvider
    
    insertOne "users", [
        name: "John Doe",
        email: "john@example.com",
        createdAt: new Date()
    ]
}
```

### Insert with Previous Result

```groovy
noSqlTask("create-order") {
    provider myProvider
    
    insertOne "orders", { prev ->
        [
            customerId: prev.customerId,
            items: prev.items,
            total: prev.total,
            orderDate: new Date()
        ]
    }
}
```

### Insert Multiple Documents

```groovy
noSqlTask("bulk-import") {
    provider myProvider
    
    insertMany "products", [
        [name: "Product A", price: 10.0],
        [name: "Product B", price: 20.0],
        [name: "Product C", price: 30.0]
    ]
}
```

---

## Update Operations

### Update Single Document

```groovy
noSqlTask("activate-user") {
    provider myProvider
    
    update "users",
        [_id: "USER_123"],
        ['$set': [status: "active", updatedAt: new Date()]]
}
```

### Update Multiple Documents

```groovy
noSqlTask("archive-old-orders") {
    provider myProvider
    
    update "orders",
        [orderDate: ['$lt': "2023-01-01"]],
        ['$set': [status: "archived"]]
    
    updateMany true
}
```

### Upsert (Insert if Not Exists)

```groovy
noSqlTask("update-or-create") {
    provider myProvider
    
    update "products",
        [sku: "PROD-001"],
        ['$set': [name: "New Product", price: 99.99]]
    
    upsert true
}
```

### Update Operators

```groovy
// Set fields
update "users", [_id: "123"], ['$set': [name: "New Name"]]

// Increment
update "products", [sku: "PROD-001"], ['$inc': [stock: 10]]

// Push to array
update "users", [_id: "123"], ['$push': [tags: "premium"]]

// Pull from array
update "users", [_id: "123"], ['$pull': [tags: "trial"]]

// Unset (remove field)
update "users", [_id: "123"], ['$unset': [tempField: ""]]

// Rename field
update "users", [_id: "123"], ['$rename': [oldName: "newName"]]
```

---

## Delete Operations

### Delete Documents

```groovy
noSqlTask("delete-inactive-users") {
    provider myProvider
    
    delete "users", [
        status: "inactive",
        lastLogin: ['$lt': "2023-01-01"]
    ]
}
```

### Delete All Documents Matching Filter

```groovy
noSqlTask("purge-old-logs") {
    provider myProvider
    
    delete "logs", [
        createdAt: ['$lt': "2024-01-01"]
    ]
}
```

---

## Advanced Features

### Result Mapper

Transform query results:

```groovy
noSqlTask("formatted-users") {
    provider myProvider
    
    criteria {
        from "users"
        where { eq "status", "active" }
    }
    
    resultMapper { results ->
        results.collect { user ->
            [
                id: user._id.toString(),
                name: user.name,
                email: user.email,
                memberSince: user.createdAt.format('yyyy-MM-dd')
            ]
        }
    }
}
```

### Custom Execution

Access native MongoDB client:

```groovy
noSqlTask("custom-operation") {
    provider myProvider
    
    execute { client ->
        def db = client.getDatabase("mydb")
        def collection = db.getCollection("users")
        
        // Use native MongoDB driver
        def result = collection.find(new Document("status", "active"))
            .into([])
        
        return result
    }
}
```

### Transactions

```groovy
noSqlTask("transfer-funds") {
    provider myProvider
    
    execute { client ->
        def session = client.startSession()
        session.startTransaction()
        
        try {
            def accounts = client.getDatabase("bank").getCollection("accounts")
            
            // Debit from account A
            accounts.updateOne(session, 
                new Document("_id", "ACCT_A"),
                new Document('$inc', new Document("balance", -100))
            )
            
            // Credit to account B
            accounts.updateOne(session,
                new Document("_id", "ACCT_B"),
                new Document('$inc', new Document("balance", 100))
            )
            
            session.commitTransaction()
            return [success: true]
        } catch (Exception e) {
            session.abortTransaction()
            throw e
        } finally {
            session.close()
        }
    }
    
    transaction true
}
```

---

## Real-World Examples

### 1. User Analytics Dashboard

```groovy
noSqlTask("user-analytics") {
    provider myProvider
    
    criteria {
        from "users"
        aggregate {
            match {
                gte "createdAt", "2024-01-01"
            }
            group([
                _id: [
                    year: ['$year': '$createdAt'],
                    month: ['$month': '$createdAt']
                ],
                newUsers: ['$sum': 1],
                activeUsers: ['$sum': ['$cond': [['$eq': ['$status', 'active']], 1, 0]]],
                premiumUsers: ['$sum': ['$cond': [['$eq': ['$plan', 'premium']], 1, 0]]]
            ])
            project([
                period: ['$concat': [
                    ['$toString': '$_id.year'],
                    '-',
                    ['$toString': '$_id.month']
                ]],
                newUsers: 1,
                activeUsers: 1,
                premiumUsers: 1,
                conversionRate: ['$divide': ['$premiumUsers', '$newUsers']]
            ])
            sort '_id.year': -1, '_id.month': -1
        }
    }
}
```

### 2. E-Commerce Product Recommendations

```groovy
noSqlTask("product-recommendations") {
    provider myProvider
    
    criteria {
        from "userInteractions"
        aggregate {
            match {
                eq "userId", "USER_123"
                inList "eventType", ["view", "purchase", "addToCart"]
            }
            group([
                _id: '$productId',
                interactionScore: ['$sum': [
                    '$switch': [
                        branches: [
                            [case: ['$eq': ['$eventType', 'purchase']], then: 10],
                            [case: ['$eq': ['$eventType', 'addToCart']], then: 5],
                            [case: ['$eq': ['$eventType', 'view']], then: 1]
                        ],
                        default: 0
                    ]
                ]],
                lastInteraction: ['$max': '$timestamp']
            ])
            lookup("products", "_id", "_id", "product")
            unwind "product"
            match { eq "product.available", true }
            project([
                productId: '$_id',
                name: '$product.name',
                price: '$product.price',
                category: '$product.category',
                score: '$interactionScore',
                lastInteraction: 1
            ])
            sort score: -1, lastInteraction: -1
            limit 10
        }
    }
    
    resultMapper { results ->
        results.collect { [
            id: it.productId.toString(),
            name: it.name,
            price: it.price,
            category: it.category,
            relevanceScore: it.score
        ]}
    }
}
```

### 3. Customer Lifetime Value

```groovy
noSqlTask("customer-ltv") {
    provider myProvider
    
    criteria {
        from "customers"
        aggregate {
            match { eq "active", true }
            
            lookup("orders", "_id", "customerId", "orders")
            
            unwind "orders"
            
            match { eq "orders.status", "completed" }
            
            group([
                _id: '$_id',
                customerName: ['$first': '$name'],
                customerEmail: ['$first': '$email'],
                firstPurchase: ['$min': '$orders.orderDate'],
                lastPurchase: ['$max': '$orders.orderDate'],
                totalOrders: ['$sum': 1],
                totalSpent: ['$sum': '$orders.total'],
                avgOrderValue: ['$avg': '$orders.total']
            ])
            
            addFields([
                customerTenure: ['$dateDiff': [
                    startDate: '$firstPurchase',
                    endDate: '$lastPurchase',
                    unit: 'day'
                ]],
                ltv: ['$multiply': ['$avgOrderValue', 12]]  // Projected annual value
            ])
            
            match { gt "totalSpent", 1000 }
            
            sort totalSpent: -1
            limit 100
        }
    }
}
```

### 4. Inventory Management

```groovy
noSqlTask("low-stock-alert") {
    provider myProvider
    
    criteria {
        from "inventory"
        aggregate {
            lookup("products", "productId", "_id", "product")
            unwind "product"
            
            project([
                sku: '$product.sku',
                name: '$product.name',
                category: '$product.category',
                currentStock: '$quantity',
                reorderLevel: '$product.reorderLevel',
                supplierId: '$product.supplierId',
                stockStatus: [
                    '$cond': [
                        ['$lte': ['$quantity', '$product.reorderLevel']],
                        'CRITICAL',
                        'OK'
                    ]
                ]
            ])
            
            match { eq "stockStatus", "CRITICAL" }
            
            lookup("suppliers", "supplierId", "_id", "supplier")
            unwind "supplier"
            
            project([
                sku: 1,
                name: 1,
                category: 1,
                currentStock: 1,
                reorderLevel: 1,
                supplierName: '$supplier.name',
                supplierContact: '$supplier.email'
            ])
            
            sort category: 1, name: 1
        }
    }
}
```

### 5. Time-Series Analytics

```groovy
noSqlTask("hourly-traffic") {
    provider myProvider
    
    criteria {
        from "pageViews"
        aggregate {
            match {
                gte "timestamp", "2024-01-01T00:00:00Z"
                lte "timestamp", "2024-01-31T23:59:59Z"
            }
            
            group([
                _id: [
                    date: ['$dateToString': [format: '%Y-%m-%d', date: '$timestamp']],
                    hour: ['$hour': '$timestamp']
                ],
                pageViews: ['$sum': 1],
                uniqueVisitors: ['$addToSet': '$userId'],
                topPages: ['$push': '$page']
            ])
            
            project([
                date: '$_id.date',
                hour: '$_id.hour',
                pageViews: 1,
                uniqueVisitors: ['$size': '$uniqueVisitors'],
                avgPageViewsPerUser: [
                    '$divide': ['$pageViews', ['$size': '$uniqueVisitors']]
                ]
            ])
            
            sort date: 1, hour: 1
        }
    }
}
```

---

## Best Practices

### 1. Index Your Queries
Always create indexes for frequently queried fields:
```javascript
// In MongoDB shell
db.users.createIndex({ "status": 1, "createdAt": -1 })
db.orders.createIndex({ "customerId": 1, "orderDate": -1 })
```

### 2. Use Projection
Only select fields you need:
```groovy
criteria {
    from "users"
    select "name", "email"  // Don't fetch unnecessary fields
}
```

### 3. Limit Result Sets
Always use limit for large collections:
```groovy
criteria {
    from "logs"
    limit 1000  // Prevent memory issues
}
```

### 4. Efficient Aggregations
- Use $match early in pipeline to reduce documents
- Use indexes with $match
- Minimize $lookup operations
- Use $project to reduce document size

### 5. Error Handling
```groovy
noSqlTask("safe-query") {
    provider myProvider
    
    criteria {
        from "users"
        where { eq "status", "active" }
    }
    
    onError { error ->
        log.error("Query failed: ${error.message}")
        return [success: false, error: error.message]
    }
}
```

---

## Comparison: SqlTask vs NoSqlTask

| Feature | SqlTask | NoSqlTask |
|---------|---------|-----------|
| **Data Model** | Tables with fixed schema | Documents (flexible schema) |
| **Query Language** | SQL + Criteria DSL | Document filters + Aggregation |
| **Joins** | INNER/LEFT/RIGHT/FULL | $lookup (aggregation) |
| **Grouping** | GROUP BY + HAVING | $group (aggregation) |
| **Filtering** | WHERE clause | where {} DSL or filter map |
| **Transactions** | ACID guaranteed | Depends on database (MongoDB 4.0+) |
| **Schema** | Fixed, enforced | Flexible, document-level |
| **Best For** | Structured, relational data | Semi-structured, hierarchical data |

---

## Troubleshooting

### MongoProvider in Stub Mode
If you see warnings about "stub mode", add MongoDB driver:
```groovy
implementation 'org.mongodb:mongodb-driver-sync:4.11.1'
```

### Connection Failures
Check connection string format and network access:
```groovy
// Test connection
def provider = new MongoProvider(
    connectionString: "mongodb://localhost:27017",
    databaseName: "test"
)
provider.initialize()
```

### Slow Queries
1. Add indexes for filtered fields
2. Use $match early in aggregation pipelines
3. Use projection to reduce document size
4. Monitor query performance with explain()

---

## Next Steps

- Explore the [DocumentCriteriaBuilder API](DocumentCriteriaBuilder.groovy)
- Review [Integration Tests](../../test/groovy/org/softwood/dag/task/nosql/)
- Check [Real-World Examples](#real-world-examples)
- Learn about [MongoDB Aggregation Framework](https://docs.mongodb.com/manual/aggregation/)
