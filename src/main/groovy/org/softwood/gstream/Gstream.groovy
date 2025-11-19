package org.softwood.gstream

import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.transform.TypeChecked
import groovy.transform.TypeCheckingMode

import groovy.lang.Tuple2

import java.util.concurrent.atomic.AtomicLong
import java.util.function.BinaryOperator
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Predicate
import java.util.function.Supplier
import java.util.function.UnaryOperator
import java.util.stream.Collector
import java.util.stream.Collectors
import java.util.stream.IntStream
import java.util.stream.LongStream
import java.util.stream.DoubleStream
import java.util.stream.Stream
import java.util.stream.StreamSupport

/**
 * A fluent, idiomatic Groovy wrapper around Java's {@link java.util.stream.Stream} API that
 * combines the power of Java Streams with Groovy's closure syntax and collection methods.
 *
 * <p>{@code Gstream} is designed to:</p>
 * <ul>
 *   <li>Allow use of Groovy closures throughout the pipeline.</li>
 *   <li>Expose the full Java Stream API surface in a Groovy-friendly way.</li>
 *   <li>Integrate smoothly with Groovy collection methods via
 *       {@link org.softwood.gstream.Gstream#methodMissing}.</li>
 *   <li>Support both sequential and parallel processing.</li>
 * </ul>
 *
 * <h2>Key Features</h2>
 * <ul>
 *   <li><b>Groovy Closure Support:</b> All functional parameters use Groovy closures.</li>
 *   <li><b>Type Safety:</b> Fully {@code @CompileStatic} for performance and safety.</li>
 *   <li><b>Fluent API:</b> Chain operations naturally with method chaining.</li>
 *   <li><b>Parallel Processing:</b> Use
 *       {@link org.softwood.gstream.Gstream#parallel()} and
 *       {@link org.softwood.gstream.Gstream#fromParallel(java.util.Collection)}.</li>
 *   <li><b>Groovy Integration:</b> Delegates unknown methods to Groovy's {@link java.util.List} methods.</li>
 *   <li><b>Extended Utility:</b> Helpers such as
 *       {@link org.softwood.gstream.Gstream#flatMapIterable},
 *       {@link org.softwood.gstream.Gstream#sortedBy},
 *       {@link org.softwood.gstream.Gstream#distinctBy},
 *       {@link org.softwood.gstream.Gstream#maxBy},
 *       {@link org.softwood.gstream.Gstream#minBy} and
 *       {@link org.softwood.gstream.Gstream#zipWithIndex()}.</li>
 * </ul>
 *
 * <h2>Important Semantics</h2>
 * <ul>
 *   <li>A {@code Gstream} is <b>single-use</b>: terminal operations consume it, e.g.
 *       {@link org.softwood.gstream.Gstream#toList()},
 *       {@link org.softwood.gstream.Gstream#count()},
 *       {@link org.softwood.gstream.Gstream#reduce}.</li>
 *   <li>{@link org.softwood.gstream.Gstream#methodMissing} materializes the stream into a List.</li>
 *   <li>Closures used after {@link org.softwood.gstream.Gstream#parallel()} must be stateless.</li>
 * </ul>
 *
 * <h2>Security Note</h2>
 * <p>
 * {@link org.softwood.gstream.Gstream#methodMissing} dynamically invokes {@link java.util.List} methods.
 * Do not pass method names derived from untrusted input.
 * </p>
 *
 * @param <T> the element type
 * @version 2.0
 * @author
 */
@CompileStatic
class Gstream<T> {

    /** Underlying Java stream that backs this Gstream. */
    private Stream<T> stream

    /**
     * Private constructor wrapping an existing Java {@link Stream}.
     *
     * @param stream the stream to wrap
     */
    private Gstream(Stream<T> stream) {
        this.stream = stream
    }

    /**
     * Returns the underlying Java {@link Stream} for direct access when needed.
     * <p>
     * <b>Warning:</b> Running terminal operations on the returned stream will also
     * consume this {@code Gstream}. Use with care.
     * </p>
     *
     * @return the underlying Java stream
     */
    Stream<T> getStream() {
        stream
    }

    // -------------------------------------------------------------------------
    // Static builder / factory methods
    // -------------------------------------------------------------------------

    /**
     * Creates a {@code Gstream} from the specified varargs elements.
     *
     * @param elements the elements to create the stream from
     * @param <T> the type of stream elements
     * @return a new {@code Gstream} containing the specified elements
     */
    static <T> Gstream<T> of(T... elements) {
        new Gstream<T>(Stream.of(elements))
    }

