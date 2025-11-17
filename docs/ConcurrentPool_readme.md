# ConcurrentPool – Virtual-Thread-Friendly Executor Wrapper
is a small utility class that centralizes creation and use of and with a strong preference for **virtual threads** (Java 21+), while still supporting classic platform-thread pools when needed. `ConcurrentPool``ExecutorService``ScheduledExecutorService`
It gives you:
- a default virtual-thread per-task executor,
- a default scheduled executor (virtual-thread-based when possible),
- instance-level executors you can override or inject,
- a simple DSL-style `withPool { … }` helper,
- graceful shutdown semantics that avoid killing shared defaults.

## Design goals
- **Virtual threads by default**
Use `Executors.newVirtualThreadPerTaskExecutor()` whenever the runtime supports it.
- **Reasonable fallbacks**
On older runtimes or in failure scenarios, fall back to standard implementations (e.g. `newFixedThreadPool` or `newSingleThreadScheduledExecutor`). `ExecutorService`
- **Centralized configuration**
Keep global defaults in one place (`defaultExecutor`, ) while allowing per-instance overrides. `defaultScheduledExecutor`
- **Explicit error tracking**
Track initialization errors in a static queue so callers can inspect them if something goes wrong. `errors`

## Static defaults
At class load time, tries to initialize: `ConcurrentPool`
- `defaultExecutor` as a virtual-thread-per-task executor.
- as a scheduled executor using virtual threads when available, otherwise a single-thread platform executor. `defaultScheduledExecutor`

If any of these steps fail, the cause is logged into the static collection and a fallback executor is created. `errors`
Key static APIs:
- `static ExecutorService getDefaultExecutor()` – returns the global default executor (virtual threads if available, otherwise a cached pool).
- `static boolean isVirtualThreadsEnabled()` – indicates whether virtual-thread initialization succeeded.
- `static boolean hasErrors()` – true if any initialization errors were recorded.
- `static List getErrors()` – immutable view of the recorded error messages.

Example:
``` groovy
import org.softwood.pool.ConcurrentPool

if (ConcurrentPool.hasErrors()) {
    ConcurrentPool.getErrors().each { println "ConcurrentPool error: $it" }
}

def exec = ConcurrentPool.defaultExecutor
```

 
## Instance-level executors
Each ConcurrentPool instance has:
an ExecutorService executor for regular tasks,
an optional ScheduledExecutorService scheduledExecutor for scheduled tasks.
### Constructors
1. Default constructor (virtual threads)
``` groovy
def pool = new ConcurrentPool()
```

executor 
is a new virtual-thread-per-task executor (via Executors.newVirtualThreadPerTaskExecutor()).
scheduledExecutor is lazily initialized from defaultScheduledExecutor when first needed.
Use this when you want a per-instance virtual-thread executor.
2. Fixed-size platform-thread pool
``` groovy
def pool = new ConcurrentPool(8)  // 8 platform threads
```

Creates executor as Executors.newFixedThreadPool(poolSize).
Intended for cases where you explicitly want a bounded set of platform threads (e.g. for blocking I/O constrained by external resources).
You can inspect the configured pool size via:
``` groovy
assert pool.poolSize == 8
```

For virtual-thread executors, poolSize returns 0 (no fixed size concept).
3. Custom executors
``` groovy
def exec = Executors.newFixedThreadPool(4)
def sched = Executors.newScheduledThreadPool(2)

def pool = new ConcurrentPool(exec, sched)
```

If executor argument is null, it falls back to defaultExecutor.
scheduledExecutor argument is optional; if null, the instance will lazily adopt defaultScheduledExecutor.
This is useful when integrating with existing executors or frameworks.
 
## Accessors and configuration
### Executors
- ExecutorService getExecutor() – returns the instance’s executor.
- void setExecutor(ExecutorService executor) – override the executor for this instance (non-null only).
- ScheduledExecutorService getScheduledExecutor() – lazily initializes and returns the scheduled executor:
- if scheduledExecutor is null, it is set to defaultScheduledExecutor;
otherwise the existing value is returned.
- void setScheduledExecutor(ScheduledExecutorService scheduledExecutor) – override the scheduled executor for this instance (non-null only).
- Pool size
``` groovy
int getPoolSize()
```

