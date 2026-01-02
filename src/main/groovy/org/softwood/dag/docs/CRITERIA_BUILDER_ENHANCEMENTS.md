# CriteriaBuilder Enhancement Guide

Complete guide to the enhanced CriteriaBuilder features for SqlTask.

## Table of Contents
- [GROUP BY Support](#group-by-support)
- [HAVING Clause Support](#having-clause-support)
- [SQL Functions](#sql-functions)
  - [Aggregate Functions](#aggregate-functions)
  - [String Functions](#string-functions)
  - [Date Functions](#date-functions)
- [JOIN Support](#join-support)
- [Subquery Support](#subquery-support)
- [Real-World Examples](#real-world-examples)

---

## GROUP BY Support

Group query results by one or more columns for aggregation.

### Basic GROUP BY

```groovy
sqlTask("user-count-by-country") {
    provider myProvider
    
    criteria {
        select "country"
        count "*", "user_count"
        from "users"
        groupBy "country"
        orderBy "user_count DESC"
    }
}
```

### Multiple Column GROUP BY

```groovy
criteria {
    select "country", "city"
    count "*", "user_count"
    avg "age", "avg_age"
    from "users"
    groupBy "country", "city"
    orderBy "country", "city"
}
```

---

## HAVING Clause Support

Filter grouped results using HAVING (like WHERE for aggregated data).

### Basic HAVING

```groovy
criteria {
    select "department"
    count "*", "employee_count"
    avg "salary", "avg_salary"
    from "employees"
    groupBy "department"
    having {
        gt "COUNT(*)", 10
        gt "AVG(salary)", 50000
    }
    orderBy "avg_salary DESC"
}
```

### HAVING with OR Conditions

```groovy
criteria {
    select "product_category"
    sum "revenue", "total_revenue"
    from "sales"
    groupBy "product_category"
    having {
        or {
            gt "SUM(revenue)", 1000000
            gt "COUNT(*)", 500
        }
    }
}
```

---

## SQL Functions

### Aggregate Functions

#### COUNT, SUM, AVG, MIN, MAX

```groovy
criteria {
    count "*", "total_orders"
    sum "amount", "total_revenue"
    avg "amount", "avg_order_value"
    min "amount", "smallest_order"
    max "amount", "largest_order"
    from "orders"
}
```

#### Custom Function

```groovy
criteria {
    function "COUNT(DISTINCT customer_id)", "unique_customers"
    function "STDDEV(amount)", "amount_stddev"
    from "orders"
}
```

### String Functions

#### UPPER / LOWER

```groovy
criteria {
    select "id"
    upper "name", "name_upper"
    lower "email", "email_lower"
    from "users"
}
```

#### LENGTH

```groovy
criteria {
    select "product_name"
    length "product_name", "name_length"
    from "products"
    where {
        gt "LENGTH(product_name)", 50
    }
}
```

#### SUBSTRING

```groovy
criteria {
    select "email"
    substring "email", 1, 10, "email_prefix"
    from "users"
}
```

#### TRIM

```groovy
criteria {
    trim "name", "trimmed_name"
    from "users"
}
```

#### CONCAT

```groovy
criteria {
    concat "full_name", "first_name", "' '", "last_name"
    from "users"
}
// Result: full_name = "John Doe"
```

#### COALESCE

```groovy
criteria {
    select "id"
    coalesce "display_name", "nickname", "username", "'Guest'"
    from "users"
}
// Returns first non-null value
// Note: String literals must include quotes: "'Guest'" not "Guest"
```

### Date Functions

#### Current Date/Time

```groovy
criteria {
    currentDate "today"
    currentTime "now_time"
    currentTimestamp "now"
    from "system"
}
```

#### Extract Date Components

```groovy
criteria {
    year "created_at", "year"
    month "created_at", "month"
    day "created_at", "day"
    from "orders"
    groupBy "YEAR(created_at)", "MONTH(created_at)"
}
```

#### Date Arithmetic

```groovy
// Add 30 days
criteria {
    select "order_id"
    dateAdd "order_date", 30, "DAY", "due_date"
    from "orders"
}

// Subtract 1 year
criteria {
    select "user_id"
    dateSub "created_at", 1, "YEAR", "one_year_ago"
    from "users"
}

// Calculate difference
criteria {
    select "project_id"
    dateDiff "end_date", "start_date", "duration_days"
    from "projects"
}
```

---

## JOIN Support

Add related data from multiple tables.

### INNER JOIN

```groovy
criteria {
    select "u.name", "o.total", "o.created_at"
    from "users u"
    innerJoin "orders o", "u.id = o.user_id"
    where {
        gt "o.total", 100
    }
    orderBy "o.total DESC"
}
```

### LEFT JOIN

```groovy
criteria {
    select "u.name", "COUNT(o.id) AS order_count"
    from "users u"
    leftJoin "orders o", "u.id = o.user_id"
    groupBy "u.id", "u.name"
    orderBy "order_count DESC"
}
// Returns all users, even those without orders
```

### RIGHT JOIN

```groovy
criteria {
    from "users u"
    rightJoin "orders o", "u.id = o.user_id"
    where {
        isNull "u.id"  // Orders without users
    }
}
```

### FULL OUTER JOIN

```groovy
criteria {
    from "users u"
    fullJoin "orders o", "u.id = o.user_id"
}
```

### Multiple JOINs

```groovy
criteria {
    select "u.name", "o.total", "p.name AS product"
    from "users u"
    innerJoin "orders o", "u.id = o.user_id"
    innerJoin "order_items oi", "o.id = oi.order_id"
    leftJoin "products p", "oi.product_id = p.id"
    where {
        eq "o.status", "completed"
    }
}
```

---

## Subquery Support

Embed queries within queries for complex filtering.

### EXISTS

Check if a subquery returns any rows.

```groovy
criteria {
    select "u.name", "u.email"
    from "users u"
    where {
        exists "SELECT 1 FROM orders WHERE orders.user_id = u.id"
        eq "u.active", true
    }
}
// Returns users who have placed at least one order
```

### NOT EXISTS

```groovy
criteria {
    from "users u"
    where {
        notExists "SELECT 1 FROM orders WHERE orders.user_id = u.id"
    }
}
// Returns users who have never placed an order
```

### IN Subquery

```groovy
criteria {
    from "users"
    where {
        inSubquery "country_id", "SELECT id FROM countries WHERE region = 'EU'"
    }
}
// Returns users from EU countries
```

### Custom Subquery Comparisons

```groovy
criteria {
    from "employees"
    where {
        subquery "salary", ">", "SELECT AVG(salary) FROM employees"
    }
}
// Returns employees earning above average
```

### Subquery in HAVING

```groovy
criteria {
    select "department"
    avg "salary", "avg_salary"
    from "employees"
    groupBy "department"
    having {
        subquery "AVG(salary)", ">", "SELECT AVG(salary) * 1.5 FROM employees"
    }
}
// Returns departments with avg salary > 150% of company average
```

---

## Real-World Examples

### 1. Sales Analytics Dashboard

```groovy
sqlTask("sales-by-region") {
    provider myProvider
    
    criteria {
        select "r.region_name"
        year "s.sale_date", "year"
        month "s.sale_date", "month"
        count "*", "transaction_count"
        sum "s.amount", "total_revenue"
        avg "s.amount", "avg_transaction"
        
        from "regions r"
        innerJoin "sales s", "r.id = s.region_id"
        
        where {
            ge "s.sale_date", "2024-01-01"
            eq "s.status", "completed"
        }
        
        groupBy "r.region_name", "YEAR(s.sale_date)", "MONTH(s.sale_date)"
        
        having {
            gt "COUNT(*)", 100
            gt "SUM(s.amount)", 50000
        }
        
        orderBy "year DESC", "month DESC", "total_revenue DESC"
        limit 50
    }
    
    resultMapper { rows ->
        rows.collect { [
            region: it.region_name,
            period: "${it.year}-${String.format('%02d', it.month)}",
            revenue: it.total_revenue,
            avgTransaction: it.avg_transaction,
            count: it.transaction_count
        ]}
    }
}
```

### 2. Customer Lifetime Value

```groovy
sqlTask("customer-ltv") {
    provider myProvider
    
    criteria {
        select "c.customer_id", "c.name", "c.email"
        count "o.id", "total_orders"
        sum "o.total", "lifetime_value"
        avg "o.total", "avg_order_value"
        max "o.created_at", "last_order_date"
        dateDiff "CURRENT_DATE", "MAX(o.created_at)", "days_since_order"
        
        from "customers c"
        innerJoin "orders o", "c.id = o.customer_id"
        
        where {
            eq "c.active", true
            eq "o.status", "completed"
            exists "SELECT 1 FROM payments WHERE payments.order_id = o.id"
        }
        
        groupBy "c.customer_id", "c.name", "c.email"
        
        having {
            ge "COUNT(o.id)", 5
            gt "SUM(o.total)", 1000
        }
        
        orderBy "lifetime_value DESC"
        limit 100
    }
}
```

### 3. Product Recommendation Engine

```groovy
sqlTask("frequently-bought-together") {
    provider myProvider
    
    criteria {
        select "p1.product_id AS product_a"
        select "p2.product_id AS product_b"
        count "*", "times_together"
        
        from "order_items p1"
        innerJoin "order_items p2", 
            "p1.order_id = p2.order_id AND p1.product_id < p2.product_id"
        innerJoin "orders o", "p1.order_id = o.id"
        
        where {
            eq "o.status", "completed"
            ge "o.created_at", "2024-01-01"
        }
        
        groupBy "p1.product_id", "p2.product_id"
        
        having {
            ge "COUNT(*)", 10
        }
        
        orderBy "times_together DESC"
        limit 100
    }
    
    resultMapper { rows ->
        rows.collect { [
            productA: it.product_a,
            productB: it.product_b,
            frequency: it.times_together
        ]}
    }
}
```

### 4. Employee Salary Analysis

```groovy
sqlTask("salary-analysis") {
    provider myProvider
    
    criteria {
        select "department"
        upper "department", "dept_upper"
        count "*", "employee_count"
        avg "salary", "avg_salary"
        min "salary", "min_salary"
        max "salary", "max_salary"
        coalesce "bonus_avg", "AVG(bonus)", "'0'"
        
        from "employees"
        
        where {
            eq "active", true
            ge "hire_date", "2020-01-01"
        }
        
        groupBy "department"
        
        having {
            ge "COUNT(*)", 5
            gt "AVG(salary)", 50000
        }
        
        orderBy "avg_salary DESC"
    }
}
```

### 5. Subscription Churn Analysis

```groovy
sqlTask("churn-analysis") {
    provider myProvider
    
    criteria {
        select "s.plan_type"
        year "s.created_at", "signup_year"
        month "s.created_at", "signup_month"
        count "s.id", "total_signups"
        count "c.id", "churned_count"
        
        from "subscriptions s"
        leftJoin "cancellations c", 
            "s.id = c.subscription_id AND c.created_at < DATE_ADD(s.created_at, INTERVAL 3 MONTH)"
        
        where {
            ge "s.created_at", "2024-01-01"
            notExists """
                SELECT 1 FROM renewals r 
                WHERE r.subscription_id = s.id
            """
        }
        
        groupBy "s.plan_type", "YEAR(s.created_at)", "MONTH(s.created_at)"
        
        having {
            gt "COUNT(s.id)", 50
        }
        
        orderBy "signup_year DESC", "signup_month DESC", "churned_count DESC"
    }
    
    resultMapper { rows ->
        rows.collect {
            def churnRate = it.total_signups > 0 ? 
                (it.churned_count / it.total_signups * 100) : 0
            
            [
                plan: it.plan_type,
                period: "${it.signup_year}-${String.format('%02d', it.signup_month)}",
                signups: it.total_signups,
                churned: it.churned_count,
                churnRate: String.format('%.2f%%', churnRate)
            ]
        }
    }
}
```

### 6. Inventory Turnover Report

```groovy
sqlTask("inventory-turnover") {
    provider myProvider
    
    criteria {
        select "w.warehouse_name"
        upper "p.category", "category"
        count "DISTINCT p.id", "product_count"
        sum "i.quantity", "total_stock"
        sum "s.quantity_sold", "total_sold"
        avg "p.unit_price", "avg_price"
        
        from "warehouses w"
        innerJoin "inventory i", "w.id = i.warehouse_id"
        innerJoin "products p", "i.product_id = p.id"
        leftJoin """
            (SELECT product_id, SUM(quantity) as quantity_sold
             FROM sales
             WHERE sale_date >= DATE_SUB(CURRENT_DATE, INTERVAL 3 MONTH)
             GROUP BY product_id) s
        """, "p.id = s.product_id"
        
        where {
            gt "i.quantity", 0
            inSubquery "p.category", 
                "SELECT category FROM product_categories WHERE active = 1"
        }
        
        groupBy "w.warehouse_name", "UPPER(p.category)"
        
        having {
            ge "COUNT(DISTINCT p.id)", 10
        }
        
        orderBy "total_stock DESC"
    }
}
```

---

## Usage Pattern Summary

### Basic Query Pattern
```groovy
criteria {
    select "columns..."
    from "table"
    where { conditions }
    orderBy "column"
    limit 10
}
```

### Aggregation Pattern
```groovy
criteria {
    select "group_column"
    count "*", "count"
    sum "amount", "total"
    avg "amount", "average"
    from "table"
    where { filters }
    groupBy "group_column"
    having { aggregate_filters }
    orderBy "total DESC"
}
```

### JOIN Pattern
```groovy
criteria {
    select "t1.col", "t2.col"
    from "table1 t1"
    innerJoin "table2 t2", "t1.id = t2.table1_id"
    leftJoin "table3 t3", "t2.id = t3.table2_id"
    where { conditions }
}
```

### Subquery Pattern
```groovy
criteria {
    from "table"
    where {
        exists "SELECT 1 FROM related WHERE related.parent_id = table.id"
        inSubquery "category_id", "SELECT id FROM categories WHERE active = 1"
        subquery "value", ">", "SELECT AVG(value) FROM table"
    }
}
```

---

## Best Practices

1. **Use GROUP BY with aggregate functions** - Always pair COUNT, SUM, AVG, etc. with GROUP BY
2. **HAVING for aggregates, WHERE for rows** - Use HAVING to filter grouped results
3. **Alias your JOINs** - Use table aliases (e.g., "users u") for clarity
4. **Index your GROUP BY columns** - Ensure database indexes support your grouping
5. **Limit result sets** - Use LIMIT to prevent overwhelming result sets
6. **Test subqueries separately** - Verify subquery logic before embedding
7. **Use appropriate JOIN types** - LEFT JOIN for optional relationships, INNER for required
8. **Consider performance** - Complex queries with multiple JOINs and subqueries can be slow

---

## Feature Compatibility

All features work with:
- ✅ H2 Database (in-memory and file-based)
- ✅ MySQL / MariaDB
- ✅ PostgreSQL
- ✅ SQLite (limited date functions)
- ✅ Oracle
- ✅ SQL Server

Note: Some date functions may have slightly different syntax across databases. The examples use MySQL/H2 syntax.