    /**
     * Creates a {@code Gstream} from a Groovy {@link Range}.
     * <p>
     * This method leverages Groovy's {@link Range#stream()} method to create a stream
     * from any comparable range (e.g., {@code 1..10}, {@code 'a'..'z'}).
     * </p>
     *
     * @param range the range to create the stream from
     * @param <T> the type of range elements (must be Comparable)
     * @return a new {@code Gstream} containing the range elements
     */
    static <T extends Comparable<T>> Gstream<T> of(Range<T> range) {
        new Gstream<T>(range.stream())
    }

    /**
     * Creates a {@code Gstream} from a {@link java.util.Collection}.
     *
     * @param collection the collection to create the stream from
     * @param <T> the type of collection elements
     * @return a new {@code Gstream} containing the collection elements
     */
    static <T> Gstream<T> from(Collection<T> collection) {
        new Gstream<T>(collection.stream())
    }

    /**
     * Wraps an existing Java {@link Stream} in a {@code Gstream}.
     * <p>
     * This method allows interoperability with existing Java Stream code
     * and enables gradual migration to {@code Gstream}.
     * </p>
     *
     * @param stream the Java Stream to wrap
     * @param <T> the type of stream elements
     * @return a new {@code Gstream} wrapping the provided stream
     */
    static <T> Gstream<T> from(Stream<T> stream) {
        new Gstream<T>(stream)
    }

    /**
     * Wraps an {@link IntStream} in a {@code Gstream} of boxed {@link Integer}.
     *
     * @param intStream the primitive int stream
     * @return a new {@code Gstream} of {@code Integer}
     */
    static Gstream<Integer> fromIntStream(IntStream intStream) {
        new Gstream<Integer>(intStream.boxed())
    }

    /**
     * Wraps a {@link LongStream} in a {@code Gstream} of boxed {@link Long}.
     *
     * @param longStream the primitive long stream
     * @return a new {@code Gstream} of {@code Long}
     */
    static Gstream<Long> fromLongStream(LongStream longStream) {
        new Gstream<Long>(longStream.boxed())
    }

    /**
     * Wraps a {@link DoubleStream} in a {@code Gstream} of boxed {@link Double}.
     *
     * @param doubleStream the primitive double stream
     * @return a new {@code Gstream} of {@code Double}
     */
    static Gstream<Double> fromDoubleStream(DoubleStream doubleStream) {
        new Gstream<Double>(doubleStream.boxed())
    }

    /**
     * Creates an empty {@code Gstream}.
     *
     * @param <T> the type of stream elements
     * @return an empty {@code Gstream}
     */
    static <T> Gstream<T> empty() {
        new Gstream<T>(Stream.empty())
    }

    /**
     * Creates an infinite {@code Gstream} where each element is generated by the provided closure.
     * <p>
     * <b>Warning:</b> This creates an infinite stream. Always use with {@link #limit(long)}
     * or other short-circuiting operations to avoid infinite loops.
     * </p>
     *
     * @param supplier a closure that generates stream elements
     * @param <T> the type of stream elements
     * @return a new infinite {@code Gstream} of generated elements
     * @see #iterate(Object, groovy.lang.Closure)
     */
    static <T> Gstream<T> generate(Closure<T> supplier) {
        new Gstream<T>(Stream.generate(supplier as Supplier<T>))
    }

    /**
     * Creates an infinite sequential ordered {@code Gstream} by iteratively applying a function.
     * <p>
     * The stream starts with the seed value and applies the next function repeatedly:
     * {@code seed, next(seed), next(next(seed)), ...}
     * </p>
     * <p>
     * <b>Warning:</b> This creates an infinite stream. Always use with {@link #limit(long)}
     * or other short-circuiting operations.
     * </p>
     *
     * @param seed the initial element
     * @param next a closure applied to the previous element to produce the next element
     * @param <T> the type of stream elements
     * @return a new infinite {@code Gstream}
     * @see #generate(groovy.lang.Closure)
     */
    static <T> Gstream<T> iterate(T seed, Closure<T> next) {
        new Gstream<T>(Stream.iterate(seed, next as UnaryOperator<T>))
    }

    /**
     * Creates a parallel {@code Gstream} from the specified varargs elements.
     *
     * @param elements the elements to create the parallel stream from
     * @param <T> the type of stream elements
     * @return a new parallel {@code Gstream} containing the specified elements
     * @see #parallel()
     */
    static <T> Gstream<T> ofParallel(T... elements) {
        new Gstream<T>(Stream.of(elements).parallel())
    }

    /**
     * Creates a parallel {@code Gstream} from a {@link java.util.Collection}.
     *
     * @param collection the collection to create the parallel stream from
     * @param <T> the type of collection elements
     * @return a new parallel {@code Gstream} containing the collection elements
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
     * @return a concatenated {@code Gstream}
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
     * @return a new {@code GstreamBuilder}
     */
    static <T> GstreamBuilder<T> builder() {
        new GstreamBuilder<T>()
    }

