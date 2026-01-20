# SubGraph Composition Patterns - Managing Workflow Complexity

## The Problem: Complexity Growth Over Time

As applications evolve, workflow requirements grow:
- **Month 1:** Simple 5-task workflow
- **Month 6:** 15 tasks with branching logic
- **Month 12:** 40+ tasks across multiple domains
- **Month 18:** Multiple workflows sharing common patterns

**Without composition strategy:**
- Code duplication across workflows
- Monolithic graphs become unmaintainable
- Changes require touching multiple places
- Testing becomes unwieldy
- Mental overhead increases exponentially

**With SubGraph composition:**
- Build library of tested, reusable components
- Compose large workflows from smaller modules
- Change once, benefit everywhere
- Test components independently
- Manage complexity through decomposition

---

## Core Concept: SubGraph as Compositional Unit

### What is a SubGraph?

A **SubGraph** is a self-contained, reusable TaskGraph that:
1. **Encapsulates** a logical unit of work (3-10 tasks typically)
2. **Has clear inputs** - Receives data from previous task/context
3. **Produces clear outputs** - Returns result for downstream tasks
4. **Is independently testable** - Can verify behavior in isolation
5. **Is reusable** - Can be composed into multiple workflows

Think of SubGraphs as **functions** in traditional programming:
- **Task** = single statement
- **SubGraph** = function (group of related statements)
- **TaskGraph** = program (composition of functions)

### SubGraphTask vs SubprocessTask

| Aspect | SubGraphTask | SubprocessTask |
|--------|-------------|----------------|
| **Purpose** | Reusable composition | Inline subprocess |
| **Definition** | External reference | Inline definition |
| **Reusability** | ✅ High - define once, use many | ❌ Low - specific to one workflow |
| **Use Case** | Library components | One-off subprocess |
| **Analogy** | Function call | Code block |

**Example:**
```groovy
// SubGraphTask - REUSABLE (define once)
def validateCustomer = TaskGraph.build {
    // 5 validation tasks
}

def workflow1 = TaskGraph.build {
    subGraphTask("validate") { graph validateCustomer }  // Reuse
}

def workflow2 = TaskGraph.build {
    subGraphTask("validate") { graph validateCustomer }  // Same component
}

// SubprocessTask - ONE-OFF (inline specific logic)
def workflow3 = TaskGraph.build {
    subprocessTask("special-logic") {
        subprocess {
            // Inline tasks specific to this workflow only
        }
    }
}
```

**Decision Rule:** If you might use it elsewhere (or in 6 months), use **SubGraphTask**.

---

## Part 1: Library Pattern for Reusable SubGraphs

### Organizing SubGraph Libraries

As your application grows, organize subgraphs into libraries:

```groovy
// Organize by domain/capability
class CustomerWorkflows {
    static TaskGraph validateAndEnrich() { ... }
    static TaskGraph updateProfile() { ... }
    static TaskGraph calculateRiskScore() { ... }
}

class PaymentWorkflows {
    static TaskGraph authorizePayment() { ... }
    static TaskGraph capturePayment() { ... }
    static TaskGraph processRefund() { ... }
}

class NotificationWorkflows {
    static TaskGraph notifyByEmail() { ... }
    static TaskGraph notifyByMultiChannel() { ... }
}

class DataWorkflows {
    static TaskGraph validateData() { ... }
    static TaskGraph transformData() { ... }
    static TaskGraph persistData() { ... }
}
```

### Composing from Library

```groovy
// Build application workflows from library components
def orderProcessingWorkflow = TaskGraph.build {
    httpTask("receive-order") { ... }

    subGraphTask("validate-customer") {
        graph CustomerWorkflows.validateAndEnrich()
    }

    subGraphTask("payment") {
        graph PaymentWorkflows.authorizePayment()
    }

    subGraphTask("notify") {
        graph NotificationWorkflows.notifyByEmail()
    }

    chainVia("receive-order", "validate-customer", "payment", "notify")
}
```

**Benefits:**
- ✅ Each library encapsulates domain expertise
- ✅ Subgraphs are tested once, trusted everywhere
- ✅ New workflows compose from proven components
- ✅ Teams can own different libraries
- ✅ Refactoring isolated to library internals

