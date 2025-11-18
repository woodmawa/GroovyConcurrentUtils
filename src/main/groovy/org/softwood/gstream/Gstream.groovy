
package org.softwood.gstream

import groovy.transform.CompileStatic
import groovy.transform.TypeChecked
import groovy.transform.TypeCheckingMode

import java.util.function.BinaryOperator
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Predicate
import java.util.function.Supplier
import java.util.function.UnaryOperator
import java.util.stream.Collector
import java.util.stream.Stream
import java.util.stream.Collectors

/**
 * A fluent, idiomatic Groovy wrapper around Java's Stream API that combines the power
 * of Java Streams with Groovy's closure syntax and collection methods.
 * <p>
 * Gstream provides a type-safe, compile-time checked interface for stream processing
 * while allowing developers to use familiar Groovy closures instead of Java lambda expressions.
 * It bridges the gap between Groovy's collection-oriented programming style and Java's
 * powerful stream processing capabilities.
 * </p>
 *
 * <h2>Key Features</h2>
 * <ul>
 *   <li><b>Groovy Closure Support:</b> Use natural Groovy closure syntax throughout</li>
 *   <li><b>Type Safety:</b> Full {@code @CompileStatic} support for performance</li>
 *   <li><b>Fluent API:</b> Chain operations naturally with method chaining</li>
 *   <li><b>Parallel Processing:</b> Built-in support for parallel stream execution</li>
 *   <li><b>Groovy Integration:</b> Seamless fallback to Groovy collection methods</li>
 *   <li><b>Rich Factory Methods:</b> Multiple ways to create streams from various sources</li>
 * </ul>
 *
 * <h2>Quick Start Examples</h2>
 * <pre>
 * // Basic filtering and mapping
 * def result = Gstream.of(1, 2, 3, 4, 5)
 *     .filter { it % 2 == 0 }
 *     .map { it * 2 }
 *     .toList()
 * // Result: [4, 8]
 *
 * // Working with ranges
 * def squares = Gstream.of(1..10)
 *     .map { it * it }
 *     .toList()
 * // Result: [1, 4, 9, 16, 25, 36, 49, 64, 81, 100]
 *
 * // Grouping data
 * def words = ["apple", "banana", "apricot", "blueberry"]
 * def grouped = Gstream.from(words)
 *     .groupBy { it[0] }
 * // Result: [a: ["apple", "apricot"], b: ["banana", "blueberry"]]
 *
 * // Parallel processing
 * def result = Gstream.of(1..1000)
 *     .parallel()
 *     .filter { isPrime(it) }
 *     .toList()
 * </pre>
 *
 * <h2>Internal Architecture</h2>
 * <p>
 * Gstream internally wraps a Java {@link Stream} and automatically converts Groovy closures
 * to the appropriate Java functional interfaces:
 * </p>
 * <ul>
 *   <li>{@code Closure<Boolean>} → {@link Predicate}</li>
 *   <li>{@code Closure<R>} → {@link Function}</li>
 *   <li>{@code Closure<Void>} → {@link Consumer}</li>
 *   <li>{@code Closure<Integer>} → {@link Comparator}</li>
 *   <li>{@code Closure<T>} → {@link BinaryOperator} or {@link UnaryOperator}</li>
 * </ul>
 * <p>
 * All intermediate operations return a new Gstream instance, enabling fluent method chaining.
 * Terminal operations consume the stream and return final results.
 * </p>
 *
 * <h2>Creating Streams</h2>
 * <pre>
 * // From varargs
 * Gstream.of(1, 2, 3, 4, 5)
 *
 * // From collections
 * Gstream.from([1, 2, 3, 4, 5])
 *
 * // From ranges
 * Gstream.of(1..100)
 *
 * // Infinite generated streams
 * Gstream.generate { Math.random() }
 * Gstream.iterate(0) { it + 1 }
 *
 * // Parallel streams
 * Gstream.ofParallel(1, 2, 3, 4, 5)
 * Gstream.fromParallel(myList)
 * </pre>
 *
 * <h2>Stream Operations</h2>
 * <h3>Intermediate Operations (Lazy, Chainable)</h3>
 * <pre>
 * stream.filter { it % 2 == 0 }        // Filter elements
 * stream.findAll { it > 10 }           // Groovy-style filter
 * stream.map { it * 2 }                // Transform elements
 * stream.flatMap { it.stream() }       // Transform and flatten
 * stream.distinct()                     // Remove duplicates
 * stream.sorted()                       // Sort naturally
 * stream.sorted { a, b -> ... }        // Sort with comparator
 * stream.peek { println it }           // Side effects without consuming
 * stream.limit(10)                      // Limit size
 * stream.skip(5)                        // Skip elements
 * stream.takeWhile { it < 100 }        // Take while true
 * stream.dropWhile { it < 10 }         // Drop while true
 * stream.parallel()                     // Enable parallel processing
 * stream.sequential()                   // Disable parallel processing
 * </pre>
 *
 * <h3>Terminal Operations (Eager, Consuming)</h3>
 * <pre>
 * stream.toList()                       // Collect to List
 * stream.toSet()                        // Collect to Set
 * stream.forEach { println it }        // Perform action on each
 * stream.reduce(0) { acc, val -> ... } // Reduce with identity
 * stream.reduce { acc, val -> ... }    // Reduce without identity
 * stream.groupBy { it.property }       // Group by classifier
 * stream.joining(", ")                  // Join to string
 * stream.findFirst()                    // Find first element
 * stream.findAny()                      // Find any element
 * stream.anyMatch { it > 5 }           // Test if any match
 * stream.allMatch { it > 0 }           // Test if all match
 * stream.noneMatch { it < 0 }          // Test if none match
 * stream.count()                        // Count elements
 * </pre>
 *
 * <h2>Groovy Collections Integration</h2>
 * <p>
 * Through {@code methodMissing}, Gstream provides seamless integration with Groovy's
 * collection methods. When a method doesn't exist on Gstream, it automatically converts
 * to a list and delegates to Groovy's collection API:
 * </p>
 * <pre>
 * Gstream.of(1, 2, 3, 4, 5)
 *     .findAll { it % 2 == 0 }
 *     .sum()  // Delegates to List.sum()
 * // Result: 6
 *
 * Gstream.of(1, 5, 3, 9, 2)
 *     .max()  // Delegates to List.max()
 * // Result: 9
 * </pre>
 *
 * <h2>Performance Considerations</h2>
 * <ul>
 *   <li>Uses {@code @CompileStatic} for compile-time optimization</li>
 *   <li>Lazy evaluation: intermediate operations don't execute until terminal operation</li>
 *   <li>Parallel streams leverage multiple cores for large datasets</li>
 *   <li>Method missing provides flexibility but converts to List (use sparingly for large streams)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>
 * Like Java Streams, Gstream is not thread-safe for mutation. However, parallel streams
 * safely distribute work across threads. Ensure stateless closures for parallel operations:
 * </p>
 * <pre>
 * // Safe: stateless operation
 * Gstream.of(1..1000).parallel().map { it * it }.toList()
 *
 * // Unsafe: shared mutable state
 * def sum = 0
 * Gstream.of(1..1000).parallel().forEach { sum += it }  // WRONG!
 *
 * // Safe: use reduce instead
 * def sum = Gstream.of(1..1000).parallel().reduce(0) { acc, val -> acc + val }
 * </pre>
 *
 * @param <T> the type of elements in this stream
 * @author Will Woodman
 * @version 1.0
 * @since 1.0
 * @see Stream
 * @see Collector
 * @see Collectors
 */