    /**
     * Creates a {@code Gstream} using a declarative DSL closure.
     * <p>
     * This method provides an idiomatic Groovy way to configure stream pipelines
     * using a nested closure syntax.
     * </p>
     *
     * <pre>{@code
     * def result = Gstream.build {
     *     from(1..10)
     *     filter { it % 2 == 0 }
     *     map { it * 10 }
     * }.toList()
     * }</pre>
     *
     * @param config a closure that configures the stream using DSL syntax
     * @param <T> the type of stream elements
     * @return a configured {@code Gstream} ready for terminal operations
     */
    static <T> Gstream<T> build(@DelegatesTo(GstreamBuilder) Closure<T> config) {
        def builder = new GstreamBuilder<T>()
        config.delegate = builder
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config()
        builder.build()
    }

    // -------------------------------------------------------------------------
    // Intermediate operations
    // -------------------------------------------------------------------------

    /**
     * Returns a stream consisting of the elements that match the given predicate.
     *
     * @param predicate a closure that returns true for elements to be included
     * @return a new {@code Gstream} containing only matching elements
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
     * @return a new {@code Gstream} containing only matching elements
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
     * @return a new {@code Gstream} containing the transformed elements
     */
    def <R> Gstream<R> map(Closure<R> mapper) {
        new Gstream<R>(stream.map(mapper as Function<T, R>))
    }

    /**
     * Returns a stream consisting of the results of replacing each element with
     * the contents of a mapped stream produced by applying the mapper function.
     * <p>
     * This version of flatMap expects the mapper to return a {@link Stream}.
     * For convenience when dealing with {@link Iterable}, see
     * {@link #flatMapIterable(Closure)}.
     * </p>
     *
     * @param mapper a closure that transforms each element into a {@link Stream}
     * @param <R> the type of elements in the resulting stream
     * @return a new {@code Gstream} containing all flattened elements
     */
    def <R> Gstream<R> flatMap(Closure<Stream<R>> mapper) {
        new Gstream<R>(stream.flatMap(mapper as Function<T, Stream<R>>))
    }

    /**
     * Returns a stream consisting of the results of replacing each element with the
     * contents of an {@link Iterable} produced by the mapper function.
     * <p>
     * This is a convenience version of flatMap when working with Groovy/Java collections:
     * </p>
     * <pre>{@code
     * Gstream.from([[1, 2], [3, 4]])
     *        .flatMapIterable { it }
     *        .toList()  // [1, 2, 3, 4]
     * }</pre>
     *
     * @param mapper a closure that transforms each element into an {@link Iterable}
     * @param <R> the type of elements in the resulting stream
     * @return a new {@code Gstream} containing all flattened elements
     */
    def <R> Gstream<R> flatMapIterable(Closure<Iterable<R>> mapper) {
        new Gstream<R>(stream.flatMap { T t ->
            Iterable<R> iterable = mapper.call(t)
            if (iterable == null) {
                return Stream.<R>empty()
            }
            StreamSupport.stream(iterable.spliterator(), false)
        })
    }

    /**
     * Returns a stream consisting of the distinct elements (according to
     * {@link Object#equals(Object)}) of this stream.
     *
     * @return a new {@code Gstream} with distinct elements
     */
    Gstream<T> distinct() {
        new Gstream<T>(stream.distinct())
    }

    /**
     * Returns a stream consisting of the distinct elements of this stream according to
     * a key extracted from each element.
     * <p>
     * Only the first element for each key is retained.
     * </p>
     *
     * @param keyExtractor a closure extracting a key from each element
     * @param <K> the type of the key
     * @return a new {@code Gstream} with elements distinct by key
     */
    def <K> Gstream<T> distinctBy(Closure<K> keyExtractor) {
        Set<Object> seen = new HashSet<>()
        new Gstream<T>(stream.filter { T t ->
            Object key = keyExtractor.call(t)
            seen.add(key)
        })
    }

    /**
     * Returns a stream consisting of the elements sorted according to natural order.
     * <p>
     * Elements must implement {@link Comparable}.
     * </p>
     *
     * @return a new {@code Gstream} with sorted elements
     */
    Gstream<T> sorted() {
        new Gstream<T>(stream.sorted())
    }

    /**
     * Returns a stream consisting of the elements sorted according to the provided comparator.
     *
     * @param comparator a closure that compares two elements, returning negative, zero, or positive
     * @return a new {@code Gstream} with sorted elements
     */
    Gstream<T> sorted(Closure<Integer> comparator) {
        new Gstream<T>(stream.sorted(comparator as Comparator<T>))
    }

