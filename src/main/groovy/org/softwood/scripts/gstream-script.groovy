package org.softwood.scripts

import org.softwood.gstream.Gstream

// Simple builder style
def result = Gstream.of(1, 2, 3, 4, 5)
        .filter { it % 2 == 0 }
        .map { it * 2 }
        .toList()

println result  // [4, 8]

// Direct chaining - no more pipe needed!
def people = [
        [name: 'Alice', age: 30],
        [name: 'Bob', age: 25],
        [name: 'Charlie', age: 35]
]

def names = Gstream.from(people)
        .filter { it.age > 26 }
        .map { it.name }
        .sorted()
        .toList()
println "Direct chaining: " + names  // [Alice, Charlie]

// Chaining multiple operations
def squares = Gstream.of(1, 2, 3, 4, 5)
        .filter { it > 2 }
        .map { it ** 2 }
        .peek { println "Processing: $it" }
        .toList()

// Using Groovy Collections fallback with methodMissing
def summed = Gstream.of(1, 2, 3, 4, 5)
        .filter { it % 2 == 0 }
        .sum()  // Falls back to Groovy's Collection.sum() via methodMissing!

println "Sum: " + summed  // 6

// More examples of Groovy collection methods working seamlessly
def reversed = Gstream.of(1, 2, 3, 4, 5)
        .filter { it > 2 }
        .reverse()  // Groovy collection method!
println "Reversed: " + reversed  // [5, 4, 3]

def first = Gstream.of(1, 2, 3, 4, 5)
        .filter { it > 2 }
        .first()  // Groovy collection method!
println "First: " + first  // 3

def last = Gstream.of(1, 2, 3, 4, 5)
        .filter { it > 2 }
        .last()  // Groovy collection method!
println "Last: " + last  // 5

// Complex example with flatMap
def matrix = [[1, 2], [3, 4], [5, 6]]
def flattened = Gstream.from(matrix)
        .flatMap { row -> row.stream() }
        .filter { it > 2 }
        .toList()

println "Flattened: " + flattened  // [3, 4, 5, 6]

// Using generate and limit
def randoms = Gstream.generate { Math.random() }
        .limit(5)
        .map { (it * 100).intValue() }
        .toList()

println "Randoms: " + randoms  // e.g., [42, 87, 23, 91, 56]

// Use filter to stay in Gstream pipeline - BEST PRACTICE
def result2 = Gstream.of("apple", "banana", "cherry", "date", "elderberry")
        .filter { it.length() > 5 }
        .map { it.toUpperCase() }
        .filter { it.startsWith("B") || it.startsWith("C") }
        .toList()
println "Filtered fruits: " + result2  // [BANANA, CHERRY]

// Using take (Groovy collection method)
def taken = Gstream.of(1, 2, 3, 4, 5)
        .filter { it > 0 }
        .take(3)  // Groovy collection method!
println "Taken: " + taken  // [1, 2, 3]

// Using groupBy - now works directly!
def grouped = Gstream.of("apple", "apricot", "banana", "blueberry", "cherry")
        .filter { it.length() > 4 }
        .groupBy { it[0] }  // Group by first letter
println "Grouped: " + grouped  // [a:[apple, apricot], b:[banana, blueberry], c:[cherry]]

// Using max/min (Groovy collection methods without closures work fine)
def maximum = Gstream.of(5, 2, 8, 1, 9, 3)
        .filter { it > 2 }
        .max()
println "Maximum: " + maximum  // 9

def minimum = Gstream.of(5, 2, 8, 1, 9, 3)
        .filter { it > 2 }
        .min()
println "Minimum: " + minimum  // 3

// Using sort (method without parameters)
def sorted = Gstream.of(5, 2, 8, 1, 9, 3)
        .filter { it > 2 }
        .sort()
println "Sorted: " + sorted  // [3, 5, 8, 9]

// Using unique
def unique = Gstream.of(1, 2, 2, 3, 3, 3, 4, 4, 4, 4)
        .filter { it > 1 }
        .unique()
println "Unique: " + unique  // [2, 3, 4]

// Combining multiple stream operations
def complex = Gstream.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        .filter { it % 2 == 0 }     // Stream: [2, 4, 6, 8, 10]
        .map { it * 3 }              // Stream: [6, 12, 18, 24, 30]
        .filter { it > 10 }          // Stream: [12, 18, 24, 30]
        .toList()
println "Complex pipeline: " + complex  // [12, 18, 24, 30]

println "\n✅ parallel examples "
// Create parallel streams
def resultp = Gstream.ofParallel(1, 2, 3, 4, 5)
        .findAll { it % 2 == 0 }
        .map { it * 2 }
        .toList()

println "parallel stream result " + resultp

// Convert to parallel mid-stream
def resultp2 = Gstream.of(1..1000)
        .parallel()
        .filter { it % 2 == 0 }
        .toList()

println "convert to parallel mid stream result " + resultp2

println "\n✅ All examples completed successfully!"