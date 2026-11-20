# Agent & AgentPool – Thread-Safe State Management

**Version 1.1.0** - Now with agent naming, state versioning, batch operations, and enhanced configurability

The Agent and AgentPool abstractions provide thread-safe, message-driven access to mutable state with sequential execution guarantees, virtual thread support, and comprehensive observability.

---

## Table of Contents
1. [Overview](#overview)
2. [What's New in 1.1.0](#whats-new-in-110)
3. [Core Concepts](#core-concepts)
4. [Quick Start](#quick-start)
5. [Agent API](#agent-api)
6. [AgentPool API](#agentpool-api)
7. [Error Management](#error-management)
8. [Health & Metrics](#health--metrics)
9. [Advanced Usage](#advanced-usage)
10. [Performance Considerations](#performance-considerations)
11. [Thread Safety](#thread-safety)
12. [Examples](#examples)

---

## Overview

### What is an Agent?

An **Agent** wraps a mutable object and guarantees that all updates execute sequentially on a dedicated worker thread. Multiple threads can submit work concurrently, but the Agent ensures serialized execution.

```groovy
def agent = AgentFactory.create([count: 0])

// From multiple threads
agent.send { count++ }  // Thread-safe, sequential execution
```

### What is an AgentPool?

An **AgentPool** manages multiple agents for load distribution, providing round-robin dispatch and broadcast capabilities.

```groovy
def pool = AgentPoolFactory.create(4) { [count: 0] }

pool.send { count++ }      // Dispatched to next agent (round-robin)
pool.broadcast { count = 0 }  // Sent to all agents
```

### When to Use

**Use Agent when:**
- You have mutable state that isn't thread-safe
- You want thread-safe updates without explicit locking
- You're happy with sequential execution (one update at a time)
- You want readers to see immutable snapshots

**Use AgentPool when:**
- You need to distribute load across multiple agents
- You want to scale processing horizontally
- You need both targeted and broadcast operations

---

## What's New in 1.1.0

### 🆕 Agent Naming & Identification
Agents can now be named for easier debugging and monitoring:

```groovy
def agent = AgentFactory.builder([count: 0])
    .name("order-processor")
    .build()

println agent.getName()  // "order-processor"

def metrics = agent.metrics()
println metrics.name  // Appears in all metrics/logs
```

### 🆕 State Versioning
Track how many times state has been accessed:

```groovy
def agent = AgentFactory.create([count: 0])

agent.getValue()  // version: 1
agent.getValue()  // version: 2
println agent.getStateVersion()  // 2

def metrics = agent.metrics()
println metrics.stateVersion  // 2
```

### 🆕 Batch Operations
Submit multiple tasks efficiently:

```groovy
// Fire-and-forget batch
agent.sendBatch([
    { count++ },
    { count++ },
    { items << count }
])

// Synchronous batch with results
def results = agent.sendAndGetBatch([
    { count },
    { count * 2 },
    { count + 10 }
], 5)  // 5 second timeout

println results  // [current, current*2, current+10]
```

### 🆕 Configurable Error Retention
Control how many errors are kept in history:

```groovy
def agent = AgentFactory.builder([count: 0])
    .maxErrorsRetained(50)  // Keep last 50 errors (default: 100)
    .build()
```

### 🆕 Graceful Deep Copy Degradation
If deep copy fails, agent logs warning and returns direct reference instead of crashing:

```groovy
def metrics = agent.metrics()
if (metrics.deepCopyFailures > 0) {
    log.warn("Deep copy failures detected: ${metrics.deepCopyFailures}")
}
```

### 🆕 Enhanced Input Validation
AgentPool now validates null inputs:

```groovy
pool.send(null)  // IllegalArgumentException
pool.broadcast(null)  // IllegalArgumentException
```

---

## Core Concepts

### 1. Sequential Execution
All closures submitted via `send()` or `sendAndGet()` execute one after another on a single worker thread.

### 2. Thread-Safe Access
Callers never touch the wrapped object directly—they interact through closures that run on the agent's executor.

### 3. Defensive Snapshots
`getValue()` returns a deep copy of the current state. Changes to the snapshot don't affect the agent's internal state.

### 4. Virtual Threads by Default
Agents use virtual-thread-based ExecutorPools by default, making blocking operations cheap.

---

## Quick Start

### Creating an Agent

```groovy
import org.softwood.agent.AgentFactory

// Simple creation
def agent = AgentFactory.create([count: 0, items: []])

// With configuration (v1.1.0 features)
def agent = AgentFactory.builder([count: 0])
    .name("my-agent")                    // 🆕 Named agent
    .maxQueueSize(1000)
    .maxErrorsRetained(50)               // 🆕 Configurable error history
    .onError({ e -> log.error("Task failed", e) })
    .build()

// With custom pool
def pool = ExecutorPoolFactory.builder().name("my-pool").build()
def agent = AgentFactory.builder([count: 0])
    .pool(pool)
    .build()
```

### Basic Operations

```groovy
// Async update (fire-and-forget)
agent.send { count++ }
agent >> { items << count }  // Operator alias

// Sync update (wait for result)
def result = agent.sendAndGet({ count }, 0)
def result = agent << { count }  // Operator alias

// 🆕 Batch operations
agent.sendBatch([{ count++ }, { count++ }, { count++ }])
def results = agent.sendAndGetBatch([{ count }, { count * 2 }], 5)

// Get snapshot
def snapshot = agent.getValue()
println snapshot.count

// 🆕 Check state version
println agent.getStateVersion()  // How many times getValue() called

// 🆕 Get agent name
println agent.getName()

// Shutdown
agent.shutdown(30, TimeUnit.SECONDS)
```

### Creating an AgentPool

```groovy
import org.softwood.agent.AgentPoolFactory

// Create pool with 4 agents
def pool = AgentPoolFactory.create(4) { [count: 0] }

// Round-robin dispatch
pool.send { count++ }

// Broadcast to all
pool.broadcast { count = 0 }

// Get all snapshots
def values = pool.getAllValues()
```

---

## Agent API

### Creation Methods

#### AgentFactory.create()
```groovy
Agent<T> create(T initialValue)
```
Creates an agent with default settings and auto-generated name.

**Example:**
```groovy
def agent = AgentFactory.create([count: 0])
println agent.getName()  // "Agent-a1b2c3d4" (auto-generated)
```

#### AgentFactory.builder()
```groovy
Builder<T> builder(T initialValue)
```
Returns a builder for custom configuration.

**Builder Methods:**
- `name(String)` - 🆕 Set agent name for debugging
- `maxErrorsRetained(int)` - 🆕 Set error history size (default: 100)
- `pool(ExecutorPool)` - Use specific pool
- `poolOwned(ExecutorPool)` - Use pool and own it (shutdown with agent)
- `copyStrategy(Supplier<T>)` - Custom snapshot copy strategy
- `maxQueueSize(int)` - Set queue size limit
- `onError(Closure)` - Set error handler

**Example:**
```groovy
def agent = AgentFactory.builder([count: 0])
    .name("payment-processor")           // 🆕
    .maxErrorsRetained(25)                // 🆕
    .maxQueueSize(1000)
    .onError({ e -> log.error("Error", e) })
    .copyStrategy({ -> [count: state.count] })  // Custom copy
    .build()
```

#### Factory Convenience Methods
```groovy
// Agent with named pool (agent owns and will shutdown pool)
Agent<T> createWithPool(T initialValue, String poolName)

// Agent with shared pool (agent does NOT own pool)
Agent<T> createWithSharedPool(T initialValue, ExecutorPool pool)

// Agent with queue limit
Agent<T> createWithQueueLimit(T initialValue, int maxQueueSize)
```

### Core Operations

#### send()
```groovy
Agent<T> send(Closure action)
```
Fire-and-forget async update. Queues the closure for execution.

**Aliases:** `async(Closure)`, `>> operator`

**Example:**
```groovy
agent.send { count++ }
agent.async { items << count }
agent >> { data.updated = true }
```

**Throws:**
- `IllegalStateException` - If agent is shutting down
- `RejectedExecutionException` - If queue is full (when maxQueueSize set)

#### sendAndGet()
```groovy
<R> R sendAndGet(Closure<R> action, long timeoutSeconds = 0)
```
Synchronous update. Blocks until closure completes and returns result.

**Aliases:** `sync(Closure, long)`, `<< operator`

**Parameters:**
- `timeoutSeconds` - Timeout in seconds (0 = no timeout)

**Example:**
```groovy
def result = agent.sendAndGet({ count += 5; count }, 0)
def result = agent.sync({ count }, 10)  // 10 second timeout
def result = agent << { count * 2 }
```

**Throws:**
- `RuntimeException` - If timeout expires
- Original exception if closure throws

#### 🆕 sendBatch()
```groovy
Agent<T> sendBatch(List<Closure> actions)
```
Submits multiple tasks for sequential execution. All tasks are queued in order.

**Example:**
```groovy
agent.sendBatch([
    { count++ },
    { count++ },
    { items << count },
    { lastUpdated = System.currentTimeMillis() }
])

// Returns immediately, tasks execute asynchronously
```

**Parameters:**
- `actions` - List of closures to execute sequentially

**Returns:** `this` for chaining

#### 🆕 sendAndGetBatch()
```groovy
List<Object> sendAndGetBatch(List<Closure> actions, long timeoutSeconds = 0)
```
Submits multiple tasks and waits for all results.

**Example:**
```groovy
def results = agent.sendAndGetBatch([
    { count },              // Get current count
    { count * 2 },          // Get double
    { count + 100 }         // Get plus 100
], 10)  // 10 second timeout per task

println results  // [5, 10, 105] (if count was 5)
```

**Parameters:**
- `actions` - List of closures to execute
- `timeoutSeconds` - Timeout in seconds per task

**Returns:** List of results from each closure

#### getValue()
```groovy
T getValue()
```
Returns an immutable defensive snapshot of the current state. Increments state version counter.

**Example:**
```groovy
def snapshot = agent.getValue()  // stateVersion: 1
snapshot.count = 999  // Doesn't affect agent's internal state

def snapshot2 = agent.getValue()  // stateVersion: 2
assert snapshot2.count != 999  // Still has original value

println agent.getStateVersion()  // 2
```

**Note:** In v1.1.0, if deep copy fails, returns direct reference (not safe) and logs warning instead of throwing exception.

### 🆕 Identification Methods

#### getName()
```groovy
String getName()
```
Returns the agent's name for debugging and logging.

**Example:**
```groovy
def agent = AgentFactory.builder([count: 0])
    .name("order-agent")
    .build()

println agent.getName()  // "order-agent"

// Auto-generated if not specified
def agent2 = AgentFactory.create([count: 0])
println agent2.getName()  // "Agent-f7e8d9c0"
```

#### 🆕 getStateVersion()
```groovy
long getStateVersion()
```
Returns the current state version number. Increments each time `getValue()` is called.

**Example:**
```groovy
def agent = AgentFactory.create([count: 0])

long v1 = agent.getStateVersion()  // 0 (no getValue() calls yet)
agent.getValue()
long v2 = agent.getStateVersion()  // 1
agent.getValue()
long v3 = agent.getStateVersion()  // 2
```

**Use Cases:**
- Monitoring snapshot access frequency
- Detecting potential performance issues (excessive getValue() calls)
- Debugging state synchronization

### Lifecycle Management

#### shutdown()
```groovy
void shutdown()
boolean shutdown(long timeout, TimeUnit unit)
void shutdownNow()
```

**shutdown()**: Graceful shutdown, blocks until queue is drained.

**shutdown(timeout, unit)**: Graceful shutdown with timeout, returns true if completed.

**shutdownNow()**: Force shutdown, discards pending tasks.

**Example:**
```groovy
// Graceful with timeout
if (!agent.shutdown(30, TimeUnit.SECONDS)) {
    agent.shutdownNow()  // Force if timeout
}

// Check status
if (agent.isTerminated()) {
    println "Agent fully shutdown"
}
```

#### Status Checks
```groovy
boolean isShutdown()    // Has shutdown been initiated?
boolean isTerminated()  // Is shutdown complete?
```

### Error Management

#### onError()
```groovy
Agent<T> onError(Closure<Void> errorHandler)
```
Sets a custom error handler called when tasks throw exceptions.

**Example:**
```groovy
agent.onError({ e ->
    log.error("[${agent.getName()}] Task failed: ${e.message}", e)
    metrics.recordError(agent.getName(), e)
})
```

#### getErrors()
```groovy
List<Map<String, Object>> getErrors(int maxCount = Integer.MAX_VALUE)
```
Returns recent errors (up to maxErrorsRetained, default 100).

**Error Map Structure:**
```groovy
[
    timestamp: Long,
    errorType: String,
    message: String,
    stackTrace: List<String>  // Top 5 stack frames
]
```

**Example:**
```groovy
def errors = agent.getErrors(10)
errors.each { error ->
    println "${error.timestamp}: ${error.errorType} - ${error.message}"
    error.stackTrace.each { println "  $it" }
}
```

#### clearErrors()
```groovy
void clearErrors()
```
Clears the error history.

### Configuration

#### Queue Size Limits
```groovy
void setMaxQueueSize(int max)  // 0 = unbounded
int getMaxQueueSize()
```

**Example:**
```groovy
agent.setMaxQueueSize(1000)  // Limit to 1000 pending tasks

try {
    agent.send { count++ }
} catch (RejectedExecutionException e) {
    println "Queue full!"
}
```

### Observability

#### health()
```groovy
Map<String, Object> health()
```

Returns health status.

**Response:**
```groovy
[
    name: String,                    // 🆕 Agent name
    status: String,                  // HEALTHY, DEGRADED, SHUTTING_DOWN
    shuttingDown: boolean,
    terminated: boolean,
    processing: boolean,
    queueSize: int,
    maxQueueSize: int,
    queueUtilization: double,        // Percentage (0-100)
    recentErrorCount: int,
    timestamp: long
]
```

**Example:**
```groovy
def health = agent.health()
println "Agent ${health.name}: ${health.status}"

if (health.status == "DEGRADED") {
    alert("Agent ${health.name} queue at ${health.queueUtilization}%")
}
```

#### metrics()
```groovy
Map<String, Object> metrics()
```

Returns operational metrics.

**Response:**
```groovy
[
    name: String,                    // 🆕 Agent name
    stateVersion: long,              // 🆕 State version counter
    tasksSubmitted: long,
    tasksCompleted: long,
    tasksPending: long,
    tasksErrored: long,
    queueRejections: long,
    deepCopyFailures: long,          // 🆕 Deep copy failure count
    queueDepth: int,
    maxQueueSize: int,
    processing: boolean,
    shuttingDown: boolean,
    terminated: boolean,
    uptimeMs: long,
    throughputPerSec: double,
    errorRatePercent: double,
    lastTaskCompletedAt: long,
    createdAt: long,
    timestamp: long
]
```

**Example:**
```groovy
def metrics = agent.metrics()
println """
Agent: ${metrics.name}
State Version: ${metrics.stateVersion}
Throughput: ${metrics.throughputPerSec} tasks/sec
Error Rate: ${metrics.errorRatePercent}%
Deep Copy Failures: ${metrics.deepCopyFailures}
Pending: ${metrics.tasksPending}
"""

// Alert on copy failures
if (metrics.deepCopyFailures > 0) {
    log.warn("Agent ${metrics.name} has ${metrics.deepCopyFailures} deep copy failures")
}
```

---

## AgentPool API

### Creation

#### AgentPoolFactory.create()
```groovy
AgentPool<T> create(int size, Closure<T> stateFactory)
```

**Example:**
```groovy
def pool = AgentPoolFactory.create(4) { [count: 0] }
// Each agent gets auto-generated name: "Agent-xxx"
```

#### AgentPoolFactory.builder()
```groovy
Builder<T> builder(int size, Closure<T> stateFactory)
```

**Builder Methods:**
- `sharedPool(ExecutorPool)` - Use shared pool for all agents
- `copyStrategy(Supplier<T>)` - Custom copy strategy for all agents
- `maxQueueSize(int)` - Set queue limit for all agents
- `maxErrorsRetained(int)` - 🆕 Set error history size for all agents
- `onError(Closure)` - Set error handler for all agents

**Example:**
```groovy
def pool = AgentPoolFactory.builder(4) { [count: 0] }
    .maxQueueSize(500)
    .maxErrorsRetained(50)           // 🆕
    .onError({ e -> log.error("Pool task error", e) })
    .build()
```

**Note:** Individual agent names in pools are auto-generated. For custom names, create agents individually.

#### Factory Convenience Methods
```groovy
// Each agent gets its own dedicated pool
AgentPool<T> createWithDedicatedPools(int size, Closure<T> stateFactory, String poolNamePrefix)

// All agents have queue limits
AgentPool<T> createWithQueueLimit(int size, Closure<T> stateFactory, int maxQueueSize)
```

### Dispatch Operations

#### send() / async()
```groovy
AgentPool<T> send(Closure action)
AgentPool<T> async(Closure action)
```
Dispatches task to next agent (round-robin). 🆕 Validates non-null action.

**Example:**
```groovy
pool.send { count++ }
pool >> { items << count }  // Operator alias
```

**Throws:**
- `IllegalArgumentException` - 🆕 If action is null

#### sendAndGet() / sync()
```groovy
<R> R sendAndGet(Closure<R> action, long timeoutSeconds = 0)
<R> R sync(Closure<R> action, long timeoutSeconds = 0)
```
Dispatches task synchronously to next agent. 🆕 Validates non-null action.

**Example:**
```groovy
def result = pool.sendAndGet({ count }, 0)
def result = pool << { count }  // Operator alias
```

**Throws:**
- `IllegalArgumentException` - 🆕 If action is null

#### broadcast()
```groovy
AgentPool<T> broadcast(Closure action)
```
Sends task to all agents in the pool. 🆕 Validates non-null action.

**Example:**
```groovy
pool.broadcast { count = 0 }  // Reset all agents
```

**Throws:**
- `IllegalArgumentException` - 🆕 If action is null

#### broadcastAndGet()
```groovy
List<Object> broadcastAndGet(Closure action, long timeoutSeconds = 0)
```
Broadcasts task to all agents and waits for all results. 🆕 Validates non-null action.

**Example:**
```groovy
def results = pool.broadcastAndGet({ count * 2 }, 10)
results.each { println it }
```

**Throws:**
- `IllegalArgumentException` - 🆕 If action is null

### State Access

#### getAllValues()
```groovy
List<T> getAllValues()
```
Returns snapshots from all agents.

**Example:**
```groovy
def values = pool.getAllValues()
values.eachWithIndex { state, i ->
    println "Agent $i count: ${state.count}"
}
```

#### getValue()
```groovy
T getValue(int index)
```
Returns snapshot from specific agent by index.

**Example:**
```groovy
def snapshot = pool.getValue(0)  // First agent
```

#### getAgent()
```groovy
Agent<T> getAgent(int index)
```
Returns reference to specific agent for direct operations.

**Example:**
```groovy
def agent = pool.getAgent(2)
println agent.getName()  // Get agent's name
agent.send { count += 100 }
```

### Pool Management

```groovy
int size()                    // Number of agents in pool
List<Agent<T>> getAgents()    // All agents (read-only)
```

### Error Management

```groovy
AgentPool<T> onError(Closure<Void> errorHandler)
Map<Integer, List<Map<String, Object>>> getAllErrors(int maxPerAgent)
void clearAllErrors()
```

**Example:**
```groovy
// Get errors from all agents
def allErrors = pool.getAllErrors(5)  // Max 5 per agent
allErrors.each { agentIndex, errors ->
    def agent = pool.getAgent(agentIndex)
    println "Agent ${agent.getName()} (index $agentIndex) has ${errors.size()} errors"
}
```

### Configuration

```groovy
void setMaxQueueSize(int max)  // Set for all agents
```

### Lifecycle

```groovy
void shutdown()
boolean shutdown(long timeout, TimeUnit unit)
void shutdownNow()
boolean isShutdown()
boolean isTerminated()
```

**Example:**
```groovy
pool.shutdown(60, TimeUnit.SECONDS)
```

### Observability

#### health()
```groovy
Map<String, Object> health()
```

**Pool Health Response:**
```groovy
[
    status: String,                    // HEALTHY, DEGRADED, SHUTTING_DOWN
    poolSize: int,
    agentsShuttingDown: int,
    agentsProcessing: int,
    totalQueueSize: int,
    totalRecentErrors: int,
    agentHealths: List<Map>,          // Health from each agent (includes name)
    timestamp: long
]
```

#### metrics()
```groovy
Map<String, Object> metrics()
```

**Pool Metrics Response:**
```groovy
[
    poolSize: int,
    totalDispatches: long,
    totalBroadcasts: long,
    poolUptimeMs: long,
    poolThroughputPerSec: double,
    totalTasksSubmitted: long,
    totalTasksCompleted: long,
    totalTasksErrored: long,
    totalQueueRejections: long,
    totalQueueDepth: int,
    avgErrorRatePercent: double,
    agentMetrics: List<Map>,          // Metrics from each agent (includes name, stateVersion)
    timestamp: long
]
```

**Example:**
```groovy
def metrics = pool.metrics()
println """
Pool: ${metrics.poolSize} agents
Total Dispatches: ${metrics.totalDispatches}
Pool Throughput: ${metrics.poolThroughputPerSec} tasks/sec
"""

// Check individual agent metrics
metrics.agentMetrics.each { agentMetric ->
    println "  ${agentMetric.name}: ${agentMetric.throughputPerSec} tasks/sec"
}
```

---

## Error Management

### Error Handling Behavior

#### Fire-and-Forget (send/async)
If an exception occurs in a `send()` closure:
1. Error is logged via SLF4J (includes agent name in v1.1.0)
2. Error is tracked in error history (configurable retention)
3. Custom error handler is called (if set)
4. Agent continues processing subsequent tasks
5. Error does NOT propagate to caller

#### Synchronous (sendAndGet/sync)
If an exception occurs in `sendAndGet()` closure:
1. Error is tracked in error history
2. Custom error handler is called (if set)
3. Exception is propagated to caller
   - RuntimeException: re-thrown as-is
   - Other Throwable: wrapped in RuntimeException

### 🆕 Configurable Error Retention

In v1.1.0, you can control how many errors are retained:

```groovy
// Keep only last 25 errors (saves memory)
def agent = AgentFactory.builder([count: 0])
    .maxErrorsRetained(25)
    .build()

// Keep more errors for detailed debugging
def debugAgent = AgentFactory.builder([count: 0])
    .maxErrorsRetained(200)
    .build()
```

### Error Handler Pattern

```groovy
def agent = AgentFactory.builder([count: 0])
    .name("payment-processor")
    .maxErrorsRetained(50)
    .onError({ e ->
        // Log with agent name
        log.error("[${agent.getName()}] Task failed", e)
        
        // Record metrics
        errorCounter.labels(agent.getName()).increment()
        
        // Alert if critical
        if (e instanceof CriticalException) {
            alertService.notify("Agent ${agent.getName()} critical error", e)
        }
    })
    .build()
```

### Error Inspection

```groovy
// Check for errors periodically
def errors = agent.getErrors(10)
if (!errors.isEmpty()) {
    log.warn("Agent ${agent.getName()} has ${errors.size()} recent errors")
    
    errors.each { error ->
        println "${error.timestamp}: ${error.message}"
    }
    
    // Clear after handling
    agent.clearErrors()
}
```

---

## Health & Metrics

### Health Status Values

- **HEALTHY**: Operating normally
- **DEGRADED**: Queue is > 80% full (when maxQueueSize set)
- **SHUTTING_DOWN**: Shutdown initiated

### Monitoring Pattern

```groovy
// Health check with agent identification
def performHealthCheck = {
    def health = agent.health()
    
    if (health.status == "DEGRADED") {
        log.warn("Agent ${health.name} degraded: queue at ${health.queueUtilization}%")
    }
    
    if (health.recentErrorCount > 10) {
        log.warn("Agent ${health.name} high error count: ${health.recentErrorCount}")
    }
}

// Metrics collection with new fields
def collectMetrics = {
    def metrics = agent.metrics()
    
    metricsCollector.record([
        'agent.throughput': metrics.throughputPerSec,
        'agent.error_rate': metrics.errorRatePercent,
        'agent.queue_depth': metrics.queueDepth,
        'agent.pending_tasks': metrics.tasksPending,
        'agent.state_version': metrics.stateVersion,         // 🆕
        'agent.deep_copy_failures': metrics.deepCopyFailures  // 🆕
    ], [name: metrics.name])
    
    // Alert on high error rate
    if (metrics.errorRatePercent > 5.0) {
        alertService.notify("High agent error rate: ${metrics.name} at ${metrics.errorRatePercent}%")
    }
    
    // Alert on copy failures
    if (metrics.deepCopyFailures > 0) {
        alertService.notify("Agent ${metrics.name} has deep copy failures")
    }
    
    // Monitor state access patterns
    if (metrics.stateVersion > 10000) {
        log.info("Agent ${metrics.name} has high snapshot access: ${metrics.stateVersion}")
    }
}

// Schedule checks
scheduler.scheduleAtFixedRate(performHealthCheck, 0, 30, TimeUnit.SECONDS)
scheduler.scheduleAtFixedRate(collectMetrics, 0, 60, TimeUnit.SECONDS)
```

---

## Advanced Usage

### Custom Copy Strategy

For performance or special copying needs:

```groovy
def agent = AgentFactory.builder(expensiveObject)
    .name("expensive-agent")
    .copyStrategy({ ->
        // Return a cheap, immutable view or DTO
        new StateSnapshot(
            count: state.count,
            summary: state.computeSummary()
        )
    })
    .build()
```

### 🆕 Batch Processing Patterns

```groovy
// Process multiple updates efficiently
def updates = fetchPendingUpdates()
agent.sendBatch(updates.collect { update ->
    { state -> applyUpdate(state, update) }
})

// Collect multiple metrics
def results = agent.sendAndGetBatch([
    { computeMetric1() },
    { computeMetric2() },
    { computeMetric3() }
], 5)

def [metric1, metric2, metric3] = results
```

### Chaining Operations

```groovy
agent.send { count++ }
     .send { items << count }
     .send { lastUpdated = System.currentTimeMillis() }

// 🆕 Batch alternative
agent.sendBatch([
    { count++ },
    { items << count },
    { lastUpdated = System.currentTimeMillis() }
])
```

### Conditional Operations

```groovy
// Only update if condition met
agent.send {
    if (count < 100) {
        count++
    }
}

// Using sendAndGet for read-modify-write
def shouldUpdate = agent.sendAndGet({
    count < 100
}, 0)

if (shouldUpdate) {
    agent.send { count++ }
}
```

### Pool Load Balancing

```groovy
// Round-robin automatically distributes load
1000.times { i ->
    pool.send { data << i }  // Distributed evenly
}

// Target specific agents when needed
def agent = pool.getAgent(0)
println "Targeting ${agent.getName()}"
agent.send { /* special task for this agent */ }
```

### Pool Aggregation

```groovy
// Collect results from all agents
def totals = pool.broadcastAndGet({ count }, 10)
def grandTotal = totals.sum()

println "Total across all agents: $grandTotal"

// Get metrics from all agents
def poolMetrics = pool.metrics()
poolMetrics.agentMetrics.each { m ->
    println "${m.name}: ${m.throughputPerSec} tasks/sec, version ${m.stateVersion}"
}
```

### 🆕 Monitoring State Access Patterns

```groovy
// Track snapshot access frequency
def checkAccessPatterns = {
    def version = agent.getStateVersion()
    
    if (version > lastVersion + 100) {
        log.warn("Agent ${agent.getName()} high snapshot access: ${version - lastVersion} calls")
    }
    
    lastVersion = version
}
```

---

## Performance Considerations

### 1. Keep State Small

Agents work best with:
- Small to moderate-sized maps (configs, counters, aggregates)
- Small POJOs with a few fields
- Focused, bounded state

Avoid:
- Large in-memory databases
- Huge caches
- Deeply nested object graphs

### 2. Deep Copy Cost

Each `getValue()` performs a deep copy. For large state:
- Use custom copy strategy
- Consider immutable data structures
- Call `getValue()` sparingly
- 🆕 Monitor `deepCopyFailures` metric
- 🆕 Monitor `stateVersion` to track access frequency

```groovy
def metrics = agent.metrics()

// Alert on frequent snapshots
if (metrics.stateVersion > threshold) {
    log.warn("Frequent snapshots: ${metrics.stateVersion}")
}

// Alert on copy failures
if (metrics.deepCopyFailures > 0) {
    log.error("Deep copy failures detected")
}
```

### 3. Queue Management

Monitor queue depth:
```groovy
def metrics = agent.metrics()
if (metrics.queueDepth > 1000) {
    log.warn("Agent ${metrics.name} large queue: ${metrics.queueDepth}")
}
```

Set limits when needed:
```groovy
agent.setMaxQueueSize(1000)  // Reject submissions when full
```

### 4. AgentPool Sizing

Rule of thumb:
- **CPU-bound tasks**: poolSize = number of cores
- **I/O-bound tasks**: poolSize = 2-4x number of cores
- **Mixed workload**: Start with cores * 2, tune based on metrics

### 5. 🆕 Batch Operations Performance

Use batch operations for efficiency:
```groovy
// Less efficient: multiple send calls
agent.send { count++ }
agent.send { count++ }
agent.send { count++ }

// More efficient: single batch
agent.sendBatch([
    { count++ },
    { count++ },
    { count++ }
])
```

### 6. Benchmarking Results

Typical performance (varies by system):
- Single agent: 5,000-10,000 tasks/sec
- Pool of 4: 15,000-30,000 tasks/sec
- Pool of 8: 25,000-50,000 tasks/sec
- 🆕 Batch operations: 10-15% overhead vs individual sends

Deep copy overhead:
- Simple map (10 fields): <1ms
- Complex nested (50 objects): 10-20ms
- Copy failure fallback: ~0.1ms + warning log

---

## Thread Safety

### Thread-Safe Operations
- `send()`, `sendAndGet()` - Safe to call from multiple threads
- 🆕 `sendBatch()`, `sendAndGetBatch()` - Safe to call from multiple threads
- `getValue()` - Safe, returns isolated snapshot
- 🆕 `getName()`, `getStateVersion()` - Safe, read-only
- `shutdown()` - Safe, idempotent
- `health()`, `metrics()` - Safe, consistent snapshot
- `getErrors()`, `clearErrors()` - Safe

### NOT Thread-Safe (Call Before Concurrent Use)
- `setMaxQueueSize()` - Set during initialization
- `onError()` - Set during initialization

### Visibility Guarantees

All changes made within agent closures are visible to:
- Subsequent closures in the same agent
- Snapshots obtained via `getValue()`
- State version counter via `getStateVersion()`

---

## Examples

### Example 1: 🆕 Named Counter Service

```groovy
class CounterService {
    private final Agent<Map> agent
    
    CounterService(String name) {
        agent = AgentFactory.builder([counters: [:]])
            .name(name)                          // 🆕 Named agent
            .maxQueueSize(10000)
            .maxErrorsRetained(50)                // 🆕 Limited error history
            .onError({ e -> 
                log.error("[${agent.getName()}] Counter error", e) 
            })
            .build()
    }
    
    void increment(String name) {
        agent.send {
            counters[name] = (counters[name] ?: 0) + 1
        }
    }
    
    void incrementBatch(List<String> names) {    // 🆕 Batch increment
        agent.sendBatch(names.collect { name ->
            { counters[name] = (counters[name] ?: 0) + 1 }
        })
    }
    
    Map<String, Integer> getCounters() {
        agent.getValue().counters
    }
    
    Map getMetrics() {
        def m = agent.metrics()
        [
            name: m.name,                        // 🆕
            stateVersion: m.stateVersion,        // 🆕
            throughput: m.throughputPerSec,
            errorRate: m.errorRatePercent
        ]
    }
    
    void reset() {
        agent.send { counters.clear() }
    }
}
```

### Example 2: Session Manager with Pool

```groovy
class SessionManager {
    private final AgentPool<Map> pool
    
    SessionManager() {
        pool = AgentPoolFactory.builder(4) { [sessions: [:]] }
            .maxQueueSize(5000)
            .maxErrorsRetained(25)                           // 🆕
            .onError({ e -> log.error("Session error", e) })
            .build()
    }
    
    void createSession(String sessionId, Map data) {
        pool.send {
            sessions[sessionId] = [
                data: data,
                created: System.currentTimeMillis()
            ]
        }
    }
    
    void createSessionBatch(List<Map> sessionData) {         // 🆕 Batch create
        pool.sendBatch(sessionData.collect { sd ->
            { 
                sessions[sd.id] = [
                    data: sd.data,
                    created: System.currentTimeMillis()
                ]
            }
        })
    }
    
    void expireSessions(long maxAge) {
        long cutoff = System.currentTimeMillis() - maxAge
        
        pool.broadcast {
            sessions.removeAll { k, v ->
                v.created < cutoff
            }
        }
    }
    
    Map getMetrics() {
        def poolMetrics = pool.metrics()
        def allValues = pool.getAllValues()
        
        [
            totalSessions: allValues.sum { it.sessions.size() },
            throughput: poolMetrics.poolThroughputPerSec,
            errorRate: poolMetrics.avgErrorRatePercent,
            agents: poolMetrics.agentMetrics.collect { m ->  // 🆕 Per-agent detail
                [
                    name: m.name,
                    stateVersion: m.stateVersion,
                    throughput: m.throughputPerSec
                ]
            }
        ]
    }
}
```

### Example 3: Rate Limiter

```groovy
class RateLimiter {
    private final Agent<Map> agent
    
    RateLimiter(int maxRequests, long windowMs) {
        agent = AgentFactory.builder([
            requests: [],
            maxRequests: maxRequests,
            windowMs: windowMs
        ])
            .name("rate-limiter")                            // 🆕
            .build()
    }
    
    boolean allowRequest(String clientId) {
        agent.sendAndGet({
            long now = System.currentTimeMillis()
            long cutoff = now - windowMs
            
            // Clean old requests
            requests.removeAll { it.timestamp < cutoff }
            
            // Check limit
            if (requests.size() >= maxRequests) {
                return false
            }
            
            // Record request
            requests << [clientId: clientId, timestamp: now]
            return true
        }, 1)  // 1 second timeout
    }
    
    List<Boolean> allowRequestBatch(List<String> clientIds) {  // 🆕 Batch check
        agent.sendAndGetBatch(clientIds.collect { clientId ->
            {
                long now = System.currentTimeMillis()
                long cutoff = now - windowMs
                requests.removeAll { it.timestamp < cutoff }
                
                if (requests.size() >= maxRequests) {
                    return false
                }
                
                requests << [clientId: clientId, timestamp: now]
                return true
            }
        }, 1)
    }
    
    Map getStatus() {
        def m = agent.metrics()
        [
            name: m.name,                                    // 🆕
            requestsProcessed: m.tasksCompleted,
            currentLoad: agent.getValue().requests.size()
        ]
    }
}
```

---

## Migration Guide (1.0.x → 1.1.0)

### Breaking Changes
None! Version 1.1.0 is fully backward compatible.

### New Features Available

1. **Agent Naming** - Add names to existing agents:
```groovy
// Before
def agent = AgentFactory.create([count: 0])

// After (optional)
def agent = AgentFactory.builder([count: 0])
    .name("my-agent")
    .build()
```

2. **Batch Operations** - Use for efficiency:
```groovy
// Before
agent.send { count++ }
agent.send { count++ }
agent.send { count++ }

// After
agent.sendBatch([{ count++ }, { count++ }, { count++ }])
```

3. **Monitor New Metrics**:
```groovy
def metrics = agent.metrics()
println metrics.name              // Agent name
println metrics.stateVersion      // Snapshot access count
println metrics.deepCopyFailures  // Copy failure count
```

4. **Configure Error Retention**:
```groovy
def agent = AgentFactory.builder([count: 0])
    .maxErrorsRetained(50)  // Reduce memory usage
    .build()
```

---

## Summary

### Agent (v1.1.0)
- **Purpose**: Thread-safe wrapper for mutable state
- **Pattern**: Message-driven sequential updates
- **Best For**: Single-threaded state management with concurrent access
- **Key Methods**: `send()`, `sendBatch()`, `sendAndGet()`, `getValue()`, `shutdown()`
- **🆕 New Methods**: `getName()`, `getStateVersion()`, `sendBatch()`, `sendAndGetBatch()`

### AgentPool (v1.1.0)
- **Purpose**: Load distribution across multiple agents
- **Pattern**: Round-robin dispatch and broadcast
- **Best For**: Scaling agent processing horizontally
- **Key Methods**: `send()`, `broadcast()`, `getAllValues()`, `metrics()`
- **🆕 Enhancements**: Null validation, configurable error retention

### Key Features
✅ Sequential execution guarantees  
✅ Virtual thread support  
✅ Defensive snapshots  
✅ 🆕 Agent naming & identification  
✅ 🆕 State versioning  
✅ 🆕 Batch operations  
✅ 🆕 Configurable error retention  
✅ 🆕 Graceful deep copy degradation  
✅ Error management and tracking  
✅ Health and metrics observability  
✅ Queue overflow protection  
✅ Graceful shutdown with timeout  
✅ Production-ready with comprehensive testing

---

**Version History:**
- **1.1.0** - Added agent naming, state versioning, batch operations, configurable error retention, graceful copy degradation
- **1.0.0** - Initial release with Agent and AgentPool