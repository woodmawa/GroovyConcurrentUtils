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
         * <pre>
         * def numbers = Gstream.of(1, 2, 3, 4, 5, 6)
         *     .partitioningBy { it % 2 == 0 }
         * // Result: [false: [1, 3, 5], true: [2, 4, 6]]
         * </pre>
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
         * <pre>
         * def people = [[name: 'Alice', age: 30], [name: 'Bob', age: 25]]
         * def ageMap = Gstream.from(people)
         *     .toMap({ it.name }, { it.age })
         * // Result: [Alice: 30, Bob: 25]
         * </pre>
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
         * <pre>
         * def words = ["apple", "banana", "cherry"]
         * def lengthMap = Gstream.from(words)
         *     .toMap { it.length() }
         * // Result: [5: "apple", 6: "banana", cherry: "cherry"]
         * </pre>
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
         * <pre>
         * def items = [[cat: 'A', val: 1], [cat: 'B', val: 2], [cat: 'A', val: 3]]
         * def sumMap = Gstream.from(items)
         *     .toMap({ it.cat }, { it.val }, { a, b -> a + b })
         * // Result: [A: 4, B: 2]
         * </pre>
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
         * <pre>
         * def linkedList = Gstream.of(1, 2, 3)
         *     .toCollection { new LinkedList() }
         * </pre>
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
         * Closes this stream, causing all close handlers registered with {@link #onClose(Closure)}
         * to be called.
         */
        void close() {
            stream.close()
        }

            /**
             * A builder for constructing Gstream instances incrementally.
             * <p>
             * GstreamBuilder provides both a programmatic builder pattern and serves as the
             * delegate for the DSL-style {@link Gstream#build(Closure)} method. Elements can
             * be added one at a time or in batches, and stream operations can be chained before
             * building the final stream.
             * </p>
             * <h3>Builder Pattern Usage</h3>
             * <pre>
             * def builder = Gstream.builder()
             * builder.add(1)
             * builder.add(2, 3, 4)
             * def stream = builder.build()
             *     .filter { it > 1 }
             *     .toList()
             * // Result: [2, 3, 4]
             * </pre>
             *
             * <h3>DSL Usage</h3>
             * <pre>
             * def result = Gstream.build {
             *     add 1, 2, 3, 4, 5
             *     parallel true
             *     filter { it % 2 == 0 }
             *     map { it * 10 }
             * }.toList()
             * // Result: [20, 40]
             * </pre>
             *
             * <h3>Using from() in DSL</h3>
             * <pre>
             * def result = Gstream.build {
             *     from([10, 20, 30])
             *     filter { it > 15 }
             * }.toList()
             * // Result: [20, 30]
             * </pre>
             *
             * @param <T> the type of elements in the stream being built
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

                /**
                 * Adds a single element to the stream being built.
                 *
                 * @param element the element to add
                 * @return this builder for method chaining
                 */
                GstreamBuilder<T> add(T element) {
                    streamBuilder.add(element)
                    this
                }

                /**
                 * Adds multiple elements to the stream being built.
                 *
                 * @param elements the elements to add
                 * @return this builder for method chaining
                 */
                GstreamBuilder<T> add(T... elements) {
                    elements.each { streamBuilder.add(it) }
                    this
                }

                /**
                 * Sets the source stream from a collection, replacing any previously added elements.
                 * <p>
                 * This is useful in DSL mode to specify the data source.
                 * </p>
                 *
                 * @param collection the collection to use as the stream source
                 * @return this builder for method chaining
                 */
                GstreamBuilder<T> from(Collection<T> collection) {
                    this.sourceStream = collection.stream()
                    this
                }

                /**
                 * Sets the source stream from a range, replacing any previously added elements.
                 * <p>
                 * This is useful in DSL mode to specify the data source.
                 * </p>
                 *
                 * @param range the range to use as the stream source
                 * @return this builder for method chaining
                 */
                GstreamBuilder<T> from(Range<T> range) where T extends Comparable<T> {
                    this.sourceStream = range.stream()
                    this
                }

                /**
                 * Sets the source stream from varargs, replacing any previously added elements.
                 *
                 * @param elements the elements to use as the stream source
                 * @return this builder for method chaining
                 */
                GstreamBuilder<T> of(T... elements) {
                    this.sourceStream = Stream.of(elements)
                    this
                }

                /**
                 * Configures whether the resulting stream should be parallel.
                 *
                 * @param parallel true for parallel stream, false for sequential
                 * @return this builder for method chaining
                 */
                GstreamBuilder<T> parallel(boolean parallel) {
                    this.isParallel = parallel
                    this
                }

                /**
                 * Adds a filter operation to be applied when the stream is built.
                 * <p>
                 * This is useful in DSL mode to chain operations before building.
                 * </p>
                 *
                 * @param predicate the filter predicate
                 * @return this builder for method chaining
                 */
                GstreamBuilder<T> filter(Closure<Boolean> predicate) {
                    operations << { Gstream<T> s -> s.filter(predicate) }
                    this
                }

                /**
                 * Adds a map operation to be applied when the stream is built.
                 * <p>
                 * This is useful in DSL mode to chain operations before building.
                 * </p>
                 *
                 * @param mapper the mapping function
                 * @return this builder for method chaining
                 */
                GstreamBuilder<T> map(Closure mapper) {
                    operations << { Gstream s -> s.map(mapper) }
                    this
                }

                /**
                 * Adds a distinct operation to be applied when the stream is built.
                 *
                 * @return this builder for method chaining
                 */
                GstreamBuilder<T> distinct() {
                    operations << { Gstream<T> s -> s.distinct() }
                    this
                }

                /**
                 * Adds a sorted operation to be applied when the stream is built.
                 *
                 * @return this builder for method chaining
                 */
                GstreamBuilder<T> sorted() {
                    operations << { Gstream<T> s -> s.sorted() }
                    this
                }

                /**
                 * Adds a sorted operation with comparator to be applied when the stream is built.
                 *
                 * @param comparator the comparator closure
                 * @return this builder for method chaining
                 */
                GstreamBuilder<T> sorted(Closure<Integer> comparator) {
                    operations << { Gstream<T> s -> s.sorted(comparator) }
                    this
                }

                /**
                 * Adds a limit operation to be applied when the stream is built.
                 *
                 * @param maxSize the maximum number of elements
                 * @return this builder for method chaining
                 */
                GstreamBuilder<T> limit(long maxSize) {
                    operations << { Gstream<T> s -> s.limit(maxSize) }
                    this
                }

                /**
                 * Adds a skip operation to be applied when the stream is built.
                 *
                 * @param n the number of elements to skip
                 * @return this builder for method chaining
                 */
                GstreamBuilder<T> skip(long n) {
                    operations << { Gstream<T> s -> s.skip(n) }
                    this
                }

                /**
                 * Builds and returns the configured Gstream.
                 * <p>
                 * This method constructs the stream from either the added elements or the
                 * specified source, applies any configured operations, and returns the
                 * resulting Gstream ready for terminal operations.
                 * </p>
                 *
                 * @return a configured Gstream
                 */
                Gstream<T> build() {
                    Stream<T> baseStream = sourceStream ?: streamBuilder.build()
                    if (isParallel) {
                        baseStream = baseStream.parallel()
                    }
                    Gstream<T> result = new Gstream<T>(baseStream)

                    // Apply queued operations
                    operations.each { operation ->
                        result = operation(result) as Gstream<T>
                    }

                    result
                }
            }

