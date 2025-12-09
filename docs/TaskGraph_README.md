# TaskGraph - Modern DAG Execution Engine

## Overview

TaskGraph is a lightweight, asynchronous Directed Acyclic Graph (DAG) execution engine with a fluent Groovy DSL. It enables you to model complex workflows as interconnected tasks with automatic dependency resolution, dynamic routing, and promise-based asynchronous execution.

**Key Features:**
- 🎯 **Interface-based architecture** - Testable, mockable, flexible
- 🔄 **Promise-based async execution** - Clean async/await semantics
- 🌳 **Deferred wiring** - Build complex graphs with forward references
- 🔀 **Dynamic routing** - Conditional branches, sharding, fan-out/fan-in
- 🎨 **Fluent DSL** - Readable, declarative workflow definitions
- 🔌 **Pluggable backends** - Dataflow, CompletableFuture, Vert.x, etc.

---

## Quick Start

### Simple Linear Workflow

```groovy
import org.softwood.dag.TaskGraph

def graph = TaskGraph.build {
    
    serviceTask("fetchUser") {
        action { ctx, _ ->
            ctx.promiseFactory.executeAsync {
                // Fetch user from API
                return httpClient.get("/users/123")
            }
        }
    }
    
    serviceTask("processUser") {
        dependsOn "fetchUser"
        action { ctx, prevValue ->
            ctx.promiseFactory.executeAsync {
                def user = prevValue  // Previous task's result
                return processData(user)
            }
        }
    }
}

// Execute and get result
def result = graph.run().get()
```

### Parallel Execution with Join

```groovy
def graph = TaskGraph.build {
    
    serviceTask("loadUser") {
        action { ctx, _ ->
            ctx.promiseFactory.executeAsync { userService.load(123) }
        }
    }
    
    // These run in parallel
    serviceTask("loadOrders") {
        dependsOn "loadUser"
        action { ctx, user ->
            ctx.promiseFactory.executeAsync { orderService.loadFor(user.id) }
        }
    }
    
    serviceTask("loadInvoices") {
        dependsOn "loadUser"
        action { ctx, user ->
            ctx.promiseFactory.executeAsync { invoiceService.loadFor(user.id) }
        }
    }
    
    // Join results
    serviceTask("combineData") {
        dependsOn "loadOrders", "loadInvoices"
        action { ctx, prevValue ->
            ctx.promiseFactory.executeAsync {
                // prevValue is a List of [orders, invoices]
                def orders = prevValue[0]
                def invoices = prevValue[1]
                return combine(orders, invoices)
            }
        }
    }
}

def result = graph.run().get()
```

---

## Architecture

### Core Interfaces

TaskGraph uses an interface-based architecture for maximum flexibility and testability:

```
ITask<T>                    Interface for all executable tasks
  └─ TaskBase<T>            Abstract base implementation
      ├─ ServiceTask        Executes user-defined actions
      └─ IDecisionTask<T>   Interface for routing tasks
          └─ RouterTask     Abstract router implementation
              ├─ ConditionalForkTask
              ├─ DynamicRouterTask
              └─ ShardingRouterTask
```

### Key Components

| Component | Purpose |
|-----------|---------|
| **TaskGraph** | Container holding all tasks and execution context |
| **ITask** | Interface defining task contract |
| **TaskBase** | Abstract implementation with retry, timeout, state management |
| **ServiceTask** | Concrete task executing user-defined closures |
| **RouterTask** | Base for tasks that control flow (forks, branches) |
| **TaskContext** | Shared execution context with globals, config, promise factory |
| **TaskFactory** | Factory for creating task instances (for testing/mocking) |

### Execution Model

```
┌─────────────────────────────────────────────────────────┐
│                    TaskGraph.build { }                   │
│                                                          │
│  1. Parse DSL → Create tasks                            │
│  2. Store dependencies (not wired yet)                  │
│  3. Process fork/join DSLs                              │
│  4. Deferred wiring (finalizeWiring)                    │
└─────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────┐
│                    graph.run() / graph.start()           │
│                                                          │
│  1. Call finalizeWiring() if not yet done               │
│  2. Find root tasks (no predecessors)                   │
│  3. Schedule each root task                             │
│  4. Tasks complete → schedule successors if ready       │
│  5. Return Promise that resolves when all complete      │
└─────────────────────────────────────────────────────────┘
```

---

## Deferred Wiring Explained

**The Problem:** When building complex graphs with forks/joins, you need to reference tasks that may not exist yet.