---

## Part 2: When to Decompose - Decision Guidelines

### Size-Based Triggers

| Workflow Size | Recommendation | Rationale |
|---------------|----------------|-----------|
| **1-5 tasks** | Keep as single graph | Simple enough to understand |
| **6-10 tasks** | Consider logical phases | Start thinking about grouping |
| **11-20 tasks** | Extract 2-4 subgraphs | Complexity management kicks in |
| **20+ tasks** | **Must decompose** | Unmaintainable as monolith |

### Pattern-Based Triggers

**Extract to SubGraph when you see:**

1. **Repeated Sequences**
   ```
   Workflow A: validate → enrich → check
   Workflow B: validate → enrich → check  ← SAME PATTERN

   → Extract: ValidationAndEnrichment subgraph
   ```

2. **Logical Phases**
   ```
   Order workflow:
   - Receive + Parse (input phase)
   - Validate + Enrich (validation phase)
   - Authorize + Charge (payment phase)
   - Allocate + Ship (fulfillment phase)
   - Notify + Log (completion phase)

   → Each phase = subgraph
   ```

3. **Domain Boundaries**
   ```
   Customer tasks ← Customer domain
   Payment tasks  ← Payment domain
   Shipping tasks ← Shipping domain

   → Each domain = subgraph library
   ```

4. **Complexity Hotspots**
   ```
   If one section has:
   - Multiple gateways
   - Complex error handling
   - Parallel branches

   → Extract to subgraph for focused testing
   ```

5. **Team Ownership**
   ```
   If different teams own different parts:
   - Team A: Customer validation
   - Team B: Payment processing
   - Team C: Fulfillment

   → Each team maintains their subgraph library
   ```

### Decision Tree

```
Is this task sequence used elsewhere?
├─ YES → SubGraphTask (reusable library component)
└─ NO
   │
   └─ Is it more than 5 tasks?
      ├─ YES → SubGraphTask (complexity management)
      └─ NO
         │
         └─ Does it have complex logic (gateways, error handling)?
            ├─ YES → SubGraphTask (focused testing)
            └─ NO → Keep inline (simple sequence)
```

---

## Part 3: Composition Strategies

### Strategy 1: Sequential Composition (Chain)

**Pattern:** Connect subgraphs in sequence, output → input

```groovy
def workflow = TaskGraph.build {
    subGraphTask("phase1") { graph Library.phase1() }
    subGraphTask("phase2") { graph Library.phase2() }
    subGraphTask("phase3") { graph Library.phase3() }

    chainVia("phase1", "phase2", "phase3")
}
```

**Use When:**
- Clear workflow phases
- Each phase depends on previous completion
- Linear progression (no branching)

**Example:** Data pipeline (Extract → Validate → Transform → Load)

---

### Strategy 2: Parallel Composition (Concurrent)

**Pattern:** Execute multiple subgraphs concurrently

```groovy
def workflow = TaskGraph.build {
    serviceTask("prepare-data") { ... }

    // Execute these in parallel
    subGraphTask("process-A") { graph Library.processA() }
    subGraphTask("process-B") { graph Library.processB() }
    subGraphTask("process-C") { graph Library.processC() }

    serviceTask("aggregate-results") { ... }

    // Setup: prepare feeds all three
    dependsOn("process-A", "prepare-data")
    dependsOn("process-B", "prepare-data")
    dependsOn("process-C", "prepare-data")

    // Aggregate waits for all three
    dependsOn("aggregate-results", "process-A", "process-B", "process-C")
}
```

**Use When:**
- Independent operations
- Can run concurrently
- Results merged downstream

**Example:** Multi-channel notification (Email, SMS, Push simultaneously)

---

### Strategy 3: Conditional Composition (Gateway Selection)

**Pattern:** Gateway chooses which subgraph to execute