@CompileStatic
class Gstream<T> {
    private Stream<T> stream

    private Gstream(Stream<T> stream) {
        this.stream = stream
    }

    /**
     * Returns the underlying Java Stream for direct access when needed.
     * <p>
     * This method provides an escape hatch to access Java Stream API directly
     * for operations not yet wrapped by Gstream or for interoperability with
     * existing Java Stream code.
     * </p>
     *
     * @return the underlying Java Stream instance
     */
    Stream<T> getStream() {
        stream
    }

    // Static builder methods

    /**
     * Creates a Gstream from the specified varargs elements.
     *
     * @param elements the elements to create the stream from
     * @param <T> the type of stream elements
     * @return a new Gstream containing the specified elements
     */
    static <T> Gstream<T> of(T... elements) {
        new Gstream<T>(Stream.of(elements))
    }

    /**
     * Creates a Gstream from a Groovy Range.
     * <p>
     * This method leverages Groovy's Range.stream() method to create a stream
     * from any comparable range (e.g., {@code 1..10}, {@code 'a'..'z'}).
     * </p>
     *
     * @param range the range to create the stream from
     * @param <T> the type of range elements (must be Comparable)
     * @return a new Gstream containing the range elements
     */
    static <T extends Comparable<T>> Gstream<T> of(Range<T> range) {
        new Gstream<T>(range.stream())
    }

