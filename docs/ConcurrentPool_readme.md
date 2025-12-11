# ConcurrentPool – Virtual-Thread-First Executor Architecture

**ConcurrentPool is the cornerstone** of the GroovyConcurrentUtils project, providing a modern, production-ready abstraction over Java's executor services with first-class support for virtual threads, dependency injection, and comprehensive testing capabilities.

---

## 📋 Table of Contents

- [Architecture Overview](#architecture-overview)
- [Core Design Principles](#core-design-principles)
- [Component Architecture](#component-architecture)
- [Virtual Threads First](#virtual-threads-first)
- [Ownership & Lifecycle](#ownership--lifecycle)
- [ExecutorPool Interface](#executorpool-interface)
- [ExecutorPoolFactory](#executorpoolfactory)
- [Construction Patterns](#construction-patterns)
- [API Reference](#api-reference)
- [Testing & Mocking](#testing--mocking)
- [Usage Patterns](#usage-patterns)
- [Thread Safety Guarantees](#thread-safety-guarantees)
- [Performance Characteristics](#performance-characteristics)
- [Best Practices](#best-practices)

---

## Architecture Overview

### The Big Picture

```
┌─────────────────────────────────────────────────────────────┐
│                    Application Code                          │
│  (Promises, DataflowFactory, TaskGraph, Custom Services)    │
└───────────────────┬─────────────────────────────────────────┘
                    │
                    │ depends on interface
                    ▼
┌─────────────────────────────────────────────────────────────┐
│                  ExecutorPool Interface                      │
│     (Single contract for execution & scheduling)            │
└───────────────────┬─────────────────────────────────────────┘
                    │
                    │ implemented by
                    ▼
┌─────────────────────────────────────────────────────────────┐
│                    ConcurrentPool                            │
│              (Virtual-thread-first implementation)           │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  Shared Resources (Static, JVM-wide)                 │  │
│  │  • sharedVirtualThreadExecutor                       │  │
│  │  • sharedScheduledExecutor                           │  │
│  │  • virtualThreadsAvailable flag                      │  │
│  └──────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  Instance Resources                                   │  │
│  │  • executor (may reference shared or own private)    │  │
│  │  • scheduledExecutor (usually shared)                │  │
│  │  • ownsExecutor flag                                 │  │
│  │  • closed state                                      │  │
│  └──────────────────────────────────────────────────────┘  │
└───────────────────┬─────────────────────────────────────────┘
                    │
                    │ created by
                    ▼
┌─────────────────────────────────────────────────────────────┐
│              ExecutorPoolFactory                             │
│         (Builder pattern for flexible construction)          │
└─────────────────────────────────────────────────────────────┘
                    │
                    │ delegates to
                    ▼
┌─────────────────────────────────────────────────────────────┐
│            Java ExecutorService & Variants                   │
│  • VirtualThreadPerTaskExecutor (Java 21+)                  │
│  • CachedThreadPool (fallback)                              │
│  • FixedThreadPool (user-requested)                         │
│  • ScheduledThreadPoolExecutor                              │
│  • Custom user-provided executors                           │
└─────────────────────────────────────────────────────────────┘
```

### Key Architectural Decisions

1. **Interface-Based Design**: All code depends on `ExecutorPool`, not `ConcurrentPool` directly
2. **Shared by Default**: Virtual thread executor is shared across all instances (when available)
3. **Ownership Tracking**: Clear distinction between shared and owned executors
4. **Factory Pattern**: `ExecutorPoolFactory` enables flexible construction without coupling
5. **CompletableFuture API**: Modern async programming with full composition support

---

## Core Design Principles

### 1. Virtual Threads First 🚀

**Philosophy**: Virtual threads are cheap, abundant, and the future of Java concurrency.

```
When Java 21+ is available:
┌────────────────┐
│ new            │
│ ConcurrentPool │
│   ()           │
└───────┬────────┘
        │
        ▼
Uses sharedVirtualThreadExecutor
        │
        ▼
Millions of virtual threads possible
Zero pool size tuning needed
Ideal for I/O-bound workloads
```

**Rationale:**
- Virtual threads are so lightweight that sharing one executor across the entire application is optimal
- No need for multiple executor instances or complex sizing calculations
- Automatically scales to workload demands
- Reduces resource contention and context switching

### 2. Graceful Degradation

```
Check Java version
        │
        ├─→ Java 21+? → Use virtual threads (shared)
        │
        └─→ Java < 21? → Create cached thread pool (owned)
                         Still works, just less optimal
```

### 3. Explicit Control When Needed

```groovy
// Default: Virtual threads (shared, unbound concurrency)
def pool1 = new ConcurrentPool()

// Explicit: Platform threads (owned, bounded concurrency)
def pool2 = new ConcurrentPool(4)  // Max 4 concurrent tasks

// Custom: User-provided executor (not owned)
def pool3 = new ConcurrentPool(myExecutor)
```

**Use bounded pools when:**
- Rate limiting external APIs (e.g., max 10 concurrent requests)
- Resource protection (e.g., connection pool limits)
- CPU-intensive workloads (e.g., fixed to CPU count)
- Legacy integration requiring platform threads

### 4. Ownership Semantics

```
┌────────────────────────┐
│ Pool Instance          │
│                        │
│ ownsExecutor?          │
│    YES → shutdown()    │
│          shuts it down │
│                        │
│    NO  → shutdown()    │
│          leaves it     │
│          running       │
└────────────────────────┘
```

**Rules:**
- Shared virtual thread executor: **Never owned** (lives for JVM lifetime)
- Created fixed pool: **Owned** (shut down with instance)
- External executor: **Never owned** (caller manages lifecycle)

### 5. Closed State Enforcement

```
┌─────────────────┐
│ Pool Active     │
│ closed = false  │
└────────┬────────┘
         │
         │ pool.shutdown()
         │
         ▼
┌─────────────────┐
│ Pool Closed     │
│ closed = true   │
└────────┬────────┘
         │
         │ pool.execute { ... }
         │
         ▼
    Throws IllegalStateException
    (No tasks accepted after shutdown)
```

---

## Component Architecture

### ExecutorPool Interface

**Location:** `org.softwood.pool.ExecutorPool`

**Purpose:** Single contract for all pool operations, enabling:
- Dependency injection
- Easy mocking in tests
- Decoupling from implementation
- Future alternative implementations

```groovy
interface ExecutorPool {
    // Execution
    CompletableFuture execute(Closure task)
    CompletableFuture execute(Callable task)
    CompletableFuture execute(Runnable task)
    boolean tryExecute(Closure task)
    
    // Scheduling
    ScheduledFuture scheduleExecution(int delay, TimeUnit unit, Closure task)
    ScheduledFuture scheduleWithFixedDelay(int initial, int delay, TimeUnit unit, Closure task)
    ScheduledFuture scheduleAtFixedRate(int initial, int period, TimeUnit unit, Closure task)
    
    // Access to underlying executors
    ExecutorService getExecutor()
    ScheduledExecutorService getScheduledExecutor()
    
    // State
    boolean isClosed()
    String getName()
    boolean isUsingVirtualThreads()
    void shutdown()
}
```

**Design Benefits:**
- All application code depends on `ExecutorPool`, not concrete class
- Tests can inject mocks without changing production code
- Alternative implementations possible (e.g., reactive streams adapter)

### ConcurrentPool Implementation

**Location:** `org.softwood.pool.ConcurrentPool`

**Responsibilities:**
1. Manage shared application-wide virtual thread executor
2. Manage shared scheduled executor for delayed/periodic tasks
3. Track executor ownership per instance
4. Enforce closed state after shutdown
5. Convert `Future` to `CompletableFuture` for modern async patterns
6. Provide Groovy-friendly closure-based API

**Key Fields:**

```groovy
class ConcurrentPool implements ExecutorPool {
    // Shared across ALL instances (static)
    private static ExecutorService sharedVirtualThreadExecutor
    private static ScheduledExecutorService sharedScheduledExecutor
    private static AtomicBoolean virtualThreadsAvailable
    
    // Per-instance
    private ExecutorService executor
    private ScheduledExecutorService scheduledExecutor
    private boolean ownsExecutor
    private boolean ownsScheduledExecutor
    private AtomicBoolean closed
    private String name
}
```

### ExecutorPoolFactory

**Location:** `org.softwood.pool.ExecutorPoolFactory`

**Purpose:** Builder pattern for flexible pool construction without coupling to ConcurrentPool directly.

```groovy
// Simple wrapping
ExecutorPool pool = ExecutorPoolFactory.wrap(myExecutor)

// Builder pattern
ExecutorPool pool = ExecutorPoolFactory.builder()
    .executor(myExecutor)
    .scheduler(myScheduler)
    .name("api-pool")
    .build()
```

**Benefits:**
- Hides ConcurrentPool construction details
- Enables future alternative implementations
- Cleaner API for complex configurations
- Better for dependency injection frameworks

---

## Virtual Threads First

### Why Virtual Threads?

**Traditional Platform Threads:**
```
┌──────────────────┐
│ Platform Thread  │  → OS thread (expensive)
│   1 MB stack     │  → Limited by memory
│   OS scheduler   │  → Context switch overhead
└──────────────────┘

Typical limits: ~1000-5000 threads per JVM
```

**Virtual Threads (Java 21+):**
```
┌──────────────────┐
│ Virtual Thread   │  → JVM-managed (cheap)
│   ~1 KB stack    │  → Millions possible
│   JVM scheduler  │  → Minimal context switching
└──────────────────┘

Practical limits: Millions of threads per JVM
```

### Virtual Thread Execution Model

```
Application submits task
        │
        ▼
┌────────────────────────────┐
│ sharedVirtualThreadExecutor│
│ (JVM-wide singleton)       │
└───────────┬────────────────┘
            │
            │ Creates virtual thread on-demand
            ▼
┌────────────────────────────┐
│ Virtual Thread             │
│ • Runs on carrier thread   │
│ • Auto-parks on I/O wait   │
│ • Releases carrier thread  │
│ • Resumed when I/O ready   │
└────────────────────────────┘
```

**Key Advantages:**
1. **No pool sizing needed** - Create threads on-demand, unlimited
2. **Automatic I/O optimization** - Virtual threads park during blocking operations
3. **Memory efficient** - 1000x smaller stack footprint
4. **Application-wide sharing** - One executor for all code that wants virtual threads

### When Virtual Threads Are Used

```groovy
// Default constructor → uses virtual threads (Java 21+)
def pool = new ConcurrentPool()
assert pool.isUsingVirtualThreads() == true  // Java 21+
assert pool.poolSize == -1  // No fixed size

// Explicit platform threads → uses fixed pool
def platformPool = new ConcurrentPool(4)
assert platformPool.isUsingVirtualThreads() == false
assert platformPool.poolSize == 4
```

### Fallback Behavior (Java < 21)

```
┌────────────────────────────┐
│ Java 20 or earlier detected│
└───────────┬────────────────┘
            │
            ▼
┌────────────────────────────┐
│ Create cached thread pool  │
│ (platform threads)         │
│ • Reuses idle threads      │
│ • Creates on-demand        │
│ • Still effective          │
└────────────────────────────┘
```

### Scheduling and Virtual Threads

**Important:** Scheduled tasks use **platform threads** for the scheduling mechanism:

```
scheduleExecution(100, MILLISECONDS) { task() }
        │
        ▼
┌────────────────────────────┐
│ sharedScheduledExecutor    │  ← Platform threads
│ (Manages timing)           │  ← Required for scheduling
└───────────┬────────────────┘
            │
            │ After delay, submits to:
            ▼
┌────────────────────────────┐
│ sharedVirtualThreadExecutor│  ← Virtual thread
│ (Runs actual task)         │  ← If using default pool
└────────────────────────────┘
```

**Rationale:** Scheduling requires persistent threads that can track time and wake up tasks. Virtual threads are unsuitable for this. However, the *actual task* still runs on a virtual thread.

---

## Ownership & Lifecycle

### Ownership Matrix

| Pool Creation | Executor Type | Owned by Instance? | Shutdown Behavior |
|---------------|---------------|-------------------|-------------------|
| `new ConcurrentPool()` (Java 21+) | Shared virtual | ❌ No | Stays running |
| `new ConcurrentPool()` (Java < 21) | Cached platform | ✅ Yes | Shuts down |
| `new ConcurrentPool(4)` | Fixed platform | ✅ Yes | Shuts down |
| `new ConcurrentPool(myExecutor)` | User-provided | ❌ No | Stays running |

### Lifecycle State Machine

```
┌────────────┐
│   CREATED  │
│ closed=false│
└─────┬──────┘
      │
      │ execute() → works
      │ scheduleExecution() → works
      │
      ▼
┌────────────┐
│   ACTIVE   │
│ closed=false│
└─────┬──────┘
      │
      │ pool.shutdown() or pool.shutdownNow()
      │
      ▼
┌────────────┐
│   CLOSED   │
│ closed=true │
└─────┬──────┘
      │
      │ execute() → throws IllegalStateException
      │ scheduleExecution() → throws IllegalStateException
      │
      │ If owned executor:
      │   executor.shutdown() called
      │   executor.isShutdown() → true
      │
      └→ Instance logically dead (cannot be reopened)
```

### Shutdown Methods

#### `shutdown()` - Graceful Shutdown

```groovy
def pool = new ConcurrentPool(4)  // Owns executor

// Submit tasks
pool.execute { task1() }
pool.execute { task2() }

// Initiate shutdown
pool.shutdown()

// Effects:
// 1. closed = true (no new tasks accepted)
// 2. executor.shutdown() called (owned executors only)
// 3. Existing tasks complete normally
// 4. executor.isTerminated() eventually becomes true

// Wait for completion
if (pool.awaitTermination(10, TimeUnit.SECONDS)) {
    println "All tasks completed"
}
```

#### `shutdownNow()` - Immediate Shutdown

```groovy
def pool = new ConcurrentPool(4)

// Submit tasks
(1..100).each { pool.execute { longRunningTask(it) } }

// Force immediate shutdown
List<Runnable> pendingTasks = pool.shutdownNow()

println "Cancelled ${pendingTasks.size()} pending tasks"
// Active tasks are interrupted (if they respect interruption)
```

#### `shutdownSharedExecutors()` - Now a No-Op

```groovy
ConcurrentPool.shutdownSharedExecutors()
// Logs warning: "shutdownSharedExecutors() is now a no-op"
// Does NOT shut down shared executors
```

**Design Decision:** Shared executors are JVM-wide resources that may be used by arbitrary code. Shutting them down globally can cause cascading failures. Instead, they live for the JVM lifetime.

**Legacy Compatibility:** Method preserved but made harmless for backward compatibility.

---

## Construction Patterns

### Pattern 1: Default (Virtual Threads)

```groovy
// Simplest and recommended for most cases
def pool = new ConcurrentPool()

// With name for debugging
def pool = new ConcurrentPool("api-worker-pool")

// Characteristics:
// - Uses shared virtual thread executor (Java 21+)
// - Falls back to cached thread pool (Java < 21)
// - Does NOT own the executor
// - Lazily uses shared scheduled executor
// - shutdown() doesn't shut down the executor
```

**Best for:**
- I/O-bound workloads (HTTP, database, file operations)
- High-concurrency scenarios (1000s of concurrent tasks)
- General-purpose concurrent execution
- Microservices and web applications

### Pattern 2: Bounded Platform Threads

```groovy
// Explicit platform thread pool with fixed size
def pool = new ConcurrentPool(8)

// Characteristics:
// - Creates FixedThreadPool(8)
// - Owns the executor
// - Max 8 concurrent tasks
// - shutdown() shuts down the executor
// - Tasks beyond 8 queue up
```

**Best for:**
- Rate-limited external APIs
- Resource protection (connection pools, file handles)
- CPU-intensive workloads
- Predictable resource consumption

### Pattern 3: Custom Executor

```groovy
// Bring your own executor
def myExecutor = Executors.newSingleThreadExecutor()
def pool = new ConcurrentPool(myExecutor)

// With custom scheduler too
def myScheduler = Executors.newScheduledThreadPool(2)
def pool = new ConcurrentPool(myExecutor, myScheduler)

// Characteristics:
// - Uses your executor
// - Does NOT own the executor
// - Caller responsible for lifecycle
```

**Best for:**
- Integration with existing executor infrastructure
- Testing with mock executors
- Advanced configuration (custom ThreadFactory, RejectedExecutionHandler)

### Pattern 4: Factory Builder

```groovy
// Most flexible, hides implementation details
ExecutorPool pool = ExecutorPoolFactory.builder()
    .executor(customExecutor)
    .scheduler(customScheduler)
    .name("payment-processor")
    .build()

// Or simple wrapping
ExecutorPool pool = ExecutorPoolFactory.wrap(myExecutor)
```

**Best for:**
- Dependency injection scenarios
- Configuration-driven construction
- Decoupling from ConcurrentPool directly
- Framework integration

---

## API Reference

### Execution Methods

#### `execute(Closure task)` → CompletableFuture

```groovy
def pool = new ConcurrentPool()

CompletableFuture<String> future = pool.execute {
    // Runs on virtual thread or pool thread
    performWork()
    return "result"
}

String result = future.get(5, TimeUnit.SECONDS)
```

**Throws:** `IllegalStateException` if pool is closed

#### `execute(Callable<T> task)` → CompletableFuture<T>

```groovy
Callable<Integer> task = { -> 
    42 * 2 
}

CompletableFuture<Integer> future = pool.execute(task)
assert future.get() == 84
```

**Use case:** Java interop, when you have Callable objects

#### `execute(Runnable task)` → CompletableFuture<Void>

```groovy
Runnable task = { println "Hello from runnable" }

CompletableFuture<Void> future = pool.execute(task)
future.get()  // Waits for completion, returns null
```

**Use case:** Fire-and-forget tasks, Java interop

#### `execute(Closure task, Object[] args)` → CompletableFuture

```groovy
CompletableFuture future = pool.execute(
    { String name, int value ->
        "Processing $name with $value"
    },
    ["test", 42] as Object[]
)

println future.get()  // "Processing test with 42"
```

**Use case:** When you have a parameterized closure and arguments separately

#### `tryExecute(Closure task)` → boolean

```groovy
boolean accepted = pool.tryExecute {
    performTask()
}

if (accepted) {
    println "Task submitted"
} else {
    println "Pool is closed or rejected task"
}
```

**Use case:** Non-throwing variant, graceful degradation

### Scheduling Methods

#### `scheduleExecution(int delay, TimeUnit unit, Closure task)` → ScheduledFuture

```groovy
ScheduledFuture future = pool.scheduleExecution(500, TimeUnit.MILLISECONDS) {
    println "Executed after 500ms"
    return "delayed result"
}

def result = future.get()
```

**Behavior:**
- Executes task once after delay
- Returns immediately with ScheduledFuture
- Can cancel with `future.cancel(false)`

#### `scheduleWithFixedDelay(int initial, int delay, TimeUnit unit, Closure task)` → ScheduledFuture

```groovy
ScheduledFuture repeating = pool.scheduleWithFixedDelay(1, 5, TimeUnit.SECONDS) {
    println "Runs 5 seconds after previous execution completes"
}

// Cancel after some time
Thread.sleep(30_000)
repeating.cancel(false)
```

**Behavior:**
- First execution: after `initial` delay
- Subsequent executions: `delay` time after previous task completes
- Fixed delay between task completions

#### `scheduleAtFixedRate(int initial, int period, TimeUnit unit, Closure task)` → ScheduledFuture

```groovy
ScheduledFuture repeating = pool.scheduleAtFixedRate(0, 10, TimeUnit.SECONDS) {
    println "Runs every 10 seconds (fixed rate)"
}

repeating.cancel(false)
```

**Behavior:**
- First execution: after `initial` delay
- Subsequent executions: every `period` time units
- If task takes longer than period, next execution starts immediately

#### `tryScheduleExecution(int delay, TimeUnit unit, Closure task)` → boolean

```groovy
boolean scheduled = pool.tryScheduleExecution(1, TimeUnit.SECONDS) {
    performDelayedTask()
}

if (!scheduled) {
    println "Failed to schedule (pool closed?)"
}
```

### DSL Method

#### `withPool(ExecutorService executor = null, Closure work)` → CompletableFuture

```groovy
def pool = new ConcurrentPool()

CompletableFuture result = pool.withPool {
    // Delegate is the pool
    execute { task1() }
    execute { task2() }
    
    scheduleExecution(100, TimeUnit.MILLISECONDS) {
        task3()
    }
    
    "block completed"
}

println result.get()  // "block completed"
```

**Optional executor override:**
```groovy
def customExec = Executors.newFixedThreadPool(2)

pool.withPool(customExec) {
    // Tasks in this block use customExec
    execute { limitedConcurrencyTask() }
}
```

### Accessor Methods

#### `getExecutor()` → ExecutorService

```groovy
ExecutorService exec = pool.executor

// Use directly if needed
exec.submit { println "Direct submission" }
```

**Use case:** When you need the raw executor for advanced operations or integration with libraries that expect ExecutorService

#### `getScheduledExecutor()` → ScheduledExecutorService

```groovy
ScheduledExecutorService sched = pool.scheduledExecutor

// Use directly
sched.schedule({ -> println "Scheduled" }, 1, TimeUnit.SECONDS)
```

### State Methods

#### `isClosed()` → boolean

```groovy
assert !pool.isClosed()

pool.shutdown()

assert pool.isClosed()

try {
    pool.execute { "won't work" }
} catch (IllegalStateException e) {
    println "Pool is closed!"
}
```

#### `isShutdown()` → boolean

```groovy
def pool = new ConcurrentPool(4)  // Owns executor

assert !pool.isShutdown()

pool.shutdown()

assert pool.isShutdown()
```

**Note:** For pools that don't own their executor, returns `closed` state instead.

#### `isUsingVirtualThreads()` → boolean

```groovy
def pool = new ConcurrentPool()
println "Virtual threads: ${pool.isUsingVirtualThreads()}"  // true on Java 21+

def platformPool = new ConcurrentPool(4)
println "Virtual threads: ${platformPool.isUsingVirtualThreads()}"  // false
```

#### `getName()` → String

```groovy
def pool = new ConcurrentPool("worker-pool")
assert pool.name == "worker-pool"
```

#### `getPoolSize()` → int

```groovy
def fixed = new ConcurrentPool(8)
assert fixed.poolSize == 8

def virtual = new ConcurrentPool()
assert virtual.poolSize == -1  // No fixed size
```


### Enhanced Observability & Reliability Methods

#### `isHealthy()` → boolean

**Health check method for monitoring and load balancing.**

```groovy
// Check if pool can accept work
if (pool.isHealthy()) {
    pool.execute { task() }
} else {
    log.warn("Pool unhealthy, using fallback")
    fallbackPool.execute { task() }
}
```

**Returns `false` if:**
- Pool has been shut down (`isClosed() == true`)
- Owned executor has been shut down externally

**Returns `true` if:**
- Pool is active and accepting tasks

**Use cases:**
- Health check endpoints for load balancers
- Monitoring dashboards
- Automatic failover to backup pools
- Circuit breaker patterns

**Example - Health Check Endpoint:**
```groovy
@Get("/health")
def healthCheck() {
    return [
        status: pool.isHealthy() ? "UP" : "DOWN",
        poolName: pool.name,
        closed: pool.isClosed()
    ]
}
```

#### `getMetrics()` → Map<String, Object>

**Comprehensive metrics for production monitoring and debugging.**

```groovy
def metrics = pool.metrics

println "Pool: ${metrics.name}"
println "Healthy: ${metrics.healthy}"
println "Active tasks: ${metrics.activeCount}"
println "Queue size: ${metrics.queueSize}"
```

**Returned Map Structure:**

| Key | Type | Description | Available For |
|-----|------|-------------|---------------|
| `name` | String | Pool name or "unnamed" | All pools |
| `closed` | boolean | Whether shutdown() was called | All pools |
| `healthy` | boolean | Result of isHealthy() | All pools |
| `usingVirtualThreads` | boolean | true if using virtual threads | All pools |
| `ownsExecutor` | boolean | true if pool owns executor | All pools |
| `poolSize` | int | Fixed pool size, or -1 | All pools |
| `activeCount` | int | Currently executing tasks | ThreadPoolExecutor only |
| `taskCount` | long | Total tasks submitted | ThreadPoolExecutor only |
| `completedTaskCount` | long | Completed tasks | ThreadPoolExecutor only |
| `queueSize` | int | Tasks waiting in queue | ThreadPoolExecutor only |
| `largestPoolSize` | int | Max concurrent threads used | ThreadPoolExecutor only |
| `corePoolSize` | int | Core pool size | ThreadPoolExecutor only |
| `maximumPoolSize` | int | Maximum pool size | ThreadPoolExecutor only |

**Use cases:**
- Production monitoring (Prometheus, Grafana)
- Capacity planning
- Performance debugging
- Alerting (queue backup, pool saturation)
- Auto-scaling decisions

**Example - Monitoring Integration:**
```groovy
// Expose metrics to Prometheus
def poolMetrics = pool.metrics

if (metrics.queueSize > 1000) {
    alerting.send("Pool queue backing up: ${metrics.queueSize} tasks")
}

if (metrics.activeCount == metrics.maximumPoolSize) {
    alerting.send("Pool at maximum capacity!")
}

// Capacity planning
def utilizationPct = (metrics.activeCount / metrics.maximumPoolSize) * 100
log.info("Pool utilization: ${utilizationPct}%")
```

**Example - Auto-scaling:**
```groovy
def metrics = pool.metrics

if (metrics.queueSize > 100 && metrics.activeCount >= metrics.corePoolSize * 0.8) {
    log.info("High load detected, scaling up")
    scaleUpWorkers()
}
```

**Example - Health Dashboard:**
```groovy
@Get("/admin/pools/status")
def poolStatus() {
    return pools.collect { name, pool ->
        [
            name: name,
            metrics: pool.metrics,
            timestamp: System.currentTimeMillis()
        ]
    }
}
```

#### `tryExecute(Callable<T> task)` → boolean

**Non-throwing variant for Callable tasks (Java interop).**

```groovy
Callable<Integer> task = { -> computeValue() }

if (pool.tryExecute(task)) {
    println "Task submitted"
} else {
    println "Pool closed or rejected task"
}
```

**Use case:** Graceful degradation with Callable objects

#### `tryExecute(Runnable task)` → boolean

**Non-throwing variant for Runnable tasks (Java interop).**

```groovy
Runnable task = { processData() }

if (pool.tryExecute(task)) {
    println "Task submitted"  
} else {
    handleRejection()
}
```

**Use case:** Graceful degradation with Runnable objects

**Example - Graceful Degradation Pattern:**
```groovy
// Try primary pool first
if (!primaryPool.tryExecute(task)) {
    log.warn("Primary pool unavailable, trying backup")
    
    // Try backup pool
    if (!backupPool.tryExecute(task)) {
        log.error("All pools unavailable, queuing for later")
        persistForRetry(task)
    }
}
```

---

## Testing & Mocking

### Why Testability Matters

The architecture enables comprehensive testing without production code changes:

1. **Interface-based**: Code depends on `ExecutorPool`, not concrete class
2. **Dependency injection**: Pools can be injected into services
3. **Mock executors**: Custom executors for controlled testing
4. **Factory pattern**: Flexible construction without coupling

### Testing Pattern 1: Mock ExecutorPool

```groovy
import spock.lang.Specification

class MyServiceSpec extends Specification {
    
    def "service processes tasks concurrently"() {
        given: "a mock pool"
        ExecutorPool mockPool = Mock()
        
        and: "our service"
        MyService service = new MyService(mockPool)
        
        when: "service processes data"
        service.processData([1, 2, 3])
        
        then: "verify pool was used correctly"
        3 * mockPool.execute(_ as Closure) >> CompletableFuture.completedFuture("done")
    }
}
```

### Testing Pattern 2: Controlled Executor

```groovy
def "test with single-threaded executor for deterministic behavior"() {
    given: "single-threaded executor"
    def testExecutor = Executors.newSingleThreadExecutor()
    
    and: "pool using test executor"
    ExecutorPool pool = new ConcurrentPool(testExecutor)
    
    when: "submit tasks"
    def results = []
    pool.execute { results << "first" }.get()
    pool.execute { results << "second" }.get()
    
    then: "tasks executed in order"
    results == ["first", "second"]
    
    cleanup:
    testExecutor.shutdown()
}
```

### Testing Pattern 3: Verify Shutdown Behavior

```groovy
def "pool rejects tasks after shutdown"() {
    given:
    ExecutorPool pool = new ConcurrentPool(2)
    
    when: "shutdown is called"
    pool.shutdown()
    
    then: "pool is closed"
    pool.isClosed()
    
    and: "new tasks are rejected"
    when:
    pool.execute { "should fail" }
    
    then:
    thrown(IllegalStateException)
}
```

### Testing Pattern 4: Async Behavior Testing

```groovy
def "CompletableFuture composition works correctly"() {
    given:
    ExecutorPool pool = new ConcurrentPool()
    
    when: "chain async operations"
    def result = pool.execute {
        return 10
    }.thenApply { value ->
        value * 2
    }.thenApply { value ->
        value + 5
    }.get()
    
    then:
    result == 25
    
    cleanup:
    pool.shutdown()
}
```

### Testing Pattern 5: Scheduled Execution

```groovy
def "scheduled tasks execute after delay"() {
    given:
    ExecutorPool pool = new ConcurrentPool()
    def executed = new AtomicBoolean(false)
    
    when: "schedule a task"
    def future = pool.scheduleExecution(100, TimeUnit.MILLISECONDS) {
        executed.set(true)
    }
    
    then: "initially not executed"
    !executed.get()
    
    when: "wait for execution"
    future.get()
    
    then: "task executed"
    executed.get()
    
    cleanup:
    pool.shutdown()
}
```

### Testing Pattern 6: Factory-Based Injection

```groovy
class ServiceFactory {
    ExecutorPool createPool(String name) {
        return ExecutorPoolFactory.builder()
            .name(name)
            .build()
    }
}

class ServiceFactorySpec extends Specification {
    def "factory creates configured pools"() {
        given:
        def factory = new ServiceFactory()
        
        when:
        ExecutorPool pool = factory.createPool("test-pool")
        
        then:
        pool.name == "test-pool"
        pool.isUsingVirtualThreads()  // On Java 21+
        
        cleanup:
        pool.shutdown()
    }
}
```

---

## Usage Patterns

### Pattern 1: Simple Parallel Execution

```groovy
def pool = new ConcurrentPool()

// Process list concurrently
def urls = ['http://a.com', 'http://b.com', 'http://c.com']

def futures = urls.collect { url ->
    pool.execute {
        fetchData(url)
    }
}

// Wait for all results
def results = futures*.get()
println "Fetched ${results.size()} results"

pool.shutdown()
```

### Pattern 2: Bounded Concurrency for External API

```groovy
// Respect API rate limit: max 10 concurrent requests
def apiPool = new ConcurrentPool(10)

(1..1000).each { requestId ->
    apiPool.execute {
        callExternalAPI(requestId)
    }
}

// Max 10 requests execute concurrently
// Remaining 990 queue up
```

### Pattern 3: Background Processing with Scheduling

```groovy
def pool = new ConcurrentPool()

// Check queue every 5 seconds
def queueProcessor = pool.scheduleWithFixedDelay(0, 5, TimeUnit.SECONDS) {
    def messages = pollMessageQueue()
    messages.each { process(it) }
}

// Cleanup job every hour
def cleanup = pool.scheduleAtFixedRate(1, 60, TimeUnit.MINUTES) {
    performCleanup()
}

// Cancel when application shuts down
Runtime.runtime.addShutdownHook {
    queueProcessor.cancel(false)
    cleanup.cancel(false)
    pool.shutdown()
    pool.awaitTermination(30, TimeUnit.SECONDS)
}
```

### Pattern 4: CompletableFuture Pipeline

```groovy
def pool = new ConcurrentPool()

pool.execute {
    fetchUser(userId)
}.thenCompose { user ->
    pool.execute { fetchOrders(user.id) }
}.thenCompose { orders ->
    pool.execute { enrichOrders(orders) }
}.thenAccept { enrichedOrders ->
    saveToDatabase(enrichedOrders)
}.exceptionally { error ->
    log.error("Pipeline failed", error)
    null
}
```

### Pattern 5: Parallel Scatter-Gather

```groovy
def pool = new ConcurrentPool()

// Scatter: Query multiple data sources in parallel
def userFuture = pool.execute { queryUserService(id) }
def orderFuture = pool.execute { queryOrderService(id) }
def inventoryFuture = pool.execute { queryInventoryService(id) }

// Gather: Wait for all results
CompletableFuture.allOf(userFuture, orderFuture, inventoryFuture)
    .thenRun {
        def user = userFuture.get()
        def orders = orderFuture.get()
        def inventory = inventoryFuture.get()
        
        assembleResponse(user, orders, inventory)
    }
```

### Pattern 6: Dependency Injection

```groovy
@CompileStatic
class OrderProcessingService {
    private final ExecutorPool pool
    
    OrderProcessingService(ExecutorPool pool) {
        this.pool = pool
    }
    
    CompletableFuture<Order> processOrder(OrderRequest request) {
        return pool.execute {
            validateOrder(request)
            def order = createOrder(request)
            notifyInventory(order)
            return order
        }
    }
}

// Production: Virtual threads
def productionService = new OrderProcessingService(
    new ConcurrentPool("order-processor")
)

// Testing: Controlled executor
def testService = new OrderProcessingService(
    new ConcurrentPool(Executors.newSingleThreadExecutor())
)
```

### Pattern 7: Graceful Shutdown

```groovy
def pool = new ConcurrentPool()

// Submit work
(1..100).each { i ->
    pool.execute {
        processItem(i)
    }
}

// Graceful shutdown
println "Shutting down..."
pool.shutdown()

// Wait for completion (up to 30 seconds)
if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
    println "Timeout! Force shutdown."
    def pending = pool.shutdownNow()
    println "Cancelled ${pending.size()} pending tasks"
}

println "Shutdown complete"
```

---

## Thread Safety Guarantees

### Guaranteed Thread-Safe Operations

1. **Static initialization**: Happens once, thread-safe via class loader
2. **Closed state**: `AtomicBoolean` ensures atomic reads/writes
3. **Error collection**: `ConcurrentLinkedQueue` for thread-safe error tracking
4. **Multiple threads submitting tasks**: All submission methods are thread-safe
5. **Concurrent shutdown**: Multiple threads can call `shutdown()` safely (idempotent)

### Not Thread-Safe

1. **Modifying `name` after construction**: No synchronization on name field
   ```groovy
   pool.name = "new-name"  // Don't do this from multiple threads
   ```

2. **Reading state during shutdown**: Shutdown is not atomic across all state
   ```groovy
   // Possible race: pool may close between these calls
   if (!pool.isClosed()) {
       pool.execute { ... }  // May throw if shutdown happened in between
   }
   
   // Better: Use tryExecute
   if (!pool.tryExecute { ... }) {
       println "Task rejected"
   }
   ```

### Visibility Guarantees

The `closed` flag is an `AtomicBoolean`, providing:
- **Atomicity**: Changes are indivisible
- **Visibility**: Changes visible to all threads immediately (via volatile semantics)
- **Happens-before**: Setting closed happens-before any subsequent read

---

## Performance Characteristics

### Virtual Thread Pool (Default on Java 21+)

| Metric | Value | Notes |
|--------|-------|-------|
| Task submission overhead | ~1 μs | Extremely fast |
| Memory per thread | ~1 KB | Vs ~1 MB for platform thread |
| Maximum threads | Millions | Limited by heap, not OS |
| Context switch | ~10 ns | JVM-managed, very fast |
| I/O blocking | Zero cost | Virtual thread parks, carrier thread freed |

**Optimal for:**
- High I/O concurrency (1000s-millions of blocking operations)
- Microservices with many concurrent requests
- Database connection pooling
- File I/O operations

### Platform Thread Pool (Fixed Size)

| Metric | Value | Notes |
|--------|-------|-------|
| Task submission overhead | ~5-10 μs | Slightly slower than virtual |
| Memory per thread | ~1 MB | OS thread stack |
| Maximum threads | 100s-1000s | Limited by OS |
| Context switch | ~1-10 μs | OS scheduler |
| Queue overhead | O(1) | LinkedBlockingQueue |

**Optimal for:**
- CPU-intensive workloads
- Bounded concurrency requirements
- Predictable resource usage

### Cached Thread Pool (Fallback on Java < 21)

| Metric | Value | Notes |
|--------|-------|-------|
| Task submission overhead | ~5-10 μs | Creates threads on demand |
| Memory per thread | ~1 MB | OS thread stack |
| Thread reuse | 60s idle timeout | Good for bursty workloads |
| Maximum threads | Unbounded | Can exhaust system resources |

**Optimal for:**
- Moderate concurrency (10s-100s of threads)
- Bursty workloads
- Fallback for Java < 21

### CompletableFuture Bridge Overhead

Each `execute()` call creates two tasks:
1. Original task
2. Bridge task to convert Future → CompletableFuture

**Overhead:** ~1-2 μs per submission

**Impact:**
- Negligible for I/O-bound tasks (ms-s durations)
- Measurable for very fast CPU tasks (μs durations)

**Mitigation:** For ultra-low-latency scenarios, use `getExecutor()` and submit directly:
```groovy
Future future = pool.executor.submit(task)
// Work with raw Future, skip CompletableFuture conversion
```

---


### Metrics & Health Check Overhead

| Operation | Cost | Impact |
|-----------|------|--------|
| `isHealthy()` | O(1) - 2 flag checks | Negligible, < 1 μs |
| `getMetrics()` | O(1) - field reads | < 5 μs for all fields |
| `tryExecute()` | Same as `execute()` | No additional overhead |

**Recommendation:** Call `getMetrics()` at monitoring intervals (seconds/minutes), not in hot paths.

## Best Practices

### ✅ DO

1. **Use default constructor for most cases**
   ```groovy
   def pool = new ConcurrentPool()  // Virtual threads FTW
   ```

2. **Depend on ExecutorPool interface**
   ```groovy
   class MyService {
       private final ExecutorPool pool
       
       MyService(ExecutorPool pool) {  // Not ConcurrentPool
           this.pool = pool
       }
   }
   ```

3. **Name pools for debugging**
   ```groovy
   def pool = new ConcurrentPool("payment-processor")
   ```

4. **Shutdown owned pools properly**
   ```groovy
   def pool = new ConcurrentPool(4)
   try {
       // Use pool
   } finally {
       pool.shutdown()
       pool.awaitTermination(30, TimeUnit.SECONDS)
   }
   ```

5. **Use tryExecute for graceful degradation**
   ```groovy
   if (!pool.tryExecute { task() }) {
       handleRejection()
   }
   ```

6. **Leverage CompletableFuture composition**
   ```groovy
   pool.execute { step1() }
       .thenCompose { pool.execute { step2(it) } }
       .thenAccept { result -> process(result) }
   ```


7. **Monitor pool health in production**
   ```groovy
   // Regular health checks
   scheduler.scheduleAtFixedRate(0, 30, TimeUnit.SECONDS) {
       if (!pool.isHealthy()) {
           alerting.send("Pool ${pool.name} is unhealthy!")
       }
   }
   ```

8. **Collect metrics for capacity planning**
   ```groovy
   // Log metrics periodically
   scheduler.scheduleAtFixedRate(0, 5, TimeUnit.MINUTES) {
       def metrics = pool.metrics
       metricsLogger.info("Pool metrics: ${metrics}")
       
       // Send to monitoring system
       prometheus.gauge("pool_queue_size", metrics.queueSize)
       prometheus.gauge("pool_active_count", metrics.activeCount)
   }
   ```

9. **Use tryExecute for critical paths**
   ```groovy
   // Don't let exceptions crash critical code
   if (!pool.tryExecute { criticalTask() }) {
       // Handle gracefully
       fallbackMechanism()
   }
   ```

### ❌ DON'T

1. **Don't create fixed pools without reason**
   ```groovy
   // Bad: Arbitrary fixed size
   def pool = new ConcurrentPool(10)  // Why 10?
   
   // Good: Virtual threads scale automatically
   def pool = new ConcurrentPool()
   ```

2. **Don't call shutdownSharedExecutors()**
   ```groovy
   // Bad: Breaks other code using shared executor
   ConcurrentPool.shutdownSharedExecutors()
   
   // This is now a no-op anyway (logs warning)
   ```

3. **Don't modify name from multiple threads**
   ```groovy
   pool.name = "new-name"  // Not thread-safe
   ```

4. **Don't catch IllegalStateException and retry**
   ```groovy
   // Bad: If pool is closed, it stays closed
   try {
       pool.execute { task() }
   } catch (IllegalStateException e) {
       pool.execute { task() }  // Still closed!
   }
   
   // Good: Check first or use tryExecute
   if (!pool.isClosed()) {
       pool.execute { task() }
   }
   ```

5. **Don't submit blocking operations to scheduled executor**
   ```groovy
   // Bad: Blocks scheduling thread
   pool.scheduleWithFixedDelay(0, 1, TimeUnit.SECONDS) {
       Thread.sleep(5000)  // Takes 5s, but scheduled every 1s!
   }
   
   // Good: If task takes long, use fixed delay (waits for completion)
   // Or better: Submit long-running work to main executor
   pool.scheduleWithFixedDelay(0, 1, TimeUnit.SECONDS) {
       pool.execute { longRunningWork() }  // Offload to main pool
   }
   ```

6. **Don't ignore shutdown hooks for long-running apps**
   ```groovy
   // Bad: Pools never shut down
   def pool = new ConcurrentPool(10)
   // ... app runs forever
   
   // Good: Register shutdown hook
   Runtime.runtime.addShutdownHook {
       pool.shutdown()
       pool.awaitTermination(30, TimeUnit.SECONDS)
   }
   ```

---

## Summary

ConcurrentPool is the foundation of concurrent execution in GroovyConcurrentUtils:

**Core Strengths:**
- ✅ Virtual threads first with graceful fallback
- ✅ Interface-based for testing and dependency injection
- ✅ Factory pattern for flexible construction
- ✅ Clear ownership semantics
- ✅ CompletableFuture-based modern async API
- ✅ Thread-safe and production-ready

**Use It When:**
- You need concurrent execution (99% of use cases)
- You want virtual threads automatically
- You need scheduling capabilities
- You want testable, mockable code

**The Pattern:**
```groovy
// Production: Virtual threads
ExecutorPool pool = new ConcurrentPool("my-service")

// Or via factory
ExecutorPool pool = ExecutorPoolFactory.builder()
    .name("my-service")
    .build()

// Use it
pool.execute { doWork() }
    .thenAccept { result -> process(result) }

// Clean shutdown
pool.shutdown()
pool.awaitTermination(30, TimeUnit.SECONDS)
```

ConcurrentPool makes modern concurrent programming in Groovy simple, efficient, and maintainable. 🚀

---

## Version History

### v1.1 - Enhanced Monitoring & Reliability
- **NEW:** `isHealthy()` - Health check method for monitoring
- **NEW:** `getMetrics()` - Comprehensive metrics for observability
- **NEW:** `tryExecute(Callable)` - Non-throwing variant for Java interop
- **NEW:** `tryExecute(Runnable)` - Non-throwing variant for Java interop
- **ENHANCED:** `transformFutureToCFuture()` - Better exception handling
- **ENHANCED:** `getScheduledExecutor()` - Null safety check
- **ENHANCED:** Constructor validation - Warns for shut-down executors
- **DEPRECATED:** `shutdownSharedExecutors()` - Now a no-op for safety

### v1.0 - Initial Release
- Virtual threads first architecture
- ExecutorPool interface for testing
- ExecutorPoolFactory for flexible construction
- CompletableFuture-based API
- Comprehensive scheduling support