# Promises – Unified Async Abstraction for Groovy

A pluggable, implementation-agnostic Promise API for Groovy that provides a consistent interface across multiple async backends including Dataflow, CompletableFuture, and Vert.x.

## Overview

`Promises` provides a fluent, type-safe API for asynchronous programming in Groovy while allowing you to choose the underlying implementation that best fits your needs. Whether you prefer dataflow variables, Java's CompletableFuture, or Vert.x's event-driven model, this library presents a unified interface.

### Key Features

- **Implementation-agnostic API** - Write code once, swap backends without changing user code
- **Multiple backends** - Dataflow, CompletableFuture, and Vert.x implementations included
- **Fluent chaining** - Natural composition with `then()`, `recover()`, `onComplete()`, `onError()`
- **Type-safe** - Full generic type support throughout the API
- **Single-assignment semantics** - Promises complete exactly once with either a value or an error
- **Interoperability** - Convert between Promise and CompletableFuture seamlessly
- **Groovy-friendly** - Closures, optional typing, and idiomatic Groovy syntax

---

## Architecture

### Core Components

```
┌─────────────────────────────────────────────────────────────┐
│                         Promises                             │
│                   (Static Facade/Entry Point)                │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           │ delegates to
                           │
┌──────────────────────────▼──────────────────────────────────┐
│                  PromiseConfiguration                        │
│          (Registry & Factory Management)                     │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           │ returns
                           │
┌──────────────────────────▼──────────────────────────────────┐
│                    PromiseFactory                            │
│                     (Interface)                              │
└─────────┬──────────────┬──────────────┬─────────────────────┘
          │              │              │
          │              │              │
┌─────────▼─────┐ ┌──────▼──────┐ ┌────▼──────────────────┐
│ Dataflow      │ │ Completable │ │ Vertx               │
│ Promise       │ │ Future      │ │ Promise             │
│ Factory       │ │ Promise     │ │ Factory             │
│               │ │ Factory     │ │                     │
└───────┬───────┘ └──────┬──────┘ └────┬────────────────┘
        │                │             │
        │ creates        │ creates     │ creates
        │                │             │
┌───────▼───────┐ ┌──────▼──────┐ ┌────▼────────────────┐
│ Dataflow      │ │ Completable │ │ Vertx               │
│ Promise       │ │ Future      │ │ Promise             │
│               │ │ Promise     │ │                     │
└───────────────┘ └─────────────┘ └─────────────────────┘
        │                │             │
        └────────────────┴─────────────┘
                         │
                    implements
                         │
                ┌────────▼────────┐
                │    Promise<T>   │
                │   (Interface)   │
                └─────────────────┘
```

### Design Philosophy

1. **Separation of Concerns**
    - `Promise<T>` - User-facing interface defining the contract
    - `PromiseFactory` - Creation and lifecycle management
    - `PromiseConfiguration` - Registry and implementation selection
    - `Promises` - Static facade for convenient access

2. **Single-Assignment Semantics**
    - A promise completes exactly once
    - Completion can be a value or an error
    - Subsequent completion attempts are ignored or logged

3. **Error Propagation**
    - Errors flow through transformation chains automatically
    - `recover()` allows transforming errors into values
    - Callbacks are type-specific (success vs error)

---

## Quick Start

### Basic Usage

```groovy
import org.softwood.promise.Promises

// Create and complete a promise
def promise = Promises.newPromise()
promise.accept(42)
println promise.get()  // 42

// Create already-completed promise
def completed = Promises.newPromise(100)
println completed.get()  // 100

// Async execution
Promises.async {
    // Expensive computation
    Thread.sleep(1000)
    "result"
}.then { result ->
    "Processed: $result"
}.onComplete { finalResult ->
    println finalResult  // "Processed: result"
}
```

### Choosing an Implementation

```groovy
import org.softwood.promise.PromiseImplementation
import org.softwood.promise.core.PromiseConfiguration

// Set default implementation globally
PromiseConfiguration.setDefaultImplementation(PromiseImplementation.COMPLETABLE_FUTURE)

// Or specify per-promise
def dataflowPromise = Promises.newPromise(PromiseImplementation.DATAFLOW)
def cfPromise = Promises.newPromise(PromiseImplementation.COMPLETABLE_FUTURE)
def vertxPromise = Promises.newPromise(PromiseImplementation.VERTX)
```