    /**
     * Creates a Gstream from a Collection.
     *
     * @param collection the collection to create the stream from
     * @param <T> the type of collection elements
     * @return a new Gstream containing the collection elements
     */
    static <T> Gstream<T> from(Collection<T> collection) {
        new Gstream<T>(collection.stream())
    }

    /**
     * Wraps an existing Java Stream in a Gstream.
     * <p>
     * This method allows interoperability with existing Java Stream code
     * and enables gradual migration to Gstream.
     * </p>
     *
     * @param stream the Java Stream to wrap
     * @param <T> the type of stream elements
     * @return a new Gstream wrapping the provided stream
     */
    static <T> Gstream<T> from(Stream<T> stream) {
        new Gstream<T>(stream)
    }

    /**
     * Creates an empty Gstream.
     *
     * @param <T> the type of stream elements
     * @return an empty Gstream
     */
    static <T> Gstream<T> empty() {
        new Gstream<T>(Stream.empty())
    }

    /**
     * Creates an infinite Gstream where each element is generated by the provided closure.
     * <p>
     * Warning: This creates an infinite stream. Always use with {@link #limit(long)}
     * or other short-circuiting operations to avoid infinite loops.
     * </p>
     *
     * @param supplier a closure that generates stream elements
     * @param <T> the type of stream elements
     * @return a new infinite Gstream of generated elements
     * @see #iterate(Object, Closure)
     */
    static <T> Gstream<T> generate(Closure<T> supplier) {
        new Gstream<T>(Stream.generate(supplier as Supplier<T>))
    }

    /**
     * Creates an infinite sequential ordered Gstream by iteratively applying a function.
     * <p>
     * The stream starts with the seed value and applies the next function repeatedly:
     * {@code seed, next(seed), next(next(seed)), ...}
     * </p>
     * <p>
     * Warning: This creates an infinite stream. Always use with {@link #limit(long)}
     * or other short-circuiting operations.
     * </p>
     *
     * @param seed the initial element
     * @param next a closure applied to the previous element to produce the next element
     * @param <T> the type of stream elements
     * @return a new infinite Gstream
     * @see #generate(Closure)
     */
    static <T> Gstream<T> iterate(T seed, Closure<T> next) {
        new Gstream<T>(Stream.iterate(seed, next as UnaryOperator<T>))
    }

    /**
     * Creates a parallel Gstream from the specified varargs elements.
     *
     * @param elements the elements to create the parallel stream from
     * @param <T> the type of stream elements
     * @return a new parallel Gstream containing the specified elements
     * @see #parallel()
     */
    static <T> Gstream<T> ofParallel(T... elements) {
        new Gstream<T>(Stream.of(elements).parallel())
    }

    /**
     * Creates a parallel Gstream from a Collection.
     *
     * @param collection the collection to create the parallel stream from
     * @param <T> the type of collection elements
     * @return a new parallel Gstream containing the collection elements
     * @see #parallel()
     */
    static <T> Gstream<T> fromParallel(Collection<T> collection) {
        new Gstream<T>(collection.parallelStream())
    }

    /**
     * Creates a lazily concatenated stream whose elements are all the elements
     * of the first stream followed by all the elements of the second stream.
     * <p>
     * The resulting stream is ordered if both input streams are ordered, and parallel
     * if either input stream is parallel. When the resulting stream is closed, the close
     * handlers for both input streams are invoked.
     * </p>
     *
     * @param stream1 the first stream
     * @param stream2 the second stream
     * @param <T> the type of stream elements
     * @return a concatenated Gstream
     */
    static <T> Gstream<T> concat(Gstream<T> stream1, Gstream<T> stream2) {
        new Gstream<T>(Stream.concat(stream1.stream, stream2.stream))
    }

