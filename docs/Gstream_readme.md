# Gstream - Groovy Stream Wrapper

A fluent, idiomatic Groovy wrapper around Java Streams that combines the power of Java's Stream API with Groovy's closure syntax and collection methods.

## Table of Contents

1. [Overview](#overview)
2. [Features](#features)
3. [Quick Start](#quick-start)
4. [Internal Architecture](#internal-architecture)
5. [Creating Streams](#creating-streams)
6. [Builder DSL](#builder-dsl)
7. [Intermediate Operations](#intermediate-operations)
8. [Terminal Operations](#terminal-operations)
9. [Parallel Streams](#parallel-streams)
10. [Error Handling](#error-handling)
11. [Statistical Operations](#statistical-operations)
12. [Advanced Stream Operations](#advanced-stream-operations)
13. [Groovy Collections Integration](#groovy-collections-integration)
14. [Advanced Examples](#advanced-examples)
15. [API Reference](#api-reference)

## Overview

Gstream is a type-safe, compile-time checked wrapper around Java's Stream API that allows you to use Groovy closures instead of lambda expressions. It provides a more natural Groovy experience while maintaining the performance and capabilities of Java Streams.

The class bridges the gap between Groovy's familiar collection methods and Java's powerful stream processing, giving you the best of both worlds.

## Features

- **Groovy Closure Support**: Use familiar Groovy closure syntax instead of Java lambdas
- **Type Safety**: Full `@CompileStatic` support for performance and compile-time checking
- **Fluent API**: Chain operations naturally with method chaining
- **DSL Builder**: Declarative stream construction using Groovy DSL syntax
- **Parallel Processing**: Built-in support for parallel stream execution
- **Primitive Stream Support**: Integration with IntStream, LongStream, DoubleStream
- **Error Handling**: Built-in error recovery with `onErrorContinue`
- **Statistical Operations**: Summarizing operations for numeric streams
- **Advanced Operations**: `zip`, `zipWithIndex`, `chunked`, `windowed`, `tee`, `mapMulti`
- **Groovy Integration**: Fallback to Groovy collection methods via `methodMissing`
- **Rich Factory Methods**: Multiple ways to create streams from various sources
- **Comprehensive Operations**: All standard stream operations plus Groovy-friendly aliases

## Quick Start

```groovy
import org.softwood.gstream.Gstream

// Basic filtering and mapping
def result = Gstream.of(1, 2, 3, 4, 5)
    .filter { it % 2 == 0 }
    .map { it * 2 }
    .toList()
// Result: [4, 8]

// Using the DSL builder
def squares = Gstream.build {
    from(1..10)
    filter { it % 2 == 0 }
    map { it * it }
}.toList()
// Result: [4, 16, 36, 64, 100]

// Zipping streams
def zipped = Gstream.of(1, 2, 3)
    .zip(Gstream.of("a", "b", "c"))
    .toList()
// Result: [(1, 'a'), (2, 'b'), (3, 'c')]

// Error handling
def result = Gstream.of(1, 2, 0, 4)
    .onErrorContinue { ex, val -> println "Error with $val: ${ex.message}" }
    .map { 10 / it }
    .toList()
// Skips division by zero, continues processing
```

## Internal Architecture

Gstream wraps a Java `Stream<T>` internally and delegates all operations to it while converting Groovy closures to appropriate Java functional interfaces:

- `Closure<Boolean>` → `Predicate<T>`
- `Closure<R>` → `Function<T, R>`
- `Closure<Void>` → `Consumer<T>`
- `Closure<Integer>` → `Comparator<T>`
- `Closure<T>` → `BinaryOperator<T>` or `UnaryOperator<T>`

All intermediate operations return a new `Gstream` instance, allowing for fluent method chaining. Terminal operations consume the stream and return results.

The class uses `@CompileStatic` for performance, with `@CompileDynamic` for the `GstreamBuilder` to enable DSL syntax, and `@TypeChecked(TypeCheckingMode.SKIP)` only on the `methodMissing` method to enable dynamic Groovy collection method fallback.

## Creating Streams

### From Varargs
```groovy
def stream = Gstream.of(1, 2, 3, 4, 5)
```

### From Collections
```groovy
def list = [1, 2, 3, 4, 5]
def stream = Gstream.from(list)
```

### From Ranges
```groovy
def stream = Gstream.of(1..100)
def charStream = Gstream.of('a'..'z')
```

### From Existing Streams
```groovy
def javaStream = list.stream()
def stream = Gstream.from(javaStream)
```

### From Primitive Streams
```groovy
def intStream = IntStream.range(1, 100)
def gstream = Gstream.fromIntStream(intStream)

def longStream = LongStream.of(1L, 2L, 3L)
def gstream = Gstream.fromLongStream(longStream)

def doubleStream = DoubleStream.of(1.0, 2.0, 3.0)
def gstream = Gstream.fromDoubleStream(doubleStream)
```

### Empty Streams
```groovy
def empty = Gstream.empty()
```

### Generated Streams
```groovy
// Infinite stream of random numbers
def random = Gstream.generate { Math.random() }
    .limit(10)

// Infinite sequence starting from seed
def sequence = Gstream.iterate(0) { it + 1 }
    .limit(100)
```

### Concatenating Streams
```groovy
def stream1 = Gstream.of(1, 2, 3)
def stream2 = Gstream.of(4, 5, 6)
def combined = Gstream.concat(stream1, stream2)
// Result: [1, 2, 3, 4, 5, 6]
```

### Parallel Streams
```groovy
def parallel = Gstream.ofParallel(1, 2, 3, 4, 5)
def parallelFromList = Gstream.fromParallel(myList)
```

## Builder DSL

Gstream provides a powerful DSL for declarative stream construction using the `GstreamBuilder`.

### Basic Builder Usage
```groovy
def result = Gstream.build {
    elements 1, 2, 3, 4, 5
    filter { it % 2 == 0 }
    map { it * 10 }
}.toList()
// Result: [20, 40]
```

### Builder with Range
```groovy
def result = Gstream.build {
    from(1..100)
    filter { it % 3 == 0 }
    limit(5)
}.toList()
// Result: [3, 6, 9, 12, 15]
```

### Builder with Collection
```groovy
def result = Gstream.build {
    fromCollection([10, 20, 30, 40])
    map { it / 10 }
    sorted()
}.toList()
```

### Parallel Builder
```groovy
def result = Gstream.build {
    from(1..1000)
    parallel(true)
    filter { isPrime(it) }
    limit(10)
}.toList()
```

### Incremental Builder
```groovy
def builder = Gstream.builder()
builder.add(1)
builder.add(2, 3, 4)
def stream = builder.build()
```

### Complex Pipeline with Builder
```groovy
def result = Gstream.build {
    from(people)
    filter { it.age >= 18 }
    sortedBy { it.name }
    distinctBy { it.email }
    map { [name: it.name, age: it.age] }
}.toList()
```

## Intermediate Operations

Intermediate operations are lazy and return a new `Gstream` for chaining.

### filter / findAll
Filter elements based on a predicate.
```groovy
Gstream.of(1, 2, 3, 4, 5)
    .filter { it % 2 == 0 }
    .toList()
// Result: [2, 4]

// Groovy-style alias
Gstream.of(1, 2, 3, 4, 5)
    .findAll { it > 2 }
    .toList()
// Result: [3, 4, 5]
```

### map
Transform each element.
```groovy
Gstream.of("a", "b", "c")
    .map { it.toUpperCase() }
    .toList()
// Result: ["A", "B", "C"]
```

### flatMap / flatMapIterable
Transform each element into a stream and flatten.
```groovy
// flatMap with Stream
Gstream.of([1, 2], [3, 4])
    .flatMap { list -> Stream.of(*list) }
    .toList()
// Result: [1, 2, 3, 4]

// flatMapIterable for convenience
Gstream.of([[1, 2], [3, 4]])
    .flatMapIterable { it }
    .toList()
// Result: [1, 2, 3, 4]
```

### distinct / distinctBy
Remove duplicates.
```groovy
Gstream.of(1, 2, 2, 3, 3, 3)
    .distinct()
    .toList()
// Result: [1, 2, 3]

// Distinct by key
def people = [
    [name: "Alice", age: 30],
    [name: "Bob", age: 30],
    [name: "Charlie", age: 25]
]
Gstream.from(people)
    .distinctBy { it.age }
    .toList()
// Result: [[name: "Alice", age: 30], [name: "Charlie", age: 25]]
```

### sorted / sortedBy
Sort elements naturally or with a comparator.
```groovy
Gstream.of(3, 1, 4, 1, 5)
    .sorted()
    .toList()
// Result: [1, 1, 3, 4, 5]

// With custom comparator
Gstream.of("apple", "pie", "a")
    .sorted { a, b -> a.length() <=> b.length() }
    .toList()
// Result: ["a", "pie", "apple"]

// Sort by extracted key
Gstream.from(people)
    .sortedBy { it.age }
    .toList()
```

### peek
Perform an action on each element without consuming the stream.
```groovy
Gstream.of(1, 2, 3)
    .peek { println "Processing: $it" }
    .map { it * 2 }
    .toList()
```

### limit / skip
Control stream size.
```groovy
Gstream.of(1..100)
    .skip(10)
    .limit(5)
    .toList()
// Result: [11, 12, 13, 14, 15]
```

### takeWhile / dropWhile
Take or drop elements based on a predicate.
```groovy
Gstream.of(1, 2, 3, 4, 5)
    .takeWhile { it < 4 }
    .toList()
// Result: [1, 2, 3]

Gstream.of(1, 2, 3, 4, 5)
    .dropWhile { it < 3 }
    .toList()
// Result: [3, 4, 5]
```

### unordered
Allow unordered processing for better parallel performance.
```groovy
Gstream.of(1..1000)
    .parallel()
    .unordered()
    .distinct()
    .toList()
```

### onClose
Register a close handler.
```groovy
def resource = openResource()
Gstream.from(resource.stream())
    .onClose { resource.close() }
    .forEach { process(it) }
```

## Terminal Operations

Terminal operations consume the stream and produce a result.

### toList / toSet
Collect elements into collections.
```groovy
def list = Gstream.of(1, 2, 3).toList()
def set = Gstream.of(1, 1, 2, 2, 3).toSet()

// Unmodifiable versions
def immutableList = Gstream.of(1, 2, 3).toUnmodifiableList()
def immutableSet = Gstream.of(1, 2, 3).toUnmodifiableSet()
```

### toCollection
Collect into a specific collection type.
```groovy
def linkedList = Gstream.of(1, 2, 3)
    .toCollection { new LinkedList() }

def treeSet = Gstream.of(3, 1, 2)
    .toCollection { new TreeSet() }
```

### toArray
Collect to array.
```groovy
def array = Gstream.of(1, 2, 3, 4, 5).toArray()
```

### forEach
Perform an action on each element.
```groovy
Gstream.of(1, 2, 3)
    .forEach { println it }
```

### reduce
Combine elements into a single result.
```groovy
// With identity value
def sum = Gstream.of(1, 2, 3, 4, 5)
    .reduce(0) { acc, val -> acc + val }
// Result: 15

// Without identity (returns Optional)
def product = Gstream.of(1, 2, 3, 4)
    .reduce { acc, val -> acc * val }
// Result: Optional[24]
```

### collect
Use Java Collectors.
```groovy
import java.util.stream.Collectors

def joined = Gstream.of("a", "b", "c")
    .collect(Collectors.joining(", "))
// Result: "a, b, c"
```

### groupBy
Group elements by a classifier.
```groovy
def people = [
    [name: "Alice", age: 30],
    [name: "Bob", age: 25],
    [name: "Charlie", age: 30]
]

def byAge = Gstream.from(people)
    .groupBy { it.age }
// Result: [30: [[name: "Alice", age: 30], [name: "Charlie", age: 30]], 
//          25: [[name: "Bob", age: 25]]]

// With downstream collector
def countByAge = Gstream.from(people)
    .groupBy({ it.age }, Collectors.counting())
// Result: [30: 2, 25: 1]
```

### partitioningBy
Partition elements into two groups.
```groovy
def partitioned = Gstream.of(1, 2, 3, 4, 5, 6)
    .partitioningBy { it % 2 == 0 }
// Result: [true: [2, 4, 6], false: [1, 3, 5]]
```

### toMap
Convert to map with various strategies.
```groovy
// Key mapper only (elements become values)
def map = Gstream.of("apple", "banana", "cherry")
    .toMap { it[0] }
// Result: [a: "apple", b: "banana", c: "cherry"]

// Key and value mappers
def map = Gstream.of(1, 2, 3)
    .toMap({ it }, { it * it })
// Result: [1: 1, 2: 4, 3: 9]

// With merge function for duplicates
def map = Gstream.of("apple", "apricot", "banana")
    .toMap({ it[0] }, { it }, { a, b -> "$a, $b" })
// Result: [a: "apple, apricot", b: "banana"]

// Concurrent map
def concurrent = Gstream.of(1..100)
    .parallel()
    .toConcurrentMap({ it }, { it * it }, { a, b -> a })
```

### joining
Join elements into a string.
```groovy
def result = Gstream.of(1, 2, 3, 4)
    .joining(", ")
// Result: "1, 2, 3, 4"

def csv = Gstream.from(people)
    .map { "${it.name},${it.age}" }
    .joining("\n")
```

### collectList
Map and collect in one operation.
```groovy
def doubled = Gstream.of(1, 2, 3)
    .collectList { it * 2 }
// Result: [2, 4, 6]
```

### findFirst / findAny
Find elements.
```groovy
def first = Gstream.of(1, 2, 3)
    .findFirst()
// Result: Optional[1]

def any = Gstream.of(1, 2, 3)
    .parallel()
    .findAny()
// Result: Optional[any element]
```

### Matching Operations
Test predicates against elements.
```groovy
def hasEven = Gstream.of(1, 2, 3, 4)
    .anyMatch { it % 2 == 0 }
// Result: true

def allPositive = Gstream.of(1, 2, 3, 4)
    .allMatch { it > 0 }
// Result: true

def noneNegative = Gstream.of(1, 2, 3, 4)
    .noneMatch { it < 0 }
// Result: true
```

### count
Count elements.
```groovy
def count = Gstream.of(1, 2, 3, 4, 5)
    .filter { it % 2 == 0 }
    .count()
// Result: 2
```

### max / min / maxBy / minBy
Find maximum and minimum elements.
```groovy
// Natural ordering
def max = Gstream.of(1, 5, 3, 9, 2).max()
def min = Gstream.of(1, 5, 3, 9, 2).min()

// With comparator
def longest = Gstream.of("a", "abc", "ab")
    .max { a, b -> a.length() <=> b.length() }

// By extracted key
def oldest = Gstream.from(people)
    .maxBy { it.age }

def youngest = Gstream.from(people)
    .minBy { it.age }
```

## Parallel Streams

Gstream supports parallel processing for improved performance on large datasets.

### Creating Parallel Streams
```groovy
// Create parallel from the start
def parallel = Gstream.ofParallel(1..1000)

// Convert sequential to parallel
def stream = Gstream.of(1..1000)
    .parallel()
```

### Switching Between Sequential and Parallel
```groovy
def result = Gstream.of(1..1000)
    .parallel()           // Enable parallel processing
    .map { it * it }
    .sequential()         // Switch back to sequential
    .sorted()
    .toList()
```

### Checking Parallel Status
```groovy
def stream = Gstream.of(1, 2, 3).parallel()
println stream.isParallel()  // true
```

### Parallel Processing Example
```groovy
// Heavy computation benefits from parallel processing
def result = Gstream.of(1..10000)
    .parallel()
    .filter { isPrime(it) }
    .toList()
```

### Important Notes for Parallel Streams
- Closures must be stateless and thread-safe
- Order may not be preserved unless using `forEachOrdered()`
- Use `unordered()` for better parallel performance when order doesn't matter

## Error Handling

Gstream provides robust error handling with the `onErrorContinue` operation.

### Basic Error Handling
```groovy
def result = Gstream.of(1, 2, 0, 4)
    .onErrorContinue { ex, val -> 
        println "Error processing $val: ${ex.message}" 
    }
    .map { 10 / it }
    .toList()
// Logs error for 0, continues with [10, 5, 2]
```

### Error Handling in Complex Pipelines
```groovy
def result = Gstream.from(files)
    .onErrorContinue { ex, file ->
        log.error("Failed to process $file", ex)
    }
    .map { readFile(it) }
    .filter { it.size() > 0 }
    .flatMapIterable { parseRecords(it) }
    .toList()
```

### Upstream vs Downstream Errors
```groovy
// Handles errors in both map and forEach
Gstream.of("1", "2", "invalid", "4")
    .onErrorContinue { ex, val ->
        println "Skipping: $val - ${ex.class.simpleName}"
    }
    .map { it as Integer }  // NumberFormatException for "invalid"
    .forEach { println it }
```

## Statistical Operations

Gstream provides convenient statistical summarization for numeric streams.

### Integer Statistics
```groovy
def stats = Gstream.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
    .summarizingInt { it }

println "Count: ${stats.count}"
println "Sum: ${stats.sum}"
println "Min: ${stats.min}"
println "Max: ${stats.max}"
println "Average: ${stats.average}"
```

### Long Statistics
```groovy
def stats = Gstream.of(1L..1000000L)
    .summarizingLong { it }

println "Sum of first million: ${stats.sum}"
```

### Double Statistics
```groovy
def stats = Gstream.of([1.5, 2.7, 3.2, 4.8])
    .summarizingDouble { it }

println "Average: ${stats.average}"
println "Total: ${stats.sum}"
```

### Real-World Example
```groovy
def products = [
    [name: "Widget", price: 19.99, quantity: 5],
    [name: "Gadget", price: 49.99, quantity: 3],
    [name: "Doohickey", price: 9.99, quantity: 10]
]

def revenueStats = Gstream.from(products)
    .summarizingDouble { it.price * it.quantity }

println "Total revenue: \$${revenueStats.sum}"
println "Average order value: \$${revenueStats.average}"
println "Largest order: \$${revenueStats.max}"
```

## Advanced Stream Operations

Gstream provides several advanced operations for sophisticated stream processing.

### zip - Combine Two Streams
```groovy
def numbers = Gstream.of(1, 2, 3)
def letters = Gstream.of("a", "b", "c")

def zipped = numbers.zip(letters).toList()
// Result: [(1, 'a'), (2, 'b'), (3, 'c')]

// Practical example: parallel arrays
def names = Gstream.of("Alice", "Bob", "Charlie")
def ages = Gstream.of(30, 25, 35)

def people = names.zip(ages)
    .map { tuple -> [name: tuple.first, age: tuple.second] }
    .toList()
```

### zipWithIndex - Add Index to Elements
```groovy
def indexed = Gstream.of("a", "b", "c", "d")
    .zipWithIndex()
    .toList()
// Result: [(0, 'a'), (1, 'b'), (2, 'c'), (3, 'd')]

// Create indexed map
def map = Gstream.of("apple", "banana", "cherry")
    .zipWithIndex()
    .toMap({ it.first }, { it.second })
// Result: [0: "apple", 1: "banana", 2: "cherry"]
```

### chunked - Split into Fixed-Size Chunks
```groovy
def chunks = Gstream.of(1..10)
    .chunked(3)
    .toList()
// Result: [[1, 2, 3], [4, 5, 6], [7, 8, 9], [10]]

// Process data in batches
Gstream.from(largeDataset)
    .chunked(100)
    .forEach { batch -> 
        database.batchInsert(batch)
    }
```

### windowed - Sliding Window
```groovy
def windows = Gstream.of(1, 2, 3, 4, 5)
    .windowed(3)
    .toList()
// Result: [[1, 2, 3], [2, 3, 4], [3, 4, 5]]

// Calculate moving averages
def movingAvg = Gstream.from(timeSeries)
    .windowed(5)
    .map { window -> window.sum() / window.size() }
    .toList()
```

### tee - Side Effect Without Consuming
```groovy
def result = Gstream.of(1, 2, 3, 4, 5)
    .filter { it % 2 == 0 }
    .tee { println "After filter: $it" }
    .map { it * 10 }
    .tee { println "After map: $it" }
    .toList()
// Prints intermediate values while continuing the pipeline
```

### mapMulti - One-to-Many Mapping
```groovy
// Expand each element into multiple elements
def result = Gstream.of(1, 2, 3)
    .mapMulti { value, emit ->
        emit(value)
        emit(value * 10)
        emit(value * 100)
    }
    .toList()
// Result: [1, 10, 100, 2, 20, 200, 3, 30, 300]

// Conditional emission
def result = Gstream.of(1, 2, 3, 4, 5)
    .mapMulti { value, emit ->
        if (value % 2 == 0) {
            emit(value)
            emit(value * 2)
        }
    }
    .toList()
// Result: [2, 4, 4, 8]
```

### Combining Advanced Operations
```groovy
// Process data in overlapping windows with progress tracking
def result = Gstream.of(1..20)
    .windowed(5)
    .zipWithIndex()
    .tee { idx, window -> 
        println "Processing window ${idx.first}: ${window.second}"
    }
    .map { it.second.sum() }
    .toList()
```

## Groovy Collections Integration

Gstream provides seamless integration with Groovy's collection methods through `methodMissing`. When you call a method that doesn't exist on Gstream, it automatically converts to a list and delegates to Groovy's collection API.

### Dynamic Method Delegation
```groovy
// Use Groovy's collection methods directly
def result = Gstream.of(1, 2, 3, 4, 5)
    .findAll { it % 2 == 0 }
    .sum()  // Delegates to List.sum()
// Result: 6

// Use Groovy's max/min
def max = Gstream.of(1, 5, 3, 9, 2)
    .max()
// Result: 9

// Use Groovy's each
Gstream.of("a", "b", "c")
    .each { println it.toUpperCase() }
```

### Hybrid Approach
Combine stream operations with Groovy methods.
```groovy
def result = Gstream.of(1..100)
    .filter { it % 2 == 0 }
    .map { it * it }
    .take(5)  // Groovy's take method
// Result: [4, 16, 36, 64, 100]
```

### Warning
The `methodMissing` delegation materializes the stream into a List, which may be inefficient for large streams. Prefer native Gstream methods when possible.

## Advanced Examples

### Processing CSV-like Data
```groovy
def csvData = [
    "John,30,Engineer",
    "Jane,25,Designer",
    "Bob,35,Manager",
    "Alice,28,Engineer"
]

def engineers = Gstream.from(csvData)
    .map { it.split(",") }
    .filter { it[2] == "Engineer" }
    .map { [name: it[0], age: it[1] as Integer] }
    .sortedBy { it.age }
    .toList()
// Result: [[name: "Alice", age: 28], [name: "John", age: 30]]
```

### Statistical Analysis with Error Handling
```groovy
def numbers = ["10", "20", "invalid", "30", "40"]

def stats = Gstream.from(numbers)
    .onErrorContinue { ex, val ->
        println "Skipping invalid number: $val"
    }
    .map { it as Integer }
    .summarizingInt { it }

println "Valid numbers: ${stats.count}"
println "Sum: ${stats.sum}"
println "Average: ${stats.average}"
```

### Word Frequency Counter
```groovy
def text = """
    The quick brown fox jumps over the lazy dog.
    The dog was not that lazy after all.
"""

def wordFreq = Gstream.from(text.toLowerCase().split(/\W+/))
    .filter { it.length() > 0 }
    .groupBy { it }
    .collectEntries { word, occurrences -> 
        [word, occurrences.size()] 
    }
    .sort { -it.value }
// Result: [the: 2, lazy: 2, dog: 2, ...]
```

### Pipeline with Multiple Transformations
```groovy
def result = Gstream.of(1..20)
    .filter { it % 2 == 0 }           // Even numbers
    .map { it * it }                   // Square them
    .filter { it < 200 }               // Keep only < 200
    .sorted { a, b -> b <=> a }        // Sort descending
    .limit(5)                          // Take top 5
    .toList()
// Result: [196, 144, 100, 64, 36]
```

### Flattening Nested Structures
```groovy
def departments = [
    [name: "Engineering", employees: ["Alice", "Bob"]],
    [name: "Sales", employees: ["Charlie", "David", "Eve"]],
    [name: "HR", employees: ["Frank"]]
]

def allEmployees = Gstream.from(departments)
    .flatMapIterable { dept -> dept.employees }
    .sorted()
    .toList()
// Result: ["Alice", "Bob", "Charlie", "David", "Eve", "Frank"]
```

### Batch Processing with Progress Tracking
```groovy
def totalRecords = 1000
def result = Gstream.of(1..totalRecords)
    .chunked(100)
    .zipWithIndex()
    .tee { idx, batch ->
        def progress = ((idx.first + 1) * 100) / ((totalRecords / 100) as Integer)
        println "Processing batch ${idx.first + 1}: ${progress}% complete"
    }
    .flatMapIterable { it.second }
    .map { processRecord(it) }
    .toList()
```

### Time Series Analysis
```groovy
def prices = [100.0, 102.5, 101.3, 105.0, 103.8, 106.2, 104.5]

// Calculate moving averages
def movingAvg = Gstream.from(prices)
    .windowed(3)
    .map { window -> window.sum() / window.size() }
    .toList()

// Find price changes
def changes = Gstream.from(prices)
    .windowed(2)
    .map { window -> 
        [(window[1] - window[0]), window[1] > window[0] ? "↑" : "↓"]
    }
    .toList()
```

### Parallel Data Processing with Error Resilience
```groovy
def files = findAllFiles("data/*.csv")

def results = Gstream.from(files)
    .parallel()
    .onErrorContinue { ex, file ->
        log.error("Failed to process ${file?.name}", ex)
    }
    .map { file -> parseFile(file) }
    .flatMapIterable { it }
    .filter { record -> record.isValid() }
    .groupBy { it.category }
    .toMap({ it.key }, { it.value.size() })
```

## API Reference

### Factory Methods

| Method | Description |
|--------|-------------|
| `of(T... elements)` | Create stream from varargs |
| `of(Range<T> range)` | Create stream from Groovy range |
| `from(Collection<T> collection)` | Create stream from collection |
| `from(Stream<T> stream)` | Wrap existing Java stream |
| `fromIntStream(IntStream)` | Wrap primitive int stream (boxed) |
| `fromLongStream(LongStream)` | Wrap primitive long stream (boxed) |
| `fromDoubleStream(DoubleStream)` | Wrap primitive double stream (boxed) |
| `empty()` | Create empty stream |
| `generate(Closure<T> supplier)` | Create infinite generated stream |
| `iterate(T seed, Closure<T> next)` | Create infinite iterative stream |
| `ofParallel(T... elements)` | Create parallel stream from varargs |
| `fromParallel(Collection<T>)` | Create parallel stream from collection |
| `concat(Gstream<T>, Gstream<T>)` | Concatenate two streams |
| `builder()` | Create stream builder |
| `build(Closure)` | Create stream using DSL |

### Intermediate Operations

| Method | Description |
|--------|-------------|
| `filter(Closure<Boolean> predicate)` | Filter elements |
| `findAll(Closure<Boolean> predicate)` | Groovy-style filter alias |
| `map(Closure<R> mapper)` | Transform elements |
| `flatMap(Closure<Stream<R>> mapper)` | Transform and flatten streams |
| `flatMapIterable(Closure<Iterable<R>>)` | Transform and flatten iterables |
| `distinct()` | Remove duplicates |
| `distinctBy(Closure<K> keyExtractor)` | Remove duplicates by key |
| `sorted()` | Sort naturally |
| `sorted(Closure<Integer> comparator)` | Sort with comparator |
| `sortedBy(Closure<K> keyExtractor)` | Sort by extracted key |
| `peek(Closure<Void> action)` | Perform action without consuming |
| `limit(long maxSize)` | Limit stream size |
| `skip(long n)` | Skip first n elements |
| `takeWhile(Closure<Boolean>)` | Take while predicate is true |
| `dropWhile(Closure<Boolean>)` | Drop while predicate is true |
| `parallel()` | Enable parallel processing |
| `sequential()` | Disable parallel processing |
| `unordered()` | Allow unordered processing |
| `onClose(Closure<Void>)` | Register close handler |
| `zip(Gstream<U>)` | Zip with another stream |
| `zipWithIndex()` | Pair elements with index |
| `chunked(int size)` | Split into fixed-size chunks |
| `windowed(int size)` | Create sliding windows |
| `tee(Closure)` | Execute side-effect, pass through |
| `mapMulti(Closure)` | One-to-many element mapping |
| `onErrorContinue(Closure)` | Continue on errors |

### Terminal Operations

| Method | Description |
|--------|-------------|
| `forEach(Closure<Void> action)` | Perform action on each element |
| `toList()` | Collect to mutable List |
| `toUnmodifiableList()` | Collect to immutable List |
| `toSet()` | Collect to mutable Set |
| `toUnmodifiableSet()` | Collect to immutable Set |
| `toArray()` | Collect to array |
| `toCollection(Closure)` | Collect to specific collection type |
| `reduce(T identity, Closure<T>)` | Reduce with identity |
| `reduce(Closure<T> accumulator)` | Reduce without identity |
| `collect(Collector)` | Collect with Java Collector |
| `groupBy(Closure<K> classifier)` | Group by classifier |
| `groupBy(Closure<K>, Collector)` | Group by with downstream |
| `partitioningBy(Closure<Boolean>)` | Partition into two groups |
| `toMap(Closure<K> keyMapper)` | Convert to map (identity values) |
| `toMap(Closure<K>, Closure<V>)` | Convert to map with mappers |
| `toMap(Closure<K>, Closure<V>, Closure<V>)` | Convert with merge function |
| `toConcurrentMap(Closure<K>, Closure<V>, Closure<V>)` | Convert to concurrent map |
| `joining(String delimiter)` | Join to string |
| `collectList(Closure<R> mapper)` | Map and collect to list |
| `findFirst()` | Find first element |
| `findAny()` | Find any element |
| `anyMatch(Closure<Boolean>)` | Test if any match |
| `allMatch(Closure<Boolean>)` | Test if all match |
| `noneMatch(Closure<Boolean>)` | Test if none match |
| `count()` | Count elements |
| `max()` | Find maximum (natural order) |
| `max(Closure<Integer>)` | Find maximum (comparator) |
| `maxBy(Closure<K>)` | Find maximum by key |
| `min()` | Find minimum (natural order) |
| `min(Closure<Integer>)` | Find minimum (comparator) |
| `minBy(Closure<K>)` | Find minimum by key |
| `summarizingInt(Closure<Integer>)` | Integer statistics |
| `summarizingLong(Closure<Long>)` | Long statistics |
| `summarizingDouble(Closure<Double>)` | Double statistics |

### Utility Methods

| Method | Description |
|--------|-------------|
| `getStream()` | Access underlying Java Stream |
| `isParallel()` | Check if stream is parallel |
| `close()` | Close stream and run handlers |
| `methodMissing(String, Object)` | Delegate to Groovy collection methods |

### GstreamBuilder DSL Methods

| Method | Description |
|--------|-------------|
| `elements(T... items)` | Define source elements |
| `add(T... items)` | Add elements to builder |
| `from(Object source)` | Set source (collection or range) |
| `fromCollection(Collection)` | Set collection source |
| `fromRange(Range)` | Set range source |
| `parallel(boolean)` | Enable/disable parallel mode |
| `filter(Closure)` | Add filter operation |
| `map(Closure)` | Add map operation |
| `flatMapIterable(Closure)` | Add flatMapIterable operation |
| `distinct()` | Add distinct operation |
| `distinctBy(Closure)` | Add distinctBy operation |
| `sorted()` | Add sort operation |
| `sorted(Closure)` | Add sort with comparator |
| `sortedBy(Closure)` | Add sort by key |
| `skip(long)` | Add skip operation |
| `limit(long)` | Add limit operation |
| `build()` | Build the Gstream |

---

**Package**: `org.softwood.gstream`  
**Class**: `Gstream<T>`  
**Version**: 2.0  
**Annotations**: `@CompileStatic`  
**Builder**: `GstreamBuilder<T>` (`@CompileDynamic`)
```