---

## Core API

### Promise Interface

The `Promise<T>` interface is the heart of the library. All implementations conform to this contract.

#### Completion Methods

##### `accept(T value)`
Complete the promise with a value.

```groovy
def promise = Promises.newPromise()
promise.accept(42)
assert promise.get() == 42
```

##### `accept(Supplier<T> supplier)`
Complete the promise by evaluating a supplier.

```groovy
def promise = Promises.newPromise()
promise.accept({ -> expensiveComputation() })
```

##### `accept(CompletableFuture<T> future)`
Complete the promise when the future completes.

```groovy
def promise = Promises.newPromise()
def future = CompletableFuture.supplyAsync { 42 }
promise.accept(future)
assert promise.get() == 42
```

##### `accept(Promise<T> otherPromise)`
Adopt the completion of another promise.

```groovy
def promise1 = Promises.newPromise()
def promise2 = Promises.newPromise()

promise2.accept(promise1)
promise1.accept(100)

assert promise2.get() == 100
```

##### `fail(Throwable error)`
Complete the promise with an error.

```groovy
def promise = Promises.newPromise()
promise.fail(new RuntimeException("Something went wrong"))

try {
    promise.get()
    assert false, "Should have thrown"
} catch (Exception e) {
    assert e.message.contains("Something went wrong")
}
```

#### Retrieval Methods

##### `get()`
Block until completion and return the value (or throw if failed).

```groovy
def promise = Promises.async { 42 }
def result = promise.get()  // Blocks
assert result == 42
```

##### `get(long timeout, TimeUnit unit)`
Block with timeout.

```groovy
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

def promise = Promises.newPromise()

try {
    promise.get(100, TimeUnit.MILLISECONDS)
    assert false, "Should timeout"
} catch (TimeoutException e) {
    // Expected
}
```

##### `isDone()`
Check completion status without blocking.

```groovy
def promise = Promises.newPromise()
assert !promise.isDone()

promise.accept(42)
assert promise.isDone()
```

#### Callback Methods

##### `onComplete(Consumer<T> callback)`
Register a success callback.

```groovy
def promise = Promises.async { "hello" }

promise.onComplete { result ->
    println "Got: $result"  // Prints "Got: hello"
}

// If already completed, callback executes immediately
def completed = Promises.newPromise(42)
completed.onComplete { println it }  // Prints immediately
```

##### `onError(Consumer<Throwable> errorHandler)`
Register an error callback.

```groovy
def promise = Promises.async {
    throw new RuntimeException("Oops")
}

promise.onError { error ->
    println "Error: ${error.message}"  // Prints "Error: Oops"
}
```

#### Transformation Methods

##### `then(Function<T, R> function)`
Transform the success value into a new promise.

```groovy
Promises.async { 10 }
    .then { it * 2 }
    .then { it + 5 }
    .onComplete { result ->
        assert result == 25
    }

// Errors propagate through then() chains
Promises.async {
    throw new RuntimeException("Fail")
}.then { it * 2 }  // Skipped
 .onError { println "Caught: ${it.message}" }  // Executes
```

##### `recover(Function<Throwable, R> recovery)`
Transform an error into a success value.

```groovy
Promises.async {
    throw new RuntimeException("Network error")
}.recover { error ->
    "Fallback value"
}.onComplete { result ->
    assert result == "Fallback value"
}

// Success values pass through unchanged
Promises.newPromise(42)
    .recover { 0 }  // Not called
    .onComplete { assert it == 42 }
```

#### Conversion Methods

##### `asType(Class clazz)`
Convert to CompletableFuture (Groovy coercion).

```groovy
def promise = Promises.newPromise(42)
CompletableFuture<Integer> future = promise as CompletableFuture
assert future.get() == 42

// Or explicit
def future2 = promise.asType(CompletableFuture)
```

#### Static Combining Methods