    /**
     * Creates a new stream builder for incrementally constructing a stream.
     * <p>
     * This provides a mutable builder pattern for creating streams element by element.
     * </p>
     *
     * @param <T> the type of stream elements
     * @return a new GstreamBuilder
     */
    static <T> GstreamBuilder<T> builder() {
        new GstreamBuilder<T>()
    }

    /**
     * Creates a Gstream using a declarative DSL closure.
     * <p>
     * This method provides an idiomatic Groovy way to configure stream pipelines
     * using a nested closure syntax.
     * </p>
     *
     * @param config a closure that configures the stream using DSL syntax
     * @param <T> the type of stream elements
     * @return a configured Gstream ready for terminal operations
     */
    static <T> Gstream<T> build(@DelegatesTo(GstreamBuilder) Closure<T> config) {
        def builder = new GstreamBuilder<T>()
        config.delegate = builder
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config()
        builder.build()
    }

    // Intermediate operations

    /**
     * Returns a stream consisting of the elements that match the given predicate.
     *
     * @param predicate a closure that returns true for elements to be included
     * @return a new Gstream containing only matching elements
     */
    Gstream<T> filter(Closure<Boolean> predicate) {
        new Gstream<T>(stream.filter(predicate as Predicate<T>))
    }

    /**
     * Groovy-style alias for {@link #filter(Closure)}.
     * <p>
     * This method provides a more Groovy-idiomatic name for filtering operations,
     * matching the behavior of {@code Collection.findAll()}.
     * </p>
     *
     * @param predicate a closure that returns true for elements to be included
     * @return a new Gstream containing only matching elements
     */
    Gstream<T> findAll(Closure<Boolean> predicate) {
        filter(predicate)
    }

    /**
     * Returns a stream consisting of the results of applying the given mapper
     * function to the elements of this stream.
     *
     * @param mapper a closure that transforms each element
     * @param <R> the type of elements in the resulting stream
     * @return a new Gstream containing the transformed elements
     */
    def <R> Gstream<R> map(Closure<R> mapper) {
        new Gstream<R>(stream.map(mapper as Function<T, R>))
    }

    /**
     * Returns a stream consisting of the results of replacing each element with
     * the contents of a mapped stream produced by applying the mapper function.
     * <p>
     * This is a flattening operation that transforms each element into a stream
     * and then concatenates all the streams into a single stream.
     * </p>
     *
     * @param mapper a closure that transforms each element into a Stream
     * @param <R> the type of elements in the resulting stream
     * @return a new Gstream containing all flattened elements
     */
    def <R> Gstream<R> flatMap(Closure<Stream<R>> mapper) {
        new Gstream<R>(stream.flatMap(mapper as Function<T, Stream<R>>))
    }

    /**
     * Returns a stream consisting of the distinct elements (according to
     * {@link Object#equals(Object)}) of this stream.
     *
     * @return a new Gstream with distinct elements
     */
    Gstream<T> distinct() {
        new Gstream<T>(stream.distinct())
    }

    /**
     * Returns a stream consisting of the elements sorted according to natural order.
     * <p>
     * Elements must implement {@link Comparable}.
     * </p>
     *
     * @return a new Gstream with sorted elements
     */
    Gstream<T> sorted() {
        new Gstream<T>(stream.sorted())
    }

    /**
     * Returns a stream consisting of the elements sorted according to the provided comparator.
     *
     * @param comparator a closure that compares two elements, returning negative, zero, or positive
     * @return a new Gstream with sorted elements
     */
    Gstream<T> sorted(Closure<Integer> comparator) {
        new Gstream<T>(stream.sorted(comparator as Comparator<T>))
    }

    /**
     * Returns a stream that additionally performs the provided action on each element
     * as elements are consumed from the resulting stream.
     * <p>
     * This is primarily useful for debugging and observing stream pipelines.
     * </p>
     *
     * @param action a closure to perform on each element
     * @return a new Gstream with the peek action registered
     */
    Gstream<T> peek(Closure<Void> action) {
        new Gstream<T>(stream.peek(action as Consumer<T>))
    }

    /**
     * Returns a stream consisting of the first maxSize elements of this stream.
     * <p>
     * This is a short-circuiting stateful intermediate operation.
     * </p>
     *
     * @param maxSize the maximum number of elements the stream should be limited to
     * @return a new Gstream limited to maxSize elements
     */
    Gstream<T> limit(long maxSize) {
        new Gstream<T>(stream.limit(maxSize))
    }