/**
 * Creates a lazily concatenated stream whose elements are all the elements
 * of the first stream followed by all the elements of the second stream.
 * <p>
 * The resulting stream is ordered if both input streams are ordered, and parallel
 * if either input stream is parallel. When the resulting stream is closed, the close
 * handlers for both input streams are invoked.
 * </p>
 * <pre>
 * def stream1 = Gstream.of(1, 2, 3)
 * def stream2 = Gstream.of(4, 5, 6)
 * def combined = Gstream.concat(stream1, stream2)
 *     .toList()
 * // Result: [1, 2, 3, 4, 5, 6]
 * </pre>
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
 * The builder is useful when the stream contents aren't known in advance.
 * </p>
 * <pre>
 * def builder = Gstream.builder()
 * builder.add(1)
 * builder.add(2)
 * builder.add(3)
 * def stream = builder.build()
 * // Result: Gstream of [1, 2, 3]
 * </pre>
 *
 * @param <T> the type of stream elements
 * @return a new GstreamBuilder
 * @see GstreamBuilder
 */
static <T> GstreamBuilder<T> builder() {
    new GstreamBuilder<T>()
}

/**
 * Creates a Gstream using a declarative DSL closure.
 * <p>
 * This method provides an idiomatic Groovy way to configure stream pipelines
 * using a nested closure syntax. The closure receives a {@link GstreamBuilder}
 * as its delegate, allowing direct method calls for stream configuration.
 * </p>
 * <pre>
 * def result = Gstream.build {
 *     add 1, 2, 3, 4, 5
 *     filter { it % 2 == 0 }
 *     map { it * 2 }
 * }.toList()
 * // Result: [4, 8]
 *
 * // Alternative with source specification
 * def result2 = Gstream.build {
 *     from([1, 2, 3, 4, 5])
 *     filter { it > 2 }
 * }.toList()
 * // Result: [3, 4, 5]
 * </pre>
 *
 * @param config a closure that configures the stream using DSL syntax
 * @param <T> the type of stream elements
 * @return a configured Gstream ready for terminal operations
 * @see GstreamBuilder
 */
static <T> Gstream<T> build(@DelegatesTo(GstreamBuilder) Closure<T> config) {
    def builder = new GstreamBuilder<T>()
    config.delegate = builder
    config.resolveStrategy = Closure.DELEGATE_FIRST
    config()
    builder.build()
}

        // Terminal operations