##### `Promise.all(Iterable<Promise<T>> promises)`
Wait for all promises to complete successfully. Returns a promise containing a list of all results in the same order as the input promises. If any promise fails, the returned promise fails immediately.

```groovy
import org.softwood.promise.Promise

def p1 = Promises.async { fetchUser(1) }
def p2 = Promises.async { fetchUser(2) }
def p3 = Promises.async { fetchUser(3) }

Promise.all([p1, p2, p3]).onComplete { users ->
    println "Got ${users.size()} users"
    users.each { println it.name }
}

// If any promise fails, all() fails
def failing = Promises.async { throw new RuntimeException("Failed") }
def success = Promises.async { "OK" }

Promise.all([failing, success]).onError { error ->
    println "One failed: ${error.message}"
}
```

**Key Characteristics:**
- Returns `Promise<List<T>>` containing all results
- Maintains input order in result list
- Fails fast - first error fails the entire operation
- All promises execute in parallel
- Similar to JavaScript's `Promise.all()`

##### `Promise.any(Iterable<Promise<T>> promises)`
Race multiple promises and return the first one that succeeds. If all promises fail, returns a failed promise with aggregated errors.

```groovy
import org.softwood.promise.Promise

def p1 = Promises.async { fetchFromServer1() }
def p2 = Promises.async { fetchFromServer2() }
def p3 = Promises.async { fetchFromServer3() }

Promise.any([p1, p2, p3]).onComplete { result ->
    println "First server responded: ${result}"
}

// If all fail, any() fails with aggregated errors
def fail1 = Promises.async { throw new RuntimeException("Server 1 down") }
def fail2 = Promises.async { throw new RuntimeException("Server 2 down") }

Promise.any([fail1, fail2]).onError { error ->
    println "All servers failed: ${error.message}"
}
```

**Key Characteristics:**
- Returns `Promise<T>` with the first successful result
- Ignores slower successful promises after first completes
- Only fails if ALL promises fail
- All promises execute in parallel
- Useful for fallback/redundancy scenarios
- Similar to JavaScript's `Promise.any()`

**Import Convenience:**

These static methods are available directly on the `Promise` interface, so you only need one import:

```groovy
import org.softwood.promise.Promise  // Single import needed!

// Both work:
Promise.all([p1, p2, p3])    // Via Promise interface
Promises.all([p1, p2, p3])   // Via Promises utility (still works)
```

This design follows the JavaScript Promise API pattern where `Promise.all()` and `Promise.any()` are static methods on the Promise class itself.

---

## Promises Static Facade

The `Promises` class is the main entry point. All methods are static.

### Creation Methods

```groovy
// Default implementation
def p1 = Promises.newPromise()
def p2 = Promises.newPromise(42)  // Already completed

// Specific implementation
def p3 = Promises.newPromise(PromiseImplementation.DATAFLOW)
def p4 = Promises.newPromise(PromiseImplementation.VERTX, "value")
```

### Async Execution

```groovy
// Default implementation
Promises.async {
    fetchDataFromDatabase()
}

// Specific implementation
Promises.async(PromiseImplementation.COMPLETABLE_FUTURE) {
    performComputation()
}
```

### Adaptation from Foreign Types

```groovy
// From CompletableFuture
def future = CompletableFuture.supplyAsync { 42 }
def promise = Promises.from(future)

// From another Promise (useful for mixing implementations)
def dataflowPromise = Promises.newPromise(PromiseImplementation.DATAFLOW, 100)
def cfPromise = Promises.from(dataflowPromise)
```

---

## Implementation Details

### Dataflow Implementation

Backed by `DataflowVariable` from the Softwood Dataflow library.

**Characteristics:**
- Lazy evaluation support
- Thread-safe single-assignment
- Natural dataflow-style composition
- Lightweight and memory-efficient

**Best for:**
- Dataflow-oriented architectures
- Lazy computation pipelines
- CSP-style concurrent programming

**Example:**
```groovy
import org.softwood.promise.PromiseImplementation

def promise = Promises.newPromise(PromiseImplementation.DATAFLOW)

// Dataflow variable semantics
promise.accept({ -> expensiveWork() })

promise.then { result ->
    "Computed: $result"
}.onComplete { println it }
```