    /**
     * Returns a stream consisting of the remaining elements after discarding
     * the first n elements of this stream.
     *
     * @param n the number of leading elements to skip
     * @return a new Gstream with the first n elements skipped
     */
    Gstream<T> skip(long n) {
        new Gstream<T>(stream.skip(n))
    }

    /**
     * Returns an equivalent stream that is parallel.
     * <p>
     * This operation may enable parallel execution of subsequent operations.
     * </p>
     *
     * @return a parallel Gstream
     * @see #sequential()
     * @see #isParallel()
     */
    Gstream<T> parallel() {
        new Gstream<T>(stream.parallel())
    }

    /**
     * Returns an equivalent stream that is sequential.
     * <p>
     * This operation may disable parallel execution of subsequent operations.
     * </p>
     *
     * @return a sequential Gstream
     * @see #parallel()
     * @see #isParallel()
     */
    Gstream<T> sequential() {
        new Gstream<T>(stream.sequential())
    }

    /**
     * Returns whether this stream would execute in parallel.
     *
     * @return true if this stream would execute in parallel
     * @see #parallel()
     * @see #sequential()
     */
    boolean isParallel() {
        stream.isParallel()
    }

    /**
     * Returns a stream consisting of elements while the predicate returns true.
     * <p>
     * This is a short-circuiting stateful intermediate operation.
     * Processing stops at the first element for which the predicate returns false.
     * </p>
     *
     * @param predicate a closure to test elements
     * @return a new Gstream taking elements while predicate is true
     * @see #dropWhile(Closure)
     */
    Gstream<T> takeWhile(Closure<Boolean> predicate) {
        new Gstream<T>(stream.takeWhile(predicate as Predicate<T>))
    }

    /**
     * Returns a stream consisting of elements after dropping the longest prefix
     * of elements that match the predicate.
     * <p>
     * This is a stateful intermediate operation.
     * Processing includes all elements after the first element for which the predicate returns false.
     * </p>
     *
     * @param predicate a closure to test elements
     * @return a new Gstream dropping elements while predicate is true
     * @see #takeWhile(Closure)
     */
    Gstream<T> dropWhile(Closure<Boolean> predicate) {
        new Gstream<T>(stream.dropWhile(predicate as Predicate<T>))
    }

    /**
     * Returns an equivalent stream that is unordered.
     * <p>
     * This operation may improve performance for operations like {@code distinct()} and
     * {@code groupBy()} on parallel streams by allowing the stream to ignore encounter order.
     * </p>
     *
     * @return an unordered Gstream
     */
    Gstream<T> unordered() {
        new Gstream<T>(stream.unordered())
    }

    /**
     * Returns an equivalent stream with an additional close handler.
     * <p>
     * Close handlers are run when the {@code close()} method is called on the stream,
     * and are useful for releasing resources. Multiple close handlers may be registered
     * and will be run in reverse order of registration.
     * </p>
     *
     * @param closeHandler a closure to execute when the stream is closed
     * @return a Gstream with the close handler registered
     */
    Gstream<T> onClose(Closure<Void> closeHandler) {
        new Gstream<T>(stream.onClose(closeHandler as Runnable))
    }

    /**
     * Closes this stream, causing all close handlers registered with {@link #onClose(Closure)}
     * to be called.
     */
    void close() {
        stream.close()
    }

    // Terminal operations

    /**
     * Performs an action for each element of this stream.
     * <p>
     * This is a terminal operation.
     * </p>
     *
     * @param action a closure to perform on each element
     */
    void forEach(Closure<Void> action) {
        stream.forEach(action as Consumer<T>)
    }

    /**
     * Collects all elements into a List.
     * <p>
     * This is a terminal operation.
     * </p>
     *
     * @return a List containing all stream elements
     */
    List<T> toList() {
        stream.collect(Collectors.toList())
    }

    /**
     * Collects all elements into a Set.
     * <p>
     * This is a terminal operation. Duplicate elements are removed.
     * </p>
     *
     * @return a Set containing all distinct stream elements
     */
    Set<T> toSet() {
        stream.collect(Collectors.toSet())
    }