    /**
     * Returns a stream consisting of the elements sorted according to a key extracted
     * from each element.
     * <p>
     * This is similar to Groovy's {@code list.sort { it.property }} idiom.
     * </p>
     *
     * @param keyExtractor a closure extracting a {@link Comparable} key
     * @param <K> the key type
     * @return a new {@code Gstream} sorted by the extracted key
     */
    def <K extends Comparable<? super K>> Gstream<T> sortedBy(Closure<K> keyExtractor) {
        new Gstream<T>(stream.sorted(
                Comparator.comparing(keyExtractor as Function<T, K>)
        ))
    }

    /**
     * Returns a stream that additionally performs the provided action on each element
     * as elements are consumed from the resulting stream.
     * <p>
     * This is primarily useful for debugging and observing stream pipelines.
     * </p>
     *
     * @param action a closure to perform on each element
     * @return a new {@code Gstream} with the peek action registered
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
     * @return a new {@code Gstream} limited to maxSize elements
     */
    Gstream<T> limit(long maxSize) {
        new Gstream<T>(stream.limit(maxSize))
    }

    /**
     * Returns a stream consisting of the remaining elements after discarding
     * the first n elements of this stream.
     *
     * @param n the number of leading elements to skip
     * @return a new {@code Gstream} with the first n elements skipped
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
     * @return a parallel {@code Gstream}
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
     * @return a sequential {@code Gstream}
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
     * @return a new {@code Gstream} taking elements while predicate is true
     * @see #dropWhile(Closure)
     */
    Gstream<T> takeWhile(Closure<Boolean> predicate) {
        new Gstream<T>(stream.takeWhile(predicate as Predicate<T>))
    }

    /**
     * Returns a stream consisting of elements after dropping the longest prefix
     * of elements that match the predicate.
     * <p>
     * Processing includes all elements after the first element for which the predicate returns false.
     * </p>
     *
     * @param predicate a closure to test elements
     * @return a new {@code Gstream} dropping elements while predicate is true
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
     * @return an unordered {@code Gstream}
     */
    Gstream<T> unordered() {
        new Gstream<T>(stream.unordered())
    }

    /**
     * Returns an equivalent stream with an additional close handler.
     * <p>
     * Close handlers are run when the {@code close()} method is called on the stream,
     * and are useful for releasing resources. Multiple close handlers may be registered
     * and will be run in reverse order of registration according to the JDK spec.
     * </p>
     *
     * @param closeHandler a closure to execute when the stream is closed
     * @return a {@code Gstream} with the close handler registered
     */
    Gstream<T> onClose(Closure<Void> closeHandler) {
        new Gstream<T>(stream.onClose(closeHandler as Runnable))
    }

    /**
     * Zips this stream with another stream, producing a stream of pairs.
     *
     * @param other another Gstream
     * @param <U> type of second stream elements
     * @return Gstream of Tuple2<T,U>
     */
    def <U> Gstream<Tuple2<T, U>> zip(Gstream<U> other) {
        Iterator<T> it1 = this.stream.iterator()
        Iterator<U> it2 = other.stream.iterator()

        Stream<Tuple2<T,U>> zipped = StreamSupport.stream(
                new Spliterators.AbstractSpliterator<Tuple2<T,U>>(Long.MAX_VALUE, 0) {
                    @Override
                    boolean tryAdvance(Consumer<? super Tuple2<T,U>> action) {
                        if (it1.hasNext() && it2.hasNext()) {
                            action.accept(new Tuple2<>(it1.next(), it2.next()))
                            return true
                        }
                        return false
                    }
                },
                false
        )

        return new Gstream<Tuple2<T,U>>(zipped)
    }

    /**
     * Returns a stream of {@link Tuple2} where each element is paired with its
     * zero-based index.
     * <p>
     * For parallel streams, the order and index association may not be deterministic.
     * </p>
     *
     * @return a new {@code Gstream} of {@code Tuple2<Long, T>} pairs
     */
    Gstream<Tuple2<Long, T>> zipWithIndex() {
        AtomicLong counter = new AtomicLong(0L)
        new Gstream<Tuple2<Long, T>>(
                (stream.map { T t -> new Tuple2<Long, T>(counter.getAndIncrement(), t) })
                        as Stream<Tuple2<Long, T>>
        )
    }


    /**
     * Closes this stream, causing all close handlers registered with {@link #onClose(Closure)}
     * to be called.
     */
    void close() {
        stream.close()
    }

    // -------------------------------------------------------------------------
    // Terminal operations
    // -------------------------------------------------------------------------

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
     * Collects all elements into a {@link java.util.List}.
     * <p>
     * This is a terminal operation.
     * </p>
     *
     * @return a {@code List} containing all stream elements
     */
    List<T> toList() {
        stream.collect(Collectors.toList())
    }