### CompletableFuture Implementation

Direct wrapper around Java's `CompletableFuture`.

**Characteristics:**
- Standard Java async primitive
- Rich composition API
- Excellent interoperability with Java libraries
- Thread pool backed (ForkJoinPool.commonPool() by default)

**Best for:**
- Integration with Java async APIs
- Standard enterprise applications
- Familiar Java concurrency patterns

**Example:**
```groovy
import org.softwood.promise.PromiseImplementation

def promise = Promises.newPromise(PromiseImplementation.COMPLETABLE_FUTURE)

// Standard CompletableFuture behavior
promise.accept(CompletableFuture.supplyAsync {
    longRunningTask()
})

// Convert back to CompletableFuture if needed
CompletableFuture<String> future = promise.then { it.toString() } as CompletableFuture
```

### Vert.x Implementation

Backed by Vert.x's `Promise` and event loop model.

**Characteristics:**
- Event-driven, non-blocking I/O
- Built for high-concurrency scenarios
- Integrates with Vert.x ecosystem
- Optimized for reactive workloads

**Best for:**
- Reactive applications
- High-throughput I/O operations
- Microservices with Vert.x
- Event-driven architectures

**Example:**
```groovy
import org.softwood.promise.PromiseImplementation

def promise = Promises.newPromise(PromiseImplementation.VERTX)

// Vert.x event loop execution
promise.accept({ -> fetchFromApi() })

promise.then { data ->
    processData(data)
}.onComplete { result ->
    sendToClient(result)
}
```

---

## Configuration

### Setting Default Implementation

```groovy
import org.softwood.promise.core.PromiseConfiguration
import org.softwood.promise.PromiseImplementation

// At application startup
PromiseConfiguration.setDefaultImplementation(PromiseImplementation.COMPLETABLE_FUTURE)

// Now all Promises.newPromise() calls use CompletableFuture
def promise = Promises.newPromise()  // CompletableFuture-backed
```

### Registering Custom Factories

```groovy
import org.softwood.promise.PromiseFactory

class MyCustomFactory implements PromiseFactory {
    // Implement all factory methods
    // ...
}

// Register the factory
PromiseConfiguration.registerFactory(PromiseImplementation.CUSTOM, new MyCustomFactory())

// Use it
def promise = Promises.newPromise(PromiseImplementation.CUSTOM)
```

### Accessing Implementation Details

```groovy
// Get current default
def impl = PromiseConfiguration.getDefaultImplementation()
println "Default: $impl"

// Get specific factory
def factory = PromiseConfiguration.getFactory(PromiseImplementation.DATAFLOW)

// Access Vert.x instance (if using Vert.x)
def vertx = PromiseConfiguration.getVertxInstance()
```

### Shutdown

```groovy
// At application shutdown (especially important for Vert.x)
PromiseConfiguration.shutdown()
```

---

## Usage Patterns

### Pattern 1: Simple Async Computation

```groovy
Promises.async {
    fetchUserFromDatabase(userId)
}.then { user ->
    user.name.toUpperCase()
}.onComplete { name ->
    println "User name: $name"
}.onError { error ->
    log.error("Failed to fetch user", error)
}
```

### Pattern 2: Sequential Async Operations

```groovy
Promises.async {
    fetchUser(userId)
}.then { user ->
    // Return another promise
    Promises.async { fetchOrders(user.id) }
}.onComplete { orders ->
    println "User has ${orders.size()} orders"
}
```

### Pattern 3: Error Recovery

```groovy
Promises.async {
    fetchFromPrimaryService()
}.recover { error ->
    log.warn("Primary failed, trying fallback", error)
    fetchFromFallbackService()
}.recover { error ->
    log.error("Both services failed", error)
    getDefaultValue()
}.onComplete { result ->
    processResult(result)
}
```

### Pattern 4: Parallel Execution with Promise.all()