    /**
     * Performs a reduction on the elements using the provided identity value
     * and accumulator function.
     * <p>
     * This is a terminal operation.
     * </p>
     *
     * @param identity the identity value for the accumulator
     * @param accumulator a closure that combines two values
     * @return the result of the reduction
     */
    T reduce(T identity, Closure<T> accumulator) {
        stream.reduce(identity, accumulator as BinaryOperator<T>)
    }

    /**
     * Performs a reduction on the elements using the provided accumulator function.
     * <p>
     * This is a terminal operation. Returns an Optional because the stream might be empty.
     * </p>
     *
     * @param accumulator a closure that combines two values
     * @return an Optional describing the result, or empty if the stream was empty
     */
    Optional<T> reduce(Closure<T> accumulator) {
        stream.reduce(accumulator as BinaryOperator<T>)
    }

    /**
     * Performs a mutable reduction using a Collector.
     * <p>
     * This provides access to Java's extensive Collector framework.
     * This is a terminal operation.
     * </p>
     *
     * @param collector the Collector describing the reduction
     * @param <R> the type of the result
     * @param <A> the intermediate accumulation type
     * @return the result of reduction
     * @see Collectors
     */
    def <R, A> R collect(Collector<? super T, A, R> collector) {
        stream.collect(collector)
    }

    /**
     * Groups elements according to a classification function.
     * <p>
     * This is a terminal operation.
     * </p>
     *
     * @param classifier a closure that extracts the classification key from an element
     * @param <K> the type of the keys
     * @return a Map where keys are classification results and values are Lists of elements
     */
    def <K> Map<K, List<T>> groupBy(Closure<K> classifier) {
        stream.collect(Collectors.groupingBy(classifier as Function<T, K>))
    }

    /**
     * Returns a string representation of all elements joined with the specified delimiter.
     * <p>
     * This is a terminal operation.
     * </p>
     *
     * @param delimiter the delimiter to use between elements (default: empty string)
     * @return a string of all elements joined by the delimiter
     */
    String joining(String delimiter = "") {
        stream.map { it.toString() }.collect(Collectors.joining(delimiter))
    }

    /**
     * Maps elements and collects the results into a List.
     * <p>
     * This is a convenience method combining {@link #map(Closure)} and {@link #toList()}.
     * This is a terminal operation.
     * </p>
     *
     * @param mapper a closure that transforms each element
     * @param <R> the type of mapped elements
     * @return a List of mapped elements
     */
    def <R> List<R> collectList(Closure<R> mapper) {
        map(mapper).toList()
    }

    /**
     * Returns an Optional describing the first element of this stream.
     * <p>
     * This is a short-circuiting terminal operation.
     * </p>
     *
     * @return an Optional describing the first element, or empty if the stream is empty
     */
    Optional<T> findFirst() {
        stream.findFirst()
    }

    /**
     * Returns an Optional describing any element of this stream.
     * <p>
     * This is a short-circuiting terminal operation.
     * In parallel streams, this may return any element without regard to order.
     * </p>
     *
     * @return an Optional describing an element, or empty if the stream is empty
     */
    Optional<T> findAny() {
        stream.findAny()
    }

    /**
     * Returns whether any elements match the provided predicate.
     * <p>
     * This is a short-circuiting terminal operation.
     * </p>
     *
     * @param predicate a closure to test elements
     * @return true if any element matches the predicate, otherwise false
     */
    boolean anyMatch(Closure<Boolean> predicate) {
        stream.anyMatch(predicate as Predicate<T>)
    }

    /**
     * Returns whether all elements match the provided predicate.
     * <p>
     * This is a short-circuiting terminal operation.
     * </p>
     *
     * @param predicate a closure to test elements
     * @return true if all elements match the predicate or the stream is empty, otherwise false
     */
    boolean allMatch(Closure<Boolean> predicate) {
        stream.allMatch(predicate as Predicate<T>)
    }

    /**
     * Returns whether no elements match the provided predicate.
     * <p>
     * This is a short-circuiting terminal operation.
     * </p>
     *
     * @param predicate a closure to test elements
     * @return true if no elements match the predicate or the stream is empty, otherwise false
     */
    boolean noneMatch(Closure<Boolean> predicate) {
        stream.noneMatch(predicate as Predicate<T>)
    }