```groovy
def workflow = TaskGraph.build {
    serviceTask("classify-order") { ... }

    exclusiveGateway("routing") {
        condition("standard") { prev -> prev.type == "standard" }
        condition("express") { prev -> prev.type == "express" }
        condition("bulk") { prev -> prev.type == "bulk" }
    }

    // Different subgraphs for different types
    subGraphTask("process-standard") { graph Library.standardProcessing() }
    subGraphTask("process-express") { graph Library.expressProcessing() }
    subGraphTask("process-bulk") { graph Library.bulkProcessing() }

    dependsOn("routing", "classify-order")
    routeVia("routing", "standard", "process-standard")
    routeVia("routing", "express", "process-express")
    routeVia("routing", "bulk", "process-bulk")
}
```

**Use When:**
- Different paths based on data
- Conditional processing
- Type-specific handlers

**Example:** Order routing (standard vs express vs bulk)

---

### Strategy 4: Loop Composition (Iteration)

**Pattern:** Execute subgraph for each item in collection

```groovy
def itemProcessor = TaskGraph.build {
    // Subgraph that processes one item
    serviceTask("validate-item") { ... }
    serviceTask("process-item") { ... }
    serviceTask("store-item") { ... }
    chainVia("validate-item", "process-item", "store-item")
}

def batchWorkflow = TaskGraph.build {
    serviceTask("fetch-batch") { ... }

    loopTask("process-items") {
        collection { prev -> prev.items }
        graph itemProcessor  // Execute subgraph per item
        aggregate { results -> [processed: results.size()] }
    }

    serviceTask("finalize-batch") { ... }

    chainVia("fetch-batch", "process-items", "finalize-batch")
}
```

**Use When:**
- Batch processing
- Same logic per item
- Collection iteration

**Example:** Process batch of orders, each with same workflow

---

### Strategy 5: Nested Composition (Subgraphs within Subgraphs)

**Pattern:** Subgraphs can contain other subgraphs

```groovy
// Fine-grained subgraphs
def validateSchema = TaskGraph.build { ... }
def enrichData = TaskGraph.build { ... }
def checkBusiness = TaskGraph.build { ... }

// Mid-level subgraph composed of fine-grained ones
def fullValidation = TaskGraph.build {
    subGraphTask("schema") { graph validateSchema }
    subGraphTask("enrich") { graph enrichData }
    subGraphTask("business") { graph checkBusiness }
    chainVia("schema", "enrich", "business")
}

// Top-level workflow uses mid-level subgraph
def mainWorkflow = TaskGraph.build {
    httpTask("receive") { ... }
    subGraphTask("validate") { graph fullValidation }
    subGraphTask("process") { graph processData }
    chainVia("receive", "validate", "process")
}
```

**Use When:**
- Multiple levels of abstraction
- Building hierarchies of components
- Complex domains with layers

**Example:** Validation (schema → enrichment → business rules), each itself a subgraph

---

## Part 4: Input/Output Contracts

### Defining Clear Contracts

Each subgraph should have clear expectations:

```groovy
/**
 * Customer Validation & Enrichment Subgraph
 *
 * INPUT (expects prev to contain):
 *   - customerId: String (required)
 *   - orderData: Map (required)
 *
 * OUTPUT (returns Map with):
 *   - customer: Customer object
 *   - creditScore: Integer
 *   - riskLevel: String ("low", "medium", "high")
 *   - validationStatus: String ("passed", "failed")
 *
 * ERRORS:
 *   - Throws CustomerNotFoundException if customer not found
 *   - Writes to DLQ if validation fails repeatedly
 */
static TaskGraph validateCustomer() {
    TaskGraph.build {
        // Implementation...
    }
}
```

### Contract Benefits

1. **Documentation** - Clear expectations for users
2. **Validation** - Can validate inputs at subgraph entry
3. **Testing** - Know exactly what to mock/verify
4. **Maintenance** - Changes to contract are explicit
5. **Composition** - Easy to wire subgraphs together

---

## Part 5: Real-World Conceptual Examples

### Example 1: E-Commerce Order Processing

**Problem:** Order processing grows from 5 tasks to 40+ tasks

**Decomposition Strategy:**