**Example:**
```groovy
fork("router") {
    from "loadUser"          // loadUser might be defined later
    to "processA", "processB" // These might not exist yet
}

serviceTask("loadUser") { ... }  // Defined AFTER fork references it
```

**The Solution:** Deferred wiring in two phases:

### Phase 1: DSL Parsing (Collection Phase)

During `TaskGraph.build { }`:
1. Tasks are created and added to `graph.tasks[id]`
2. Dependencies are stored in `predecessors` and `successors` sets
3. Fork DSL stores routing rules but **doesn't wire tasks yet**
4. Everything is collected, nothing executed

```groovy
// Inside TaskGraphDsl
serviceTask("loadUser") {
    dependsOn "upstream"  // Just stores "upstream" in predecessors set
    // NOT wired yet!
}
```

### Phase 2: Finalization (Wiring Phase)

After DSL completes, `finalizeWiring()` runs:

```groovy
void finalizeWiring() {
    if (wired) return
    wired = true
    
    // Wire all tasks: for each task's successors,
    // add this task to successor's predecessors
    tasks.values().each { task ->
        task.successors.each { succId ->
            tasks[succId]?.predecessors << task.id
        }
    }
    
    // Now the DAG is complete and ready to execute
}
```

**Benefits:**
- ✅ Forward references work naturally
- ✅ Tasks can be defined in any order
- ✅ Fork/join DSLs can reference tasks defined later
- ✅ Clear separation between graph construction and execution

**When it runs:**
- Automatically called at the end of `TaskGraph.build { }`
- Called again (safely) at the start of `graph.run()`
- Idempotent - only wires once

---

## DSL Reference

### Globals

Define shared configuration accessible to all tasks:

```groovy
TaskGraph.build {
    globals {
        apiUrl = "https://api.example.com"
        timeout = 5000
        apiKey = System.getenv("API_KEY")
    }
    
    serviceTask("fetch") {
        action { ctx, _ ->
            def url = ctx.globals.apiUrl  // Access globals
            // ...
        }
    }
}
```

### Service Tasks

Define a task that executes custom logic:

```groovy
serviceTask("taskId") {
    dependsOn "predecessor1", "predecessor2"  // Optional
    maxRetries = 3                            // Optional: retry on failure
    timeoutMillis = 5000                      // Optional: task timeout
    
    action { ctx, prevValue ->
        // ctx: TaskContext with globals, promiseFactory, etc.
        // prevValue: Result from predecessor(s)
        //   - null if no predecessors
        //   - single value if one predecessor
        //   - List if multiple predecessors
        
        // Must return a Promise
        return ctx.promiseFactory.executeAsync {
            // Your async work here
            return someResult
        }
    }
}
```

### Forks - Conditional Routing

Execute different branches based on data:

```groovy
fork("routerId") {
    from "loadUser"  // Source task
    
    // Define conditions for each target
    conditionalOn(["processPremi um"]) { user ->
        user.isPremium  // Return true to execute this branch
    }
    
    conditionalOn(["processStandard"]) { user ->
        !user.isPremium
    }
}

serviceTask("processPremium") {
    // Automatically depends on router
    action { ctx, user -> /* ... */ }
}

serviceTask("processStandard") {
    action { ctx, user -> /* ... */ }
}
```

### Forks - Dynamic Routing

Choose targets programmatically:

```groovy
fork("smartRouter") {
    from "analyze"
    
    route { result ->
        // Return List<String> of task IDs to execute
        if (result.score > 80) return ["highPriority", "notify"]
        if (result.score > 50) return ["mediumPriority"]
        return ["lowPriority"]
    }
}
```

### Forks - Sharding

Process data in parallel shards:

```groovy
fork("processItems") {
    from "loadItems"
    
    shard("processOne", 5) { items ->
        // Split items into 5 shards
        items.collate((items.size() / 5).toInteger())
    }
}

// This task is instantiated 5 times
serviceTask("processOne") {
    action { ctx, shard ->
        // Each instance processes one shard
        ctx.promiseFactory.executeAsync {
            shard.collect { processItem(it) }
        }
    }
}

// Join results from all shards
serviceTask("combineResults") {
    dependsOn "processOne"  // Waits for ALL shard instances
    action { ctx, shardResults ->
        // shardResults is List of results from all shards
        ctx.promiseFactory.executeAsync {
            shardResults.flatten()
        }
    }
}
```

### Context Access in Routing

Routing closures can access `ctx.globals`:

```groovy
globals {
    threshold = 100
}

fork("router") {
    from "analyze"
    
    conditionalOn(["highValue"]) { result ->
        result.value > ctx.globals.threshold  // ✅ Access globals
    }
}
```

---

## Task Lifecycle & States

Each task progresses through these states:

```
SCHEDULED → RUNNING → COMPLETED
                    → FAILED
                    → SKIPPED (if routing doesn't select it)
```

### State Transitions

1. **SCHEDULED**: Task created and added to graph
2. **RUNNING**: Task execution started
3. **COMPLETED**: Task finished successfully
4. **FAILED**: Task threw exception or timed out
5. **SKIPPED**: Task not selected by router

### Events

Listen to task state changes:

```groovy
def dispatcher = new DefaultTaskEventDispatcher()
dispatcher.addEventListener { event ->
    println "${event.taskId}: ${event.taskState} at ${event.timestamp}"
}

// Attach to tasks
graph.tasks.values().each { task ->
    task.eventDispatcher = dispatcher
}
```

---

## Error Handling

### Task Retries

```groovy
serviceTask("fetchData") {
    maxRetries = 3  // Retry up to 3 times on failure
    
    action { ctx, _ ->
        ctx.promiseFactory.executeAsync {
            riskyOperation()  // If this fails, task retries
        }
    }
}
```

### Task Timeout

```groovy
serviceTask("slowOperation") {
    timeoutMillis = 5000  // Fail if takes longer than 5 seconds
    
    action { ctx, _ ->
        ctx.promiseFactory.executeAsync {
            longRunningOperation()
        }
    }
}
```

### Handling Failed Tasks

```groovy
def graphPromise = graph.run()

graphPromise
    .onComplete { result ->
        println "Graph completed: $result"
    }
    .onError { error ->
        println "Graph failed: $error"
        
        // Check which tasks failed
        graph.tasks.each { id, task ->
            if (task.isFailed()) {
                println "Task $id failed: ${task.error}"
            }
        }
    }
```

---

## Testing

### Using TaskFactory

```groovy
import org.softwood.dag.task.TaskFactory

class MyWorkflowTest {
    
    @Test
    void testWorkflow() {
        def mockTask = Mock(ITask)
        mockTask.id >> "mockTask"
        mockTask.completionPromise >> promiseFactory.createPromise("mocked result")
        
        // Or use factory
        def realTask = TaskFactory.createServiceTask("real", "Real Task", ctx)
        // ...
    }
}
```

### Mocking Tasks

```groovy
class MockTask implements ITask<String> {
    String id
    Promise<String> completionPromise
    
    // Implement required methods
    // ...
}

def graph = new TaskGraph()
graph.tasks["mocked"] = new MockTask(id: "mocked", completionPromise: mockPromise)
```

---

## Best Practices

### 1. Use Descriptive Task IDs

```groovy
// ✅ Good
serviceTask("loadUserFromDatabase") { }
serviceTask("validateUserPermissions") { }

// ❌ Bad
serviceTask("task1") { }
serviceTask("t2") { }
```

### 2. Keep Task Actions Focused

```groovy
// ✅ Good - single responsibility
serviceTask("loadUser") {
    action { ctx, _ ->
        ctx.promiseFactory.executeAsync {
            userRepository.findById(123)
        }
    }
}

serviceTask("enrichUser") {
    dependsOn "loadUser"
    action { ctx, user ->
        ctx.promiseFactory.executeAsync {
            enrichmentService.enrich(user)
        }
    }
}

// ❌ Bad - doing too much in one task
serviceTask("loadAndProcessEverything") {
    action { ctx, _ ->
        ctx.promiseFactory.executeAsync {
            def user = userRepository.findById(123)
            def enriched = enrichmentService.enrich(user)
            def validated = validationService.validate(enriched)
            // ... too many responsibilities
        }
    }
}
```

### 3. Always Return Promises

```groovy
// ✅ Good
action { ctx, _ ->
    ctx.promiseFactory.executeAsync {
        someAsyncOperation()
    }
}

// ❌ Bad - blocking operation
action { ctx, _ ->
    someBlockingOperation()  // No promise wrapping
}
```

### 4. Handle Null Predecessor Values

```groovy
action { ctx, prevValue ->
    ctx.promiseFactory.executeAsync {
        if (prevValue == null) {
            // Handle root task case
            return defaultValue
        }
        // Process prevValue
    }
}
```

### 5. Use Globals for Configuration

