# Agent & AgentPool – Thread-Safe State Management

The Agent and AgentPool abstractions provide thread-safe, message-driven access to mutable state with sequential execution guarantees, virtual thread support, and comprehensive observability.

---

## Table of Contents
1. [Overview](#overview)
2. [Core Concepts](#core-concepts)
3. [Quick Start](#quick-start)
4. [Agent API](#agent-api)
5. [AgentPool API](#agentpool-api)
6. [Error Management](#error-management)
7. [Health & Metrics](#health--metrics)
8. [Advanced Usage](#advanced-usage)
9. [Performance Considerations](#performance-considerations)
10. [Thread Safety](#thread-safety)

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

// With configuration
def agent = AgentFactory.builder([count: 0])
    .maxQueueSize(1000)
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

// Get snapshot
def snapshot = agent.getValue()
println snapshot.count

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
Creates an agent with default settings.

**Example:**
```groovy
def agent = AgentFactory.create([count: 0])
```

#### AgentFactory.builder()
```groovy
Builder<T> builder(T initialValue)
```
Returns a builder for custom configuration.

**Builder Methods:**
- `pool(ExecutorPool)` - Use specific pool
- `poolOwned(ExecutorPool)` - Use pool and own it (shutdown with agent)
- `copyStrategy(Supplier<T>)` - Custom snapshot copy strategy
- `maxQueueSize(int)` - Set queue size limit
- `onError(Closure)` - Set error handler

**Example:**
```groovy
def agent = AgentFactory.builder([count: 0])
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

#### getValue()
```groovy
T getValue()
```
Returns an immutable defensive snapshot of the current state.

**Example:**
```groovy
def snapshot = agent.getValue()
snapshot.count = 999  // Doesn't affect agent's internal state

def snapshot2 = agent.getValue()
assert snapshot2.count != 999  // Still has original value
```

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
    log.error("Task failed: ${e.message}", e)
    metrics.recordError(e)
})
```

#### getErrors()
```groovy
List<Map<String, Object>> getErrors(int maxCount = Integer.MAX_VALUE)
```
Returns recent errors (up to last 100).

**Error Map Structure:**
```groovy
[
    timestamp: Long,
    errorType: String,
    message: String,
    stackTrace: List<String>
]
```

**Example:**
```groovy
def errors = agent.getErrors(10)
errors.each { error ->
    println "${error.timestamp}: ${error.errorType} - ${error.message}"
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
    status: String,              // HEALTHY, DEGRADED, SHUTTING_DOWN
    shuttingDown: boolean,
    terminated: boolean,
    processing: boolean,
    queueSize: int,
    maxQueueSize: int,
    queueUtilization: double,    // Percentage (0-100)
    recentErrorCount: int,
    timestamp: long
]
```

**Example:**
```groovy
def health = agent.health()
if (health.status == "DEGRADED") {
    alert("Agent queue at ${health.queueUtilization}%")
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
    tasksSubmitted: long,
    tasksCompleted: long,
    tasksPending: long,
    tasksErrored: long,
    queueRejections: long,
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
Throughput: ${metrics.throughputPerSec} tasks/sec
Error Rate: ${metrics.errorRatePercent}%
Pending: ${metrics.tasksPending}
"""
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
```

#### AgentPoolFactory.builder()
```groovy
Builder<T> builder(int size, Closure<T> stateFactory)
```

**Builder Methods:**
- `sharedPool(ExecutorPool)` - Use shared pool for all agents
- `copyStrategy(Supplier<T>)` - Custom copy strategy for all agents
- `maxQueueSize(int)` - Set queue limit for all agents
- `onError(Closure)` - Set error handler for all agents

**Example:**
```groovy
def pool = AgentPoolFactory.builder(4) { [count: 0] }
    .maxQueueSize(500)
    .onError({ e -> log.error("Pool task error", e) })
    .build()
```

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
Dispatches task to next agent (round-robin).

**Example:**
```groovy
pool.send { count++ }
pool >> { items << count }  // Operator alias
```

#### sendAndGet() / sync()
```groovy
<R> R sendAndGet(Closure<R> action, long timeoutSeconds = 0)
<R> R sync(Closure<R> action, long timeoutSeconds = 0)
```
Dispatches task synchronously to next agent.

**Example:**
```groovy
def result = pool.sendAndGet({ count }, 0)
def result = pool << { count }  // Operator alias
```

#### broadcast()
```groovy
AgentPool<T> broadcast(Closure action)
```
Sends task to all agents in the pool.

**Example:**
```groovy
pool.broadcast { count = 0 }  // Reset all agents
```

#### broadcastAndGet()
```groovy
List<Object> broadcastAndGet(Closure action, long timeoutSeconds = 0)
```
Broadcasts task to all agents and waits for all results.

**Example:**
```groovy
def results = pool.broadcastAndGet({ count * 2 }, 10)
results.each { println it }
```

### State Access

#### getAllValues()
```groovy
List<T> getAllValues()
```
Returns snapshots from all agents.

**Example:**
```groovy
def values = pool.getAllValues()
values.each { state ->
    println "Agent count: ${state.count}"
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
    println "Agent $agentIndex has ${errors.size()} errors"
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
    agentHealths: List<Map>,          // Health from each agent
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
    agentMetrics: List<Map>,          // Metrics from each agent
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
```

---

## Error Management

### Error Handling Behavior

#### Fire-and-Forget (send/async)
If an exception occurs in a `send()` closure:
1. Error is logged via SLF4J
2. Error is tracked in error history
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

### Error Handler Pattern

```groovy
def agent = AgentFactory.builder([count: 0])
    .onError({ e ->
        // Log
        log.error("Task failed", e)
        
        // Record metrics
        errorCounter.increment()
        
        // Alert if critical
        if (e instanceof CriticalException) {
            alertService.notify(e)
        }
    })
    .build()
```

### Error Inspection

```groovy
// Check for errors periodically
def errors = agent.getErrors(10)
if (!errors.isEmpty()) {
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
// Health check
def performHealthCheck = {
    def health = agent.health()
    
    if (health.status == "DEGRADED") {
        log.warn("Agent degraded: queue at ${health.queueUtilization}%")
    }
    
    if (health.recentErrorCount > 10) {
        log.warn("High error count: ${health.recentErrorCount}")
    }
}

// Metrics collection
def collectMetrics = {
    def metrics = agent.metrics()
    
    metricsCollector.record([
        'agent.throughput': metrics.throughputPerSec,
        'agent.error_rate': metrics.errorRatePercent,
        'agent.queue_depth': metrics.queueDepth,
        'agent.pending_tasks': metrics.tasksPending
    ])
    
    // Alert on high error rate
    if (metrics.errorRatePercent > 5.0) {
        alertService.notify("High agent error rate: ${metrics.errorRatePercent}%")
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
    .copyStrategy({ ->
        // Return a cheap, immutable view or DTO
        new StateSnapshot(
            count: state.count,
            summary: state.computeSummary()
        )
    })
    .build()
```

### Chaining Operations

```groovy
agent.send { count++ }
     .send { items << count }
     .send { lastUpdated = System.currentTimeMillis() }
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
pool.getAgent(0).send { /* special task for agent 0 */ }
```

### Pool Aggregation

```groovy
// Collect results from all agents
def totals = pool.broadcastAndGet({ count }, 10)
def grandTotal = totals.sum()

println "Total across all agents: $grandTotal"
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

### 3. Queue Management

Monitor queue depth:
```groovy
def metrics = agent.metrics()
if (metrics.queueDepth > 1000) {
    log.warn("Large queue, consider backpressure")
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

### 5. Benchmarking Results

Typical performance (varies by system):
- Single agent: 5,000-10,000 tasks/sec
- Pool of 4: 15,000-30,000 tasks/sec
- Pool of 8: 25,000-50,000 tasks/sec

Deep copy overhead:
- Simple map (10 fields): <1ms
- Complex nested (50 objects): 10-20ms

---

## Thread Safety

### Thread-Safe Operations
- `send()`, `sendAndGet()` - Safe to call from multiple threads
- `getValue()` - Safe, returns isolated snapshot
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

---

## Examples

### Example 1: Counter Service

```groovy
class CounterService {
    private final Agent<Map> agent
    
    CounterService() {
        agent = AgentFactory.builder([counters: [:]])
            .maxQueueSize(10000)
            .onError({ e -> log.error("Counter error", e) })
            .build()
    }
    
    void increment(String name) {
        agent.send {
            counters[name] = (counters[name] ?: 0) + 1
        }
    }
    
    Map<String, Integer> getCounters() {
        agent.getValue().counters
    }
    
    void reset() {
        agent.broadcast { counters.clear() }
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
            errorRate: poolMetrics.avgErrorRatePercent
        ]
    }
}
```

### Example 3: Rate Limiter

```groovy
class RateLimiter {
    private final Agent<Map> agent
    
    RateLimiter(int maxRequests, long windowMs) {
        agent = AgentFactory.create([
            requests: [],
            maxRequests: maxRequests,
            windowMs: windowMs
        ])
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
}
```

---

## Summary

### Agent
- **Purpose**: Thread-safe wrapper for mutable state
- **Pattern**: Message-driven sequential updates
- **Best For**: Single-threaded state management with concurrent access
- **Key Methods**: `send()`, `sendAndGet()`, `getValue()`, `shutdown()`

### AgentPool
- **Purpose**: Load distribution across multiple agents
- **Pattern**: Round-robin dispatch and broadcast
- **Best For**: Scaling agent processing horizontally
- **Key Methods**: `send()`, `broadcast()`, `getAllValues()`, `metrics()`

### Key Features
✅ Sequential execution guarantees  
✅ Virtual thread support  
✅ Defensive snapshots  
✅ Error management and tracking  
✅ Health and metrics observability  
✅ Queue overflow protection  
✅ Graceful shutdown with timeout  
✅ Production-ready with comprehensive testing