    /**
     * Returns the count of elements in this stream.
     * <p>
     * This is a terminal operation.
     * </p>
     *
     * @return the count of elements
     */
    long count() {
        stream.count()
    }

    /**
     * Returns the maximum element according to natural ordering.
     * <p>
     * This is a terminal operation. Elements must implement Comparable.
     * </p>
     *
     * @return an Optional describing the maximum element, or empty if the stream is empty
     */
    Optional<T> max() {
        stream.max(Comparator.naturalOrder() as Comparator<T>)
    }

    /**
     * Returns the maximum element according to the provided comparator.
     * <p>
     * This is a terminal operation.
     * </p>
     *
     * @param comparator a closure that compares two elements
     * @return an Optional describing the maximum element, or empty if the stream is empty
     */
    Optional<T> max(Closure<Integer> comparator) {
        stream.max(comparator as Comparator<T>)
    }

    /**
     * Returns the minimum element according to natural ordering.
     * <p>
     * This is a terminal operation. Elements must implement Comparable.
     * </p>
     *
     * @return an Optional describing the minimum element, or empty if the stream is empty
     */
    Optional<T> min() {
        stream.min(Comparator.naturalOrder() as Comparator<T>)
    }

    /**
     * Returns the minimum element according to the provided comparator.
     * <p>
     * This is a terminal operation.
     * </p>
     *
     * @param comparator a closure that compares two elements
     * @return an Optional describing the minimum element, or empty if the stream is empty
     */
    Optional<T> min(Closure<Integer> comparator) {
        stream.min(comparator as Comparator<T>)
    }

    /**
     * Partitions elements into two groups according to a predicate.
     * <p>
     * This is a terminal operation that returns a Map with Boolean keys:
     * {@code true} maps to elements matching the predicate,
     * {@code false} maps to elements not matching.
     * </p>
     *
     * @param predicate a closure to test elements
     * @return a Map partitioning elements by the predicate result
     */
    Map<Boolean, List<T>> partitioningBy(Closure<Boolean> predicate) {
        stream.collect(Collectors.partitioningBy(predicate as Predicate<T>))
    }

    /**
     * Collects elements into a Map using key and value mappers.
     * <p>
     * This is a terminal operation. If duplicate keys are encountered, an
     * {@code IllegalStateException} is thrown.
     * </p>
     *
     * @param keyMapper a closure that produces keys
     * @param valueMapper a closure that produces values
     * @param <K> the type of keys
     * @param <V> the type of values
     * @return a Map containing the mapped entries
     */
    def <K, V> Map<K, V> toMap(Closure<K> keyMapper, Closure<V> valueMapper) {
        stream.collect(Collectors.toMap(
                keyMapper as Function<T, K>,
                valueMapper as Function<T, V>
        ))
    }

    /**
     * Collects elements into a Map using a key mapper, with the elements themselves as values.
     * <p>
     * This is a terminal operation. If duplicate keys are encountered, an
     * {@code IllegalStateException} is thrown.
     * </p>
     *
     * @param keyMapper a closure that produces keys
     * @param <K> the type of keys
     * @return a Map with keys from the mapper and original elements as values
     */
    def <K> Map<K, T> toMap(Closure<K> keyMapper) {
        stream.collect(Collectors.toMap(
                keyMapper as Function<T, K>,
                Function.identity()
        ))
    }

    /**
     * Collects elements into a Map using key and value mappers, with a merge function
     * to handle duplicate keys.
     * <p>
     * This is a terminal operation.
     * </p>
     *
     * @param keyMapper a closure that produces keys
     * @param valueMapper a closure that produces values
     * @param mergeFunction a closure that merges values with the same key
     * @param <K> the type of keys
     * @param <V> the type of values
     * @return a Map containing the mapped and merged entries
     */
    def <K, V> Map<K, V> toMap(Closure<K> keyMapper, Closure<V> valueMapper, Closure<V> mergeFunction) {
        stream.collect(Collectors.toMap(
                keyMapper as Function<T, K>,
                valueMapper as Function<T, V>,
                mergeFunction as BinaryOperator<V>
        ))
    }