```groovy
// LIBRARY: Core reusable subgraphs
class OrderLibrary {
    static TaskGraph receiveAndParse() {
        // Webhook reception, JSON parsing, initial validation
    }

    static TaskGraph validateCustomer() {
        // Customer lookup, credit check, fraud detection
    }

    static TaskGraph checkInventory() {
        // Stock check, reservation, allocation
    }

    static TaskGraph processPayment() {
        // Authorize, capture, update accounting
    }

    static TaskGraph fulfillment() {
        // Pick, pack, create shipment, notify warehouse
    }

    static TaskGraph notifications() {
        // Email confirmation, SMS tracking, app notification
    }
}

// WORKFLOW 1: Standard Order
def standardOrder = TaskGraph.build {
    subGraphTask("receive") { graph OrderLibrary.receiveAndParse() }
    subGraphTask("validate") { graph OrderLibrary.validateCustomer() }
    subGraphTask("inventory") { graph OrderLibrary.checkInventory() }
    subGraphTask("payment") { graph OrderLibrary.processPayment() }
    subGraphTask("fulfill") { graph OrderLibrary.fulfillment() }
    subGraphTask("notify") { graph OrderLibrary.notifications() }

    chainVia("receive", "validate", "inventory", "payment", "fulfill", "notify")
}

// WORKFLOW 2: Quote Request (reuses some subgraphs!)
def quoteRequest = TaskGraph.build {
    subGraphTask("receive") { graph OrderLibrary.receiveAndParse() }
    subGraphTask("validate") { graph OrderLibrary.validateCustomer() }  // REUSED
    subGraphTask("inventory") { graph OrderLibrary.checkInventory() }    // REUSED

    serviceTask("calculate-quote") { ... }  // Quote-specific logic

    subGraphTask("notify") { graph OrderLibrary.notifications() }        // REUSED

    chainVia("receive", "validate", "inventory", "calculate-quote", "notify")
}

// WORKFLOW 3: Return Processing (reuses payment subgraph!)
def returnProcess = TaskGraph.build {
    subGraphTask("receive") { graph OrderLibrary.receiveAndParse() }
    subGraphTask("validate") { graph OrderLibrary.validateCustomer() }  // REUSED

    serviceTask("process-return") { ... }  // Return-specific logic

    subGraphTask("refund") { graph OrderLibrary.processPayment() }      // REUSED (handles refunds too)
    subGraphTask("notify") { graph OrderLibrary.notifications() }        // REUSED

    chainVia("receive", "validate", "process-return", "refund", "notify")
}
```

**Benefits:**
- 3 different workflows
- 6 reusable subgraphs
- Each subgraph tested once
- New workflow? Compose from library!

---

### Example 2: Data Pipeline

**Problem:** Build multiple data pipelines for different sources

**Decomposition Strategy:**

```groovy
// LIBRARY: ETL component subgraphs
class PipelineLibrary {
    // Extract subgraphs (source-specific)
    static TaskGraph extractFromAPI(String source) {
        // HTTP fetch, pagination, rate limiting
    }

    static TaskGraph extractFromDatabase(String db) {
        // SQL queries, batching, cursor handling
    }

    static TaskGraph extractFromFiles(String path) {
        // File reading, decompression, parsing
    }

    // Validation subgraph (REUSABLE across all sources)
    static TaskGraph validate() {
        // Schema validation, quality checks, deduplication
    }

    // Transform subgraph (REUSABLE across all sources)
    static TaskGraph transform() {
        // Normalize, enrich, aggregate
    }

    // Load subgraphs (target-specific)
    static TaskGraph loadToWarehouse() {
        // Bulk insert, indexing, partition management
    }

    static TaskGraph loadToCache() {
        // Redis/cache population, TTL management
    }
}

// PIPELINE 1: API → Warehouse
def apiPipeline = TaskGraph.build {
    subGraphTask("extract") { graph PipelineLibrary.extractFromAPI("customers") }
    subGraphTask("validate") { graph PipelineLibrary.validate() }
    subGraphTask("transform") { graph PipelineLibrary.transform() }
    subGraphTask("load") { graph PipelineLibrary.loadToWarehouse() }

    chainVia("extract", "validate", "transform", "load")
}

// PIPELINE 2: Database → Cache (reuses validate & transform!)
def dbPipeline = TaskGraph.build {
    subGraphTask("extract") { graph PipelineLibrary.extractFromDatabase("orders") }
    subGraphTask("validate") { graph PipelineLibrary.validate() }      // REUSED
    subGraphTask("transform") { graph PipelineLibrary.transform() }    // REUSED
    subGraphTask("load") { graph PipelineLibrary.loadToCache() }

    chainVia("extract", "validate", "transform", "load")
}

// PIPELINE 3: Files → Multiple Targets (parallel loads!)
def filePipeline = TaskGraph.build {
    subGraphTask("extract") { graph PipelineLibrary.extractFromFiles("/data") }
    subGraphTask("validate") { graph PipelineLibrary.validate() }      // REUSED
    subGraphTask("transform") { graph PipelineLibrary.transform() }    // REUSED

    // Load to multiple targets in parallel
    subGraphTask("load-warehouse") { graph PipelineLibrary.loadToWarehouse() }
    subGraphTask("load-cache") { graph PipelineLibrary.loadToCache() }

    chainVia("extract", "validate", "transform")
    dependsOn("load-warehouse", "transform")
    dependsOn("load-cache", "transform")
}
```