```groovy
import org.softwood.promise.Promise

def userPromise = Promises.async { fetchUser(userId) }
def ordersPromise = Promises.async { fetchOrders(userId) }
def prefsPromise = Promises.async { fetchPreferences(userId) }

// Wait for all with Promise.all()
Promise.all([userPromise, ordersPromise, prefsPromise]).onComplete { results ->
    def (user, orders, prefs) = results
    processUserData(user, orders, prefs)
}

// Alternative: Manual coordination (old way)
def user = userPromise.get()
def orders = ordersPromise.get()
def prefs = prefsPromise.get()
processUserData(user, orders, prefs)
```

### Pattern 4a: Failover with Promise.any()

```groovy
import org.softwood.promise.Promise

// Try multiple data sources, use first that succeeds
def primaryPromise = Promises.async { fetchFromPrimary() }
def secondaryPromise = Promises.async { fetchFromSecondary() }
def cachePromise = Promises.async { fetchFromCache() }

Promise.any([primaryPromise, secondaryPromise, cachePromise]).onComplete { data ->
    println "Got data from fastest source: ${data}"
}.onError { error ->
    println "All sources failed: ${error.message}"
}
```

### Pattern 5: CompletableFuture Integration

```groovy
// Start with Promise
def promise = Promises.async { computeValue() }

// Convert to CompletableFuture for composition
CompletableFuture<Integer> future = promise as CompletableFuture

future.thenCombine(anotherFuture) { v1, v2 ->
    v1 + v2
}.thenAccept { result ->
    println "Combined: $result"
}
```

### Pattern 6: Mixing Implementations

```groovy
// Use Dataflow for computation
def dataflowPromise = Promises.async(PromiseImplementation.DATAFLOW) {
    heavyComputation()
}

// Convert to Vert.x for I/O
def vertxPromise = Promises.from(dataflowPromise)

vertxPromise.then { result ->
    // Executes on Vert.x event loop
    sendToNetwork(result)
}
```

### Pattern 7: Lazy Evaluation with Supplier

```groovy
def promise = Promises.newPromise()

// Don't compute until accept() is called
promise.accept({ ->
    println "Computing now..."
    expensiveOperation()
})

// Trigger computation
println promise.get()
```

### Pattern 8: Promise Chaining

```groovy
def promise = Promises.newPromise()

promise
    .then { it * 2 }
    .then { it + 10 }
    .then { it.toString() }
    .onComplete { result ->
        assert result == "30"
    }

promise.accept(10)
```

### Pattern 9: Error Handling Pipeline

```groovy
Promises.async {
    parseJson(userInput)
}.then { data ->
    validateData(data)
}.then { validData ->
    saveToDatabase(validData)
}.recover { error ->
    if (error instanceof ValidationException) {
        return getDefaultData()
    } else {
        throw error  // Re-throw unexpected errors
    }
}.onComplete { result ->
    println "Success: $result"
}.onError { error ->
    log.error("Unrecoverable error", error)
}
```

### Pattern 10: Callback Composition

```groovy
def promise = Promises.async { fetchData() }

// Multiple callbacks on same promise
promise.onComplete { data ->
    updateCache(data)
}

promise.onComplete { data ->
    notifyListeners(data)
}

promise.onError { error ->
    recordMetric("fetch_error")
}

promise.onError { error ->
    alertOps(error)
}
```

---

## Best Practices

### 1. Choose the Right Implementation

- **Dataflow**: Dataflow-style architectures, lazy evaluation
- **CompletableFuture**: Standard Java integration, familiar patterns
- **Vert.x**: High-concurrency I/O, reactive systems

### 2. Handle Errors Explicitly

Always register error handlers or use `recover()`:

```groovy
// Good
Promises.async { riskyOperation() }
    .onError { log.error("Failed", it) }

// Better
Promises.async { riskyOperation() }
    .recover { getFallbackValue() }
    .onError { log.error("All attempts failed", it) }
```

### 3. Avoid Blocking in Async Chains

```groovy
// Bad - blocks the async pipeline
Promises.async { fetchData() }
    .then { data ->
        Thread.sleep(1000)  // Don't do this!
        processData(data)
    }

// Good - keep async operations async
Promises.async { fetchData() }
    .then { data ->
        Promises.async { processDataAsync(data) }
    }
```

### 4. Use `then()` for Transformations, Not Side Effects

