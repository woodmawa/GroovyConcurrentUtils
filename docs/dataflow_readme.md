# Dataflow Library

A comprehensive Groovy library for dataflow concurrency, providing single-assignment variables, asynchronous computation, and promise-style programming with full operator support.

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Core Components](#core-components)
- [Getting Started](#getting-started)
- [API Reference](#api-reference)
- [Advanced Usage](#advanced-usage)
- [Best Practices](#best-practices)

## Overview

The Dataflow library implements dataflow concurrency primitives for Groovy, enabling you to write concurrent code that automatically coordinates through data dependencies rather than explicit locks or synchronization.

### Key Features

- **Single-assignment semantics**: Variables can only be bound once, eliminating race conditions
- **Automatic synchronization**: Readers block until values are available
- **Asynchronous listeners**: React to value binding without blocking
- **Promise-style chaining**: Compose asynchronous operations with `then()` and `recover()`
- **Operator overloading**: Use natural Groovy operators (`+`, `-`, `*`, `/`) on dataflow variables
- **Thread-safe**: All operations are safe for concurrent access
- **Pool-based execution**: Configurable thread pools for listener execution

## Architecture

The library is built on a layered architecture:

```
┌─────────────────────────────────────┐
│         Dataflows                   │  Dynamic namespace container
│  (property-based DFV access)        │
└──────────────┬──────────────────────┘
               │
┌──────────────▼──────────────────────┐
│      DataflowVariable<T>            │  Single-assignment variable
│  (operators, chaining, promises)    │
└──────────────┬──────────────────────┘
               │
┌──────────────▼──────────────────────┐
│     DataflowExpression<T>           │  Base completion semantics
│  (listeners, state, error handling) │
└──────────────┬──────────────────────┘
               │
┌──────────────▼──────────────────────┐
│       ConcurrentPool                │  Thread pool management
└─────────────────────────────────────┘
```

### Component Responsibilities

- **DataflowExpression**: Provides core single-assignment semantics, listener registration, and completion tracking
- **DataflowVariable**: Adds promise-style API, operator overloading, and chaining transformations
- **Dataflows**: Offers dynamic property-based access to a namespace of DataflowVariables
- **DataflowFactory**: Creates dataflow variables and manages task execution
- **DataflowException**: Custom exception for dataflow-specific errors

## Core Components

### DataflowExpression<T>

The foundation class providing:

- Single-assignment enforcement with `setValue(T)` and `setError(Throwable)`
- State tracking: `PENDING`, `SUCCESS`, `ERROR`
- Blocking reads via `getValue()` and `getValue(long, TimeUnit)`
- Listener registration with `whenBound(Closure)` and `whenBoundAsync(Closure)`
- Property change events for framework integration

**Key States:**

| State | Description | Transitions To |
|-------|-------------|----------------|
| PENDING | Initial state, not yet bound | SUCCESS or ERROR |
| SUCCESS | Completed with a value | _(terminal)_ |
| ERROR | Completed with an error | _(terminal)_ |

### DataflowVariable<T>

Extends DataflowExpression with:

- **Binding API**: `bind(Object)`, `bindError(Throwable)`
- **Synchronous access**: `get()`, `get(timeout, unit)`, `getNonBlocking()`
- **Promise-style listeners**: `whenAvailable(Consumer)`, `whenError(Consumer)`
- **Chaining**: `then(Function)`, `recover(Function)`
- **Operators**: `plus()`, `minus()`, `multiply()`, `div()`
- **Groovy syntax**: `<<`, `>>`, `()` operator support
- **Future interop**: `toFuture()` for CompletableFuture integration

### Dataflows

A dynamic, thread-safe namespace for managing multiple DataflowVariables:

```groovy
Dataflows df = new Dataflows()

// Property-style access
df.x = 10        // Binds df.x to 10
println df.x     // Blocks until x is bound, then prints 10

// Array-style access
df[0] = 42
println df[0]

// Callback DSL
df.result { value ->
    println "Result available: $value"
}
```

**Key Features:**

- Lazy DFV creation on first access
- DFV-to-DFV wiring when assigning one variable to another
- Non-blocking inspection: `getBoundValues()`, `getBoundProperties()`, `getUnboundProperties()`
- Namespace management: `contains()`, `remove()`, `clear()`, `size()`

### DataflowFactory

Factory for creating dataflow variables and tasks:

```groovy
DataflowFactory factory = new DataflowFactory()

// Create variables
DataflowVariable<Integer> var = factory.createDataflowVariable()

// Execute async tasks
DataflowVariable<String> result = factory.task {
    // Long computation
    "Result"
}
```

## Getting Started

### Basic Usage

```groovy
import org.softwood.dataflow.*

// Create a variable
DataflowVariable<Integer> x = new DataflowVariable<>()

// Register a listener (non-blocking)
x.whenAvailable { value ->
    println "Received: $value"
}

// Bind the value (triggers listener)
x.bind(42)

// Blocking read
Integer value = x.get()
println "Value: $value"
```

### Using Dataflows for Coordination

```groovy
Dataflows df = new Dataflows()

// Thread 1: Producer
Thread.start {
    Thread.sleep(100)
    df.data = "Hello, World!"
}

// Thread 2: Consumer
Thread.start {
    // Blocks until df.data is bound
    println df.data
}
```

### Asynchronous Computation

```groovy
DataflowFactory factory = new DataflowFactory()

// Start async task
DataflowVariable<Integer> result = factory.task {
    Thread.sleep(1000)
    return 42
}

// Chain transformations
DataflowVariable<Integer> doubled = result.then { it * 2 }
DataflowVariable<String> formatted = doubled.then { "Result: $it" }

// Non-blocking: continue other work
println "Computation started..."

// Block when needed
println formatted.get()  // "Result: 84"
```

## API Reference

### DataflowVariable<T>

#### Binding

```groovy
void bind(Object value)              // Bind to a value
DataflowVariable<T> bindError(Throwable t)  // Bind to an error
```

#### Synchronous Access

```groovy
T get()                              // Block until available
T get(long timeout, TimeUnit unit)  // Block with timeout
T get(Duration duration)             // Block with timeout
T getNonBlocking()                   // Return immediately (null if pending)
Optional<T> getIfAvailable()         // Optional wrapper
```

#### State Inspection

```groovy
boolean isBound()      // Completed (success or error)
boolean isDone()       // Alias for isBound()
boolean isSuccess()    // Completed successfully
boolean hasError()     // Completed with error
Throwable getError()   // Get error (null if success)
```

#### Listeners

```groovy
// Success listener
DataflowVariable<T> whenAvailable(Consumer<? super T> callback)

// Error listener
DataflowVariable<T> whenError(Consumer<Throwable> handler)
DataflowVariable<T> whenError(Closure<?> handler)

// Generic bound listener (from DataflowExpression)
DataflowVariable<T> whenBound(Closure listener)
DataflowVariable<T> whenBound(String message, Closure listener)
DataflowVariable<T> whenBoundAsync(Closure listener)
```

#### Chaining

```groovy
<R> DataflowVariable<R> then(Function<? super T, ? extends R> fn)
<R> DataflowVariable<R> then(Closure<R> xform)
DataflowVariable<T> recover(Function<Throwable, T> fn)
```

#### Operators

```groovy
DataflowVariable plus(Object other)      // +
DataflowVariable minus(Object other)     // -
DataflowVariable multiply(Object other)  // *
DataflowVariable div(Object other)       // /
DataflowVariable rightShift(Object cb)   // >> (alias for whenAvailable)
DataflowVariable leftShift(T val)        // << (alias for bind)
```

#### Interop

```groovy
CompletableFuture<T> toFuture()  // Convert to CompletableFuture
```

### Dataflows

#### Access

```groovy
// Property access
df.propertyName = value   // Bind
value = df.propertyName   // Read (blocking)

// Array access
df[key] = value           // Bind
value = df[key]           // Read (blocking)

// Callback syntax
df.propertyName { value -> /* ... */ }
```

#### Management

```groovy
DataflowVariable<?> getDataflowVariable(Object name)
boolean contains(Object name)
DataflowVariable<?> remove(Object name)
void clear()
int size()
boolean isEmpty()
Set<Object> keySet()
```

#### Inspection

```groovy
Map<Object, Object> getBoundValues()
Set<Object> getBoundProperties()
Set<Object> getUnboundProperties()
```

### DataflowFactory

```groovy
<T> DataflowVariable<T> createDataflowVariable()
<T> DataflowVariable<T> createDataflowVariable(T initialValue)
<T> DataflowVariable<T> task(Closure<T> task)
ExecutorService getExecutor()
```

## Advanced Usage

### DFV-to-DFV Wiring

When you assign one DataflowVariable to another in a Dataflows container, they become wired together:

```groovy
Dataflows df = new Dataflows()

DataflowVariable<Integer> source = new DataflowVariable<>()
df.result = source

// Later, when source is bound...
source.bind(100)

// ...df.result automatically receives the same value
println df.result  // 100
```

### Chaining Complex Transformations

```groovy
DataflowFactory factory = new DataflowFactory()

DataflowVariable<Integer> step1 = factory.task {
    // Initial computation
    10
}

DataflowVariable<Integer> step2 = step1.then { it * 2 }

DataflowVariable<String> step3 = step2.then { value ->
    "Computed: $value"
}

DataflowVariable<String> withRecovery = step3.recover { error ->
    "Failed: ${error.message}"
}

println withRecovery.get()
```

### Operator Composition

```groovy
DataflowVariable<Integer> a = new DataflowVariable<>()
DataflowVariable<Integer> b = new DataflowVariable<>()

// Compose using operators
DataflowVariable<Integer> sum = a + b
DataflowVariable<Integer> product = a * b
DataflowVariable<Integer> complex = (a + b) * (a - b)

// Bind inputs
a.bind(10)
b.bind(5)

println sum.get()      // 15
println product.get()  // 50
println complex.get()  // 75
```

### Error Handling

```groovy
DataflowVariable<Integer> var = new DataflowVariable<>()

var.whenAvailable { value ->
    println "Success: $value"
}

var.whenError { error ->
    println "Error: ${error.message}"
}

// Bind an error
var.bindError(new RuntimeException("Computation failed"))

// Or recover from errors
DataflowVariable<Integer> recovered = var.recover { error ->
    0  // Default value
}

println recovered.get()  // 0
```

### Custom Thread Pools

```groovy
import org.softwood.pool.ConcurrentPool
import java.util.concurrent.Executors

// Create custom pool
ExecutorService customExecutor = Executors.newFixedThreadPool(4)
ConcurrentPool pool = new ConcurrentPool(customExecutor)

// Use for factory
DataflowFactory factory = new DataflowFactory(pool)

// Or directly for variables
DataflowVariable<String> var = new DataflowVariable<>(pool)
```

### Timeout Handling

```groovy
import java.util.concurrent.TimeUnit

DataflowVariable<Integer> var = new DataflowVariable<>()

// Will wait up to 5 seconds
Integer result = var.get(5, TimeUnit.SECONDS)

if (result == null) {
    println "Timeout occurred"
    // var now contains a TimeoutException error
}
```

## Best Practices

### 1. Always Handle Errors

```groovy
DataflowVariable<Integer> var = new DataflowVariable<>()

var.whenAvailable { value ->
    // Success path
}

var.whenError { error ->
    // Error path
    log.error("Computation failed", error)
}
```

### 2. Avoid Blocking in Listeners

Listeners execute on the thread pool. Long-blocking operations should be avoided:

```groovy
// BAD: Blocking in listener
var.whenAvailable { value ->
    Thread.sleep(10000)  // Blocks pool thread!
}

// GOOD: Chain to new task
var.whenAvailable { value ->
    factory.task {
        // Long operation in new task
        processingLongOperation(value)
    }
}
```

### 3. Use Appropriate Access Methods

```groovy
// Blocking - use when you need the value immediately
Integer value = var.get()

// Non-blocking - use for conditional logic
Integer maybeValue = var.getNonBlocking()
if (maybeValue != null) {
    // Use value
}

// Async - use for continuation without blocking
var.whenAvailable { value ->
    // React to value
}
```

### 4. Leverage Dataflows for Named Coordination

```groovy
Dataflows df = new Dataflows()

// Clear, self-documenting coordination
df.userInput = getUserInput()
df.validation = df.userInput.then { validateInput(it) }
df.processed = df.validation.then { processData(it) }
df.result = df.processed.then { formatResult(it) }
```

### 5. Clean Up Resources

```groovy
DataflowFactory factory = new DataflowFactory()

try {
    // Use factory...
} finally {
    factory.executor.shutdown()
}
```

### 6. Single Assignment Discipline

Never attempt to bind a variable twice:

```groovy
// BAD: Will throw IllegalStateException
DataflowVariable<Integer> var = new DataflowVariable<>()
var.bind(10)
var.bind(20)  // ERROR!

// GOOD: Create new variable for new value
DataflowVariable<Integer> result = var.then { it * 2 }
```

### 7. Use Type Parameters

```groovy
// Provides better type safety and IDE support
DataflowVariable<String> typed = new DataflowVariable<String>()
DataflowVariable untyped = new DataflowVariable()  // Less safe
```

## Examples

### Producer-Consumer Pattern

```groovy
Dataflows df = new Dataflows()

// Producer
Thread.start {
    df.items = (1..100).collect { "Item-$it" }
}

// Consumer
Thread.start {
    def items = df.items
    items.each { println "Processing: $it" }
}
```

### Pipeline Processing

```groovy
DataflowFactory factory = new DataflowFactory()

DataflowVariable<List<String>> fetchData = factory.task {
    // Fetch from database
    ["record1", "record2", "record3"]
}

DataflowVariable<List<String>> transformed = fetchData.then { records ->
    records.collect { it.toUpperCase() }
}

DataflowVariable<Integer> counted = transformed.then { records ->
    records.size()
}

println "Total records: ${counted.get()}"
```

### Parallel Computation

```groovy
Dataflows df = new Dataflows()
DataflowFactory factory = new DataflowFactory()

// Start parallel computations
df.task1 = factory.task { computeExpensiveResult1() }
df.task2 = factory.task { computeExpensiveResult2() }
df.task3 = factory.task { computeExpensiveResult3() }

// Combine results
def results = [df.task1, df.task2, df.task3]
println "All results: $results"
```

### Event-Driven Workflow

```groovy
Dataflows df = new Dataflows()

df.userRegistered { user ->
    println "Sending welcome email to ${user.email}"
    sendWelcomeEmail(user)
}

df.userRegistered { user ->
    println "Adding to mailing list: ${user.email}"
    addToMailingList(user)
}

// Later, when registration completes...
df.userRegistered = new User(email: "user@example.com")
```

---

## License

This library is part of the Softwood GroovyConcurrentUtils project.

## Support

For issues, questions, or contributions, please contact the project maintainers.