**Benefits:**
- 3 pipelines, 8 reusable components
- Validate/Transform shared across all pipelines
- Add new source? Just add extract subgraph
- Add new target? Just add load subgraph

---

### Example 3: Multi-Tenant System

**Problem:** Different tenants need customized workflows but share core logic

**Decomposition Strategy:**

```groovy
// LIBRARY: Shared core + tenant customizations
class TenantWorkflows {
    // Core processing (SAME for all tenants)
    static TaskGraph coreProcessing() {
        // Standard validation, processing, storage
    }

    // Tenant-specific customizations
    static TaskGraph tenantA_CustomValidation() {
        // Tenant A's special rules
    }

    static TaskGraph tenantB_CustomValidation() {
        // Tenant B's special rules
    }

    static TaskGraph defaultCustomValidation() {
        // Default for new tenants
    }
}

// TENANT A WORKFLOW
def tenantA_Workflow = TaskGraph.build {
    serviceTask("receive") { ... }

    subGraphTask("custom-validation") {
        graph TenantWorkflows.tenantA_CustomValidation()  // Custom
    }

    subGraphTask("core") {
        graph TenantWorkflows.coreProcessing()             // SHARED
    }

    chainVia("receive", "custom-validation", "core")
}

// TENANT B WORKFLOW
def tenantB_Workflow = TaskGraph.build {
    serviceTask("receive") { ... }

    subGraphTask("custom-validation") {
        graph TenantWorkflows.tenantB_CustomValidation()  // Custom
    }

    subGraphTask("core") {
        graph TenantWorkflows.coreProcessing()             // SHARED (same code!)
    }

    chainVia("receive", "custom-validation", "core")
}

// NEW TENANT (uses default)
def tenantC_Workflow = TaskGraph.build {
    serviceTask("receive") { ... }

    subGraphTask("custom-validation") {
        graph TenantWorkflows.defaultCustomValidation()   // Default
    }

    subGraphTask("core") {
        graph TenantWorkflows.coreProcessing()             // SHARED
    }

    chainVia("receive", "custom-validation", "core")
}
```

**Benefits:**
- Core logic shared (bug fix once, all tenants benefit)
- Easy to add new tenant (compose with default)
- Clear separation of shared vs custom
- Can upgrade core without touching tenant customizations

---

## Part 6: Testing Strategies

### Testing Individual Subgraphs (Unit Testing)

```groovy
class ValidationSubgraphTest {
    def "should validate customer successfully"() {
        given:
        def subgraph = CustomerWorkflows.validateCustomer()
        def input = [customerId: "123", orderData: [...]]

        when:
        def result = subgraph.run(input).get()

        then:
        result.validationStatus == "passed"
        result.customer != null
        result.riskLevel in ["low", "medium", "high"]
    }

    def "should fail validation for invalid customer"() {
        given:
        def subgraph = CustomerWorkflows.validateCustomer()
        def input = [customerId: "invalid", orderData: [...]]

        when:
        def result = subgraph.run(input).get()

        then:
        result.validationStatus == "failed"
    }
}
```