```groovy
// Bad - side effect in then()
Promises.async { fetchUser() }
    .then { user ->
        println user  // Side effect
        user
    }

// Good - use onComplete() for side effects
Promises.async { fetchUser() }
    .onComplete { user ->
        println user
    }
```

### 5. Leverage Type Safety

```groovy
// Type inference works well
def promise = Promises.async { 42 }  // Promise<Integer>
def transformed = promise.then { it * 2 }  // Promise<Integer>
def stringified = transformed.then { it.toString() }  // Promise<String>

// Explicit types when needed
Promise<User> userPromise = Promises.async { fetchUser() }
```

### 6. Clean Up Resources

```groovy
// At application shutdown
try {
    PromiseConfiguration.shutdown()
} catch (Exception e) {
    log.error("Error during shutdown", e)
}
```

### 7. Test with Explicit Implementations

```groovy
// In tests, use explicit implementations for predictability
def promise = Promises.newPromise(PromiseImplementation.COMPLETABLE_FUTURE)
promise.accept(testValue)
assert promise.get() == testValue
```

---

## Advanced Topics

### Custom Factory Implementation

To create your own backend:

1. Implement `PromiseFactory`:

```groovy
class MyFactory implements PromiseFactory {
    @Override
    <T> Promise<T> createPromise() {
        return new MyPromise<T>()
    }
    
    @Override
    <T> Promise<T> createPromise(T value) {
        def promise = new MyPromise<T>()
        promise.accept(value)
        return promise
    }
    
    @Override
    <T> Promise<T> createFailedPromise(Throwable cause) {
        def promise = new MyPromise<T>()
        promise.fail(cause)
        return promise
    }
    
    @Override
    <T> Promise<T> executeAsync(Closure<T> task) {
        def promise = createPromise()
        myExecutor.submit {
            try {
                promise.accept(task.call())
            } catch (Throwable e) {
                promise.fail(e)
            }
        }
        return promise
    }
    
    @Override
    <T> Promise<T> from(CompletableFuture<T> future) {
        def promise = createPromise()
        promise.accept(future)
        return promise
    }
    
    @Override
    <T> Promise<T> from(Promise<T> otherPromise) {
        def promise = createPromise()
        promise.accept(otherPromise)
        return promise
    }
}
```

2. Implement `Promise<T>`:

```groovy
class MyPromise<T> implements Promise<T> {
    // Implement all Promise interface methods
    // ...
}
```

3. Register your factory:

```groovy
enum PromiseImplementation {
    DATAFLOW,
    VERTX,
    COMPLETABLE_FUTURE,
    MY_CUSTOM  // Add your implementation
}

PromiseConfiguration.registerFactory(
    PromiseImplementation.MY_CUSTOM, 
    new MyFactory()
)
```

### Integration with Existing Async Code

```groovy
// Wrap existing async APIs
def legacyCallback(success, failure) {
    def promise = Promises.newPromise()
    
    legacyAsyncOperation(
        { result -> promise.accept(result) },
        { error -> promise.fail(error) }
    )
    
    return promise
}

// Use it
legacyCallback()
    .then { result -> transform(result) }
    .onComplete { println it }
```

### Performance Considerations

- **Dataflow**: Minimal overhead, excellent for pure dataflow patterns
- **CompletableFuture**: Standard JVM performance, uses ForkJoinPool
- **Vert.x**: Optimized for I/O, avoid blocking operations on event loop

### Thread Safety

All Promise implementations are thread-safe for completion operations. Multiple threads can safely call `accept()`, `fail()`, etc., but only the first completion wins (single-assignment).

---

## Testing

### Unit Testing Promises

```groovy
import spock.lang.Specification

class PromiseSpec extends Specification {
    
    def "promise completes with value"() {
        given:
        def promise = Promises.newPromise()
        
        when:
        promise.accept(42)
        
        then:
        promise.isDone()
        promise.get() == 42
    }
    
    def "promise fails with error"() {
        given:
        def promise = Promises.newPromise()
        def error = new RuntimeException("test")
        
        when:
        promise.fail(error)
        promise.get()
        
        then:
        thrown(Exception)
    }
    
    def "then transforms value"() {
        given:
        def promise = Promises.newPromise(10)
        
        when:
        def transformed = promise.then { it * 2 }
        
        then:
        transformed.get() == 20
    }
    
    def "recover handles error"() {
        given:
        def promise = Promises.newPromise()
        promise.fail(new RuntimeException("error"))
        
        when:
        def recovered = promise.recover { 0 }
        
        then:
        recovered.get() == 0
    }
}
```

