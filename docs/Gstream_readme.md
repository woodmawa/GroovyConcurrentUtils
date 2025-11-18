# Gstream - Groovy Stream Wrapper

A fluent, idiomatic Groovy wrapper around Java Streams that combines the power of Java's Stream API with Groovy's closure syntax and collection methods.

## Table of Contents

1. [Overview](#overview)
2. [Features](#features)
3. [Quick Start](#quick-start)
4. [Internal Architecture](#internal-architecture)
5. [Creating Streams](#creating-streams)
6. [Intermediate Operations](#intermediate-operations)
7. [Terminal Operations](#terminal-operations)
8. [Parallel Streams](#parallel-streams)
9. [Groovy Collections Integration](#groovy-collections-integration)
10. [Advanced Examples](#advanced-examples)
11. [API Reference](#api-reference)

## Overview

Gstream is a type-safe, compile-time checked wrapper around Java's Stream API that allows you to use Groovy closures instead of lambda expressions. It provides a more natural Groovy experience while maintaining the performance and capabilities of Java Streams.

The class bridges the gap between Groovy's familiar collection methods and Java's powerful stream processing, giving you the best of both worlds.

## Features

- **Groovy Closure Support**: Use familiar Groovy closure syntax instead of Java lambdas
- **Type Safety**: Full `@CompileStatic` support for performance and compile-time checking
- **Fluent API**: Chain operations naturally with method chaining
- **Parallel Processing**: Built-in support for parallel stream execution
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

// Working with ranges
def squares = Gstream.of(1..10)
    .map { it * it }
    .toList()
// Result: [1, 4, 9, 16, 25, 36, 49, 64, 81, 100]

// Grouping data
def words = ["apple", "banana", "apricot", "blueberry"]
def grouped = Gstream.from(words)
    .groupBy { it[0] }
// Result: [a: ["apple", "apricot"], b: ["banana", "blueberry"]]
```

## Internal Architecture

Gstream wraps a Java `Stream<T>` internally and delegates all operations to it while converting Groovy closures to appropriate Java functional interfaces:

- `Closure<Boolean>` → `Predicate<T>`
- `Closure<R>` → `Function<T, R>`
- `Closure<Void>` → `Consumer<T>`
- `Closure<Integer>` → `Comparator<T>`
- `Closure<T>` → `BinaryOperator<T>` or `UnaryOperator<T>`

All intermediate operations return a new `Gstream` instance, allowing for fluent method chaining. Terminal operations consume the stream and return results.

The class uses `@CompileStatic` for performance, with `@TypeChecked(TypeCheckingMode.SKIP)` only on the `methodMissing` method to enable dynamic Groovy collection method fallback.

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
```

### From Existing Streams
```groovy
def javaStream = list.stream()
def stream = Gstream.from(javaStream)
```

### Empty Streams
```groovy
def empty = Gstream.empty()
```

### Generated Streams
```groovy
// Infinite stream of random numbers
def random = Gstream.generate { Math.random() }

// Infinite sequence starting from seed
def fibonacci = Gstream.iterate(0) { it + 1 }
```

### Parallel Streams
```groovy
def parallel = Gstream.ofParallel(1, 2, 3, 4, 5)
def parallelFromList = Gstream.fromParallel(myList)
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

### flatMap
Transform each element into a stream and flatten.
```groovy
Gstream.of([1, 2], [3, 4])
    .flatMap { list -> Stream.of(*list) }
    .toList()
// Result: [1, 2, 3, 4]
```

### distinct
Remove duplicates.
```groovy
Gstream.of(1, 2, 2, 3, 3, 3)
    .distinct()
    .toList()
// Result: [1, 2, 3]
```

### sorted
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

## Terminal Operations

Terminal operations consume the stream and produce a result.

### toList / toSet
Collect elements into collections.
```groovy
def list = Gstream.of(1, 2, 3).toList()
def set = Gstream.of(1, 1, 2, 2, 3).toSet()
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
```

### joining
Join elements into a string.
```groovy
def result = Gstream.of(1, 2, 3, 4)
    .joining(", ")
// Result: "1, 2, 3, 4"
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
// Heavy computation benefit from parallel processing
def result = Gstream.of(1..10000)
    .parallel()
    .filter { isPrime(it) }
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
    .toList()
// Result: [[name: "John", age: 30], [name: "Alice", age: 28]]
```

### Statistical Analysis
```groovy
def numbers = 1..1000

def stats = Gstream.of(numbers)
    .parallel()
    .reduce([sum: 0, count: 0, min: Integer.MAX_VALUE, max: Integer.MIN_VALUE]) { acc, val ->
        [
            sum: acc.sum + val,
            count: acc.count + 1,
            min: Math.min(acc.min, val),
            max: Math.max(acc.max, val)
        ]
    }

println "Average: ${stats.sum / stats.count}"
println "Min: ${stats.min}, Max: ${stats.max}"
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
// Result: [the: 2, quick: 1, brown: 1, fox: 1, ...]
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
    .flatMap { dept -> dept.employees.stream() }
    .sorted()
    .toList()
// Result: ["Alice", "Bob", "Charlie", "David", "Eve", "Frank"]
```

## API Reference

### Factory Methods

| Method | Description |
|--------|-------------|
| `of(T... elements)` | Create stream from varargs |
| `of(Range<T> range)` | Create stream from range |
| `from(Collection<T> collection)` | Create stream from collection |
| `from(Stream<T> stream)` | Wrap existing stream |
| `empty()` | Create empty stream |
| `generate(Closure<T> supplier)` | Create infinite generated stream |
| `iterate(T seed, Closure<T> next)` | Create infinite iterative stream |
| `ofParallel(T... elements)` | Create parallel stream from varargs |
| `fromParallel(Collection<T> collection)` | Create parallel stream from collection |

### Intermediate Operations

| Method | Description |
|--------|-------------|
| `filter(Closure<Boolean> predicate)` | Filter elements |
| `findAll(Closure<Boolean> predicate)` | Groovy-style filter alias |
| `map(Closure<R> mapper)` | Transform elements |
| `flatMap(Closure<Stream<R>> mapper)` | Transform and flatten |
| `distinct()` | Remove duplicates |
| `sorted()` | Sort naturally |
| `sorted(Closure<Integer> comparator)` | Sort with comparator |
| `peek(Closure<Void> action)` | Perform action without consuming |
| `limit(long maxSize)` | Limit stream size |
| `skip(long n)` | Skip first n elements |
| `parallel()` | Enable parallel processing |
| `sequential()` | Disable parallel processing |
| `takeWhile(Closure<Boolean> predicate)` | Take while predicate is true |
| `dropWhile(Closure<Boolean> predicate)` | Drop while predicate is true |

### Terminal Operations

| Method | Description |
|--------|-------------|
| `forEach(Closure<Void> action)` | Perform action on each element |
| `toList()` | Collect to List |
| `toSet()` | Collect to Set |
| `reduce(T identity, Closure<T> accumulator)` | Reduce with identity |
| `reduce(Closure<T> accumulator)` | Reduce without identity |
| `collect(Collector<? super T, A, R> collector)` | Collect with Java Collector |
| `groupBy(Closure<K> classifier)` | Group by classifier |
| `joining(String delimiter)` | Join to string |
| `collectList(Closure<R> mapper)` | Map and collect to list |
| `findFirst()` | Find first element |
| `findAny()` | Find any element |
| `anyMatch(Closure<Boolean> predicate)` | Test if any match |
| `allMatch(Closure<Boolean> predicate)` | Test if all match |
| `noneMatch(Closure<Boolean> predicate)` | Test if none match |
| `count()` | Count elements |

### Utility Methods

| Method | Description |
|--------|-------------|
| `getStream()` | Access underlying Java Stream |
| `isParallel()` | Check if stream is parallel |
| `methodMissing(String name, Object args)` | Delegate to Groovy collection methods |

---

**Package**: `org.softwood.gstream`  
**Class**: `Gstream<T>`  
**Annotations**: `@CompileStatic`