### Testing Compositions (Integration Testing)

```groovy
class OrderWorkflowTest {
    def "should process order end-to-end"() {
        given:
        def workflow = buildOrderWorkflow()  // Composition of subgraphs
        def order = buildTestOrder()

        when:
        def result = workflow.run(order).get()

        then:
        result.orderStatus == "processed"
        result.paymentCaptured == true
        result.shipmentCreated == true
    }
}
```

### Testing Strategy Summary

| Level | What to Test | How |
|-------|--------------|-----|
| **Subgraph** | Individual component behavior | Unit test with mock inputs |
| **Composition** | Subgraphs work together | Integration test with realistic data |
| **End-to-End** | Full workflow behavior | E2E test with real services |

**Best Practice:**
- Test each subgraph thoroughly (unit)
- Trust subgraphs in composition tests (integration)
- Minimize E2E tests (most expensive)

---

## Part 7: Best Practices

### 1. Keep Subgraphs Focused (Single Responsibility)

✅ **Good:**
```groovy
static TaskGraph validateCustomer() {
    // ONLY validation logic
}

static TaskGraph enrichCustomer() {
    // ONLY enrichment logic
}
```

❌ **Bad:**
```groovy
static TaskGraph validateAndEnrichAndProcessAndNotify() {
    // TOO MUCH! Hard to reuse
}
```

### 2. Define Clear Contracts

✅ **Good:**
```groovy
/**
 * INPUT: { customerId, orderData }
 * OUTPUT: { customer, riskLevel, validationStatus }
 * ERRORS: CustomerNotFoundException
 */
static TaskGraph validateCustomer() { ... }
```

❌ **Bad:**
```groovy
// No documentation, unclear what it needs/returns
static TaskGraph validate() { ... }
```

### 3. Use Descriptive Names

✅ **Good:**
```groovy
static TaskGraph validateAndEnrichCustomer() { ... }
static TaskGraph authorizeAndCapturePayment() { ... }
static TaskGraph notifyCustomerByEmail() { ... }
```

❌ **Bad:**
```groovy
static TaskGraph process() { ... }
static TaskGraph doStuff() { ... }
static TaskGraph handler() { ... }
```

### 4. Organize by Domain

✅ **Good:**
```groovy
class CustomerWorkflows { ... }
class PaymentWorkflows { ... }
class ShippingWorkflows { ... }
```

❌ **Bad:**
```groovy
class AllWorkflows {
    // 50 different subgraphs mixed together
}
```

### 5. Version Subgraphs When Needed

For critical, widely-used subgraphs:
```groovy
class PaymentWorkflows {
    static TaskGraph processPayment_v1() { ... }
    static TaskGraph processPayment_v2() { ... }  // Breaking change

    // Alias current version
    static TaskGraph processPayment() {
        return processPayment_v2()
    }
}
```

### 6. Handle Errors at Right Level

```groovy
// Subgraph: Let errors propagate (caller decides)
static TaskGraph riskyOperation() {
    TaskGraph.build {
        serviceTask("risky") {
            action { ... }
            // No error handling here
        }
    }
}

// Caller: Handle errors with DLQ
def workflow = TaskGraph.build {
    subGraphTask("risky") {
        graph riskyOperation()

        // Error handling at composition level
        deadLetterQueue {
            maxSize 100
            autoRetry true
        }
    }
}
```

---

## Part 8: Migration Guide - Refactoring Monoliths

### Step 1: Identify Logical Phases

Look at your monolithic graph and identify phases:
```groovy
// BEFORE: 30-task monolith
def monolith = TaskGraph.build {
    // Tasks 1-5: Validation
    serviceTask("validate-1") { ... }
    serviceTask("validate-2") { ... }
    // ...

    // Tasks 6-12: Payment
    serviceTask("payment-1") { ... }
    // ...

    // Tasks 13-20: Fulfillment
    serviceTask("fulfill-1") { ... }
    // ...

    // Tasks 21-30: Notification
    serviceTask("notify-1") { ... }
    // ...

    // Chain ALL 30 tasks
    chainVia("validate-1", "validate-2", /* ... 28 more ... */)
}
```

