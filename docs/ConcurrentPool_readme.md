# ConcurrentPool – Virtual-Thread-Friendly Executor Wrapper

`ConcurrentPool` is a lightweight utility class that provides a modern approach to concurrent execution in Groovy, with strong support for **virtual threads** (Java 21+) while gracefully falling back to traditional platform thread pools when needed.

## Key Features

- **Shared virtual thread executor** - Application-wide singleton for virtual threads
- **Platform thread pools** - Optional fixed-size pools for specific use cases
- **Scheduled execution** - Built-in support for delayed and periodic tasks
- **Lifecycle management** - Proper shutdown semantics with ownership tracking
- **Closed state enforcement** - Prevents task submission after shutdown
- **Error tracking** - Centralized reporting of initialization issues
- **Groovy-friendly API** - Closures, DSL-style `withPool`, and idiomatic syntax

---

## Design Philosophy

### Virtual Threads First
When running on Java 21+, `ConcurrentPool` uses a **shared application-wide virtual thread executor**. This aligns with the virtual thread philosophy: they're cheap and abundant, so you don't need multiple executor instances. Each task gets its own virtual thread.

### Platform Threads When Needed
For scenarios requiring bounded concurrency (e.g., rate-limited external APIs, resource-constrained operations), you can explicitly create fixed-size platform thread pools.

### Ownership and Lifecycle
The class distinguishes between:
- **Shared executors** - Application-wide, never shut down by individual pool instances
- **Owned executors** - Created by the instance, shut down when the instance shuts down
- **External executors** - Provided by caller, not shut down by the pool

---

## Static Initialization

At class load time, `ConcurrentPool` initializes:

1. **`sharedVirtualThreadExecutor`** - A single virtual-thread-per-task executor (if Java 21+ is available)
2. **`sharedScheduledExecutor`** - A scheduled executor for delayed/periodic tasks (always uses platform threads, as scheduling requires persistent threads)

If initialization fails, errors are recorded in the static `errors` queue and fallbacks are used.

### Static APIs

```groovy
// Check if virtual threads are available
boolean available = ConcurrentPool.isVirtualThreadsAvailable()

// Access shared executors
ExecutorService virtExec = ConcurrentPool.sharedVirtualThreadExecutor
ScheduledExecutorService schedExec = ConcurrentPool.sharedScheduledExecutor

// Check for initialization errors
if (ConcurrentPool.hasErrors()) {
    ConcurrentPool.errors.each { println "Init error: $it" }
}
```

---

## Constructors

### 1. Default Constructor (Recommended)

```groovy
def pool = new ConcurrentPool()
```