    /**
     * Collects all elements into an unmodifiable {@link java.util.List}.
     * <p>
     * This is a terminal operation.
     * </p>
     *
     * @return an unmodifiable {@code List} containing all stream elements
     */
    List<T> toUnmodifiableList() {
        List<T> list = stream.collect(Collectors.toList())
        Collections.unmodifiableList(list)
    }

    /**
     * Collects all elements into a {@link java.util.Set}.
     * <p>
     * This is a terminal operation. Duplicate elements are removed.
     * </p>
     *
     * @return a {@code Set} containing all distinct stream elements
     */
    Set<T> toSet() {
        stream.collect(Collectors.toSet())
    }

    /**
     * Collects all elements into an unmodifiable {@link java.util.Set}.
     * <p>
     * This is a terminal operation.
     * </p>
     *
     * @return an unmodifiable {@code Set} containing all distinct stream elements
     */
    Set<T> toUnmodifiableSet() {
        Set<T> set = stream.collect(Collectors.toSet())
        Collections.unmodifiableSet(set)
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
     * Performs a mutable reduction using a {@link Collector}.
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
     * Groups elements according to the classifier and applies a downstream collector
     * to the elements of each group.
     * <p>
     * This is a terminal operation that is especially useful for multi-level aggregations.
     * </p>
     *
     * @param classifier a closure that extracts the classification key from an element
     * @param downstream a downstream collector applied to each group
     * @param <K> the key type
     * @param <A> the downstream intermediate type
     * @param <D> the downstream result type
     * @return a map of keys to downstream collection results
     */
    def <K, A, D> Map<K, D> groupBy(Closure<K> classifier, Collector<? super T, A, D> downstream) {
        stream.collect(Collectors.groupingBy(classifier as Function<T, K>, downstream))
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
     * Maps elements and collects the results into a {@link java.util.List}.
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
     * Returns an {@link Optional} describing the first element of this stream.
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
     * Returns an {@link Optional} describing any element of this stream.
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
     * This is a terminal operation. Elements must implement {@link Comparable}.
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
     * Returns the element that maximizes the given key extracted from each element.
     * <p>
     * This is a terminal operation.
     * </p>
     *
     * @param keyExtractor a closure extracting a {@link Comparable} key
     * @param <K> the key type
     * @return an Optional describing the element with the maximum key, or empty if the stream is empty
     */
    def <K extends Comparable<? super K>> Optional<T> maxBy(Closure<K> keyExtractor) {
        stream.max(Comparator.comparing(keyExtractor as Function<T, K>))
    }

    /**
     * Returns the minimum element according to natural ordering.
     * <p>
     * This is a terminal operation. Elements must implement {@link Comparable}.
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
     * Returns the element that minimizes the given key extracted from each element.
     * <p>
     * This is a terminal operation.
     * </p>
     *
     * @param keyExtractor a closure extracting a {@link Comparable} key
     * @param <K> the key type
     * @return an Optional describing the element with the minimum key, or empty if the stream is empty
     */
    def <K extends Comparable<? super K>> Optional<T> minBy(Closure<K> keyExtractor) {
        stream.min(Comparator.comparing(keyExtractor as Function<T, K>))
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
     * Collects elements into a concurrent Map using key and value mappers and merge function.
     * <p>
     * This is a terminal operation.
     * </p>
     *
     * @param keyMapper a closure that produces keys
     * @param valueMapper a closure that produces values
     * @param mergeFunction a closure that merges values with the same key
     * @param <K> the type of keys
     * @param <V> the type of values
     * @return a concurrent Map containing the mapped and merged entries
     */
    def <K, V> Map<K, V> toConcurrentMap(Closure<K> keyMapper, Closure<V> valueMapper, Closure<V> mergeFunction) {
        stream.collect(Collectors.toConcurrentMap(
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
     * Terminal operation: summarize values using an int-mapping closure.
     *
     * @param mapper closure mapping T -> int
     * @return a Map-like statistics object with count, min, max, sum
     */
    IntSummaryStatistics summarizingInt(Closure<Integer> mapper) {
        Objects.requireNonNull(mapper, "mapper must not be null")

        return stream.collect(
                java.util.stream.Collectors.summarizingInt { T value ->
                    mapper.call(value)
                }
        )
    }

    LongSummaryStatistics summarizingLong(Closure<Long> mapper) {
        Objects.requireNonNull(mapper, "mapper must not be null")

        return stream.collect(
                java.util.stream.Collectors.summarizingLong { T value ->
                    mapper.call(value)
                }
        )
    }

    DoubleSummaryStatistics summarizingDouble(Closure<Double> mapper) {
        Objects.requireNonNull(mapper, "mapper must not be null")

        return stream.collect(
                java.util.stream.Collectors.summarizingDouble { T value ->
                    mapper.call(value)
                }
        )
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
     * Splits the stream into chunks of the given size.
     * Produces a stream of Lists, each containing up to `size` items.
     *
     * @param size the chunk size, must be > 0
     * @return Gstream of List<T>
     */
    Gstream<List<T>> chunked(int size) {
        if (size <= 0)
            throw new IllegalArgumentException("chunk size must be > 0")

        Iterator<T> it = stream.iterator()

        Stream<List<T>> chunks = StreamSupport.stream(
                new Spliterators.AbstractSpliterator<List<T>>(Long.MAX_VALUE, 0) {
                    @Override
                    boolean tryAdvance(Consumer<? super List<T>> action) {
                        if (!it.hasNext()) return false

                        List<T> buffer = new ArrayList<>(size)
                        int count = 0
                        while (count < size && it.hasNext()) {
                            buffer.add(it.next())
                            count++
                        }

                        action.accept(buffer)
                        return true
                    }
                },
                false
        )

        return new Gstream<List<T>>(chunks)
    }

    /**
     * Produces a sliding window over this stream with the given size.
     * Each window is a List<T> of length `size`. Windows overlap.
     *
     * Example:
     * [1,2,3,4,5].windowed(3) =>
     * [[1,2,3], [2,3,4], [3,4,5]]
     *
     * @param size window size (> 0)
     * @return Gstream of List<T> windows
     */
    Gstream<List<T>> windowed(int size) {
        if (size <= 0)
            throw new IllegalArgumentException("window size must be > 0")

        Iterator<T> it = stream.iterator()
        LinkedList<T> buffer = new LinkedList<>()

        Stream<List<T>> resultStream = StreamSupport.stream(
                new Spliterators.AbstractSpliterator<List<T>>(Long.MAX_VALUE, Spliterator.ORDERED) {

                    @Override
                    boolean tryAdvance(Consumer<? super List<T>> action) {
                        // fill initial buffer up to 'size'
                        while (buffer.size() < size) {
                            if (!it.hasNext()) return false
                            buffer.add(it.next())
                        }

                        // we now have a full window
                        action.accept(new ArrayList<>(buffer))

                        // slide the window by 1
                        buffer.removeFirst()

                        // attempt to read one more element
                        if (it.hasNext()) {
                            buffer.add(it.next())
                            return true
                        }

                        // after emitting last window, stop
                        return false
                    }
                },
                false
        )

        return new Gstream<List<T>>(resultStream)
    }

    /**
     * Executes the given side-effect closure for each element of the stream
     * while passing the element downstream unchanged.
     *
     * Example:
     * Gstream.of(1,2,3)
     *     .tee { println it }    // prints 1,2,3
     *     .map { it * 2 }        // continues pipeline
     *
     * @param sideEffect closure to run for each element
     * @return a new Gstream with the same elements
     */
    Gstream<T> tee(Closure<?> sideEffect) {
        Objects.requireNonNull(sideEffect, "sideEffect must not be null")

        Stream<T> newStream = stream.peek { T t ->
            sideEffect.call(t)
        }

        return new Gstream<T>(newStream)
    }

    /**
     * Groovy wrapper around Java 16's mapMulti operator.
     *
     * Allows each input element to produce zero, one or many output elements
     * by invoking the provided closure with arguments (value, consumer).
     *
     * Example:
     * stream.mapMulti { v, emit ->
     *     emit(v)
     *     emit(v * 10)
     * }
     *
     * @param mapper a closure taking (T value, Consumer emit)
     * @return a new Gstream<R>
     */
    def <R> Gstream<R> mapMulti(Closure<?> mapper) {
        Objects.requireNonNull(mapper, "mapper cannot be null")

        Stream<R> newStream = stream.mapMulti { T value, java.util.function.Consumer<R> emit ->
            mapper.call(value, emit)
        }

        return new Gstream<R>(newStream)
    }

    /**
     * Continues processing when an exception occurs anywhere in the pipeline
     * (either in upstream stages like map/filter or in the downstream consumer).
     * The closure receives (Throwable ex, T value). For exceptions thrown before
     * a value is produced (e.g. in an upstream map), the value argument will be null.
     *
     * The failing element is skipped and the stream continues.
     */
    Gstream<T> onErrorContinue(Closure<?> handler) {
        Objects.requireNonNull(handler, "handler must not be null")

        Spliterator<T> base = stream.spliterator()

        Spliterator<T> wrapped = new Spliterators.AbstractSpliterator<T>(
                base.estimateSize(), base.characteristics()) {

            @Override
            boolean tryAdvance(Consumer<? super T> action) {
                // We keep trying until either:
                //  - base.tryAdvance returns true (we successfully processed an element), or
                //  - base.tryAdvance returns false (no more elements)
                while (true) {
                    try {
                        // Let the original pipeline produce a value and pass it to our wrapper consumer
                        return base.tryAdvance { T value ->
                            try {
                                // Run downstream consumer (including terminal ops) in a try/catch
                                action.accept(value)
                            } catch (Throwable ex2) {
                                // Downstream failure, we know the value
                                handler.call(ex2, value)
                                // Skip emitting this element; effectively "swallowed"
                            }
                        }
                    } catch (Throwable ex1) {
                        // Upstream failure during base.tryAdvance itself (e.g. in a map/filter before
                        // this onErrorContinue). We don't have a value here, so pass null.
                        handler.call(ex1, null)
                        // Loop again to try the next element. If the underlying stream
                        // is exhausted, base.tryAdvance will eventually return false.
                    }
                }
            }
        }

        return new Gstream<T>(StreamSupport.stream(wrapped, stream.isParallel()))
    }


    // -------------------------------------------------------------------------
    // Dynamic Groovy collection integration
    // -------------------------------------------------------------------------

    /**
     * Provides dynamic method delegation to Groovy's Collection API.
     * <p>
     * This method enables seamless integration with Groovy collection methods by converting
     * the stream to a {@link java.util.List} and delegating the method call. This allows methods like
     * {@code sum()}, {@code max()}, {@code min()}, {@code each()}, {@code reverse()}, {@code first()},
     * {@code last()}, {@code take()}, {@code unique()}, etc. to work naturally.
     * </p>
     * <p>
     * <b>Warning:</b> This method materializes the stream into a List, which may be
     * inefficient for large streams. It also dynamically invokes methods on the list:
     * avoid calling arbitrary method names derived from untrusted input.
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

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    /**
     * <p>
     * A dynamic, DSL-friendly builder used to construct {@link Gstream} pipelines.
     * </p>
     *
     * <p>
     * Unlike {@code Gstream}, which is fully {@code @CompileStatic} for performance,
     * the builder operates under {@code @CompileDynamic}. This avoids static type
     * recursion issues common in Groovy DSLs, especially when mixing varargs,
     * closures, and overloaded methods.
     * </p>
     *
     * <p>
     * The builder allows incremental construction of a stream pipeline using a clean,
     * Groovy-idiomatic syntax:
     * </p>
     *
     * <pre>
     * def result = Gstream.build {
     *     elements 1, 2, 3, 4, 5
     *     filter { it % 2 == 0 }
     *     map { it * 10 }
     *     sorted()
     * }.toList()
     * </pre>
     *
     * <p>
     * Pipeline operations are stored and applied in sequence once {@link #build()} is
     * invoked. The created {@link Gstream} instance is fully static and optimized.
     * </p>
     *
     * @param <T> element type handled by the stream
     */
    @CompileDynamic
    static class GstreamBuilder<T> {

        /** Internal builder used when no explicit source stream is provided. */
        private Stream.Builder<T> streamBuilder = Stream.builder()

        /** Explicitly provided source stream, if any. */
        private Stream<T> sourceStream = null

        /** Whether the resulting stream should be parallel. */
        private boolean parallelFlag = false

        /** List of deferred pipeline operations applied during build(). */
        private final List<Closure<Gstream<T>>> operations = []

        // -------------------------------------------------------------------------
        // SOURCE DEFINERS
        // -------------------------------------------------------------------------

        /**
         * Defines the source elements for the stream using varargs.
         * <p>
         * Example:
         * <pre>
         * elements 1, 2, 3
         * </pre>
         *
         * @param items elements to serve as the source
         * @return this builder for chaining
         */
        GstreamBuilder<T> elements(T... items) {
            this.sourceStream = Stream.of(items)
            return this
        }

        /**
         * Adds elements to the internal {@link Stream.Builder}.
         * Used only when no explicit source stream is set.
         *
         * @param items elements to add
         * @return this builder for chaining
         */
        GstreamBuilder<T> add(T... items) {
            items.each { streamBuilder.add(it) }
            return this
        }

        /**
         * Uses the provided collection as the stream source.
         *
         * @param collection a collection of elements
         * @return this builder for chaining
         */
        GstreamBuilder<T> fromCollection(Collection<T> collection) {
            this.sourceStream = collection.stream()
            return this
        }

        /**
         * Uses the provided Groovy range as the stream source.
         *
         * @param range a Groovy range
         * @return this builder for chaining
         */
        GstreamBuilder<T> fromRange(Range range) {
            this.sourceStream = range.stream()
            return this
        }

        /**
         * Flexible DSL entry that accepts either a collection or range.
         * <p>
         * Example:
         * <pre>
         * from([1,2,3])
         * from(1..10)
         * </pre>
         *
         * @param source a collection or range
         * @return this builder for chaining
         * @throws IllegalArgumentException if source type is unsupported
         */
        GstreamBuilder<T> from(Object source) {
            if (source instanceof Collection)
                return fromCollection(source as Collection<T>)
            if (source instanceof Range)
                return fromRange(source as Range)
            throw new IllegalArgumentException("Unsupported 'from' source: $source")
        }

        // -------------------------------------------------------------------------
        // CONFIGURATION FLAGS
        // -------------------------------------------------------------------------

        /**
         * Specifies whether the resulting stream should be parallel.
         *
         * @param enable true to enable parallel mode
         * @return this builder for chaining
         */
        GstreamBuilder<T> parallel(boolean enable = true) {
            this.parallelFlag = enable
            return this
        }

        // -------------------------------------------------------------------------
        // PIPELINE OPERATIONS (Deferred)
        // -------------------------------------------------------------------------

        /**
         * Adds a filter operation to the pipeline.
         *
         * @param pred a predicate closure
         * @return this builder for chaining
         */
        GstreamBuilder<T> filter(Closure<Boolean> pred) {
            operations << { s -> s.filter(pred) }
            return this
        }

        /**
         * Adds a map operation to the pipeline.
         *
         * @param mapper a closure mapping elements
         * @return this builder for chaining
         */
        GstreamBuilder<T> map(Closure mapper) {
            operations << { s -> s.map(mapper) }
            return this
        }

        /**
         * Adds a distinct operation.
         *
         * @return this builder for chaining
         */
        GstreamBuilder<T> distinct() {
            operations << { s -> s.distinct() }
            return this
        }

        /**
         * Adds a natural-order sort.
         *
         * @return this builder for chaining
         */
        GstreamBuilder<T> sorted() {
            operations << { s -> s.sorted() }
            return this
        }

        /**
         * Adds a custom comparator-based sort.
         *
         * @param cmp comparator closure
         * @return this builder for chaining
         */
        GstreamBuilder<T> sorted(Closure cmp) {
            operations << { s -> s.sorted(cmp) }
            return this
        }

        /**
         * Adds a sort based on an extracted key.
         *
         * @param extractor closure extracting sort key
         * @return this builder for chaining
         */
        GstreamBuilder<T> sortedBy(Closure extractor) {
            operations << { s -> s.sortedBy(extractor) }
            return this
        }

        /**
         * Adds a skip operation.
         *
         * @param n number of elements to skip
         * @return this builder for chaining
         */
        GstreamBuilder<T> skip(long n) {
            operations << { s -> s.skip(n) }
            return this
        }

        /**
         * Adds a limit operation.
         *
         * @param n maximum number of elements
         * @return this builder for chaining
         */
        GstreamBuilder<T> limit(long n) {
            operations << { s -> s.limit(n) }
            return this
        }

        /**
         * Adds a distinct-by-key transformation.
         *
         * @param extractor function producing the distinct key
         * @return this builder for chaining
         */
        GstreamBuilder<T> distinctBy(Closure extractor) {
            operations << { s -> s.distinctBy(extractor) }
            return this
        }

        /**
         * Adds a flatMapIterable operation to the pipeline.
         * The closure must return an Iterable for each input element.
         *
         * Example:
         * <pre>
         * flatMapIterable { [it, it * 10] }
         * </pre>
         *
         * @param mapper closure returning an iterable for each element
         * @return this builder for chaining
         */
        GstreamBuilder<T> flatMapIterable(Closure mapper) {
            operations << { s -> s.flatMapIterable(mapper) }
            return this
        }

        // -------------------------------------------------------------------------
        // BUILD
        // -------------------------------------------------------------------------

        /**
         * Constructs the {@link Gstream} instance with all accumulated settings
         * and pipeline operations.
         *
         * <p>
         * If an explicit source stream has been provided, it is used; otherwise,
         * the internal {@link Stream.Builder} is used to produce the base stream.
         * </p>
         *
         * <p>
         * Pipeline operations are then applied in the exact order they were added.
         * </p>
         *
         * @return a fully constructed {@code Gstream} instance
         */
        Gstream<T> build() {
            Stream<T> base = sourceStream ?: streamBuilder.build()
            if (parallelFlag) base = base.parallel()

            Gstream<T> g = new Gstream<>(base)

            operations.each { op ->
                g = op(g)
            }

            return g
        }
    }
}