    /**
     * Collects elements into a specified Collection using a factory.
     * <p>
     * This is a terminal operation.
     * </p>
     *
     * @param collectionFactory a closure that creates a new Collection
     * @param <C> the type of the resulting Collection
     * @return a Collection containing all stream elements
     */
    def <C extends Collection<T>> C toCollection(Closure<C> collectionFactory) {
        stream.collect(Collectors.toCollection(collectionFactory as Supplier<C>))
    }

    /**
     * Collects elements into an array.
     * <p>
     * This is a terminal operation.
     * </p>
     *
     * @return an array containing all stream elements
     */
    Object[] toArray() {
        stream.toArray()
    }

    /**
     * Provides dynamic method delegation to Groovy's Collection API.
     * <p>
     * This method enables seamless integration with Groovy collection methods by converting
     * the stream to a List and delegating the method call. This allows methods like
     * {@code sum()}, {@code max()}, {@code min()}, {@code each()}, etc. to work naturally.
     * </p>
     * <p>
     * <b>Warning:</b> This method materializes the stream into a List, which may be
     * inefficient for large streams. Consider using native stream operations when possible.
     * </p>
     * <p>
     * Type checking is disabled for this method to allow dynamic invocation.
     * </p>
     *
     * @param name the name of the method to invoke
     * @param args the arguments to pass to the method
     * @return the result of the delegated method call
     */
    @TypeChecked(TypeCheckingMode.SKIP)
    def methodMissing(String name, Object args) {
        def list = this.toList()
        if (args == null) {
            return list."$name"()
        } else if (args instanceof Object[]) {
            Object[] argsArray = (Object[]) args
            if (argsArray.length == 0) {
                return list."$name"()
            } else if (argsArray.length == 1) {
                return list."$name"(argsArray[0])
            } else {
                return list."$name"(*argsArray)
            }
        } else {
            return list."$name"(args)
        }
    }

    /**
     * A builder for constructing Gstream instances incrementally.
     */
    @CompileStatic
    static class GstreamBuilder<T> {
        private Stream.Builder<T> streamBuilder
        private Stream<T> sourceStream
        private boolean isParallel = false
        private List<Closure> operations = []

        GstreamBuilder() {
            this.streamBuilder = Stream.builder()
        }

        GstreamBuilder<T> add(T element) {
            streamBuilder.add(element)
            this
        }

        @TypeChecked(TypeCheckingMode.SKIP)
        GstreamBuilder<T> add(T... elements) {
            elements.each { streamBuilder.add(it) }
            this
        }

        GstreamBuilder<T> from(Collection<T> collection) {
            this.sourceStream = collection.stream()
            this
        }

        @TypeChecked(TypeCheckingMode.SKIP)
        GstreamBuilder<T> from(Range range) {
            this.sourceStream = range.stream()
            this
        }

        GstreamBuilder<T> of(T... elements) {
            this.sourceStream = Stream.of(elements)
            this
        }

        GstreamBuilder<T> parallel(boolean parallel) {
            this.isParallel = parallel
            this
        }

        GstreamBuilder<T> filter(Closure<Boolean> predicate) {
            operations << { Gstream<T> s -> s.filter(predicate) }
            this
        }

        GstreamBuilder<T> map(Closure mapper) {
            operations << { Gstream s -> s.map(mapper) }
            this
        }

        GstreamBuilder<T> distinct() {
            operations << { Gstream<T> s -> s.distinct() }
            this
        }

        GstreamBuilder<T> sorted() {
            operations << { Gstream<T> s -> s.sorted() }
            this
        }

        GstreamBuilder<T> sorted(Closure<Integer> comparator) {
            operations << { Gstream<T> s -> s.sorted(comparator) }
            this
        }

        GstreamBuilder<T> limit(long maxSize) {
            operations << { Gstream<T> s -> s.limit(maxSize) }
            this
        }

        GstreamBuilder<T> skip(long n) {
            operations << { Gstream<T> s -> s.skip(n) }
            this
        }

        Gstream<T> build() {
            Stream<T> baseStream = sourceStream ?: streamBuilder.build()
            if (isParallel) {
                baseStream = baseStream.parallel()
            }
            Gstream<T> result = new Gstream<T>(baseStream)

            operations.each { operation ->
                result = operation(result) as Gstream<T>
            }

            result
        }
    }
}