### Step 2: Extract to Subgraphs

```groovy
// Create library
class OrderLibrary {
    static TaskGraph validation() {
        TaskGraph.build {
            serviceTask("validate-1") { ... }
            serviceTask("validate-2") { ... }
            // ... all validation tasks
            chainVia("validate-1", "validate-2", ...)
        }
    }

    static TaskGraph payment() {
        TaskGraph.build {
            // All payment tasks
        }
    }

    static TaskGraph fulfillment() {
        TaskGraph.build {
            // All fulfillment tasks
        }
    }

    static TaskGraph notification() {
        TaskGraph.build {
            // All notification tasks
        }
    }
}
```

### Step 3: Compose from Subgraphs

```groovy
// AFTER: Clean composition
def workflow = TaskGraph.build {
    subGraphTask("validation") { graph OrderLibrary.validation() }
    subGraphTask("payment") { graph OrderLibrary.payment() }
    subGraphTask("fulfillment") { graph OrderLibrary.fulfillment() }
    subGraphTask("notification") { graph OrderLibrary.notification() }

    chainVia("validation", "payment", "fulfillment", "notification")
}
```

### Step 4: Test Each Phase

```groovy
class ValidationTest {
    def "validation phase works"() {
        expect:
        OrderLibrary.validation().run(testData).get().validated == true
    }
}

// Repeat for each subgraph
```

### Step 5: Identify Reuse Opportunities

Now that you have subgraphs, look for reuse:
```groovy
// Quote workflow can reuse validation!
def quoteWorkflow = TaskGraph.build {
    subGraphTask("validation") {
        graph OrderLibrary.validation()  // REUSED!
    }
    serviceTask("calculate-quote") { ... }
    subGraphTask("notification") {
        graph OrderLibrary.notification()  // REUSED!
    }
    chainVia("validation", "calculate-quote", "notification")
}
```

---

## Part 9: Common Pitfalls & Solutions

### Pitfall 1: Subgraphs Too Fine-Grained

❌ **Problem:**
```groovy
// Too granular - 1 task per subgraph
static TaskGraph validateFormat() { /* 1 task */ }
static TaskGraph validateSchema() { /* 1 task */ }
static TaskGraph validateBusiness() { /* 1 task */ }

// Composition is verbose
subGraphTask("format") { graph validateFormat() }
subGraphTask("schema") { graph validateSchema() }
subGraphTask("business") { graph validateBusiness() }
```

✅ **Solution:**
```groovy
// Better: Group related tasks
static TaskGraph validation() {
    TaskGraph.build {
        serviceTask("format") { ... }
        serviceTask("schema") { ... }
        serviceTask("business") { ... }
        chainVia("format", "schema", "business")
    }
}

// Simpler composition
subGraphTask("validation") { graph validation() }
```

**Rule:** Subgraph should be 3-10 tasks, not 1 task.

### Pitfall 2: Tight Coupling Between Subgraphs

❌ **Problem:**
```groovy
// Subgraph A depends on specific internal behavior of Subgraph B
static TaskGraph subgraphA() {
    // Expects subgraphB to set specific context variable
    // Breaks if subgraphB changes internally
}
```

✅ **Solution:**
```groovy
// Clear contract - only depend on documented outputs
/**
 * OUTPUT: { customer: Customer, status: String }
 */
static TaskGraph subgraphB() { ... }

static TaskGraph subgraphA() {
    // Only uses documented contract
    // Works regardless of subgraphB internals
}
```

### Pitfall 3: Subgraphs with Side Effects

❌ **Problem:**
```groovy
// Subgraph modifies shared global state
static TaskGraph problematic() {
    TaskGraph.build {
        serviceTask("mutate-global") {
            action { GlobalState.counter++ }  // BAD!
        }
    }
}
```

✅ **Solution:**
```groovy
// Subgraph is pure - returns result
static TaskGraph better() {
    TaskGraph.build {
        serviceTask("compute") {
            action { prev ->
                [result: prev.value + 1]  // Pure, returns data
            }
        }
    }
}
```