```groovy
globals {
    environment = System.getenv("ENV") ?: "development"
    apiUrl = System.getenv("API_URL")
    maxConnections = 10
}

serviceTask("connect") {
    action { ctx, _ ->
        def config = ctx.globals
        // Use config...
    }
}
```

---

## Advanced Patterns

### Fan-Out/Fan-In

```groovy
serviceTask("loadBatch") {
    action { ctx, _ ->
        ctx.promiseFactory.executeAsync {
            dataService.loadBatch()  // Returns List<Item>
        }
    }
}

fork("distributeBatch") {
    from "loadBatch"
    shard("processItem", 10) { items ->
        items.collate((items.size() / 10).toInteger())
    }
}

serviceTask("processItem") {
    action { ctx, shard ->
        ctx.promiseFactory.executeAsync {
            shard.collect { processOne(it) }
        }
    }
}

// Join all shard results
serviceTask("aggregateResults") {
    dependsOn "processItem"
    action { ctx, shardResults ->
        ctx.promiseFactory.executeAsync {
            shardResults.flatten().sum()
        }
    }
}
```

### Conditional Pipelines

```groovy
globals {
    mode = "premium"  // or "standard"
}

serviceTask("loadData") {
    action { ctx, _ -> /* ... */ }
}

fork("selectPipeline") {
    from "loadData"
    
    conditionalOn(["premiumPipeline"]) { data ->
        ctx.globals.mode == "premium"
    }
    
    conditionalOn(["standardPipeline"]) { data ->
        ctx.globals.mode == "standard"
    }
}

serviceTask("premiumPipeline") {
    action { ctx, data ->
        // Premium processing with extra features
    }
}

serviceTask("standardPipeline") {
    action { ctx, data ->
        // Basic processing
    }
}

// Both converge here
serviceTask("finalizeResults") {
    dependsOn "premiumPipeline", "standardPipeline"
    action { ctx, result ->
        // One will be skipped, process whichever ran
    }
}
```

---

## Migration from Legacy Code

If you have old code using `Task` class directly:

### Before (Old)
```groovy
Map<String, Task> tasks = [:]
Task t = new ServiceTask("id", "name", ctx)
```

### After (New)
```groovy
Map<String, ITask> tasks = [:]
ITask t = TaskFactory.createServiceTask("id", "name", ctx)
// or
ITask t = new ServiceTask("id", "name", ctx)
```

The interfaces are backward-compatible - existing DSL code requires no changes.

---

## Troubleshooting

### Task Not Executing

**Check:**
1. Are dependencies spelled correctly?
2. Is the task reachable from a root task?
3. Did you call `graph.run()`?

```groovy
// Debug: print graph structure
graph.finalizeWiring()
graph.tasks.each { id, task ->
    println "$id: predecessors=${task.predecessors}, successors=${task.successors}"
}
```

### Null Pointer in Action

**Likely cause:** Accessing `prevValue` when task has no predecessors

```groovy
action { ctx, prevValue ->
    if (prevValue == null) {
        // Handle root task
    }
    // ...
}
```

### Task Hangs Forever

**Check:**
1. Did you return a Promise?
2. Does the Promise ever complete?
3. Is there a circular dependency?

---

## Performance Tips

1. **Use appropriate shard counts** - Too many shards adds overhead
2. **Set reasonable timeouts** - Prevents hanging tasks
3. **Limit retry attempts** - Failed tasks shouldn't retry forever
4. **Monitor task states** - Use event listeners to track execution

---

## Additional Resources

- **Full Manual**: `taskgraph_manual_full.md` - Detailed technical documentation
- **Promise Guide**: `Promises_readme.md` - Understanding the Promise abstraction
- **Dataflow Guide**: `dataflow_readme.md` - Backend implementation details
- **Source Code**: `src/main/groovy/org/softwood/dag/` - Browse the implementation

---

## Quick Reference

```groovy
// Build a graph
def graph = TaskGraph.build {
    globals { /* config */ }
    
    serviceTask("id") {
        dependsOn "pred1", "pred2"
        maxRetries = 3
        timeoutMillis = 5000
        action { ctx, prevValue -> /* return Promise */ }
    }
    
    fork("router") {
        from "source"
        conditionalOn(["target"]) { data -> /* boolean */ }
        route { data -> /* List<String> */ }
        shard("template", count) { data -> /* List<List> */ }
    }
}

// Execute
def result = graph.run().get()

// Or async
graph.run()
    .onComplete { result -> /* ... */ }
    .onError { error -> /* ... */ }
```

---

**Version**: 2.0.0 (December 2025)  
**License**: Apache 2.0