### Testing Async Operations

```groovy
def "async execution completes"() {
    when:
    def promise = Promises.async {
        Thread.sleep(100)
        "result"
    }
    
    then:
    promise.get(1, TimeUnit.SECONDS) == "result"
}

def "callbacks execute"() {
    given:
    def completed = false
    def promise = Promises.async { 42 }
    
    when:
    promise.onComplete { completed = true }
    Thread.sleep(200)  // Give callback time to execute
    
    then:
    completed
}
```

---

## Requirements

- **Groovy 4.x** (tested with Groovy 4.0+)
- **Java 11+** for base functionality
- **Java 21+** recommended for best CompletableFuture features
- **Vert.x 4.x** (optional, only if using VERTX implementation)
- **Softwood Dataflow** (optional, only if using DATAFLOW implementation)

---

## Comparison with Other Async Abstractions

| Feature | Promises | CompletableFuture | Vert.x Promise | RxJava |
|---------|----------|-------------------|----------------|--------|
| Single-value | ✓ | ✓ | ✓ | ✓ (Single) |
| Multiple values | ✗ | ✗ | ✗ | ✓ (Observable) |
| Pluggable backend | ✓ | ✗ | ✗ | ✗ |
| Groovy-first API | ✓ | ✗ | ✗ | ✗ |
| Type safety | ✓ | ✓ | Partial | ✓ |
| Error recovery | ✓ | ✓ | ✓ | ✓ |
| Lazy evaluation | ✓* | ✗ | ✗ | ✓ |
| Backpressure | ✗ | ✗ | ✗ | ✓ |

*Only with DATAFLOW implementation

---

## FAQ

### Q: Which implementation should I use?

**A:** Start with `COMPLETABLE_FUTURE` for standard Java integration. Use `DATAFLOW` if you need lazy evaluation or dataflow-style composition. Choose `VERTX` if building reactive, high-I/O applications.

### Q: Can I mix implementations?

**A:** Yes! Use `Promises.from()` to convert between implementations:

```groovy
def dataflowPromise = Promises.newPromise(PromiseImplementation.DATAFLOW)
def cfPromise = Promises.from(dataflowPromise)  // Now CompletableFuture-backed
```

### Q: Are promises reusable?

**A:** No. Promises complete exactly once (single-assignment). Create a new promise for each async operation.

### Q: How do I handle multiple values?

**A:** Promises are single-value. For streams of values, consider RxJava or Reactor. For multiple independent values, create multiple promises.

### Q: What happens if I call `accept()` twice?

**A:** The first completion wins. Subsequent calls are ignored (implementation-specific logging may occur).

### Q: Can I cancel a promise?

**A:** Not directly through the Promise API. The underlying implementation may support cancellation (e.g., CompletableFuture does), but it's not part of the Promise contract.

### Q: How do I debug promise chains?

**A:** Add logging in callbacks:

```groovy
Promises.async { compute() }
    .then { result ->
        log.debug("Step 1: $result")
        transform(result)
    }
    .then { result ->
        log.debug("Step 2: $result")
        result
    }
    .onError { log.error("Failed", it) }
```

---

## Summary

The Promises library provides a clean, implementation-agnostic async API for Groovy applications. Key benefits:

- **Consistency**: Same API across Dataflow, CompletableFuture, and Vert.x
- **Flexibility**: Swap implementations without changing user code
- **Type Safety**: Full generic type support
- **Composability**: Fluent chaining with `then()`, `recover()`, callbacks
- **Groovy-friendly**: Closures, natural syntax, optional typing
- **Interoperability**: Easy conversion to/from CompletableFuture

Whether you're building enterprise applications, reactive microservices, or dataflow pipelines, Promises provides a unified abstraction that grows with your needs.