### Pitfall 4: Over-Engineering

❌ **Problem:**
```groovy
// Creating subgraphs "just in case" when not needed
def simple5TaskWorkflow = TaskGraph.build {
    subGraphTask("step1") { graph oneTaskSubgraph() }  // Overkill
    subGraphTask("step2") { graph oneTaskSubgraph() }
    subGraphTask("step3") { graph oneTaskSubgraph() }
    subGraphTask("step4") { graph oneTaskSubgraph() }
    subGraphTask("step5") { graph oneTaskSubgraph() }
}
```

✅ **Solution:**
```groovy
// Keep it simple when appropriate
def simple5TaskWorkflow = TaskGraph.build {
    serviceTask("step1") { ... }
    serviceTask("step2") { ... }
    serviceTask("step3") { ... }
    serviceTask("step4") { ... }
    serviceTask("step5") { ... }
    chainVia("step1", "step2", "step3", "step4", "step5")
}
```

**Rule:** Don't extract until you need reuse or complexity demands it.

---

## Part 10: Summary & Key Takeaways

### The SubGraph Philosophy

**Think of SubGraphs as functions:**
- **Task** = statement
- **SubGraph** = function (group of statements)
- **TaskGraph** = program (composition of functions)

### When to Use SubGraphTask

✅ Use SubGraphTask when:
1. Pattern appears in multiple workflows (reusability)
2. Workflow exceeds ~10 tasks (complexity management)
3. Logical phases exist (decomposition)
4. Complex logic needs focused testing (isolation)
5. Different teams own different parts (ownership)

❌ Don't use SubGraphTask when:
1. Simple 3-5 task workflow (keep it simple)
2. Logic is workflow-specific (inline is fine)
3. Over-engineering for hypothetical reuse (YAGNI)

### Key Patterns

| Pattern | Use Case | Example |
|---------|----------|---------|
| **Sequential** | Linear phases | ETL: Extract → Transform → Load |
| **Parallel** | Independent operations | Multi-channel notifications |
| **Conditional** | Type-specific routing | Order types: standard vs express |
| **Loop** | Batch processing | Process each item with same subgraph |
| **Nested** | Hierarchical composition | Validation = Schema + Enrichment + Business |

### Success Metrics

Your SubGraph strategy is working when:
- ✅ New workflows compose from library (not written from scratch)
- ✅ Changes to subgraphs benefit multiple workflows
- ✅ Testing focuses on components (not just end-to-end)
- ✅ Team members understand workflow structure quickly
- ✅ Complexity stays manageable as application grows

### The Composable Mindset

**Instead of thinking:**
> "I need to build a 40-task workflow"

**Think:**
> "I need to compose 5 well-tested components into a workflow"

This mindset shift is the key to managing complexity as your application evolves over time.

---

## Appendix: Quick Reference

### Decision Checklist

Before creating a subgraph, ask:
- [ ] Will this be used in multiple workflows?
- [ ] Is this more than 5 tasks?
- [ ] Does it represent a logical phase/domain?
- [ ] Would it benefit from isolated testing?
- [ ] Will it reduce complexity?

If **2+ YES**: Create SubGraphTask
If **all NO**: Keep inline

### SubGraph Design Checklist

When creating a subgraph:
- [ ] Clear, descriptive name (verb-noun pattern)
- [ ] Documented input contract
- [ ] Documented output contract
- [ ] Documented error conditions
- [ ] 3-10 tasks (focused scope)
- [ ] Unit tests written
- [ ] Organized in appropriate library class

### Composition Checklist

When composing workflows:
- [ ] Each subgraph has clear purpose
- [ ] Dependencies are explicit (chainVia, dependsOn)
- [ ] Error handling at appropriate level
- [ ] Top-level graph shows business flow
- [ ] No more than 3 levels of nesting

---

**End of Guide**

This guide provides the conceptual foundation for managing workflow complexity through SubGraph composition. Apply these patterns as your application's needs grow, building a library of reliable, reusable components that make larger workflows manageable and maintainable.