- **Java 21+**: Uses the shared virtual thread executor
- **Older Java**: Creates a cached thread pool (platform threads)
- **Scheduled tasks**: Lazily uses the shared scheduled executor
- **Ownership**: Does not own the executor (won't shut it down)

**Best for**: Most use cases where you want virtual threads and simple concurrent execution.

### 2. Fixed Platform Thread Pool

```groovy
def pool = new ConcurrentPool(8)  // 8 platform threads
```

- Creates a fixed-size thread pool with the specified number of platform threads
- **Ownership**: Owns the executor (will shut it down)
- **Pool size**: Available via `pool.poolSize` (returns 8)

**Best for**: When you need bounded concurrency (e.g., rate-limited APIs, resource constraints).

### 3. Custom Executors

```groovy
def customExec = Executors.newSingleThreadExecutor()
def customSched = Executors.newScheduledThreadPool(2)

def pool = new ConcurrentPool(customExec, customSched)
```

- Use externally-provided executors
- **Ownership**: Does not own the executors (won't shut them down)
- **Use case**: Integration with existing executor infrastructure or testing

---

## Core API

### Executing Tasks

#### `execute(Closure task)`

```groovy
def pool = new ConcurrentPool()

def future = pool.execute {
    // Runs on virtual thread (if available)
    performWork()
    "result"
}

def result = future.get(2, TimeUnit.SECONDS)
```

- Submits task to the pool's executor
- Returns a `Future` for result retrieval
- **Throws**: `IllegalStateException` if pool is closed

#### `withPool(ExecutorService executor = null, Closure work)`

```groovy
def pool = new ConcurrentPool()

pool.withPool {
    // Delegate is the pool instance
    execute { println "Task 1" }
    execute { println "Task 2" }
}

// Or with custom executor for this block only
def customExec = Executors.newFixedThreadPool(2)
pool.withPool(customExec) {
    execute { println "Using custom executor" }
}
```

- DSL-style execution with the pool as delegate
- Optionally override executor for just this invocation
- **Throws**: `IllegalStateException` if pool is closed

### Scheduled Execution

#### `scheduleExecution(int delay, TimeUnit unit, Closure task)`

```groovy
def pool = new ConcurrentPool()

def future = pool.scheduleExecution(500, TimeUnit.MILLISECONDS) {
    println "Executed after 500ms"
}

future.get()  // Wait for completion
```

- Executes task once after the specified delay
- Returns `ScheduledFuture`
- **Throws**: `IllegalStateException` if pool is closed

#### `scheduleWithFixedDelay(int initialDelay, int delay, TimeUnit unit, Closure task)`

```groovy
def pool = new ConcurrentPool()

def future = pool.scheduleWithFixedDelay(1, 5, TimeUnit.SECONDS) {
    println "Runs every 5 seconds (after previous completes)"
}

// Cancel when done
future.cancel(false)
```

- First execution after `initialDelay`
- Subsequent executions wait `delay` after previous task completes
- **Throws**: `IllegalStateException` if pool is closed

#### `scheduleAtFixedRate(int initialDelay, int period, TimeUnit unit, Closure task)`

```groovy
def pool = new ConcurrentPool()

def future = pool.scheduleAtFixedRate(0, 10, TimeUnit.SECONDS) {
    println "Runs every 10 seconds (fixed rate)"
}

// Cancel when done
future.cancel(false)
```

- Executes at fixed intervals regardless of task duration
- If task takes longer than period, next execution starts immediately
- **Throws**: `IllegalStateException` if pool is closed

---

## Lifecycle Management

### Shutdown

```groovy
void shutdown()
```

- Marks pool as **closed** - no new tasks accepted
- Shuts down **owned** executors only (shared executors continue running)
- Existing tasks complete normally
- Subsequent task submissions throw `IllegalStateException`

**Example:**

```groovy
def pool = new ConcurrentPool(4)  // Owns this executor

pool.execute { println "Task 1" }
pool.shutdown()

try {
    pool.execute { println "Task 2" }  // Throws IllegalStateException
} catch (IllegalStateException e) {
    println "Pool is closed: ${e.message}"
}
```

### Immediate Shutdown

```groovy
List<Runnable> shutdownNow()
```

- Marks pool as **closed**
- Attempts to stop actively executing tasks
- Returns list of tasks that were awaiting execution
- Only affects **owned** executors

**Example:**

```groovy
def pool = new ConcurrentPool(4)

(1..10).each { pool.execute { sleep(1000) } }

def pending = pool.shutdownNow()
println "Cancelled ${pending.size()} pending tasks"
```

### Await Termination

```groovy
boolean awaitTermination(long timeout, TimeUnit unit)
```

- Blocks until owned executors terminate or timeout occurs
- Returns `true` if all owned executors terminated, `false` if timeout elapsed
- Only waits for **owned** executors

**Example:**

```groovy
def pool = new ConcurrentPool(4)

(1..5).each { pool.execute { sleep(100) } }

pool.shutdown()
if (pool.awaitTermination(5, TimeUnit.SECONDS)) {
    println "All tasks completed"
} else {
    println "Timeout waiting for tasks"
}
```

### Application Shutdown

```groovy
static void shutdownSharedExecutors()
```

- Shuts down the shared application-wide executors
- **Should only be called during application shutdown**
- Affects all `ConcurrentPool` instances using shared executors

**Example:**

```groovy
// At application shutdown
Runtime.runtime.addShutdownHook {
    ConcurrentPool.shutdownSharedExecutors()
}
```

---

## State Inspection

### Pool State

```groovy
boolean isClosed()           // True if shutdown() or shutdownNow() was called
boolean isShutdown()         // True if owned executors are shut down, or pool is closed
boolean isUsingVirtualThreads()  // True if using shared virtual thread executor
int getPoolSize()            // Pool size for ThreadPoolExecutor, -1 for virtual threads
```

**Example:**

```groovy
def pool = new ConcurrentPool()

println "Using virtual threads: ${pool.isUsingVirtualThreads()}"  // true (Java 21+)
println "Pool size: ${pool.poolSize}"  // -1 (virtual threads have no fixed size)
println "Closed: ${pool.isClosed()}"   // false

pool.shutdown()
println "Closed: ${pool.isClosed()}"   // true
```

### Error Reporting

```groovy
static boolean hasErrors()   // True if initialization errors occurred
static List getErrors()      // Immutable list of error messages
```

**Example:**

```groovy
if (ConcurrentPool.hasErrors()) {
    println "Initialization errors:"
    ConcurrentPool.errors.each { println "  - $it" }
}
```

---

## Usage Patterns

### Pattern 1: Simple Parallel Execution

```groovy
def pool = new ConcurrentPool()

def urls = ['http://example.com', 'http://example.org', 'http://example.net']

def futures = urls.collect { url ->
    pool.execute {
        fetchData(url)
    }
}

def results = futures*.get()
println "Fetched ${results.size()} results"

pool.shutdown()
```

### Pattern 2: Bounded Concurrency

```groovy
// Limit to 4 concurrent connections to external API
def pool = new ConcurrentPool(4)

def tasks = (1..100).collect { i ->
    pool.execute {
        callRateLimitedAPI(i)
    }
}

tasks*.get()
pool.shutdown()
pool.awaitTermination(30, TimeUnit.SECONDS)
```

### Pattern 3: Background Processing with Scheduling

```groovy
def pool = new ConcurrentPool()

// Process queue every 5 seconds
def scheduledTask = pool.scheduleWithFixedDelay(0, 5, TimeUnit.SECONDS) {
    processMessageQueue()
}

// Run for 1 minute then cancel
sleep(60_000)
scheduledTask.cancel(false)
pool.shutdown()
```

### Pattern 4: Mixed Execution Types

```groovy
def pool = new ConcurrentPool()

// Immediate execution
pool.execute {
    processIncomingRequest()
}

// Delayed execution
pool.scheduleExecution(100, TimeUnit.MILLISECONDS) {
    sendDelayedNotification()
}

// Periodic execution
pool.scheduleAtFixedRate(0, 1, TimeUnit.MINUTES) {
    performHealthCheck()
}
```

### Pattern 5: DSL-Style Configuration

```groovy
def pool = new ConcurrentPool()

pool.withPool {
    // All tasks in this block use the pool
    execute { fetchUserData() }
    execute { fetchOrderData() }
    
    scheduleExecution(1, TimeUnit.SECONDS) {
        processResults()
    }
}
```

---

## Virtual Threads vs Platform Threads

### When to Use Default Constructor (Virtual Threads)

- Most concurrent operations
- High-throughput I/O (HTTP, database, file operations)
- Thousands of concurrent tasks
- Tasks that mostly wait (I/O-bound)

**Advantages:**
- Extremely lightweight (millions of threads possible)
- Low memory overhead
- Simplified concurrency model
- No need to tune pool sizes

### When to Use Fixed Platform Thread Pool

- CPU-intensive operations
- External resource constraints (connection limits)
- Explicit concurrency control needed
- Integration with legacy systems expecting platform threads

**Example:**

```groovy
// Virtual threads - for high I/O concurrency
def ioPool = new ConcurrentPool()
(1..10000).each { ioPool.execute { httpClient.get(url) } }

// Platform threads - for CPU-bound work
def cpuPool = new ConcurrentPool(Runtime.runtime.availableProcessors())
(1..100).each { cpuPool.execute { performHeavyComputation() } }
```

---

## Integration with CompletableFuture

For complex async workflows, combine `ConcurrentPool` with `CompletableFuture`:

```groovy
def pool = new ConcurrentPool()

CompletableFuture.supplyAsync({
    fetchUserData()
}, pool.executor)
.thenApplyAsync({ user ->
    enrichUserData(user)
}, pool.executor)
.thenAccept { result ->
    println "Final result: $result"
}
```

---

## Testing

The class is designed to be testable:

```groovy
// Test with custom executor
def testExecutor = Executors.newSingleThreadExecutor()
def pool = new ConcurrentPool(testExecutor, null)

// Verify task execution
def future = pool.execute { "test result" }
assert future.get() == "test result"

// Verify shutdown behavior
pool.shutdown()
assert pool.isClosed()

try {
    pool.execute { "should fail" }
    assert false, "Should have thrown exception"
} catch (IllegalStateException e) {
    // Expected
}

testExecutor.shutdown()
```

---

## Migration from Original Implementation

If you're migrating from an older version:

### Changes:
1. **Shared executor**: Default constructor now uses shared virtual thread executor
2. **Closed state**: New `isClosed()` method and enforcement after shutdown
3. **Ownership tracking**: Automatic management of executor lifecycle
4. **Additional methods**: `scheduleWithFixedDelay()`, `scheduleAtFixedRate()`, `shutdownNow()`, `awaitTermination()`
5. **Naming**: `virtualThreadsEnabled` → `virtualThreadsAvailable` (more accurate)

### Migration tips:
- If you relied on each instance having its own virtual thread executor, no change needed (it still works, just more efficiently now)
- If you call `shutdown()` on pools using shared executors, they now properly reject new tasks
- Tests expecting `RejectedExecutionException` should now catch `IllegalStateException` for shared executor pools

---

## Best Practices

1. **Use default constructor** for most cases - it's the most efficient
2. **Create fixed pools sparingly** - only when you truly need bounded concurrency
3. **Don't shut down shared executors** - let the application lifecycle handle it
4. **Prefer virtual threads** - they're the future of Java concurrency
5. **Check errors on startup** - use `hasErrors()` to detect environment issues
6. **Clean shutdown** - call `shutdown()` and `awaitTermination()` on owned pools

---

## Requirements

- **Java 21+** for virtual threads (falls back gracefully on older versions)
- **Groovy 4.x** (tested with Groovy 4.0+)
- No external dependencies beyond standard JDK

---

## Summary

`ConcurrentPool` provides a modern, virtual-thread-first approach to concurrent execution in Groovy applications. It simplifies executor management while maintaining flexibility for advanced use cases. The shared executor model reduces resource overhead while the ownership tracking ensures proper lifecycle management.

For most use cases, simply create a `new ConcurrentPool()` and start submitting tasks - the class handles the complexity of modern Java concurrency for you.