If the underlying executor is a ThreadPoolExecutor, returns its core pool size.
For virtual-thread executors (or other implementations), returns 0 to signal that there is no fixed pool size.
Example:
``` groovy
def pool1 = new ConcurrentPool(4)
assert pool1.poolSize == 4

def pool2 = new ConcurrentPool()
assert pool2.poolSize == 0      // virtual threads, no fixed size
```

 
## Executing tasks
execute(Closure)
``` groovy
def pool = new ConcurrentPool()

def future = pool.execute {
    // some work
    "result"
}

assert future.get() == "result"
```

Submits the closure to the instance’s executor.
Returns the Future returned by ExecutorService.submit(Closure).
scheduleExecution(int delay, TimeUnit unit, Closure task)
``` groovy
def pool = new ConcurrentPool()

def scheduled = pool.scheduleExecution(100, TimeUnit.MILLISECONDS) {
    println "Executed after 100ms"
}
```

Uses getScheduledExecutor() to obtain the scheduler (lazy init).
Schedules the closure to run after the given delay.
Returns a ScheduledFuture.
 
## DSL-style usage with withPool
withPool lets you inject or override the executor and run a closure whose delegate is the ConcurrentPool instance:
``` groovy
def pool = new ConcurrentPool()

pool.withPool {    // delegate = this ConcurrentPool
    execute {
        println "Running on pool executor"
    }

    scheduleExecution(1, TimeUnit.SECONDS) {
        println "Scheduled task"
    }
}
```

You can also pass a custom executor just for that block:
``` groovy
def customExec = Executors.newSingleThreadExecutor()

pool.withPool(customExec) {
    execute {
        println "Using customExec for this block"
    }
}
```

This is convenient for short-lived overrides or DSL-like configuration.
 
Shutdown semantics
``` groovy
void shutdown()
```

Calls shutdown() on executor if it is non-null.
For scheduledExecutor:
- If scheduledExecutor is non-null and not equal to the shared defaultScheduledExecutor, it is shut down.
- If the instance is using the shared defaultScheduledExecutor, it is left running to avoid impacting other users of the static default.
Example:
``` groovy
def pool = new ConcurrentPool()
pool.shutdown()
```

If you created a ConcurrentPool with a custom scheduled executor, that custom scheduler is safely shut down, but the global defaults remain untouched.
 
## Error reporting
Two static helpers summarize initialization issues:
- static boolean hasErrors() – true if any errors occurred during static initialization of default executors.
- static List getErrors() – immutable list of error messages.
Example:
``` groovy
if (ConcurrentPool.hasErrors()) {
    ConcurrentPool.getErrors().each { msg ->
        println "ConcurrentPool init error: $msg"
    }
}
```

This is particularly useful when running on older Java versions or unusual environments where virtual threads or thread factories might fail.
 
## Typical usage patterns
1. Virtual-thread executor for background tasks
``` groovy
def pool = new ConcurrentPool()

def futures = (1..10).collect { i ->
    pool.execute {
        // each task runs on its own virtual thread
        "Task $i on ${Thread.currentThread().name}"
    }
}

println futures*.get()
pool.shutdown()
```

2. Fixed-size platform-thread pool
``` groovy
def pool = new ConcurrentPool(4)  // 4 platform threads

(1..10).each {
    pool.execute {
        println "Running on ${Thread.currentThread().name}"
    }
}

pool.shutdown()
```

3. Scheduling periodic or delayed work
``` groovy
def pool = new ConcurrentPool()

def future = pool.scheduleExecution(500, TimeUnit.MILLISECONDS) {
    println "Ran after 500ms on ${Thread.currentThread().name}"
}

future.get()  // block until it runs
pool.shutdown()
```

 
## When to use ConcurrentPool
ConcurrentPool is a good fit when you:
- want a simple, centralized way to construct executors that prefer virtual threads,
- need both regular and scheduled execution in one small abstraction,
- want to share global defaults but still allow per-instance customization,
- prefer a Groovy-friendly interface (execute, withPool, closures) rather than raw Java executor APIs.
- If you need advanced task scheduling (cron-like, complex scheduling policies) or rich metrics, you might still prefer more specialized libraries, but ConcurrentPool is a compact, virtual-thread-aware default for most internal concurrency needs.